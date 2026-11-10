package com.spop.poverlay.sensor.v2

import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.readBikeCoreData
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private const val READ_DELAY = 200L
// Debug motor trace: poll this often, but hand control/flows a sample only every ~READ_DELAY.
private const val MOTOR_TRACE_POLL_MS = 50L

/**
 * Counts consecutive errors, and throws an exception if the limit is reached
 * This is used to stop the sensor if it is not responding.
 * While testing, the sensor would sometimes stop responding randomly, and this was a way to handle that
 * It is not a perfect solution, but it is better than nothing.
 */
class ConsecutiveErrorCounter(private val limit: Int = 5) {
    private var consecutiveErrors = 0

    fun increment() {
        consecutiveErrors++
        if (consecutiveErrors >= limit) {
            throw Exception("Too many consecutive errors")
        }
    }

    fun reset() {
        consecutiveErrors = 0
    }
}

class BikePlusCombinedSensor(
    private val binder: IBinder,
    private val onSample: (com.spop.poverlay.control.BikeSample?) -> Unit = {},
    /**
     * Debug builds: called with every raw read, polled every MOTOR_TRACE_POLL_MS instead of READ_DELAY.
     * Control and the sensor flows still get a sample only about every READ_DELAY (CadenceHistory and the
     * spin-up check count samples), so control sees ~220 ms instead of ~205 ms between samples.
     */
    private val motorTrace: ((com.spop.poverlay.sensor.BikeCoreData) -> Unit)? = null
) : SensorInterface {

    /** ITitanInterface transaction from dwj300's PR #50. */
    fun setResistance(resistance: Int): Boolean =
        threadRunning.get() && writeBikeResistance(binder, resistance)

    private val mutablePower = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val power = mutablePower.asSharedFlow()

    private val mutableCadence = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val cadence = mutableCadence.asSharedFlow()

    private val mutableResistance = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val resistance = mutableResistance.asSharedFlow()

    // Same ~1 s of consecutive failures before giving up, whichever poll interval is used.
    private val errorCounter = ConsecutiveErrorCounter(if (motorTrace != null) 20 else 5)
    private var threadRunning = AtomicBoolean(false)

    fun start() {
        if (threadRunning.getAndSet(true)) return

        thread(name = "BikePlusSensorPoller") {
            Timber.i("Starting BikePlus polling thread")
            val pollDelay = if (motorTrace != null) MOTOR_TRACE_POLL_MS else READ_DELAY
            var lastEmit = 0L
            try {
                while (threadRunning.get()) {
                    val parcel = Parcel.obtain()
                    val parcel2 = Parcel.obtain()
                    try {
                        parcel.writeInterfaceToken(SERVICE_ACTION)
                        // Transact code 14 fetches the full BikeData
                        check(binder.transact(14, parcel, parcel2, 0)) { "Bike+ read transaction rejected" }
                        parcel2.readException()
                        check(parcel2.readInt() != 0) { "Bike+ returned no BikeData" }
                        
                        val bikeData = readBikeCoreData(parcel2)
                        motorTrace?.invoke(bikeData)
                        val now = android.os.SystemClock.elapsedRealtime()
                        // Trace-only polls in between (no `continue`: that would skip the sleep below).
                        if (motorTrace == null || now - lastEmit >= READ_DELAY - MOTOR_TRACE_POLL_MS / 2) {
                            lastEmit = now

                            // Emit values
                            // Power is divided by 100 in original BikePlusPowerSensor
                            // Note: Property access 'rpm' vs 'RPM' depends on interop, sticking to existing convention
                            mutablePower.tryEmit(bikeData.power.toFloat() / 100f)
                            mutableCadence.tryEmit(bikeData.rpm.toFloat())
                            mutableResistance.tryEmit(bikeData.targetResistance.toFloat())
                            onSample(com.spop.poverlay.control.BikeSample(
                                bikeData.power.toFloat() / 100f, bikeData.rpm.toFloat(),
                                bikeData.targetResistance, android.os.SystemClock.elapsedRealtime()
                            ))
                        }

                        errorCounter.reset()
                    } catch (e: Exception) {
                        try {
                            errorCounter.increment()
                        } catch (e: Exception) {
                            Timber.e(e, "BikePlusCombinedSensor stopped due to errors")
                            stop()
                            break
                        }
                    } finally {
                        parcel.recycle()
                        parcel2.recycle()
                    }
                    Thread.sleep(pollDelay)
                }
            } catch (e: Exception) {
                Timber.e(e, "BikePlusCombinedSensor thread crashed")
            } finally {
                threadRunning.set(false)
                onSample(null)
                Timber.i("BikePlus polling thread stopped")
            }
        }
    }

    fun stop() {
        threadRunning.set(false)
        onSample(null)
    }
}
