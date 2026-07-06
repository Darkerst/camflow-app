package com.darkerst.cameraflow.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.darkerst.cameraflow.filters.CameraFilter
import com.darkerst.cameraflow.filters.FilterMatrices

private const val THUMB_SIZE_PX = 96

@Composable
fun FilterPickerBar(
    selectedFilter: CameraFilter,
    intensity: Float,
    onFilterSelected: (CameraFilter) -> Unit,
    onIntensityChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
    baseFrame: Bitmap? = null
) {
    // A single square crop of the live preview, shared as the source image
    // for every filter thumbnail below (so each swatch shows what the
    // filter actually looks like on the current scene, a la Instagram).
    val thumbSource = remember(baseFrame) { baseFrame?.let { centerCropSquare(it, THUMB_SIZE_PX) } }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(CameraFilter.ALL) { filter ->
                val filteredThumb = remember(thumbSource, filter.id) {
                    thumbSource?.let { applyFilter(it, filter) }
                }
                FilterThumbnail(
                    filter = filter,
                    thumb = filteredThumb,
                    selected = filter == selectedFilter,
                    onClick = { onFilterSelected(filter) }
                )
            }
        }

        if (selectedFilter != CameraFilter.None) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "Intensiteit", style = MaterialTheme.typography.labelMedium, color = Color.White)
                Slider(
                    value = intensity,
                    onValueChange = onIntensityChanged,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun FilterThumbnail(
    filter: CameraFilter,
    thumb: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        val shape = RoundedCornerShape(14.dp)
        val borderColor = if (selected) Color(0xFFE33333) else Color.Transparent
        if (thumb != null) {
            Image(
                bitmap = thumb.asImageBitmap(),
                contentDescription = filter.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(shape)
                    .border(width = if (selected) 3.dp else 0.dp, color = borderColor, shape = shape)
            )
        } else {
            // Preview frame not captured yet: fall back to a flat color swatch.
            Spacer(
                modifier = Modifier
                    .size(64.dp)
                    .clip(shape)
                    .background(thumbnailColor(filter))
                    .border(width = if (selected) 3.dp else 0.dp, color = borderColor, shape = shape)
            )
        }
        Text(
            text = filter.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = Color.White,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Center-crops [source] to a square and scales it down to [targetSize]px, for cheap thumbnails. */
private fun centerCropSquare(source: Bitmap, targetSize: Int): Bitmap {
    val d = minOf(source.width, source.height)
    val x = (source.width - d) / 2
    val y = (source.height - d) / 2
    val cropped = Bitmap.createBitmap(source, x, y, d, d)
    return if (cropped.width == targetSize) cropped else Bitmap.createScaledBitmap(cropped, targetSize, targetSize, true)
}

/** Renders [source] through the given filter's color matrix into a new bitmap. */
private fun applyFilter(source: Bitmap, filter: CameraFilter): Bitmap {
    val matrix = FilterMatrices.forFilter(filter)
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
    val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    Canvas(output).drawBitmap(source, 0f, 0f, paint)
    return output
}

private fun thumbnailColor(filter: CameraFilter): Color = when (filter) {
    CameraFilter.None -> Color.LightGray
    CameraFilter.BlackAndWhite -> Color.DarkGray
    CameraFilter.Sepia -> Color(0xFF8B5A2B)
    CameraFilter.Vintage -> Color(0xFFC49A6C)
    CameraFilter.CoolBlue -> Color(0xFF4A78C4)
    CameraFilter.WarmGlow -> Color(0xFFE0954D)
    CameraFilter.HighContrast -> Color(0xFF222222)
    CameraFilter.Vivid -> Color(0xFFE0356B)
    else -> Color.Gray
}
