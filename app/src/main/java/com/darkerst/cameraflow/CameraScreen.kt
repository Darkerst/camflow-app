package com.darkerst.cameraflow

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.darkerst.cameraflow.gl.VideoColorMatrixEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.darkerst.cameraflow.capture.FilterCaptureProcessor
import com.darkerst.cameraflow.filters.CameraFilter
import com.darkerst.cameraflow.filters.FilterMatrices
import com.darkerst.cameraflow.gl.ColorMatrixSurfaceProcessor
import com.darkerst.cameraflow.ui.FilterPickerBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CameraScreen"

private enum class CaptureMode { PHOTO, VIDEO }


@Composable
fun CameraScreen() {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        results[Manifest.permission.CAMERA]?.let { hasCameraPermission = it }
        results[Manifest.permission.RECORD_AUDIO]?.let { hasAudioPermission = it }
    }

    if (hasCameraPermission && hasAudioPermission) {
        CameraContent()
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Deze app heeft camera- en microfoontoegang nodig.",
                modifier = Modifier.padding(16.dp)
            )
            Button(onClick = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                )
            }) {
                Text("Toestemming geven")
            }
        }
    }
}

@Composable
private fun CameraContent() {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = remember(view) { view.findViewTreeLifecycleOwner() }
        ?: error("Geen LifecycleOwner gevonden voor deze view.")

    var mode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var isFrontCamera by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var activeRecording: Recording? by remember { mutableStateOf(null) }
    var selectedFilter by remember { mutableStateOf<CameraFilter>(CameraFilter.None) }
    var filterIntensity by remember { mutableStateOf(1f) }
    var flashMode by remember { mutableStateOf(ImageCapture.FLASH_MODE_OFF) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    var liveThumbnail by remember { mutableStateOf<Bitmap?>(null) }

    val cameraController = remember {
        LifecycleCameraController(context).apply {
            bindToLifecycle(lifecycleOwner)
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(
                CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE
            )
        }
    }

    // Bakes the selected filter directly into the frames CameraX hands off
    // for VIDEO_CAPTURE, on the GPU, so recordings actually contain the
    // filter (the PreviewView tint below is a view-level overlay only and
    // never reaches the encoder).
    val colorMatrixProcessor = remember { ColorMatrixSurfaceProcessor() }
    val videoEffect = remember {
        VideoColorMatrixEffect(
            colorMatrixProcessor,
            ContextCompat.getMainExecutor(context)
        ) { throwable -> Log.e(TAG, "Video filter effect error", throwable) }
    }

    DisposableEffect(cameraController) {
        cameraController.setEffects(setOf(videoEffect))
        onDispose {
            cameraController.clearEffects()
            colorMatrixProcessor.release()
        }
    }

    // Push the current filter into the GL processor as a GLSL-friendly
    // mat4 + offset whenever the selection or intensity slider changes.
    LaunchedEffect(selectedFilter, filterIntensity) {
        val matrix = FilterMatrices.lerpedForFilter(selectedFilter, filterIntensity)
        val (glMatrix, glOffset) = colorMatrixToGl(matrix)
        colorMatrixProcessor.colorMatrix = glMatrix
        colorMatrixProcessor.colorOffset = glOffset
    }

    // Keep flash mode in sync with the controller. In photo mode this drives
    // the actual flash on capture; in video mode there's no per-shot flash,
    // so anything other than OFF is treated as "torch on while recording".
    LaunchedEffect(mode, flashMode, isRecording, isFrontCamera) {
        if (isFrontCamera) {
            cameraController.enableTorch(false)
            return@LaunchedEffect
        }
        when (mode) {
            CaptureMode.PHOTO -> {
                cameraController.imageCaptureFlashMode = flashMode
                cameraController.enableTorch(false)
            }
            CaptureMode.VIDEO -> {
                cameraController.enableTorch(isRecording && flashMode != ImageCapture.FLASH_MODE_OFF)
            }
        }
    }

    // Periodically grab a snapshot of the live preview to use as the source
    // for the filter-thumbnail previews in the picker bar below.
    LaunchedEffect(previewViewRef) {
        while (true) {
            previewViewRef?.bitmap?.let { liveThumbnail = it }
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    controller = cameraController
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewViewRef = this
                }
            },
            update = { previewView ->
                // Live filter preview: works from minSdk 24 up, since it only
                // needs a hardware layer + ColorMatrixColorFilter, not
                // RenderEffect (API 31+) or RuntimeShader (API 33+).
                if (selectedFilter == CameraFilter.None) {
                    previewView.setLayerType(View.LAYER_TYPE_NONE, null)
                } else {
                    val matrix = FilterMatrices.lerpedForFilter(selectedFilter, filterIntensity)
                    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
                    previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
                }
            }
        )

        FilterPickerBar(
            selectedFilter = selectedFilter,
            intensity = filterIntensity,
            baseFrame = liveThumbnail,
            onFilterSelected = { selectedFilter = it },
            onIntensityChanged = { filterIntensity = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 128.dp)
        )

        // Mode-toggle (Foto / Video), bovenaan gecentreerd
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { mode = CaptureMode.PHOTO },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == CaptureMode.PHOTO) Color(0xFFE33333) else Color(0x99262626)
                )
            ) { Text("Foto") }
            Button(
                onClick = { mode = CaptureMode.VIDEO },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (mode == CaptureMode.VIDEO) Color(0xFFE33333) else Color(0x99262626)
                )
            ) { Text("Video") }
        }

        if (isRecording) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Filled.FiberManualRecord, contentDescription = null, tint = Color.Red)
                Text("REC", color = Color.White)
            }
        }

        // Camera wisselen + flitser, rechtsboven gestapeld
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FloatingActionButton(
                onClick = {
                    isFrontCamera = !isFrontCamera
                    cameraController.cameraSelector = if (isFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }
                },
                containerColor = Color(0xFF262626),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Camera wisselen", tint = Color.White)
            }

            if (!isFrontCamera) {
                FloatingActionButton(
                    onClick = {
                        flashMode = when (flashMode) {
                            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                            else -> ImageCapture.FLASH_MODE_OFF
                        }
                    },
                    containerColor = Color(0xFF262626),
                    modifier = Modifier.size(48.dp)
                ) {
                    val flashIcon = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                        else -> Icons.Filled.FlashOff
                    }
                    val flashTint = if (flashMode == ImageCapture.FLASH_MODE_OFF) Color.White else Color(0xFFE33333)
                    Icon(flashIcon, contentDescription = "Flitser", tint = flashTint)
                }
            }
        }

        // Ontspanknop / opnameknop
        FloatingActionButton(
            onClick = {
                when (mode) {
                    CaptureMode.PHOTO -> takePhoto(context, cameraController, selectedFilter, filterIntensity)
                    CaptureMode.VIDEO -> {
                        if (!isRecording) {
                            activeRecording = startRecording(context, cameraController) { recording ->
                                isRecording = recording
                            }
                        } else {
                            activeRecording?.stop()
                            activeRecording = null
                        }
                    }
                }
            },
            containerColor = if (mode == CaptureMode.VIDEO) Color(0xFFE33333) else Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp)
                .size(72.dp)
        ) {
            val icon = when {
                mode == CaptureMode.PHOTO -> Icons.Filled.PhotoCamera
                isRecording -> Icons.Filled.Stop
                else -> Icons.Filled.Videocam
            }
            val tint = if (mode == CaptureMode.VIDEO) Color.White else Color.Black
            Icon(icon, contentDescription = "Vastleggen", tint = tint)
        }
    }
}

