package com.transcribe.app.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/** A ScrollView that wraps its content but never grows taller than [maxHeightPx]. */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = Int.MAX_VALUE
        set(value) {
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
        // A MeasureSpec only has 30 bits for the size, so Int.MAX_VALUE must be clamped.
        val safeLimit = minOf(limit, MEASURE_SPEC_MAX_SIZE)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(safeLimit, MeasureSpec.AT_MOST))
    }

    private companion object {
        const val MEASURE_SPEC_MAX_SIZE = (1 shl 30) - 1
    }
}
