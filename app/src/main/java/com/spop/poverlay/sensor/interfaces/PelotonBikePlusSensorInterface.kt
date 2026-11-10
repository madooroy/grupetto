package com.spop.poverlay.sensor.interfaces

import android.content.Context
import com.spop.poverlay.sensor.v2.BikePlusCombinedSensor
import com.spop.poverlay.sensor.v2.getV2Binder
import com.spop.poverlay.util.windowed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlin.coroutines.CoroutineContext
import timber.log.Timber

class PelotonBikePlusSensorInterface(val context: Context, controlSupported: Boolean = false) : SensorInterface, CoroutineScope {
    companion object{
        /**
         * Resistance is filtered with a moving window since it occasionally spikes
         * The last few resistance readings will grouped, and the lowest reading will be shown
         *
         * The spikes are likely a limitation of ADC accuracy
         */
        const val ResistanceMovingAverageWindowSize = 3
    }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO
    @Volatile private var activeSensor: BikePlusCombinedSensor? = null
    override val bikeControl = com.spop.poverlay.control.BikeControl(
        controlSupported, android.os.SystemClock::elapsedRealtime
    ) { level ->
        // Debug builds: the motor trace polls 4x as often against the same bike service; flag slow writes.
        val started = android.os.SystemClock.elapsedRealtime()
        val ok = activeSensor?.setResistance(level) == true
        val took = android.os.SystemClock.elapsedRealtime() - started
        if (com.spop.poverlay.BuildConfig.DEBUG && took > 20)
            timber.log.Timber.tag("ErgTrace").d("write slow level=%d ms=%d ok=%s", level, took, ok)
        ok
    }.apply {
        // Debug builds log every ERG input and decision under the "ErgTrace" logcat tag.
        if (com.spop.poverlay.BuildConfig.DEBUG) trace = { timber.log.Timber.tag("ErgTrace").d(it) }
    }

    init {
        if (controlSupported) {
            val preferences = context.getSharedPreferences(com.spop.poverlay.ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE)
            bikeControl.tune(preferences.getInt("bikeShiftSize", 2), preferences.getFloat("bikeProportionalGain", .007f),
                preferences.getInt("bikeWattsPerShift", 10), preferences.getFloat("bikeInclineSensitivity", 2f),
                preferences.getInt("bikeRampRate", com.spop.poverlay.control.BikeControl.RAMP_RATE_DEFAULT))
            launch { while (isActive) { bikeControl.tick(); delay(100) } }
        }
    }

    // Experimental flywheel temperature estimate (doudar's disc/hub model), fed from the bike's reported watts so it
    // also runs on the Bike+ and CrossTrainer, which have no calibration screen. Debug builds log it once a second.
    private val flywheelTemperature =
        (context.applicationContext as? com.spop.poverlay.GrupettoApplication)?.grupettoCalibration?.flywheelTemperature
    private var flywheelTracedAt = Long.MIN_VALUE
    private fun observeFlywheel(sample: com.spop.poverlay.control.BikeSample?) {
        val tracker = flywheelTemperature ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        tracker.observeNativeWatts(now, sample?.power?.toDouble() ?: Double.NaN)
        if (com.spop.poverlay.BuildConfig.DEBUG && now - flywheelTracedAt >= 1000) {
            flywheelTracedAt = now
            val state = tracker.state.value
            timber.log.Timber.tag("ErgTrace").d("thermal disc=%.1fF hub=%.1fF room=%.1fF watts=%s level=%s",
                state.fahrenheit, state.hubCelsius * 1.8 + 32, state.roomFahrenheit, sample?.power, sample?.resistance)
        }
    }

    fun stop() {
        bikeControl.stop()
        job.cancel()
    }

    private val combinedSensorState = flow {
        try {
            val binding = getV2Binder(context)
            try {
                val sensor = BikePlusCombinedSensor(binding.binder, onSample = { sample ->
                    bikeControl.acceptSample(sample)
                    observeFlywheel(sample)
                }, motorTrace = if (com.spop.poverlay.BuildConfig.DEBUG) { data ->
                    // Debug builds: every 50 ms poll, to see whether the motor moves smoothly or in steps
                    // (the target echo alone cannot show it). cur = currentResistance, tgt = targetResistance.
                    timber.log.Timber.tag("ErgTrace").d("motor stepper=%d cur=%d tgt=%d power=%.1f rpm=%d",
                        data.stepperMotorPosition, data.currentResistance, data.targetResistance,
                        data.power / 100.0, data.rpm)
                } else null)
                activeSensor = sensor
                try {
                    sensor.start()
                    emit(sensor)
                    awaitCancellation()
                } finally {
                    activeSensor = null
                    sensor.stop()
                }
            } finally {
                binding.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Failed to connect to bike sensor service")
        }
    }.shareIn(this, SharingStarted.Lazily, 1)
    override val power: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.power }

    override val cadence: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.cadence }

    override val resistance: Flow<Float>
        get() = combinedSensorState.flatMapLatest { it.resistance }
            .windowed(ResistanceMovingAverageWindowSize, 1, true) { readings ->
                // Resistance sensor occasionally spikes for a single reading
                // So take the least of the last few readings
                readings.minOf { it }
            }

    init {
        // Settings must detect the connected controllable bike even with all transmitters off.
        if (controlSupported) launch { power.collect {} }
    }

}