private fun takePhoto(
    context: android.content.Context,
    controller: LifecycleCameraController,
    filter: CameraFilter,
    intensity: Float
) {
    val name = "IMG_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CamFlow")
    }

    val outputOptions = ImageCapture.OutputFileOptions.Builder(
        context.contentResolver,
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    ).build()

    controller.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = output.savedUri
                if (filter == CameraFilter.None || savedUri == null) {
                    Toast.makeText(context, "Foto opgeslagen in Galerij", Toast.LENGTH_SHORT).show()
                    return
                }
                // Bake the filter into the saved JPEG on a background thread,
                // then overwrite the same MediaStore entry in place.
                CoroutineScope(Dispatchers.IO).launch {
                    bakeFilterIntoSavedPhoto(context, savedUri, filter, intensity)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Foto opgeslagen in Galerij", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(context, "Opname mislukt: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        }
    )
}

private fun bakeFilterIntoSavedPhoto(
    context: android.content.Context,
    uri: Uri,
    filter: CameraFilter,
    intensity: Float
) {
    try {
        val resolver = context.contentResolver
        val source = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        if (source == null) {
            Log.e(TAG, "Kon opgeslagen foto niet decoderen voor filter: $uri")
            return
        }
        val filtered = FilterCaptureProcessor.apply(source, filter, intensity)
        val stream = resolver.openOutputStream(uri, "wt")
        if (stream == null) {
            Log.e(TAG, "Kon geen output stream openen om filter terug te schrijven: $uri")
            return
        }
        stream.use { out ->
            val ok = filtered.compress(Bitmap.CompressFormat.JPEG, 92, out)
            if (!ok) Log.e(TAG, "Bitmap.compress gaf false terug voor: $uri")
        }
        // Force MediaStore (and any cached thumbnails in Gallery/Photos apps)
        // to notice the file changed, since we overwrote it via the same Uri.
        val bump = ContentValues().apply {
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
        }
        resolver.update(uri, bump, null, null)
        resolver.notifyChange(uri, null)
    } catch (t: Throwable) {
        Log.e(TAG, "Filter bakken in foto is mislukt voor $uri", t)
    }
}

/**
 * Converts android.graphics.ColorMatrix's 4x5 row-major array (RGBA columns
 * + a translate column, values on a 0-255 scale) into a GLSL-friendly
 * column-major mat4 plus a normalized (0-1) offset vector, since GL shaders
 * work on 0-1 float colors rather than 0-255 bytes.
 */
private fun colorMatrixToGl(matrix: ColorMatrix): Pair<FloatArray, FloatArray> {
    val a = matrix.array
    val gl = FloatArray(16)
    for (col in 0 until 4) {
        for (row in 0 until 4) {
            gl[col * 4 + row] = a[row * 5 + col]
        }
    }
    val offset = floatArrayOf(a[4] / 255f, a[9] / 255f, a[14] / 255f, a[19] / 255f)
    return gl to offset
}

private fun startRecording(
    context: android.content.Context,
    controller: LifecycleCameraController,
    onStateChanged: (Boolean) -> Unit
): Recording {
    val name = "VID_${System.currentTimeMillis()}.mp4"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/CamFlow")
    }

    val outputOptions = MediaStoreOutputOptions.Builder(
        context.contentResolver,
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    ).setContentValues(contentValues).build()

    val pending = controller.startRecording(
        outputOptions,
        AudioConfig.create(true),
        androidx.core.content.ContextCompat.getMainExecutor(context)
    ) { event ->
        when (event) {
            is VideoRecordEvent.Start -> onStateChanged(true)
            is VideoRecordEvent.Finalize -> {
                onStateChanged(false)
                if (!event.hasError()) {
                    Toast.makeText(context, "Video opgeslagen in Galerij", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Video-opname mislukt: ${event.error}", Toast.LENGTH_SHORT).show()
                }
            }
            else -> {}
        }
    }

    return pending
}
