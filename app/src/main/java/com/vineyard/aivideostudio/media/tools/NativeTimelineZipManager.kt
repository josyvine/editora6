package com.vineyard.aivideostudio.media.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Data model for parsed speech/transcript cues.
 */
data class AudioCueSegment(
    val start: Float,
    val end: Float,
    val text: String
)

/**
 * OCR Frame structure for export and packaging.
 */
data class ZipOcrFrame(
    val frameIndex: Int,
    val time: Float,
    val lines: List<OcrLineData>,
    val rawJson: String
)

/**
 * Result payload returned after parsing a timeline ZIP archive.
 */
data class ZipScanResult(
    val detectedBoxes: List<DetectedTargetBox>,
    val transcript: List<AudioCueSegment>,
    val totalScannedFiles: Int
)

/**
 * Native ZIP Manager providing streaming archive decompression,
 * text/JSON extraction, and production ZIP generation without external libraries.
 */
object NativeTimelineZipManager {

    private const val JSON_MARKER = "RAW JSON COORDINATES:\n"
    private val SRT_PATTERN: Pattern = Pattern.compile(
        "(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{2}):(\\d{2}):(\\d{2})[,.](\\d{3})"
    )
    private val SIMPLE_TIME_PATTERN: Pattern = Pattern.compile(
        "(\\d+[:.]?\\d*)\\s*[:s\\-]?\\s*(.*)"
    )

