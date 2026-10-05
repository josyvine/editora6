package com.vineyard.aivideostudio.media.tools

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Data representation of a 2D bounding box on video coordinates.
 */
data class ToolsBoundingBox(
    val x0: Float,
    val y0: Float,
    val width: Float,
    val height: Float
) {
    val centerX: Float get() = x0 + (width / 2f)
    val centerY: Float get() = y0 + (height / 2f)
}

/**
 * Detected screen instance matching a keyword or OCR rule.
 */
data class DetectedTargetBox(
    val x0: Float,
    val y0: Float,
    val width: Float,
    val height: Float,
    val text: String,
    val tool: String,
    val frame: Int,
    val time: Float,
    val rawJson: String? = null
) {
    val centerX: Float get() = x0 + (width / 2f)
    val centerY: Float get() = y0 + (height / 2f)
}

/**
 * Represents a spatial group of text instances occurring in the same screen area.
 */
data class SpatialCluster(
    val id: Int,
    var centerX: Float,
    var centerY: Float,
    val boxes: MutableList<DetectedTargetBox> = mutableListOf(),
    val frameIndices: MutableSet<Int> = mutableSetOf(),
    var threshold: Float = 0f,
    var shortLabel: String = "",
    var displayName: String = ""
)

/**
 * Represents a continuous temporal window/session between scene cuts or pauses.
 */
data class TimeSlotSession(
    val id: Int,
    val startFrame: Int,
    val endFrame: Int,
    val startTime: Float,
    val endTime: Float,
    val duration: Float,
    val frameCount: Int,
    val frameSet: Set<Int>,
    val displayName: String
)

/**
 * High-performance native spatial clustering & timeline windowing engine.
 * Replaces browser JavaScript clustering with multithreaded native CPU math.
 */
object SpatialClusterer {

    /**
     * Cleans text for uniform casing and punctuation removal (a-z0-9 only).
     */
    fun clean(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val builder = StringBuilder()
        for (ch in input.lowercase()) {
            if (ch in 'a'..'z' || ch in '0'..'9') {
                builder.append(ch)
            }
        }
        return builder.toString()
    }

    /**
     * Groups detected keyword bounding boxes across all video frames into spatial clusters
     * using Euclidean distance thresholds and classifies UI regions (Header, Footer, Sidebar, etc.).
     */
    fun clusterBoxes(
        boxes: List<DetectedTargetBox>,
        videoWidth: Int = 1080,
        videoHeight: Int = 2400
    ): List<SpatialCluster> {
        if (boxes.isEmpty()) return emptyList()

        val clusters = mutableListOf<SpatialCluster>()
        val dynamicThreshold = max(
            50f,
            min(videoWidth.toFloat(), videoHeight.toFloat()) * 0.085f
        )

        for (box in boxes) {
            val bx = box.centerX
            val by = box.centerY

            var matchedCluster: SpatialCluster? = null
            for (cluster in clusters) {
                val dist = hypot(bx - cluster.centerX, by - cluster.centerY)
                if (dist <= dynamicThreshold) {
                    matchedCluster = cluster
                    break
                }
            }

            if (matchedCluster != null) {
                matchedCluster.boxes.add(box)
                matchedCluster.frameIndices.add(box.frame)
                val totalCount = matchedCluster.boxes.size.toFloat()
                matchedCluster.centerX = ((matchedCluster.centerX * (totalCount - 1f)) + bx) / totalCount
                matchedCluster.centerY = ((matchedCluster.centerY * (totalCount - 1f)) + by) / totalCount
            } else {
                val newCluster = SpatialCluster(
                    id = clusters.size,
                    centerX = bx,
                    centerY = by,
                    boxes = mutableListOf(box),
                    frameIndices = mutableSetOf(box.frame),
                    threshold = dynamicThreshold
                )
                clusters.add(newCluster)
            }
        }

        // Region categorization and formatted naming
        for ((index, cluster) in clusters.withIndex()) {
            val rx = if (videoWidth > 0) cluster.centerX / videoWidth.toFloat() else 0.5f
            val ry = if (videoHeight > 0) cluster.centerY / videoHeight.toFloat() else 0.5f

            val regionLabel = when {
                ry <= 0.18f -> "Top Header Bar"
                ry >= 0.82f -> "Bottom Bar / Footer"
                rx <= 0.45f -> "Left Sidebar / Drawer"
                rx >= 0.60f -> "Right Panel / Settings"
                else -> "Main Content Body"
            }

            cluster.shortLabel = regionLabel
            cluster.threshold = dynamicThreshold
            val roundedX = cluster.centerX.roundToInt()
            val roundedY = cluster.centerY.roundToInt()
            val frameCount = cluster.frameIndices.size
            cluster.displayName = "📍 Location ${index + 1}: $regionLabel (X ~ ${roundedX}px, Y ~ ${roundedY}px • $frameCount frames)"
        }

        return clusters
    }

