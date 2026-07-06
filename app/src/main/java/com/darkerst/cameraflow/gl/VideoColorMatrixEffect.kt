package com.darkerst.cameraflow.gl

import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceProcessor
import androidx.core.util.Consumer
import java.util.concurrent.Executor

/**
 * CameraX's [CameraEffect] is deliberately abstract with a *protected*
 * constructor - it's designed to be subclassed, not instantiated directly.
 * Calling `CameraEffect(...)` from app code fails to compile with
 * "Cannot access '<init>': it is protected" / "Cannot create an instance of
 * an abstract class". This thin subclass just exposes a public constructor
 * so [com.darkerst.cameraflow.CameraScreen] can wire up a VIDEO_CAPTURE
 * effect backed by [ColorMatrixSurfaceProcessor].
 */
class VideoColorMatrixEffect(
    surfaceProcessor: SurfaceProcessor,
    executor: Executor,
    errorListener: Consumer<Throwable>
) : CameraEffect(VIDEO_CAPTURE, executor, surfaceProcessor, errorListener)
