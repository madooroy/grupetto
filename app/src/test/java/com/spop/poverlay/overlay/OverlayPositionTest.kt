package com.spop.poverlay.overlay

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.LifecycleOwner
import com.spop.poverlay.ConfigurationRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    private val stored = mutableMapOf<String, Any>("serialNumber" to "1234")
    private val preferences = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val context = mockk<Context>()

    init {
        every { context.getSharedPreferences(ConfigurationRepository.SharedPrefsName, Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(any(), any()) } answers {
            stored[firstArg<String>()] as String? ?: secondArg<String?>()
        }
        every { preferences.getFloat(any(), any()) } answers {
            stored[firstArg<String>()] as Float? ?: secondArg<Float>()
        }
        every { preferences.getBoolean(any(), any()) } answers {
            stored[firstArg<String>()] as Boolean? ?: secondArg<Boolean>()
        }
        every { preferences.edit() } returns editor
        every { editor.putFloat(any(), any()) } answers {
            stored[firstArg()] = secondArg<Float>()
            editor
        }
        every { editor.putString(any(), any()) } answers {
            stored[firstArg()] = secondArg<String>()
            editor
        }
        every { editor.putBoolean(any(), any()) } answers {
            stored[firstArg()] = secondArg<Boolean>()
            editor
        }
    }

    private fun repository(): ConfigurationRepository {
        val owner = mockk<LifecycleOwner>(relaxed = true)
        return ConfigurationRepository(context, owner)
    }

    private fun dialog(offset: Float = 0f, location: OverlayLocation = OverlayLocation.Bottom, side: Float? = 0f) =
        OverlayDialogViewModel(Size(1920f, 1080f), MutableStateFlow(false), offset, location, side)
            .also { it.onOverlayLayout(IntSize(1000, 110)) }

    @Test
    fun `new installation defaults to bottom center`() {
        repository().use { config ->
            val dialog = dialog(config.overlayHorizontalOffset, config.overlayLocation)
            assertEquals(Offset.Zero, dialog.dialogOrigin.value)
            assertEquals(OverlayLocation.Bottom, dialog.dialogLocation.value)
        }
    }

    @Test
    fun `saved position survives repository and dialog recreation and subsequent dragging`() {
        repository().use { config ->
            val dialog = dialog()
            dialog.processHorizontalDrag(-250f)
            dialog.processVerticalDrag(600f)
            config.setOverlayPosition(dialog.dialogOrigin.value.x, dialog.dialogLocation.value)
        }
        verify(exactly = 1) { editor.apply() }

        repository().use { config ->
            val restored = dialog(config.overlayHorizontalOffset, config.overlayLocation)
            assertEquals(Offset(-250f, 0f), restored.dialogOrigin.value)
            assertEquals(OverlayLocation.Top, restored.dialogLocation.value)
            assertEquals(OverlayLocation.Top.gravity, restored.dialogGravity.value)

            restored.processHorizontalDrag(30f)
            assertEquals(Offset(-220f, 0f), restored.dialogOrigin.value)
            restored.processVerticalDrag(-600f)
            config.setOverlayPosition(restored.dialogOrigin.value.x, restored.dialogLocation.value)
        }
        repository().use { config ->
            assertEquals(-220f, config.overlayHorizontalOffset, 0f)
            assertEquals(OverlayLocation.Bottom, config.overlayLocation)
        }
    }

    @Test
    fun `restored position is clamped to measured overlay bounds`() {
        val dialog = dialog(800f)
        assertEquals(Offset(460f, 0f), dialog.dialogOrigin.value)
        dialog.onTimerOverlayLayout(IntSize(300, 60))
        dialog.processHorizontalDrag(50f)
        assertEquals(Offset(460f, 0f), dialog.dialogOrigin.value)
        dialog.processHorizontalDrag(-30f)
        assertEquals(Offset(430f, 0f), dialog.dialogOrigin.value)
        dialog.onOverlayLayout(IntSize(2000, 110))
        assertEquals(Offset.Zero, dialog.dialogOrigin.value)
    }

    @Test
    fun `dragging still snaps at center and can move past it`() {
        val dialog = dialog(100f)
        dialog.processHorizontalDrag(-85f)
        assertEquals(Offset.Zero, dialog.dialogOrigin.value)
        dialog.processHorizontalDrag(-50f)
        assertEquals(Offset(-35f, 0f), dialog.dialogOrigin.value)
    }

    @Test
    fun `invalid saved position falls back to defaults`() {
        stored[ConfigurationRepository.Preferences.OverlayHorizontalOffset.key] = Float.NaN
        stored[ConfigurationRepository.Preferences.OverlayLocation.key] = "unknown"
        repository().use { config ->
            assertEquals(0f, config.overlayHorizontalOffset, 0f)
            assertEquals(OverlayLocation.Bottom, config.overlayLocation)
        }
    }

    @Test
    fun `pushing the bar past a side edge docks it there as a column`() {
        val dialog = dialog()
        dialog.processHorizontalDrag(-460f) // at the left edge
        dialog.processHorizontalDrag(-100f) // a finger carrying on past the end: still a bar
        assertEquals(OverlayLocation.Bottom, dialog.dialogLocation.value)
        dialog.processHorizontalDrag(-60f)  // past 8% of the screen width
        assertEquals(OverlayLocation.Left, dialog.dialogLocation.value)
        assertEquals(OverlayLocation.Left.gravity, dialog.dialogGravity.value)
        assertEquals(Offset.Zero, dialog.dialogOrigin.value)
    }

    @Test
    fun `the first dock lands below the centre, clear of Peloton's feed`() {
        val dialog = dialog(side = null)
        dialog.processHorizontalDrag(-700f)
        assertEquals(OverlayLocation.Left, dialog.dialogLocation.value)
        assertEquals(Offset(0f, 1080f * OverlayDialogViewModel.DefaultSideOffsetFraction), dialog.dialogOrigin.value)
    }

    @Test
    fun `across in one drag - out of the left edge, along the bar, into the right edge, and it stays there`() {
        val dialog = dialog(location = OverlayLocation.Left)
        dialog.onOverlayLayout(IntSize(150, 400))
        dialog.processSideDrag(500f, 0f)                    // out of the left edge
        assertEquals(OverlayLocation.Bottom, dialog.dialogLocation.value)
        dialog.onOverlayLayout(IntSize(1000, 110))          // the bar lays out
        repeat(20) { dialog.processHorizontalDrag(60f) }    // across, and on into the right edge
        assertEquals(OverlayLocation.Right, dialog.dialogLocation.value)
        dialog.onOverlayLayout(IntSize(150, 400))
        dialog.processSideDrag(-20f, 30f)                   // a small move: still docked
        assertEquals(OverlayLocation.Right, dialog.dialogLocation.value)
    }

    @Test
    fun `a bar as wide as the screen never docks`() {
        val dialog = dialog()
        dialog.onOverlayLayout(IntSize(1920, 110))
        repeat(10) { dialog.processHorizontalDrag(-50f) }
        assertEquals(OverlayLocation.Bottom, dialog.dialogLocation.value)
    }

    @Test
    fun `easing off the edge between pushes does not dock`() {
        val dialog = dialog()
        dialog.processHorizontalDrag(460f)
        dialog.processHorizontalDrag(60f)
        dialog.processHorizontalDrag(-10f) // back inside: the push starts over
        dialog.processHorizontalDrag(10f)
        dialog.processHorizontalDrag(140f)
        assertEquals(OverlayLocation.Bottom, dialog.dialogLocation.value)
        dialog.processHorizontalDrag(30f)
        assertEquals(OverlayLocation.Right, dialog.dialogLocation.value)
    }

    @Test
    fun `a docked column slides along its edge and stays on screen`() {
        val dialog = dialog(location = OverlayLocation.Left)
        dialog.onOverlayLayout(IntSize(150, 400))
        dialog.processSideDrag(0f, -200f)
        assertEquals(Offset(0f, -200f), dialog.dialogOrigin.value)
        dialog.processSideDrag(0f, 900f)
        assertEquals(Offset(0f, 340f), dialog.dialogOrigin.value) // (1080 - 400) / 2
        assertEquals(0f, dialog.processVerticalDrag(900f), 0f)    // the top/bottom flip doesn't apply
        assertEquals(OverlayLocation.Left, dialog.dialogLocation.value)
    }

    @Test
    fun `dragging a column away from its edge puts it back at the top or bottom`() {
        val lower = dialog()
        lower.processHorizontalDrag(-600f)
        lower.processHorizontalDrag(-100f)
        lower.onOverlayLayout(IntSize(150, 400))
        lower.processSideDrag(0f, 150f)
        lower.processSideDrag(-300f, 0f)  // toward the edge: ignored
        lower.processSideDrag(300f, 0f)
        assertEquals(OverlayLocation.Left, lower.dialogLocation.value)
        lower.processSideDrag(200f, 0f)   // 500 px away > 25% of 1920
        assertEquals(OverlayLocation.Bottom, lower.dialogLocation.value)
        assertEquals(0f, lower.dialogOrigin.value.y, 0f)
        // Arrives under the finger: 75 px (half the column) + 500 px dragged = 575 px from the left, -385 from centre.
        assertEquals(-385f, lower.dialogOrigin.value.x, 0.5f)

        val upper = dialog(location = OverlayLocation.Right)
        upper.onOverlayLayout(IntSize(150, 400))
        upper.processSideDrag(0f, -100f)
        upper.processSideDrag(-500f, 0f)
        assertEquals(OverlayLocation.Top, upper.dialogLocation.value)
    }

    @Test
    fun `docked side and place survive a restart, and the bar keeps its own place`() {
        repository().use { config ->
            val dialog = dialog()
            dialog.processHorizontalDrag(-250f)
            config.setOverlayPosition(dialog.dialogOrigin.value.x, dialog.dialogLocation.value)
            dialog.processHorizontalDrag(-300f)
            dialog.processHorizontalDrag(-100f)
            dialog.onOverlayLayout(IntSize(150, 400))
            dialog.processSideDrag(0f, 120f)
            config.setOverlayPosition(dialog.dialogOrigin.value.x, dialog.dialogLocation.value, dialog.sideOffset)
        }
        repository().use { config ->
            assertEquals(OverlayLocation.Left, config.overlayLocation)
            assertEquals(-250f, config.overlayHorizontalOffset, 0f)
            assertEquals(120f, config.overlaySideOffset!!, 0f)
            val restored = OverlayDialogViewModel(Size(1920f, 1080f), MutableStateFlow(false),
                config.overlayHorizontalOffset, config.overlayLocation, config.overlaySideOffset)
            assertEquals(Offset(0f, 120f), restored.dialogOrigin.value)
            assertEquals(OverlayLocation.Left.gravity, restored.dialogGravity.value)
        }
    }

    @Test fun `shifter visibility and spacing survive settings and service recreation`() {
        repository().use { config ->
            assertEquals(true, config.showShifters.value)
            assertEquals(0f, config.shifterInset.value, 0f)
            config.setShowShifters(false)
            config.setShifterInset(.75f)
        }
        repository().use { config ->
            assertEquals(false, config.showShifters.value)
            assertEquals(.75f, config.shifterInset.value, 0f)
            config.setShowShifters(true)
            config.setShifterInset(2f)
        }
        repository().use { config ->
            assertEquals(true, config.showShifters.value)
            assertEquals(1f, config.shifterInset.value, 0f)
        }
    }

    @Test fun `invalid saved shifter spacing cannot move windows off screen`() {
        stored[ConfigurationRepository.Preferences.ShifterInset.key] = Float.NaN
        repository().use { config ->
            assertEquals(0f, config.shifterInset.value, 0f)
            config.setShifterInset(-1f)
            assertEquals(0f, config.shifterInset.value, 0f)
        }
    }
}
