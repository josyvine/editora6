package com.vineyard.aivideostudio.ui.screens.tools

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vineyard.aivideostudio.data.preferences.GeminiPreferences
import com.vineyard.aivideostudio.media.audio.AudioExtractor
import com.vineyard.aivideostudio.media.ocr.FrameOcrData
import com.vineyard.aivideostudio.media.ocr.NativeBatchOcrEngine
import com.vineyard.aivideostudio.media.tools.AudioCueSegment
import com.vineyard.aivideostudio.media.tools.DetectedTargetBox
import com.vineyard.aivideostudio.media.tools.NativeTimelineZipManager
import com.vineyard.aivideostudio.media.tools.SpatialCluster
import com.vineyard.aivideostudio.media.tools.SpatialClusterer
import com.vineyard.aivideostudio.media.tools.TimeSlotSession
import com.vineyard.aivideostudio.media.tools.ZipOcrFrame
import com.vineyard.aivideostudio.media.video.ExtractedFrame
import com.vineyard.aivideostudio.media.video.FastNativeFrameExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

enum class LogType { INFO, SUCCESS, WARNING, ERROR, NET }

data class TerminalLogEntry(
    val timestamp: String,
    val message: String,
    val type: LogType
)

data class TargetRule(
    val id: Long,
    val text: String,
    val category: String,
    val tool: String,
    val isZipSource: Boolean = false,
    val clusterCenter: ClusterCenterPoint? = null
)

data class ClusterCenterPoint(
    val x: Float,
    val y: Float,
    val threshold: Float
)

data class AudioCueUiModel(
    val id: Int,
    val startTime: String,
    val endTime: String,
    val timeStr: String,
    val snippet: String,
    val startFrame: Int,
    val endFrame: Int
)

data class ToolsUiState(
    val videoUri: Uri? = null,
    val videoWidth: Int = 1080,
    val videoHeight: Int = 2400,
    val isPlaying: Boolean = false,
    val isAudioMuted: Boolean = false,
    val isArrowPointerEnabled: Boolean = true,
    val currentFrameIndex: Int = 0,
    val targetFps: Int = 12,
    val frames: List<ExtractedFrame> = emptyList(),
    val extractedOcrData: Map<Int, FrameOcrData> = emptyMap(),
    val directBlurs: Map<Int, List<DetectedTargetBox>> = emptyMap(),
    
    // Status Badge & Progress
    val statusText: String = "Ready",
    val statusColorHex: String = "#0284c7",
    val progressPercent: Int = 0,
    val isProcessing: Boolean = false,

    // Target Panel Rules & Clusters (Tab 1)
    val activeRules: List<TargetRule> = emptyList(),
    val detectedClusters: List<SpatialCluster> = emptyList(),

    // Wizard Step State (1: Gemini, 2: Auto-Scan, 3: Export)
    val wizardStep: Int = 1,

    // Gemini AI Settings (Step 1)
    val geminiApiKey: String = "",
    val selectedGeminiModel: String = "models/gemini-2.5-flash",
    val availableGeminiModels: List<String> = listOf(
        "models/gemini-2.5-flash",
        "models/gemini-1.5-flash",
        "models/gemini-1.5-pro"
    ),

    // Auto-Scan ZIP State (Step 2)
    val activeZipTarget: String = "",
    val activeZipTool: String = "button_highlight",
    val rawDetectedZipBoxes: List<DetectedTargetBox> = emptyList(),
    val detectedZipClusters: List<SpatialCluster> = emptyList(),
    val selectedZipClusterId: String = "all",
    val detectedZipTimeSlots: List<TimeSlotSession> = emptyList(),
    val selectedZipTimeSlotId: String = "all",
    val detectedAudioCues: List<AudioCueUiModel> = emptyList(),
    val selectedAudioCueId: String = "all",

    // Audio Cues & Transcripts
    val transcriptCues: List<AudioCueSegment> = emptyList(),
    val activeLogEntries: List<TerminalLogEntry> = emptyList(),

    // Export Coordinates (Step 3)
    val exportedCoordinatesJson: String = "{\n  \"frames\": []\n}",

    // Editor Playback Slot Feature
    val slotStartFrame: Int? = null,
    val slotEndFrame: Int? = null,
    val slotStep: Int = 0, // 0: IDLE, 1: START_SET, 2: PLAYING_SLOT, 3: END_SET (Show Modal)

    // Render Progress State (Tab 3)
    val isRendering: Boolean = false,
    val renderPercent: Int = 0,
    val renderProgressStatus: String = "Ready"
)