    /**
     * Splits bounding box detections into discrete continuous sessions/time-slots based on frame gaps.
     * Prevents visual overlay drift when a UI element disappears and reappears later in the video.
     */
    fun segmentTimeSlots(
        boxes: List<DetectedTargetBox>,
        targetFps: Int = 12
    ): List<TimeSlotSession> {
        if (boxes.isEmpty()) return emptyList()

        val frameMap = LinkedHashMap<Int, Float>()
        for (box in boxes) {
            if (!frameMap.containsKey(box.frame)) {
                frameMap[box.frame] = box.time
            }
        }

        val sortedFrames = frameMap.keys.sorted()
        if (sortedFrames.isEmpty()) return emptyList()

        val sessions = mutableListOf<MutableList<Int>>()
        var currentSession = mutableListOf(sortedFrames[0])
        val gapThresholdFrames = max(8, (targetFps * 0.9f).roundToInt())

        for (i in 1 until sortedFrames.size) {
            val prevFrame = sortedFrames[i - 1]
            val curFrame = sortedFrames[i]

            if (curFrame - prevFrame > gapThresholdFrames) {
                sessions.add(currentSession)
                currentSession = mutableListOf(curFrame)
            } else {
                currentSession.add(curFrame)
            }
        }
        sessions.add(currentSession)

        return sessions.mapIndexed { index, sessionFrames ->
            val startFrame = sessionFrames.first()
            val endFrame = sessionFrames.last()
            val startTime = frameMap[startFrame] ?: (startFrame.toFloat() / targetFps.toFloat())
            val endTime = frameMap[endFrame] ?: (endFrame.toFloat() / targetFps.toFloat())
            val duration = max(0.1f, endTime - startTime)

            val startTimeFormatted = String.format(java.util.Locale.US, "%.1f", startTime)
            val endTimeFormatted = String.format(java.util.Locale.US, "%.1f", endTime)
            val durationFormatted = String.format(java.util.Locale.US, "%.1f", duration)

            TimeSlotSession(
                id = index,
                startFrame = startFrame,
                endFrame = endFrame,
                startTime = startTime,
                endTime = endTime,
                duration = duration,
                frameCount = sessionFrames.size,
                frameSet = sessionFrames.toSet(),
                displayName = "⏱️ Slot ${index + 1}: ${startTimeFormatted}s ➔ ${endTimeFormatted}s (${durationFormatted}s • ${sessionFrames.size} frames)"
            )
        }
    }

    /**
     * Merges adjacent word-level bounding boxes that match a multi-word target string.
     */
    fun findMatchingBoundingBoxes(
        lines: List<OcrLineData>,
        query: String
    ): List<ToolsBoundingBox> {
        if (lines.isEmpty() || query.isBlank()) return emptyList()
        val cleanQuery = clean(query)
        val matches = mutableListOf<ToolsBoundingBox>()

        for (line in lines) {
            val cleanLineText = clean(line.text)
            if (cleanLineText.contains(cleanQuery) || cleanQuery.contains(cleanLineText)) {
                if (line.words.isNotEmpty()) {
                    val matchingWords = line.words.filter { word ->
                        val cw = clean(word.text)
                        cw.contains(cleanQuery) || cleanQuery.contains(cw)
                    }

                    if (matchingWords.isNotEmpty()) {
                        val minX = matchingWords.minOf { it.bbox.x0 }
                        val minY = matchingWords.minOf { it.bbox.y0 }
                        val maxX = matchingWords.maxOf { it.bbox.x0 + it.bbox.width }
                        val maxY = matchingWords.maxOf { it.bbox.y0 + it.bbox.height }
                        matches.add(ToolsBoundingBox(minX, minY, maxX - minX, maxY - minY))
                        continue
                    }
                }
                matches.add(line.bbox)
            }
        }
        return matches
    }
}

/**
 * Lightweight OCR model representation used across clustering and calibration.
 */
data class OcrWordData(
    val text: String,
    val bbox: ToolsBoundingBox
)

data class OcrLineData(
    val text: String,
    val bbox: ToolsBoundingBox,
    val words: List<OcrWordData> = emptyList()
)