    /**
     * Parses an incoming ZIP input stream in a single streaming pass on Dispatchers.IO.
     * Extracts keyword matches for [targetQuery] and parses embedded transcripts if present.
     */
    suspend fun parseZip(
        zipInputStreamSource: InputStream,
        targetQuery: String,
        selectedTool: String
    ): ZipScanResult = withContext(Dispatchers.IO) {
        val detectedBoxes = mutableListOf<DetectedTargetBox>()
        var parsedTranscript = mutableListOf<AudioCueSegment>()
        var scannedFilesCount = 0

        val cleanTarget = SpatialClusterer.clean(targetQuery)
        val userPart = if (targetQuery.contains("@")) {
            SpatialClusterer.clean(targetQuery.substringBefore("@"))
        } else {
            ""
        }

        val textFilesMap = mutableMapOf<Int, String>()
        val zipIn = ZipInputStream(zipInputStreamSource)

        try {
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                val entryName = entry.name.lowercase()

                // Check for embedded transcript/subtitles
                if (entryName.contains("transcript") || entryName.contains("subtitles") ||
                    entryName.endsWith(".srt") || entryName.endsWith(".vtt") || entryName.endsWith(".json")
                ) {
                    val content = readEntryAsString(zipIn)
                    val cues = parseTranscript(content)
                    if (cues.isNotEmpty()) {
                        parsedTranscript = cues.toMutableList()
                    }
                } else if (entryName.endsWith(".txt")) {
                    val frameNumber = extractFrameIndexFromName(entryName)
                    val content = readEntryAsString(zipIn)
                    textFilesMap[frameNumber] = content
                    scannedFilesCount++
                }

                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        } catch (_: Exception) {
            // Safe stream handling on EOF / closed streams
        } finally {
            try {
                zipIn.close()
            } catch (_: Exception) {}
        }

        // Sort frames sequentially
        val sortedKeys = textFilesMap.keys.sorted()
        for (frameIdx in sortedKeys) {
            val content = textFilesMap[frameIdx] ?: continue
            val markerIdx = content.indexOf(JSON_MARKER)
            if (markerIdx == -1) continue

            val jsonString = content.substring(markerIdx + JSON_MARKER.length).trim()
            try {
                val ocrObj = JSONObject(jsonString)
                val linesArray = ocrObj.optJSONArray("lines") ?: JSONArray()
                val frameTime = ocrObj.optDouble("time", (frameIdx / 12.0)).toFloat()

                for (l in 0 until linesArray.length()) {
                    val lineObj = linesArray.getJSONObject(l)
                    val lineText = lineObj.optString("text", "")
                    val lineClean = SpatialClusterer.clean(lineText)

                    val isMatch = cleanTarget.isEmpty() || lineClean.contains(cleanTarget) ||
                            (userPart.length >= 4 && lineClean.contains(userPart))

                    if (isMatch) {
                        val bboxObj = lineObj.optJSONObject("bbox")
                        val lineBox = if (bboxObj != null) {
                            ToolsBoundingBox(
                                x0 = bboxObj.optDouble("x0", 0.0).toFloat(),
                                y0 = bboxObj.optDouble("y0", 0.0).toFloat(),
                                width = bboxObj.optDouble("width", 100.0).toFloat(),
                                height = bboxObj.optDouble("height", 30.0).toFloat()
                            )
                        } else {
                            ToolsBoundingBox(0f, 0f, 100f, 30f)
                        }

                        val wordsArray = lineObj.optJSONArray("words") ?: JSONArray()
                        val matchedWordBoxes = mutableListOf<ToolsBoundingBox>()

                        for (w in 0 until wordsArray.length()) {
                            val wordObj = wordsArray.getJSONObject(w)
                            val wordText = wordObj.optString("text", "")
                            val wordClean = SpatialClusterer.clean(wordText)

                            if (cleanTarget.isEmpty() || wordClean.contains(cleanTarget) || (userPart.length >= 4 && wordClean.contains(userPart))) {
                                val wb = wordObj.optJSONObject("bbox")
                                if (wb != null) {
                                    matchedWordBoxes.add(
                                        ToolsBoundingBox(
                                            x0 = wb.optDouble("x0", 0.0).toFloat(),
                                            y0 = wb.optDouble("y0", 0.0).toFloat(),
                                            width = wb.optDouble("width", 50.0).toFloat(),
                                            height = wb.optDouble("height", 25.0).toFloat()
                                        )
                                    )
                                }
                            }
                        }

                        if (matchedWordBoxes.isNotEmpty()) {
                            val minX = matchedWordBoxes.minOf { it.x0 }
                            val minY = matchedWordBoxes.minOf { it.y0 }
                            val maxX = matchedWordBoxes.maxOf { it.x0 + it.width }
                            val maxY = matchedWordBoxes.maxOf { it.y0 + it.height }

                            detectedBoxes.add(
                                DetectedTargetBox(
                                    x0 = minX,
                                    y0 = minY,
                                    width = maxX - minX,
                                    height = maxY - minY,
                                    text = lineText,
                                    tool = selectedTool,
                                    frame = frameIdx,
                                    time = frameTime,
                                    rawJson = lineObj.toString()
                                )
                            )
                        } else {
                            detectedBoxes.add(
                                DetectedTargetBox(
                                    x0 = lineBox.x0,
                                    y0 = lineBox.y0,
                                    width = lineBox.width,
                                    height = lineBox.height,
                                    text = lineText,
                                    tool = selectedTool,
                                    frame = frameIdx,
                                    time = frameTime,
                                    rawJson = lineObj.toString()
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip malformed frame JSON without breaking batch parse
            }
        }

        ZipScanResult(
            detectedBoxes = detectedBoxes,
            transcript = parsedTranscript,
            totalScannedFiles = scannedFilesCount
        )
    }

    /**
     * Parses transcripts in JSON, SRT, VTT, or timestamped plain-text formats.
     */
    fun parseTranscript(rawText: String): List<AudioCueSegment> {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return emptyList()

        val results = mutableListOf<AudioCueSegment>()

        // 1. JSON Array format: [{"start": 1.2, "end": 3.4, "text": "click here"}]
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                val jsonArr = if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("segments")
                        ?: root.optJSONArray("lines")
                        ?: root.optJSONArray("speech")
                        ?: JSONArray()
                }

                for (i in 0 until jsonArr.length()) {
                    val item = jsonArr.getJSONObject(i)
                    val start = item.optDouble("start", item.optDouble("time", 0.0)).toFloat()
                    val end = item.optDouble("end", (start + 2.0)).toFloat()
                    val text = item.optString("text", item.optString("words", "")).trim()
                    if (text.isNotEmpty()) {
                        results.add(AudioCueSegment(start = start, end = end, text = text))
                    }
                }
                if (results.isNotEmpty()) return results
            } catch (_: Exception) {}
        }

        // 2. Standard SRT / WebVTT timestamp parsing
        val lines = trimmed.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            val matcher = SRT_PATTERN.matcher(line)
            if (matcher.find()) {
                val h1 = matcher.group(1)?.toLongOrNull() ?: 0L
                val m1 = matcher.group(2)?.toLongOrNull() ?: 0L
                val s1 = matcher.group(3)?.toLongOrNull() ?: 0L
                val ms1 = matcher.group(4)?.toLongOrNull() ?: 0L
                val startTime = (h1 * 3600f) + (m1 * 60f) + s1.toFloat() + (ms1 / 1000f)

                val h2 = matcher.group(5)?.toLongOrNull() ?: 0L
                val m2 = matcher.group(6)?.toLongOrNull() ?: 0L
                val s2 = matcher.group(7)?.toLongOrNull() ?: 0L
                val ms2 = matcher.group(8)?.toLongOrNull() ?: 0L
                val endTime = (h2 * 3600f) + (m2 * 60f) + s2.toFloat() + (ms2 / 1000f)

                val textBuilder = StringBuilder()
                while (i + 1 < lines.size && lines[i + 1].trim().isNotEmpty() && !lines[i + 1].contains("-->")) {
                    i++
                    textBuilder.append(" ").append(lines[i].trim())
                }

                val text = textBuilder.toString().trim()
                if (text.isNotEmpty()) {
                    results.add(AudioCueSegment(start = startTime, end = endTime, text = text))
                }
            }
            i++
        }
        if (results.isNotEmpty()) return results

        // 3. Fallback: simple line timestamp format (e.g., "16.7: click on playground")
        for (l in lines) {
            val matcher = SIMPLE_TIME_PATTERN.matcher(l.trim())
            if (matcher.matches()) {
                val timeStr = matcher.group(1) ?: continue
                val text = matcher.group(2)?.trim() ?: ""
                val t = if (timeStr.contains(":")) {
                    val parts = timeStr.split(":")
                    (parts.getOrNull(0)?.toFloatOrNull() ?: 0f) * 60f +
                            (parts.getOrNull(1)?.toFloatOrNull() ?: 0f)
                } else {
                    timeStr.toFloatOrNull() ?: 0f
                }
                if (text.isNotEmpty()) {
                    results.add(AudioCueSegment(start = t, end = t + 2.0f, text = text))
                }
            }
        }

        return results
    }

    /**
     * Packages a collection of scanned OCR frames into a standard [frames_timeline_data.zip]
     * archive adhering to the exact multi-section file schema.
     */
    suspend fun createTimelineZip(
        scannedFrames: List<ZipOcrFrame>,
        outputStream: OutputStream
    ) = withContext(Dispatchers.IO) {
        val zipOut = ZipOutputStream(outputStream)

        try {
            for (frame in scannedFrames) {
                val entryName = "fr${frame.frameIndex}.timeline.txt"
                val zipEntry = ZipEntry(entryName)
                zipOut.putNextEntry(zipEntry)

                val timeStr = String.format(java.util.Locale.US, "%.2f", frame.time)
                val sb = StringBuilder()
                sb.append("FRAME: ").append(frame.frameIndex).append("\n")
                sb.append("TIMELINE: ").append(timeStr).append("s\n")
                sb.append("TOTAL_LINES: ").append(frame.lines.size).append("\n")
                sb.append("----------------------------------------\n")
                sb.append("EXTRACTED TEXT & BOUNDING BOXES [x0, y0, width, height]:\n")

                for (line in frame.lines) {
                    val x = line.bbox.x0.toInt()
                    val y = line.bbox.y0.toInt()
                    val w = line.bbox.width.toInt()
                    val h = line.bbox.height.toInt()
                    sb.append("[").append(x).append(", ").append(y).append(", ")
                        .append(w).append(", ").append(h).append("] ")
                        .append(line.text).append("\n")
                }

                sb.append("\n----------------------------------------\n")
                sb.append(JSON_MARKER)
                sb.append(frame.rawJson)

                val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
                zipOut.write(bytes, 0, bytes.size)
                zipOut.closeEntry()
            }
            zipOut.finish()
        } finally {
            zipOut.close()
        }
    }

    /**
     * Serializes active highlight coordinates into standard exportable JSON format.
     */
    fun exportHighlightedFramesJson(
        tool: String,
        target: String,
        location: String,
        timeline: String,
        audio: String,
        frames: List<DetectedTargetBox>
    ): String {
        val root = JSONObject()
        root.put("tool", tool)
        root.put("target", target)
        root.put("location", location)
        root.put("timeline", timeline)
        root.put("audio", audio)

        // Group boxes by frame
        val frameMap = LinkedHashMap<Int, MutableList<DetectedTargetBox>>()
        for (b in frames) {
            frameMap.getOrPut(b.frame) { mutableListOf() }.add(b)
        }

        root.put("total_highlighted_frames", frameMap.size)

        val framesArray = JSONArray()
        for ((frameIdx, boxList) in frameMap) {
            val fObj = JSONObject()
            fObj.put("frame", frameIdx)
            fObj.put("time", String.format(java.util.Locale.US, "%.2f", boxList.first().time))
            fObj.put("tool", tool)
            fObj.put("target", target)

            val boxesArr = JSONArray()
            for (box in boxList) {
                val bObj = JSONObject()
                bObj.put("x0", box.x0.toInt())
                bObj.put("y0", box.y0.toInt())
                bObj.put("width", box.width.toInt())
                bObj.put("height", box.height.toInt())
                bObj.put("text", box.text)
                bObj.put("tool", box.tool)
                boxesArr.put(bObj)
            }
            fObj.put("boxes", boxesArr)
            framesArray.put(fObj)
        }

        root.put("frames", framesArray)
        return root.toString(2)
    }

    private fun readEntryAsString(zipIn: ZipInputStream): String {
        val buffer = ByteArray(4096)
        val out = ByteArrayOutputStream()
        var len: Int
        while (zipIn.read(buffer).also { len = it } > 0) {
            out.write(buffer, 0, len)
        }
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun extractFrameIndexFromName(name: String): Int {
        val numbersOnly = name.filter { it.isDigit() }
        return numbersOnly.toIntOrNull() ?: 0
    }
}