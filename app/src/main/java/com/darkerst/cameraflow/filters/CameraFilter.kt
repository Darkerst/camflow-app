package com.darkerst.cameraflow.filters

import android.graphics.ColorMatrix

/**
 * Filters supported live in the viewfinder + baked into captures.
 *
 * Deliberately ColorMatrix-only (no AGSL/RuntimeShader) so every filter works
 * uniformly from minSdk 24 up, via View.setLayerType(LAYER_TYPE_HARDWARE, paint)
 * for the live preview and ColorMatrixColorFilter+Canvas for baked captures.
 * Shader-only looks (duotone, grain) are a later addition once RuntimeShader's
 * API 33 floor is an acceptable cost, or via the CameraX CameraEffect/GL path
 * (cameraController.setEffects(...) is available on your camera-view 1.3.4).
 */
sealed class CameraFilter(val id: String, val label: String) {
    object None : CameraFilter("none", "Origineel")
    object BlackAndWhite : CameraFilter("bw", "Z/W")
    object Sepia : CameraFilter("sepia", "Sepia")
    object Vintage : CameraFilter("vintage", "Vintage")
    object CoolBlue : CameraFilter("cool", "Koel")
    object WarmGlow : CameraFilter("warm", "Warm")
    object HighContrast : CameraFilter("contrast", "Contrast")
    object Vivid : CameraFilter("vivid", "Levendig")

    companion object {
        val ALL: List<CameraFilter> by lazy {
            listOf(None, BlackAndWhite, Sepia, Vintage, CoolBlue, WarmGlow, HighContrast, Vivid)
        }
        fun fromId(id: String): CameraFilter = ALL.firstOrNull { it.id == id } ?: None
    }
}

object FilterMatrices {

    val IDENTITY = ColorMatrix()

    val BLACK_AND_WHITE = ColorMatrix(
        floatArrayOf(
            0.33f, 0.33f, 0.33f, 0f, 0f,
            0.33f, 0.33f, 0.33f, 0f, 0f,
            0.33f, 0.33f, 0.33f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    val SEPIA = ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    val COOL_BLUE = ColorMatrix(
        floatArrayOf(
            0.9f, 0f, 0.05f, 0f, 0f,
            0f, 0.95f, 0.05f, 0f, 0f,
            0.05f, 0.05f, 1.15f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    val WARM_GLOW = ColorMatrix(
        floatArrayOf(
            1.15f, 0.05f, 0f, 0f, 10f,
            0.05f, 1.0f, 0f, 0f, 5f,
            0f, 0f, 0.85f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    val VIVID = ColorMatrix().apply { setSaturation(1.6f) }

    val HIGH_CONTRAST = ColorMatrix(
        floatArrayOf(
            1.4f, 0f, 0f, 0f, -40f,
            0f, 1.4f, 0f, 0f, -40f,
            0f, 0f, 1.4f, 0f, -40f,
            0f, 0f, 0f, 1f, 0f
        )
    )

    val VINTAGE: ColorMatrix by lazy {
        val desat = ColorMatrix().apply { setSaturation(0.75f) }
        val result = ColorMatrix(desat)
        result.postConcat(WARM_GLOW)
        result
    }

    fun forFilter(filter: CameraFilter): ColorMatrix = when (filter) {
        CameraFilter.None -> IDENTITY
        CameraFilter.BlackAndWhite -> BLACK_AND_WHITE
        CameraFilter.Sepia -> SEPIA
        CameraFilter.Vintage -> VINTAGE
        CameraFilter.CoolBlue -> COOL_BLUE
        CameraFilter.WarmGlow -> WARM_GLOW
        CameraFilter.HighContrast -> HIGH_CONTRAST
        CameraFilter.Vivid -> VIVID
        else -> IDENTITY
    }

    /** Linear interpolation between identity and the target matrix, for the intensity slider. */
    fun lerpedForFilter(filter: CameraFilter, intensity: Float): ColorMatrix {
        val target = forFilter(filter)
        val a = IDENTITY.array
        val b = target.array
        val out = FloatArray(20)
        for (i in 0 until 20) out[i] = a[i] + (b[i] - a[i]) * intensity
        return ColorMatrix(out)
    }
}
