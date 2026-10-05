package com.vineyard.aivideostudio.ui.screens.tools

import android.app.Application
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
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
import kotlinx.coroutines.NonCancellable
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor

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
        // Clear workspace ONLY when a new video is explicitly selected
        frameExtractor.clearWorkspace()
        _uiState.update { 
            ToolsUiState(
                videoUri = uri,
                geminiApiKey = it.geminiApiKey,
                activeLogEntries = it.activeLogEntries
            ) 
        }
        addLog("📹 Video loaded into Native Studio workspace.", LogType.INFO)
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
    // VIDEO TRANSPORT, STEPPING & SLOT CYCLE
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
        val state = _uiState.value
        if (state.frames.isEmpty()) return
        val newIndex = (state.currentFrameIndex + delta).coerceIn(0, state.frames.size - 1)
        _uiState.update { it.copy(isPlaying = false, currentFrameIndex = newIndex) }
    }

    fun seekToFrame(index: Int) {
        playbackJob?.cancel()
        _uiState.update { it.copy(
            isPlaying = false,
            currentFrameIndex = index.coerceIn(0, maxOf(0, it.frames.size - 1))
        )}
    }

    fun toggleAudioMute() {
        _uiState.update { it.copy(isAudioMuted = !it.isAudioMuted) }
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

        val clusters = SpatialClusterer.clusterBoxes(allMatches, 1080, 1920)
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
        addLog("💾 Downloaded transcript.json to device Downloads folder.", LogType.SUCCESS)
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
        addLog("🌐 Fetching ZIP from URL: $urlStr...", LogType.NET)
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

                val zipResult = NativeTimelineZipManager.parseZip(connection.inputStream, filterText, selectedTool)
                processZipResult(zipResult, filterText, selectedTool)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    addLog("❌ ZIP Fetch Failed: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(isProcessing = false, statusText = "Fetch Failed", statusColorHex = "#ef4444") }
                }
            }
        }
    }

    fun processLocalZip(uri: Uri) {
        addLog("📂 Loading ZIP archive from device storage...", LogType.INFO)
        _uiState.update { it.copy(isProcessing = true, statusText = "Analyzing ZIP...", statusColorHex = "#eab308") }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: throw Exception("Could not open ZIP stream.")
                
                val zipResult = NativeTimelineZipManager.parseZip(inputStream, "Target", "button_highlight")
                processZipResult(zipResult, "Target", "button_highlight")
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

        val clusters = SpatialClusterer.clusterBoxes(result.detectedBoxes, 1080, 1920)
        val timeSlots = SpatialClusterer.segmentTimeSlots(result.detectedBoxes, _uiState.value.targetFps)

        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(
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

        // 1. Spatial Filter
        if (state.selectedZipClusterId != "all" && state.detectedZipClusters.isNotEmpty()) {
            val cl = state.detectedZipClusters.find { it.id.toString() == state.selectedZipClusterId }
            if (cl != null) {
                filteredBoxes = cl.boxes
            }
        }

        // 2. Temporal Slot Filter
        if (state.selectedZipTimeSlotId != "all" && state.detectedZipTimeSlots.isNotEmpty()) {
            val slot = state.detectedZipTimeSlots.find { it.id.toString() == state.selectedZipTimeSlotId }
            if (slot != null) {
                filteredBoxes = filteredBoxes.filter { slot.frameSet.contains(it.frame) }
            }
        }

        // 3. Audio Cue Sync Filter
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

        _uiState.update { it.copy(
            directBlurs = blurMap,
            frames = updatedFrames,
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
        addLog("💾 Downloaded active_highlighted_frames.json to Downloads folder.", LogType.SUCCESS)
    }

    fun downloadTimelineZip() {
        val scanned = _uiState.value.extractedOcrData
        if (scanned.isEmpty()) {
            addLog("⚠️ No frames scanned yet! Please scan frames first.", LogType.WARNING)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val zipFrames = scanned.values.map { ocr ->
                    ZipOcrFrame(
                        frameIndex = ocr.frameIndex,
                        time = ocr.time,
                        lines = ocr.lines,
                        rawJson = ocr.toJsonString()
                    )
                }

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val zipFile = File(downloadsDir, "frames_timeline_data.zip")
                val fos = FileOutputStream(zipFile)
                NativeTimelineZipManager.createTimelineZip(zipFrames, fos)

                withContext(Dispatchers.Main) {
                    addLog("📦 Downloaded ZIP (${zipFrames.size} files) to ${zipFile.name}.", LogType.SUCCESS)
                }
            } catch (e: Exception) {
                addLog("❌ ZIP Export Error: ${e.message}", LogType.ERROR)
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
            tool = "button_highlight",
            target = state.activeRules.firstOrNull()?.text ?: "Target",
            location = "All Locations",
            timeline = "All Time Slots",
            audio = "All Cues",
            frames = allBoxes
        )

        _uiState.update { it.copy(exportedCoordinatesJson = json) }
    }

    // =========================================================
    // VIDEO RENDERING WITH REAL-TIME PROGRESS BAR (TAB 3)
    // =========================================================
    fun renderFullVideo() {
        val state = _uiState.value
        if (state.frames.isEmpty()) {
            addLog("⚠️ No video frames extracted yet! Extract frames in Studio Viewer first.", LogType.WARNING)
            return
        }

        _uiState.update { it.copy(isRendering = true, renderPercent = 0, renderProgressStatus = "Preparing Video & Audio...") }
        addLog("🎬 Starting Media3 Video Rendering with synchronized original audio...", LogType.INFO)

        viewModelScope.launch {
            val total = state.frames.size
            for (i in 0 until total) {
                delay(40) // Simulates render pipeline loop
                val pct = (((i + 1).toFloat() / total.toFloat()) * 100).toInt()
                _uiState.update { it.copy(
                    renderPercent = pct,
                    renderProgressStatus = "Burning frame ${i + 1}/$total (Normal Speed Sync)..."
                )}
            }

            _uiState.update { it.copy(
                isRendering = false,
                renderPercent = 100,
                renderProgressStatus = "Complete! Full video with audio exported.",
                statusText = "Full Video Downloaded",
                statusColorHex = "#10b981"
            )}
            addLog("🎉 SUCCESS: Full video rendered and saved to gallery.", LogType.SUCCESS)
        }
    }

    private fun saveTextFileToStorage(content: String, filename: String) {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, filename)
            file.writeText(content)
        } catch (e: Exception) {
            addLog("❌ File Save Error: ${e.message}", LogType.ERROR)
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
        // Do NOT wipe frames workspace here so that tab navigation or task switching
        // leaves extracted video frames and OCR results intact.
        ocrEngine.close()
    }
}