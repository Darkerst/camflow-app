package com.darkerst.cameraflow.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.darkerst.cameraflow.filters.CameraFilter
import com.darkerst.cameraflow.filters.FilterMatrices

/**
 * Bakes the selected filter into a captured Bitmap so the saved photo matches
 * what was shown live in the filtered viewfinder. Works on every API level
 * back to minSdk 24 — plain Canvas + Paint + ColorMatrixColorFilter, no
 * RenderEffect/RuntimeShader dependency.
 */
object FilterCaptureProcessor {

    fun apply(source: Bitmap, filter: CameraFilter, intensity: Float): Bitmap {
        if (filter == CameraFilter.None || intensity <= 0f) return source

        val matrix = FilterMatrices.lerpedForFilter(filter, intensity)
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }
}