class ToolsViewModel(
    application: Application,
    private val geminiPreferences: GeminiPreferences,
    private val frameExtractor: FastNativeFrameExtractor,
    private val ocrEngine: NativeBatchOcrEngine,
    private val audioExtractor: AudioExtractor
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var playbackJob: Job? = null
    private var extractionJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        val savedKey = geminiPreferences.getApiKey()
        _uiState.update { it.copy(geminiApiKey = savedKey) }
        addLog("🤖 Gemini Native Audio Engine Ready. Awaiting user action.", LogType.INFO)
    }

    // =========================================================
    // TERMINAL LOGGING
    // =========================================================
    fun addLog(message: String, type: LogType = LogType.INFO) {
        val timestamp = timeFormatter.format(Date())
        val newEntry = TerminalLogEntry("[$timestamp]", message, type)
        _uiState.update { it.copy(activeLogEntries = it.activeLogEntries + newEntry) }
    }

    fun clearLogs() {
        _uiState.update { it.copy(activeLogEntries = emptyList()) }
        addLog("🧹 Terminal log cleared.", LogType.INFO)
    }

    fun setWizardStep(step: Int) {
        _uiState.update { it.copy(wizardStep = step.coerceIn(1, 3)) }
    }

    // =========================================================
    // VIDEO LOAD & FRAME EXTRACTION
    // =========================================================
    fun setVideoUri(uri: Uri) {
        frameExtractor.clearWorkspace()
        releaseMediaPlayer()

        var vWidth = 1080
        var vHeight = 2400

        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(getApplication<Application>(), uri)
            val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1080
            val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 2400
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                vWidth = h
                vHeight = w
            } else {
                vWidth = w
                vHeight = h
            }
            retriever.release()
        } catch (_: Exception) {}

        initMediaPlayer(uri)

        _uiState.update { 
            ToolsUiState(
                videoUri = uri,
                videoWidth = vWidth,
                videoHeight = vHeight,
                geminiApiKey = it.geminiApiKey,
                activeLogEntries = it.activeLogEntries
            ) 
        }
        addLog("📹 Video loaded into Native Studio workspace ($vWidth x $vHeight).", LogType.INFO)
    }

    private fun initMediaPlayer(uri: Uri) {
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(getApplication<Application>(), uri)
                prepare()
                val vol = if (_uiState.value.isAudioMuted) 0f else 1f
                setVolume(vol, vol)
            }
        } catch (e: Exception) {
            addLog("⚠️ Notice: Audio player initialization skipped (${e.message})", LogType.WARNING)
        }
    }

    private fun releaseMediaPlayer() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    fun extractAllFrames(targetFps: Int = 12) {
        val uri = _uiState.value.videoUri ?: return
        
        _uiState.update { it.copy(
            isProcessing = true, 
            targetFps = targetFps,
            statusText = "Extracting...", 
            statusColorHex = "#eab308"
        ) }
        addLog("🎞️ Starting hardware-accelerated frame extraction at $targetFps FPS...", LogType.INFO)

        extractionJob?.cancel()
        extractionJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val extractedList = frameExtractor.extractFrames(uri, targetFps) { current, total ->
                    val pct = ((current.toFloat() / total.toFloat()) * 100).toInt()
                    _uiState.update { it.copy(
                        statusText = "Extracting $current/$total",
                        progressPercent = pct
                    )}
                }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        frames = extractedList,
                        isProcessing = false,
                        statusText = "${extractedList.size} Frames Ready",
                        statusColorHex = "#10b981",
                        progressPercent = 0
                    )}
                    addLog("✅ Successfully extracted ${extractedList.size} frames to persistent cache.", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ Extraction Error: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(isProcessing = false, statusText = "Extraction Failed", statusColorHex = "#ef4444") }
                }
            }
        }
    }

    // =========================================================
    // VIDEO TRANSPORT, STEPPING, AUDIO & SLOT CYCLE
    // =========================================================
    fun togglePlayPause() {
        if (_uiState.value.isPlaying) pausePlayback() else startPlayback()
    }

    private fun startPlayback() {
        val state = _uiState.value
        if (state.frames.isEmpty()) return

        _uiState.update { it.copy(isPlaying = true) }
        if (state.slotStep == 1) {
            _uiState.update { it.copy(slotStep = 2) }
        }

        try {
            val seekMs = (state.currentFrameIndex * (1000f / state.targetFps)).toLong()
            mediaPlayer?.seekTo(seekMs.toInt())
            val vol = if (state.isAudioMuted) 0f else 1f
            mediaPlayer?.setVolume(vol, vol)
            mediaPlayer?.start()
        } catch (_: Exception) {}

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val fps = _uiState.value.targetFps
            val frameIntervalMs = (1000L / fps).coerceAtLeast(10L)
            
            var currentIndex = _uiState.value.currentFrameIndex
            while (currentIndex < _uiState.value.frames.size - 1) {
                val startTime = System.currentTimeMillis()
                currentIndex++
                _uiState.update { it.copy(currentFrameIndex = currentIndex) }
                
                val elapsed = System.currentTimeMillis() - startTime
                val sleepTime = (frameIntervalMs - elapsed).coerceAtLeast(5L)
                delay(sleepTime)
            }
            pausePlayback()
        }
    }

    private fun pausePlayback() {
        playbackJob?.cancel()
        try {
            mediaPlayer?.pause()
        } catch (_: Exception) {}

        val state = _uiState.value
        var newSlotStep = state.slotStep
        var newSlotStart = state.slotStartFrame
        var newSlotEnd = state.slotEndFrame

        if (state.slotStep == 0) {
            newSlotStart = state.currentFrameIndex
            newSlotStep = 1
        } else if (state.slotStep == 2) {
            newSlotEnd = state.currentFrameIndex
            newSlotStep = 3
        }

        _uiState.update { it.copy(
            isPlaying = false,
            slotStep = newSlotStep,
            slotStartFrame = newSlotStart,
            slotEndFrame = newSlotEnd
        )}
    }

    fun stepFrame(delta: Int) {
        playbackJob?.cancel()
        try { mediaPlayer?.pause() } catch (_: Exception) {}

        val state = _uiState.value
        if (state.frames.isEmpty()) return
        val newIndex = (state.currentFrameIndex + delta).coerceIn(0, state.frames.size - 1)
        
        try {
            val seekMs = (newIndex * (1000f / state.targetFps)).toLong()
            mediaPlayer?.seekTo(seekMs.toInt())
        } catch (_: Exception) {}

        _uiState.update { it.copy(isPlaying = false, currentFrameIndex = newIndex) }
    }

    fun seekToFrame(index: Int) {
        playbackJob?.cancel()
        try { mediaPlayer?.pause() } catch (_: Exception) {}

        val state = _uiState.value
        val safeIndex = index.coerceIn(0, maxOf(0, state.frames.size - 1))
        
        try {
            val seekMs = (safeIndex * (1000f / state.targetFps)).toLong()
            mediaPlayer?.seekTo(seekMs.toInt())
        } catch (_: Exception) {}

        _uiState.update { it.copy(isPlaying = false, currentFrameIndex = safeIndex) }
    }

    fun toggleAudioMute() {
        val newMute = !_uiState.value.isAudioMuted
        _uiState.update { it.copy(isAudioMuted = newMute) }
        try {
            val vol = if (newMute) 0f else 1f
            mediaPlayer?.setVolume(vol, vol)
        } catch (_: Exception) {}
        addLog(if (newMute) "🔇 Audio muted." else "🔊 Audio unmuted (100% Volume).", LogType.INFO)
    }

    fun toggleFrameHighlight(frameIndex: Int) {
        val updatedFrames = _uiState.value.frames.map { f ->
            if (f.index == frameIndex) f.copy(isHighlightEnabled = !f.isHighlightEnabled) else f
        }
        _uiState.update { it.copy(frames = updatedFrames) }
        updateExportedJsonState()
    }

    fun resetSlotCycle() {
        _uiState.update { it.copy(slotStep = 0, slotStartFrame = null, slotEndFrame = null) }
    }

    // =========================================================
    // NATIVE ML KIT OCR SCANNING
    // =========================================================
    fun scanCurrentFrame() {
        val state = _uiState.value
        val frame = state.frames.getOrNull(state.currentFrameIndex) ?: return
        
        _uiState.update { it.copy(statusText = "Scanning Frame...", statusColorHex = "#eab308") }
        
        viewModelScope.launch(Dispatchers.Default) {
            val ocrResult = ocrEngine.scanFrame(frame)
            val updatedMap = _uiState.value.extractedOcrData.toMutableMap()
            updatedMap[frame.index] = ocrResult
            
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(
                    extractedOcrData = updatedMap,
                    statusText = "Frame #${frame.index} Ready",
                    statusColorHex = "#10b981"
                )}
                updateExportedJsonState()
            }
        }
    }

    fun scanCapturedSlot() {
        val state = _uiState.value
        val start = state.slotStartFrame ?: 0
        val end = state.slotEndFrame ?: 0
        val minF = minOf(start, end)
        val maxF = maxOf(start, end)
        
        val framesToScan = state.frames.filter { it.index in minF..maxF }
        if (framesToScan.isEmpty()) return

        _uiState.update { it.copy(isProcessing = true, statusText = "Ultra-Fast OCR...", statusColorHex = "#eab308", slotStep = 0) }
        addLog("🚀 Initiating parallel native OCR scan on ${framesToScan.size} frames...", LogType.INFO)

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val batchResults = ocrEngine.scanBatch(framesToScan, parallelWorkers = 3) { current, total ->
                    val pct = ((current.toFloat() / total.toFloat()) * 100).toInt()
                    _uiState.update { it.copy(
                        statusText = "OCR: $current/$total",
                        progressPercent = pct
                    )}
                }

                val updatedMap = _uiState.value.extractedOcrData.toMutableMap()
                updatedMap.putAll(batchResults)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        extractedOcrData = updatedMap,
                        isProcessing = false,
                        progressPercent = 0,
                        statusText = "Scanned ${framesToScan.size} Frames",
                        statusColorHex = "#10b981"
                    )}
                    addLog("✅ Ultra-Fast OCR completed successfully.", LogType.SUCCESS)
                    updateExportedJsonState()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ OCR Batch Error: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(isProcessing = false, statusText = "OCR Failed", statusColorHex = "#ef4444") }
                }
            }
        }
    }

    // =========================================================
    // TARGET PANEL, CLUSTERING & DRIFT PURGE (TAB 1)
    // =========================================================
    fun updateTargetClusters(query: String) {
        if (query.trim().length < 2) {
            _uiState.update { it.copy(detectedClusters = emptyList()) }
            return
        }

        val allMatches = mutableListOf<DetectedTargetBox>()
        for ((frameIdx, ocrData) in _uiState.value.extractedOcrData) {
            val boxes = SpatialClusterer.findMatchingBoundingBoxes(ocrData.lines, query)
            for (b in boxes) {
                allMatches.add(
                    DetectedTargetBox(
                        x0 = b.x0, y0 = b.y0, width = b.width, height = b.height,
                        text = query, tool = "button_highlight", frame = frameIdx,
                        time = ocrData.time
                    )
                )
            }
        }

        val refW = _uiState.value.videoWidth
        val refH = _uiState.value.videoHeight
        val clusters = SpatialClusterer.clusterBoxes(allMatches, refW, refH)
        _uiState.update { it.copy(detectedClusters = clusters) }
    }

    fun addTargetRule(keyword: String, category: String, tool: String, clusterId: String = "all") {
        if (keyword.isBlank()) return
        
        var clusterCenter: ClusterCenterPoint? = null
        if (clusterId != "all") {
            val selectedCluster = _uiState.value.detectedClusters.find { it.id.toString() == clusterId }
            if (selectedCluster != null) {
                clusterCenter = ClusterCenterPoint(selectedCluster.centerX, selectedCluster.centerY, selectedCluster.threshold)
            }
        }

        val newRule = TargetRule(
            id = System.currentTimeMillis(),
            text = keyword.trim(),
            category = category,
            tool = tool,
            isZipSource = false,
            clusterCenter = clusterCenter
        )
        
        _uiState.update { it.copy(activeRules = it.activeRules + newRule) }
        evaluateHighlightMatches()
    }

    fun removeTargetRule(id: Long) {
        _uiState.update { state -> 
            state.copy(activeRules = state.activeRules.filter { it.id != id }) 
        }
        evaluateHighlightMatches()
    }

    fun setArrowPointerEnabled(enabled: Boolean) {
        _uiState.update { it.copy(isArrowPointerEnabled = enabled) }
    }

    fun discardDriftHighlights() {
        val state = _uiState.value
        if (state.activeRules.isEmpty()) return

        var unmarkedCount = 0
        val updatedFrames = state.frames.map { f ->
            val ocr = state.extractedOcrData[f.index]
            var hasExactMatch = false

            if (ocr != null) {
                for (rule in state.activeRules) {
                    val matches = SpatialClusterer.findMatchingBoundingBoxes(ocr.lines, rule.text)
                    if (matches.isNotEmpty()) {
                        hasExactMatch = true
                        break
                    }
                }
            }

            if (!hasExactMatch && f.isHighlightEnabled) {
                unmarkedCount++
                f.copy(isHighlightEnabled = false, hasMismatch = true)
            } else if (hasExactMatch) {
                f.copy(isHighlightEnabled = true, hasMismatch = false)
            } else {
                f
            }
        }

        _uiState.update { it.copy(frames = updatedFrames) }
        addLog("🧹 Discarded wrong highlights on $unmarkedCount non-matching frames.", LogType.INFO)
        updateExportedJsonState()
    }

    private fun evaluateHighlightMatches() {
        val state = _uiState.value
        val updatedFrames = state.frames.map { f ->
            val ocr = state.extractedOcrData[f.index]
            var hasMatch = false
            if (ocr != null) {
                for (rule in state.activeRules) {
                    val matches = SpatialClusterer.findMatchingBoundingBoxes(ocr.lines, rule.text)
                    if (matches.isNotEmpty()) {
                        hasMatch = true
                        break
                    }
                }
            }
            if (hasMatch) f.copy(isHighlightEnabled = true) else f
        }
        _uiState.update { it.copy(frames = updatedFrames) }
        updateExportedJsonState()
    }

    // =========================================================
    // GEMINI SETTINGS & LIVE MODEL DISCOVERY (STEP 1)
    // =========================================================
    fun setGeminiApiKey(key: String) {
        geminiPreferences.setApiKey(key.trim())
        _uiState.update { it.copy(geminiApiKey = key.trim()) }
    }

    fun setSelectedGeminiModel(model: String) {
        _uiState.update { it.copy(selectedGeminiModel = model) }
        addLog("Active model switched to: $model", LogType.INFO)
    }

    fun fetchGeminiModels(apiKey: String) {
        if (apiKey.isBlank()) {
            addLog("❌ Error: API Key input is empty.", LogType.ERROR)
            return
        }

        addLog("🌐 Fetching live generateContent models from Google AI Studio...", LogType.NET)
        _uiState.update { it.copy(statusText = "Fetching Models...", statusColorHex = "#eab308") }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
                val url = URL(endpoint)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"

                if (connection.responseCode != 200) {
                    val err = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw Exception("HTTP ${connection.responseCode}: $err")
                }

                val jsonResponse = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonResponse)
                val modelsArray = root.optJSONArray("models") ?: JSONArray()
                val parsedModels = mutableListOf<String>()

                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    var canGenerate = false
                    for (j in 0 until methods.length()) {
                        if (methods.getString(j) == "generateContent") canGenerate = true
                    }
                    if (canGenerate) {
                        parsedModels.add(m.getString("name"))
                    }
                }

                if (parsedModels.isEmpty()) throw Exception("No generateContent models found.")

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        availableGeminiModels = parsedModels,
                        selectedGeminiModel = parsedModels.find { it.contains("2.5-flash") || it.contains("1.5-flash") } ?: parsedModels.first(),
                        statusText = "Loaded ${parsedModels.size} Models!",
                        statusColorHex = "#10b981"
                    )}
                    addLog("✅ Loaded ${parsedModels.size} models from Google AI Studio.", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ Model Discovery Failed: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(statusText = "Models Failed", statusColorHex = "#ef4444") }
                }
            }
        }
    }

    fun downloadTranscriptJson() {
        val cues = _uiState.value.transcriptCues
        if (cues.isEmpty()) return

        val jsonArray = JSONArray()
        for (c in cues) {
            val obj = JSONObject()
            obj.put("start", c.start)
            obj.put("end", c.end)
            obj.put("text", c.text)
            jsonArray.put(obj)
        }

        saveTextFileToStorage(jsonArray.toString(2), "transcript.json")
    }

    // =========================================================
    // NATIVE GEMINI AUDIO TRANSCRIPTION (STEP 1 PIPELINE)
    // =========================================================
    fun transcribeAudioWithGemini(apiKey: String, modelName: String) {
        val uri = _uiState.value.videoUri
        if (uri == null) {
            addLog("❌ Validation Failed: No media file loaded.", LogType.ERROR)
            return
        }

        if (apiKey.isBlank()) {
            addLog("❌ Validation Failed: Gemini API Key field is empty.", LogType.ERROR)
            return
        }

        _uiState.update { it.copy(isProcessing = true, statusText = "Extracting Audio...", statusColorHex = "#eab308") }
        addLog("========================================", LogType.INFO)
        addLog("🚀 [PIPELINE START] Native Audio Transcription.", LogType.INFO)

        viewModelScope.launch(Dispatchers.IO) {
            val audioOutputFile = File(getApplication<Application>().cacheDir, "temp_extracted_audio.m4a")
            try {
                addLog("🎧 Step 1/5: Demuxing audio stream via MediaExtractor & MediaMuxer...", LogType.INFO)
                extractAudioTrackNative(uri, audioOutputFile)
                
                val audioSizeMb = audioOutputFile.length() / (1024f * 1024f)
                addLog("✅ Audio extraction complete (${String.format(Locale.US, "%.2f", audioSizeMb)} MB M4A container).", LogType.SUCCESS)

                addLog("🔄 Step 2/5: Converting audio payload to Base64...", LogType.INFO)
                val audioBytes = audioOutputFile.readBytes()
                val base64Audio = android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP)
                addLog("✅ Base64 payload created (${base64Audio.length} characters).", LogType.INFO)

                val cleanModel = if (modelName.startsWith("models/")) modelName else "models/$modelName"
                val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/$cleanModel:generateContent?key=$apiKey"

                addLog("🌐 Step 3/5: Connecting to Google AI Studio ($cleanModel)...", LogType.NET)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusText = "Gemini AI Transcribing...") }
                }

                val payloadObj = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Listen to this audio track. Transcribe all spoken speech accurately. Output a JSON array of speech segments where each object has: \"start\" (start time in seconds as a float number), \"end\" (end time in seconds as a float number), and \"text\" (exact spoken words string). Example format: [{\"start\": 3.2, \"end\": 4.8, \"text\": \"click on playground\"}].")
                            })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "audio/mp4")
                                    put("data", base64Audio)
                                })
                            })
                        })
                    }))
                    put("generationConfig", JSONObject().apply {
                        put("responseMimeType", "application/json")
                    })
                }

                val startTime = System.currentTimeMillis()
                val url = URL(endpointUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 60000
                connection.readTimeout = 60000

                connection.outputStream.use { os ->
                    val input = payloadObj.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                val responseCode = connection.responseCode
                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                addLog("📥 Response received in ${String.format(Locale.US, "%.1f", elapsedSec)}s. HTTP Status: $responseCode ${connection.responseMessage}", if (responseCode == 200) LogType.SUCCESS else LogType.ERROR)

                if (responseCode != 200) {
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    throw Exception("HTTP $responseCode: $errorStream")
                }

                val responseJson = connection.inputStream.bufferedReader().use { it.readText() }
                addLog("📄 Step 4/5: Parsing candidate response from Gemini JSON payload...", LogType.INFO)

                val rootNode = JSONObject(responseJson)
                val candidates = rootNode.optJSONArray("candidates") ?: throw Exception("No candidates returned from Gemini.")
                val rawText = candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")

                addLog("📝 Extracted raw text. Parsing JSON array...", LogType.INFO)
                
                val parsedCues = NativeTimelineZipManager.parseTranscript(rawText)

                if (parsedCues.isEmpty()) {
                    addLog("⚠️ Warning: Transcription completed, but 0 speech segments were detected.", LogType.WARNING)
                } else {
                    addLog("🎉 SUCCESS: Received ${parsedCues.size} timestamped speech cues!", LogType.SUCCESS)
                }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        transcriptCues = parsedCues,
                        isProcessing = false,
                        statusText = "Transcribed ${parsedCues.size} Cues!",
                        statusColorHex = "#10b981"
                    )}
                    filterAudioCues("")
                }

                // Auto-save transcript to device Downloads folder via MediaStore
                saveTextFileToStorage(rawText, "transcript.json")
                addLog("========================================", LogType.INFO)

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    addLog("❌ PIPELINE EXCEPTION: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(isProcessing = false, statusText = "Transcription Failed", statusColorHex = "#ef4444") }
                }
            } finally {
                if (audioOutputFile.exists()) {
                    audioOutputFile.delete()
                }
            }
        }
    }

    // =========================================================
    // AUTO-SCAN ZIP, SPATIAL, TEMPORAL & AUDIO FILTERS (STEP 2)
    // =========================================================
    fun filterAudioCues(query: String) {
        val cues = _uiState.value.transcriptCues
        if (cues.isEmpty()) {
            _uiState.update { it.copy(detectedAudioCues = emptyList()) }
            return
        }

        val cleanQ = SpatialClusterer.clean(query)
        val fps = _uiState.value.targetFps
        val filteredList = mutableListOf<AudioCueUiModel>()

        // Check direct timestamp list (e.g., "17s, 30s")
        val numbersRegex = Regex("\\d+(?:\\.\\d+)?")
        val matches = numbersRegex.findAll(query).map { it.value }.toList()
        val isPureNumbers = matches.isNotEmpty() && query.replace(Regex("[\\d.,\\s]"), "").length <= 2

        if (isPureNumbers) {
            matches.forEachIndexed { idx, tStr ->
                val t = tStr.toFloatOrNull() ?: 0f
                val start = maxOf(0f, t - 0.3f)
                val end = t + 2.0f
                filteredList.add(
                    AudioCueUiModel(
                        id = idx,
                        startTime = String.format(Locale.US, "%.1f", start),
                        endTime = String.format(Locale.US, "%.1f", end),
                        timeStr = "${t}s",
                        snippet = "Direct cue at ${t}s",
                        startFrame = floor(start * fps).toInt(),
                        endFrame = ceil(end * fps).toInt()
                    )
                )
            }
        } else {
            cues.forEachIndexed { idx, seg ->
                if (cleanQ.isEmpty() || SpatialClusterer.clean(seg.text).contains(cleanQ) || cleanQ.contains(SpatialClusterer.clean(seg.text))) {
                    val start = maxOf(0f, seg.start - 0.3f)
                    val end = seg.end + 0.8f
                    filteredList.add(
                        AudioCueUiModel(
                            id = idx,
                            startTime = String.format(Locale.US, "%.1f", start),
                            endTime = String.format(Locale.US, "%.1f", end),
                            timeStr = "${String.format(Locale.US, "%.1f", seg.start)}s",
                            snippet = seg.text,
                            startFrame = floor(start * fps).toInt(),
                            endFrame = ceil(end * fps).toInt()
                        )
                    )
                }
            }
        }

        _uiState.update { it.copy(detectedAudioCues = filteredList) }
        reapplyZipFilters()
    }

    fun setZipClusterFilter(clusterId: String) {
        _uiState.update { it.copy(selectedZipClusterId = clusterId) }
        reapplyZipFilters()
    }

    fun setZipTimeSlotFilter(slotId: String) {
        _uiState.update { it.copy(selectedZipTimeSlotId = slotId) }
        reapplyZipFilters()
    }

    fun setAudioCueFilter(cueId: String) {
        _uiState.update { it.copy(selectedAudioCueId = cueId) }
        reapplyZipFilters()
    }

    fun loadExternalTranscriptFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri) ?: return@launch
                val text = inputStream.bufferedReader().use { it.readText() }
                val parsed = NativeTimelineZipManager.parseTranscript(text)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        transcriptCues = parsed,
                        statusText = "${parsed.size} Speech Cues Loaded",
                        statusColorHex = "#10b981"
                    )}
                    filterAudioCues("")
                    addLog("📂 Loaded external transcript file (${parsed.size} cues).", LogType.INFO)
                }
            } catch (e: Exception) {
                addLog("❌ Failed to parse external transcript: ${e.message}", LogType.ERROR)
            }
        }
    }

    fun fetchZipFromUrl(urlStr: String, filterText: String, selectedTool: String) {
        if (urlStr.isBlank()) return
        val actualQuery = if (filterText.isBlank()) "Target" else filterText.trim()
        addLog("🌐 Fetching ZIP from URL: $urlStr for '$actualQuery'...", LogType.NET)
        _uiState.update { it.copy(isProcessing = true, statusText = "Fetching ZIP...", statusColorHex = "#eab308") }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                if (connection.responseCode != 200) {
                    throw Exception("HTTP ${connection.responseCode}: ${connection.responseMessage}")
                }

                val zipResult = NativeTimelineZipManager.parseZip(connection.inputStream, actualQuery, selectedTool)
                processZipResult(zipResult, actualQuery, selectedTool)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ ZIP Fetch Failed: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(isProcessing = false, statusText = "Fetch Failed", statusColorHex = "#ef4444") }
                }
            }
        }
    }

    fun processLocalZip(uri: Uri, targetQuery: String = "Target", selectedTool: String = "button_highlight") {
        val actualQuery = if (targetQuery.isBlank()) "Target" else targetQuery.trim()
        addLog("📂 Loading ZIP archive from device storage for target: '$actualQuery'...", LogType.INFO)
        _uiState.update { it.copy(isProcessing = true, statusText = "Analyzing ZIP...", statusColorHex = "#eab308") }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw Exception("Could not open ZIP stream.")
                
                val zipResult = NativeTimelineZipManager.parseZip(inputStream, actualQuery, selectedTool)
                processZipResult(zipResult, actualQuery, selectedTool)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ Local ZIP Error: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(isProcessing = false, statusText = "ZIP Error", statusColorHex = "#ef4444") }
                }
            }
        }
    }

    private suspend fun processZipResult(result: com.vineyard.aivideostudio.media.tools.ZipScanResult, targetQuery: String, selectedTool: String) {
        if (result.detectedBoxes.isEmpty()) {
            withContext(Dispatchers.Main) {
                addLog("⚠️ Target '$targetQuery' was not found in any frame of this ZIP.", LogType.WARNING)
                _uiState.update { it.copy(isProcessing = false, statusText = "Target Not Found", statusColorHex = "#ef4444") }
            }
            return
        }

        val refW = _uiState.value.videoWidth
        val refH = _uiState.value.videoHeight
        val clusters = SpatialClusterer.clusterBoxes(result.detectedBoxes, refW, refH)
        val timeSlots = SpatialClusterer.segmentTimeSlots(result.detectedBoxes, _uiState.value.targetFps)

        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(
                activeZipTarget = targetQuery,
                activeZipTool = selectedTool,
                rawDetectedZipBoxes = result.detectedBoxes,
                detectedZipClusters = clusters,
                detectedZipTimeSlots = timeSlots,
                transcriptCues = if (result.transcript.isNotEmpty()) result.transcript else it.transcriptCues,
                isProcessing = false
            )}
            if (result.transcript.isNotEmpty()) {
                filterAudioCues("")
            }
            reapplyZipFilters()
            addLog("🎉 Processed ZIP: Found ${result.detectedBoxes.size} boxes across ${clusters.size} clusters and ${timeSlots.size} slots.", LogType.SUCCESS)
        }
    }

    private fun reapplyZipFilters() {
        val state = _uiState.value
        if (state.rawDetectedZipBoxes.isEmpty()) return

        var filteredBoxes = state.rawDetectedZipBoxes
        val targetQuery = state.activeZipTarget.ifBlank { "Target" }
        val selectedTool = state.activeZipTool.ifBlank { "button_highlight" }
        var activeCluster: SpatialCluster? = null

        // 1. Spatial Filter
        if (state.selectedZipClusterId != "all" && state.detectedZipClusters.isNotEmpty()) {
            val cl = state.detectedZipClusters.find { it.id.toString() == state.selectedZipClusterId }
            if (cl != null) {
                filteredBoxes = cl.boxes
                activeCluster = cl
            }
        }

        // 2. Temporal Slot Filter
        if (state.selectedZipTimeSlotId != "all" && state.detectedZipTimeSlots.isNotEmpty()) {
            val slot = state.detectedZipTimeSlots.find { it.id.toString() == state.selectedZipTimeSlotId }
            if (slot != null) {
                filteredBoxes = filteredBoxes.filter { slot.frameSet.contains(it.frame) }
            }
        }

        // 3. Audio Cue Sync Filter (matches exact HTML frame-range logic)
        if (state.selectedAudioCueId != "all" && state.detectedAudioCues.isNotEmpty()) {
            val cue = state.detectedAudioCues.find { it.id.toString() == state.selectedAudioCueId }
            if (cue != null) {
                filteredBoxes = filteredBoxes.filter { it.frame in cue.startFrame..cue.endFrame }
            }
        }

        // Group into direct blurs map
        val blurMap = mutableMapOf<Int, MutableList<DetectedTargetBox>>()
        for (b in filteredBoxes) {
            blurMap.getOrPut(b.frame) { mutableListOf() }.add(b)
        }

        val updatedFrames = state.frames.map { f ->
            val hasDirect = blurMap.containsKey(f.index)
            if (hasDirect) f.copy(isHighlightEnabled = true) else f.copy(isHighlightEnabled = false)
        }

        // Also synchronize activeRules with the ZIP source so overlays activate in Studio Viewer
        val updatedRules = state.activeRules.filter { it.text != targetQuery }.toMutableList()
        updatedRules.add(
            TargetRule(
                id = System.currentTimeMillis(),
                text = targetQuery,
                category = "ZIP: Active Filter",
                tool = selectedTool,
                isZipSource = true,
                clusterCenter = activeCluster?.let {
                    ClusterCenterPoint(it.centerX, it.centerY, it.threshold)
                }
            )
        )

        _uiState.update { it.copy(
            directBlurs = blurMap,
            frames = updatedFrames,
            activeRules = updatedRules,
            statusText = "Applied to ${blurMap.size} frames!",
            statusColorHex = "#10b981"
        )}

        updateExportedJsonState()
    }

    // =========================================================
    // JSON & ZIP EXPORTER (STEP 3)
    // =========================================================
    fun applyPastedJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val framesArr = root.optJSONArray("frames") ?: JSONArray()
            val tool = root.optString("tool", "button_highlight")
            val target = root.optString("target", "Pasted Target")

            val blurMap = mutableMapOf<Int, MutableList<DetectedTargetBox>>()

            for (i in 0 until framesArr.length()) {
                val fObj = framesArr.getJSONObject(i)
                val frameIdx = fObj.getInt("frame")
                val time = fObj.optDouble("time", 0.0).toFloat()
                val boxesArr = fObj.optJSONArray("boxes") ?: JSONArray()

                for (j in 0 until boxesArr.length()) {
                    val b = boxesArr.getJSONObject(j)
                    val box = DetectedTargetBox(
                        x0 = b.getDouble("x0").toFloat(),
                        y0 = b.getDouble("y0").toFloat(),
                        width = b.getDouble("width").toFloat(),
                        height = b.getDouble("height").toFloat(),
                        text = b.optString("text", target),
                        tool = b.optString("tool", tool),
                        frame = frameIdx,
                        time = time
                    )
                    blurMap.getOrPut(frameIdx) { mutableListOf() }.add(box)
                }
            }

            val updatedFrames = _uiState.value.frames.map { f ->
                if (blurMap.containsKey(f.index)) f.copy(isHighlightEnabled = true) else f.copy(isHighlightEnabled = false)
            }

            _uiState.update { it.copy(
                directBlurs = blurMap,
                frames = updatedFrames,
                statusText = "Applied ${blurMap.size} frames from JSON!",
                statusColorHex = "#10b981"
            )}

            addLog("📋 Successfully applied tool coordinates to ${blurMap.size} video frames!", LogType.SUCCESS)
            updateExportedJsonState()
        } catch (e: Exception) {
            addLog("❌ Invalid JSON: ${e.message}", LogType.ERROR)
        }
    }

    fun downloadCoordinatesJson() {
        val json = _uiState.value.exportedCoordinatesJson
        saveTextFileToStorage(json, "active_highlighted_frames.json")
    }

    fun downloadTimelineZip() {
        val scanned = _uiState.value.extractedOcrData
        if (scanned.isEmpty()) {
            addLog("⚠️ No frames scanned yet! Please scan frames first.", LogType.WARNING)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val zipFrames = scanned.values.map { ocr ->
                ZipOcrFrame(
                    frameIndex = ocr.frameIndex,
                    time = ocr.time,
                    lines = ocr.lines,
                    rawJson = ocr.toJsonString()
                )
            }

            val filename = "frames_timeline_data.zip"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Could not allocate MediaStore entry for ZIP.")
                    resolver.openOutputStream(uri)?.use { os ->
                        NativeTimelineZipManager.createTimelineZip(zipFrames, os)
                    }
                } else {
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val zipFile = File(downloadsDir, filename)
                    FileOutputStream(zipFile).use { fos ->
                        NativeTimelineZipManager.createTimelineZip(zipFrames, fos)
                    }
                }

                withContext(Dispatchers.Main) {
                    addLog("📦 Downloaded ZIP (${zipFrames.size} files) to Downloads folder.", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ ZIP Export Error: ${e.message}", LogType.ERROR)
                }
            }
        }
    }

    private fun updateExportedJsonState() {
        val state = _uiState.value
        val allBoxes = mutableListOf<DetectedTargetBox>()
        for ((_, list) in state.directBlurs) {
            allBoxes.addAll(list)
        }

        val json = NativeTimelineZipManager.exportHighlightedFramesJson(
            tool = state.activeZipTool.ifBlank { "button_highlight" },
            target = state.activeZipTarget.ifBlank { state.activeRules.firstOrNull()?.text ?: "Target" },
            location = "All Locations",
            timeline = "All Time Slots",
            audio = "All Cues",
            frames = allBoxes
        )

        _uiState.update { it.copy(exportedCoordinatesJson = json) }
    }

    // =========================================================
    // VIDEO RENDERING PIPELINE (BURNS HIGHLIGHTS & ORIGINAL AUDIO)
    // =========================================================
    fun renderFullVideo() {
        val state = _uiState.value
        val uri = state.videoUri
        if (state.frames.isEmpty() || uri == null) {
            addLog("⚠️ No video frames extracted yet! Extract frames in Studio Viewer first.", LogType.WARNING)
            return
        }

        _uiState.update { it.copy(isRendering = true, renderPercent = 0, renderProgressStatus = "Preparing Video & Audio...") }
        addLog("🎬 Starting full video rendering with original synchronized audio...", LogType.INFO)

        viewModelScope.launch(Dispatchers.Default) {
            val context = getApplication<Application>()
            val tempAudioFile = File(context.cacheDir, "temp_render_audio.m4a")
            val tempVideoFile = File(context.cacheDir, "temp_render_video.mp4")
            val tempFinalFile = File(context.cacheDir, "temp_render_final.mp4")

            try {
                // Step 1: Extract audio track from original video
                var hasAudio = false
                try {
                    extractAudioTrackNative(uri, tempAudioFile)
                    hasAudio = tempAudioFile.exists() && tempAudioFile.length() > 0
                } catch (audioErr: Exception) {
                    addLog("⚠️ Audio extract skipped: ${audioErr.message}", LogType.WARNING)
                }

                // Step 2: Encode video frames with burned overlays using native MediaCodec
                val outWidth = (state.videoWidth / 2) * 2
                val outHeight = (state.videoHeight / 2) * 2
                val fps = state.targetFps
                val bitRate = 4_000_000 // 4 Mbps high quality

                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = encoder.createInputSurface()
                encoder.start()

                val videoMuxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var videoTrackIndex = -1
                var muxerStarted = false

                val bufferInfo = MediaCodec.BufferInfo()
                val totalFrames = state.frames.size
                val frameDurationUs = (1_000_000L / fps)

                // Paint tools for burning overlays into video frames
                val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                }

                for (i in 0 until totalFrames) {
                    val frameObj = state.frames[i]
                    val ptsUs = i * frameDurationUs

                    // Load full resolution frame
                    val fullBitmap = try {
                        val file = File(frameObj.fullResImagePath)
                        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else frameObj.thumbBitmap
                    } catch (_: Exception) {
                        frameObj.thumbBitmap
                    } ?: continue

                    // Lock surface canvas to burn overlays
                    val surfaceCanvas: Canvas? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        inputSurface.lockHardwareCanvas()
                    } else {
                        inputSurface.lockCanvas(null)
                    }

                    if (surfaceCanvas != null) {
                        try {
                            surfaceCanvas.drawBitmap(fullBitmap, 0f, 0f, null)

                            // Burn direct highlights if enabled
                            if (frameObj.isHighlightEnabled) {
                                val boxes = state.directBlurs[frameObj.index] ?: emptyList()
                                val timeMs = (frameObj.timeSeconds * 1000).toLong()

                                for (box in boxes) {
                                    val pulse = (sin(timeMs * 0.010) * 0.5 + 0.5).toFloat()
                                    val pad = 6f + pulse * 4f
                                    val bx = box.x0 - pad
                                    val by = box.y0 - pad
                                    val bw = box.width + pad * 2f
                                    val bh = box.height + pad * 2f

                                    // Button Highlight (Pulsing Brackets)
                                    fillPaint.color = android.graphics.Color.argb(
                                        ((0.12f + pulse * 0.22f) * 255).toInt(), 245, 158, 11
                                    )
                                    surfaceCanvas.drawRect(bx, by, bx + bw, by + bh, fillPaint)

                                    rectPaint.color = android.graphics.Color.rgb(245, 158, 11)
                                    rectPaint.strokeWidth = 3f + pulse * 2f
                                    surfaceCanvas.drawRect(bx, by, bx + bw, by + bh, rectPaint)
                                }
                            }
                        } finally {
                            inputSurface.unlockCanvasAndPost(surfaceCanvas)
                        }
                    }

                    if (fullBitmap != frameObj.thumbBitmap) {
                        fullBitmap.recycle()
                    }

                    // Drain encoder output buffers
                    var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                    while (outIndex >= 0) {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null) {
                            if (!muxerStarted) {
                                val newFormat = encoder.outputFormat
                                videoTrackIndex = videoMuxer.addTrack(newFormat)
                                videoMuxer.start()
                                muxerStarted = true
                            }
                            bufferInfo.presentationTimeUs = ptsUs
                            videoMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    val pct = (((i + 1).toFloat() / totalFrames.toFloat()) * 100).toInt()
                    _uiState.update { it.copy(
                        renderPercent = pct,
                        renderProgressStatus = "Burning frame ${i + 1}/$totalFrames (Normal Speed Sync)..."
                    )}
                }

                // Signal end of stream
                encoder.signalEndOfInputStream()
                var outIndex = encoder.dequeueOutputBuffer(bufferInfo, 20000)
                while (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if (encodedData != null && muxerStarted) {
                        videoMuxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    outIndex = encoder.dequeueOutputBuffer(bufferInfo, 20000)
                }

                encoder.stop()
                encoder.release()
                inputSurface.release()

                if (muxerStarted) {
                    videoMuxer.stop()
                    videoMuxer.release()
                }

                // Step 3: Combine encoded video track with original audio track
                val finalExportFile = if (hasAudio && tempVideoFile.exists()) {
                    mergeVideoAndAudio(tempVideoFile, tempAudioFile, tempFinalFile)
                    tempFinalFile
                } else {
                    tempVideoFile
                }

                // Step 4: Export final video to device Movies / Downloads using MediaStore
                val filename = "full_video_with_editora_effects_${System.currentTimeMillis()}.mp4"
                saveVideoToMediaStore(finalExportFile, filename)

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(
                        isRendering = false,
                        renderPercent = 100,
                        renderProgressStatus = "Complete! Full video with audio exported to gallery.",
                        statusText = "Full Video Downloaded",
                        statusColorHex = "#10b981"
                    )}
                    addLog("🎉 SUCCESS: Full video rendered with synchronized audio and saved to gallery.", LogType.SUCCESS)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    addLog("❌ Render Video Error: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(
                        isRendering = false,
                        renderProgressStatus = "Render Failed: ${e.message}",
                        statusText = "Render Failed",
                        statusColorHex = "#ef4444"
                    )}
                }
            } finally {
                tempAudioFile.delete()
                tempVideoFile.delete()
                tempFinalFile.delete()
            }
        }
    }

    private fun mergeVideoAndAudio(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var videoTrackIndex = -1
        for (i in 0 until videoExtractor.trackCount) {
            val format = videoExtractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                videoTrackIndex = muxer.addTrack(format)
                videoExtractor.selectTrack(i)
                break
            }
        }

        var audioTrackIndex = -1
        for (i in 0 until audioExtractor.trackCount) {
            val format = audioExtractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                audioTrackIndex = muxer.addTrack(format)
                audioExtractor.selectTrack(i)
                break
            }
        }

        muxer.start()
        val buffer = ByteBuffer.allocate(64 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        if (videoTrackIndex >= 0) {
            while (true) {
                val size = videoExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = videoExtractor.sampleFlags
                muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                videoExtractor.advance()
            }
        }

        if (audioTrackIndex >= 0) {
            while (true) {
                val size = audioExtractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                bufferInfo.flags = audioExtractor.sampleFlags
                muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                audioExtractor.advance()
            }
        }

        muxer.stop()
        muxer.release()
        videoExtractor.release()
        audioExtractor.release()
    }

    private fun saveVideoToMediaStore(sourceFile: File, displayName: String) {
        val context = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Editora")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not allocate MediaStore video entry.")
            resolver.openOutputStream(uri)?.use { os ->
                FileInputStream(sourceFile).copyTo(os)
            }
        } else {
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val dest = File(moviesDir, displayName)
            sourceFile.copyTo(dest, overwrite = true)
        }
    }

    /**
     * Saves text/JSON payloads directly to public Downloads folder using MediaStore (Android 10+ compliant).
     */
    private fun saveTextFileToStorage(content: String, filename: String) {
        val context = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Could not allocate MediaStore entry.")
                resolver.openOutputStream(uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(downloadsDir, filename)
                file.writeText(content)
            }
            addLog("💾 Downloaded $filename to device Downloads folder.", LogType.SUCCESS)
        } catch (e: Exception) {
            try {
                val fallbackFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename)
                fallbackFile.writeText(content)
                addLog("💾 Saved $filename to app storage: ${fallbackFile.name}", LogType.SUCCESS)
            } catch (_: Exception) {
                addLog("❌ File Save Error: ${e.message}", LogType.ERROR)
            }
        }
    }

    private fun extractAudioTrackNative(videoUri: Uri, outputFile: File) {
        val extractor = MediaExtractor()
        val context = getApplication<Application>()
        extractor.setDataSource(context, videoUri, null)

        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || audioFormat == null) {
            extractor.release()
            throw IllegalStateException("No valid audio track found in the loaded video file.")
        }

        extractor.selectTrack(audioTrackIndex)

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxerTrackIndex = muxer.addTrack(audioFormat)
        muxer.start()

        val maxBufferSize = audioFormat.optInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        val buffer = ByteBuffer.allocate(maxBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            try {
                muxer.stop()
                muxer.release()
            } catch (_: Exception) {}
            extractor.release()
        }
    }

    private fun MediaFormat.optInteger(key: String, defaultValue: Int): Int {
        return if (containsKey(key)) getInteger(key) else defaultValue
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        releaseMediaPlayer()
        ocrEngine.close()
    }
}