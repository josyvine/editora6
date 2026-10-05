package com.vineyard.aivideostudio.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Represents a single extracted frame ready for UI display, OCR scanning, and overlay rendering.
 */
data class ExtractedFrame(
    val index: Int,
    val timeSeconds: Float,
    val timeFormatted: String,
    val fullResImagePath: String,   // Path to the cached high-resolution image on disk
    val thumbBitmap: Bitmap,        // Lightweight in-memory bitmap for the filmstrip UI
    var isHighlightEnabled: Boolean = false,
    var hasMismatch: Boolean = false
)

/**
 * High-performance, hardware-accelerated frame extraction engine.
 * Solves mobile memory constraints by buffering frames to persistent internal storage
 * and retaining only low-res thumbnails in the JVM heap.
 */
class FastNativeFrameExtractor(private val context: Context) {

    /**
     * Extracts frames at the given [targetFps], reporting progress via [onProgress].
     * Runs on Dispatchers.IO to prevent UI blocking.
     */
    suspend fun extractFrames(
        videoUri: Uri,
        targetFps: Int,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<ExtractedFrame> = withContext(Dispatchers.IO) {
        
        val framesList = mutableListOf<ExtractedFrame>()
        val retriever = MediaMetadataRetriever()

        // Use filesDir rather than cacheDir so Android OS does not purge frames
        // when switching tabs or when the app sits in recent tasks for a long time.
        val workspaceDir = File(context.filesDir, "editora_frames_workspace")
        if (workspaceDir.exists()) {
            workspaceDir.deleteRecursively() // Purge old session cleanly
        }
        workspaceDir.mkdirs()

        try {
            retriever.setDataSource(context, videoUri)
            
            // Retrieve video duration in milliseconds
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val durationSec = durationMs / 1000f

            val totalFrames = floor(durationSec * targetFps).toInt()
            val intervalUs = (1000000 / targetFps).toLong() // Microseconds

            // Read original video dimensions for hardware-scaled decoding
            val origWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val origHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

            // Cap maximum dimension to 1280px to optimize decode speed and ML Kit OCR performance
            val maxAllowedDimension = 1280
            var targetDecodeWidth = origWidth
            var targetDecodeHeight = origHeight

            if (origWidth > 0 && origHeight > 0) {
                if (origWidth >= origHeight && origWidth > maxAllowedDimension) {
                    targetDecodeWidth = maxAllowedDimension
                    targetDecodeHeight = (origHeight * (maxAllowedDimension.toFloat() / origWidth)).roundToInt()
                } else if (origHeight > origWidth && origHeight > maxAllowedDimension) {
                    targetDecodeHeight = maxAllowedDimension
                    targetDecodeWidth = (origWidth * (maxAllowedDimension.toFloat() / origHeight)).roundToInt()
                }
            }

            var lastProgressDispatchTime = 0L

            for (i in 0 until totalFrames) {
                if (!isActive) break // Honor coroutine cancellation if triggered

                val targetTimeUs = i * intervalUs
                val timeSec = targetTimeUs / 1000000f

                // Use hardware-scaled decoding on API 27+ to avoid full 4K frame memory allocations
                val frameBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && targetDecodeWidth > 0 && targetDecodeHeight > 0) {
                    retriever.getScaledFrameAtTime(
                        targetTimeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                        targetDecodeWidth,
                        targetDecodeHeight
                    ) ?: retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } else {
                    retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } ?: continue

                // 1. Generate a lightweight thumbnail for the UI Filmstrip (width: 120px)
                val thumbWidth = 120
                val aspect = frameBitmap.height.toFloat() / frameBitmap.width.toFloat()
                val thumbHeight = (thumbWidth * aspect).roundToInt().coerceAtLeast(1)
                val thumbBitmap = Bitmap.createScaledBitmap(frameBitmap, thumbWidth, thumbHeight, true)

                // 2. Save image to disk using JPEG with buffered stream (4x-8x faster than WebP on mobile)
                val frameFile = File(workspaceDir, "frame_$i.jpg")
                BufferedOutputStream(FileOutputStream(frameFile), 32768).use { outStream ->
                    frameBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outStream)
                }

                // Recycle decode bitmap immediately to free native graphics memory
                frameBitmap.recycle()

                val timeFormatted = String.format(Locale.US, "%.2f", timeSec)

                framesList.add(
                    ExtractedFrame(
                        index = i,
                        timeSeconds = timeSec,
                        timeFormatted = timeFormatted,
                        fullResImagePath = frameFile.absolutePath,
                        thumbBitmap = thumbBitmap
                    )
                )

                // Dispatch progress updates throttled to avoid Compose recomposition jank
                val now = System.currentTimeMillis()
                if (now - lastProgressDispatchTime > 80 || i == totalFrames - 1) {
                    lastProgressDispatchTime = now
                    withContext(Dispatchers.Main) {
                        onProgress(i + 1, totalFrames)
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
            throw RuntimeException("Failed to extract frames: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // Ignore release errors
            }
        }

        return@withContext framesList
    }

    /**
     * Cleans up the disk workspace. Should be called when a new video is loaded or the app is closed.
     */
    fun clearWorkspace() {
        val workspaceDir = File(context.filesDir, "editora_frames_workspace")
        if (workspaceDir.exists()) {
            workspaceDir.deleteRecursively()
        }
    }
}