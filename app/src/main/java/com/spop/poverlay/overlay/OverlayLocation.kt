package com.spop.poverlay.overlay

import android.view.Gravity

enum class OverlayLocation(val gravity: Int) {
    Top(Gravity.TOP or Gravity.CENTER_HORIZONTAL),
    Bottom(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL),
    // Docked against a side edge: the same numbers stacked in a column, chart hidden.
    Left(Gravity.LEFT or Gravity.CENTER_VERTICAL),
    Right(Gravity.RIGHT or Gravity.CENTER_VERTICAL);

    val isSide: Boolean get() = this == Left || this == Right
}
