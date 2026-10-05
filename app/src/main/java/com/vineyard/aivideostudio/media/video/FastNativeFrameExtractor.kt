package com.vineyard.aivideostudio.media.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    val thumbBitmap: Bitmap,        // Tiny in-memory bitmap for the filmstrip UI
    var isHighlightEnabled: Boolean = false,
    var hasMismatch: Boolean = false
)

/**
 * High-performance, hardware-accelerated frame extraction engine.
 * Solves mobile memory constraints by buffering high-res frames to local storage
 * and retaining only low-res thumbnails in the JVM heap.
 */
class FastNativeFrameExtractor(private val context: Context) {

    /**
     * Extracts frames at the given [targetFps], reporting progress via [onProgress].
     * Runs entirely on the IO thread to prevent UI blocking.
     */
    suspend fun extractFrames(
        videoUri: Uri,
        targetFps: Int,
        onProgress: (current: Int, total: Int) -> Unit
    ): List<ExtractedFrame> = withContext(Dispatchers.IO) {
        
        val framesList = mutableListOf<ExtractedFrame>()
        val retriever = MediaMetadataRetriever()

        // Create an isolated workspace directory for this session's frames
        val cacheDir = File(context.cacheDir, "editora_frames_workspace")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively() // Purge old session
        }
        cacheDir.mkdirs()

        try {
            retriever.setDataSource(context, videoUri)
            
            // Retrieve video duration in milliseconds
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L
            val durationSec = durationMs / 1000f

            val totalFrames = floor(durationSec * targetFps).toInt()
            val intervalUs = (1000000 / targetFps).toLong() // Microseconds

            for (i in 0 until totalFrames) {
                if (!isActive) break // Allow coroutine cancellation

                val targetTimeUs = i * intervalUs
                val timeSec = targetTimeUs / 1000000f

                // OPTION_CLOSEST guarantees exact frame accuracy rather than nearest sync frame
                val fullBitmap = retriever.getFrameAtTime(targetTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: continue

                // 1. Generate a lightweight thumbnail for the UI Filmstrip (e.g., 95px width)
                val thumbWidth = 120
                val aspect = fullBitmap.height.toFloat() / fullBitmap.width.toFloat()
                val thumbHeight = (thumbWidth * aspect).roundToInt()
                val thumbBitmap = Bitmap.createScaledBitmap(fullBitmap, thumbWidth, thumbHeight, true)

                // 2. Save the full-resolution bitmap to disk to prevent RAM overflow
                val frameFile = File(cacheDir, "frame_$i.webp")
                FileOutputStream(frameFile).use { outStream ->
                    // WebP offers fast encoding and low disk footprint with 100% visual fidelity
                    fullBitmap.compress(Bitmap.CompressFormat.WEBP, 90, outStream)
                }

                // Immediately recycle the full bitmap from RAM
                fullBitmap.recycle()

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

                // Dispatch progress update to the UI Layer
                withContext(Dispatchers.Main) {
                    onProgress(i + 1, totalFrames)
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
     * Cleans up the disk cache. Should be called when a new video is loaded or the app is closed.
     */
    fun clearWorkspace() {
        val cacheDir = File(context.cacheDir, "editora_frames_workspace")
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }
}