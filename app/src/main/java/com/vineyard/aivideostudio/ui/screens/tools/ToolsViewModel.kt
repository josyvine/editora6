package com.vineyard.aivideostudio.ui.screens.tools

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vineyard.aivideostudio.data.preferences.PreferencesRepository
import com.vineyard.aivideostudio.media.audio.AudioExtractor
import com.vineyard.aivideostudio.media.ocr.FrameOcrData
import com.vineyard.aivideostudio.media.ocr.NativeBatchOcrEngine
import com.vineyard.aivideostudio.media.tools.AudioCueSegment
import com.vineyard.aivideostudio.media.tools.DetectedTargetBox
import com.vineyard.aivideostudio.media.tools.NativeTimelineZipManager
import com.vineyard.aivideostudio.media.tools.SpatialCluster
import com.vineyard.aivideostudio.media.tools.SpatialClusterer
import com.vineyard.aivideostudio.media.tools.TimeSlotSession
import com.vineyard.aivideostudio.media.video.ExtractedFrame
import com.vineyard.aivideostudio.media.video.FastNativeFrameExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
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
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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
    val isZipSource: Boolean
)

data class ToolsUiState(
    val videoUri: Uri? = null,
    val isPlaying: Boolean = false,
    val isAudioMuted: Boolean = false,
    val currentFrameIndex: Int = 0,
    val frames: List<ExtractedFrame> = emptyList(),
    val extractedOcrData: Map<Int, FrameOcrData> = emptyMap(),
    
    // Status Badge & Progress
    val statusText: String = "Ready",
    val statusColorHex: String = "#0284c7",
    val progressPercent: Int = 0,
    val isProcessing: Boolean = false,

    // Target Panel Rules & Clusters
    val activeRules: List<TargetRule> = emptyList(),
    val detectedClusters: List<SpatialCluster> = emptyList(),
    val detectedTimeSlots: List<TimeSlotSession> = emptyList(),

    // Wizard Step State (1: Gemini, 2: Auto-Scan, 3: Export)
    val wizardStep: Int = 1,

    // Audio Cues & Transcripts
    val transcriptCues: List<AudioCueSegment> = emptyList(),
    val activeLogEntries: List<TerminalLogEntry> = emptyList(),

    // Editor Playback Slot Feature
    val slotStartFrame: Int? = null,
    val slotEndFrame: Int? = null,
    val slotStep: Int = 0 // 0: IDLE, 1: START_SET, 2: PLAYING_SLOT, 3: END_SET (Show Modal)
)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val frameExtractor: FastNativeFrameExtractor,
    private val ocrEngine: NativeBatchOcrEngine,
    private val audioExtractor: AudioExtractor
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ToolsUiState())
    val uiState: StateFlow<ToolsUiState> = _uiState.asStateFlow()

    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var playbackJob: Job? = null

    init {
        addLog("🤖 Gemini Native Audio Engine Ready. Awaiting user action.", LogType.INFO)
    }

    // =========================================================
    // TERMINAL LOGGING (A-to-Z Diagnostics)
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

    // =========================================================
    // UI NAVIGATION & WIZARD
    // =========================================================
    fun setWizardStep(step: Int) {
        _uiState.update { it.copy(wizardStep = step.coerceIn(1, 3)) }
    }

    // =========================================================
    // VIDEO LOAD & FRAME EXTRACTION
    // =========================================================
    fun setVideoUri(uri: Uri) {
        frameExtractor.clearWorkspace()
        _uiState.update { 
            ToolsUiState(videoUri = uri, activeLogEntries = it.activeLogEntries) 
        }
        addLog("📹 Video loaded into Native Studio workspace.", LogType.INFO)
    }

    fun extractAllFrames(targetFps: Int = 12) {
        val uri = _uiState.value.videoUri ?: return
        
        _uiState.update { it.copy(isProcessing = true, statusText = "Extracting...", statusColorHex = "#eab308") }
        addLog("🎞️ Starting hardware-accelerated frame extraction at $targetFps FPS...", LogType.INFO)

        viewModelScope.launch {
            try {
                val extractedList = frameExtractor.extractFrames(uri, targetFps) { current, total ->
                    _uiState.update { it.copy(
                        statusText = "Extracting $current/$total",
                        progressPercent = ((current.toFloat() / total.toFloat()) * 100).toInt()
                    )}
                }

                _uiState.update { it.copy(
                    frames = extractedList,
                    isProcessing = false,
                    statusText = "${extractedList.size} Frames Ready",
                    statusColorHex = "#10b981",
                    progressPercent = 0
                )}
                addLog("✅ Successfully extracted ${extractedList.size} frames to disk cache.", LogType.SUCCESS)
            } catch (e: Exception) {
                addLog("❌ Extraction Error: ${e.message}", LogType.ERROR)
                _uiState.update { it.copy(isProcessing = false, statusText = "Extraction Failed", statusColorHex = "#ef4444") }
            }
        }
    }

    // =========================================================
    // VIDEO TRANSPORT & SLOT CYCLE
    // =========================================================
    fun togglePlayPause() {
        val currentlyPlaying = _uiState.value.isPlaying
        
        if (currentlyPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        val state = _uiState.value
        if (state.frames.isEmpty()) return

        _uiState.update { it.copy(isPlaying = true) }
        
        // Handle Slot Cycle logic: Resume from 1st Pause
        if (state.slotStep == 1) {
            _uiState.update { it.copy(slotStep = 2) }
        }

        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            val fps = 12 // Assumed standard for playback loop mapping
            val delayMs = 1000L / fps
            
            var currentIndex = _uiState.value.currentFrameIndex
            while (currentIndex < _uiState.value.frames.size - 1) {
                delay(delayMs)
                currentIndex++
                _uiState.update { it.copy(currentFrameIndex = currentIndex) }
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
            newSlotStep = 1 // Marked Start
        } else if (state.slotStep == 2) {
            newSlotEnd = state.currentFrameIndex
            newSlotStep = 3 // Trigger Modal
        }

        _uiState.update { it.copy(
            isPlaying = false,
            slotStep = newSlotStep,
            slotStartFrame = newSlotStart,
            slotEndFrame = newSlotEnd
        )}
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
        
        viewModelScope.launch {
            val ocrResult = ocrEngine.scanFrame(frame)
            val updatedMap = state.extractedOcrData.toMutableMap()
            updatedMap[frame.index] = ocrResult
            
            _uiState.update { it.copy(
                extractedOcrData = updatedMap,
                statusText = "Frame #${frame.index} Ready",
                statusColorHex = "#10b981"
            )}
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

        viewModelScope.launch {
            try {
                val batchResults = ocrEngine.scanBatch(framesToScan, parallelWorkers = 5) { current, total ->
                    _uiState.update { it.copy(statusText = "OCR: $current/$total") }
                }

                val updatedMap = state.extractedOcrData.toMutableMap()
                updatedMap.putAll(batchResults)

                _uiState.update { it.copy(
                    extractedOcrData = updatedMap,
                    isProcessing = false,
                    statusText = "Scanned ${framesToScan.size} Frames",
                    statusColorHex = "#10b981"
                )}
                addLog("✅ Ultra-Fast OCR completed successfully.", LogType.SUCCESS)
                
            } catch (e: Exception) {
                addLog("❌ OCR Batch Error: ${e.message}", LogType.ERROR)
                _uiState.update { it.copy(isProcessing = false, statusText = "OCR Failed", statusColorHex = "#ef4444") }
            }
        }
    }

    // =========================================================
    // TARGET LOCKING & CLUSTERING
    // =========================================================
    fun addTargetRule(keyword: String, category: String, tool: String) {
        if (keyword.isBlank()) return
        
        val newRule = TargetRule(
            id = System.currentTimeMillis(),
            text = keyword.trim(),
            category = category,
            tool = tool,
            isZipSource = false
        )
        
        _uiState.update { it.copy(
            activeRules = it.activeRules + newRule
        )}
        
        reevaluateHighlights()
    }

    fun removeTargetRule(id: Long) {
        _uiState.update { state -> 
            state.copy(activeRules = state.activeRules.filter { it.id != id }) 
        }
        reevaluateHighlights()
    }

    private fun reevaluateHighlights() {
        // Triggers the UI canvas to update overlays based on current activeRules and extractedOcrData
        // (Handled automatically by Compose observing the uiState)
    }

    // =========================================================
    // NATIVE GEMINI AUDIO TRANSCRIPTION (A-to-Z Pipeline)
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
            try {
                addLog("🎧 Step 1/5: Extracting audio track natively using MediaExtractor...", LogType.INFO)
                // Use native MediaExtractor to isolate the audio track to PCM
                val pcmFile = File(getApplication<Application>().cacheDir, "temp_audio.pcm")
                audioExtractor.extractAudioToPcm(uri, pcmFile.absolutePath)
                
                val pcmSizeMb = pcmFile.length() / (1024f * 1024f)
                addLog("✅ Extraction complete (${String.format(Locale.US, "%.2f", pcmSizeMb)} MB PCM buffer).", LogType.SUCCESS)

                addLog("🔄 Step 2/5: Converting PCM to Base64 payload...", LogType.INFO)
                val base64Audio = android.util.Base64.encodeToString(pcmFile.readBytes(), android.util.Base64.NO_WRAP)
                addLog("✅ Base64 payload created (${base64Audio.length} chars).", LogType.INFO)

                val cleanModel = if (modelName.startsWith("models/")) modelName else "models/$modelName"
                val endpointUrl = "https://generativelanguage.googleapis.com/v1beta/$cleanModel:generateContent?key=$apiKey"

                addLog("🌐 Step 3/5: Connecting to Google AI Studio ($cleanModel)...", LogType.NET)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusText = "Gemini AI Transcribing...") }
                }

                // Construct raw JSON payload natively
                val payloadObj = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "Listen to this audio track. Transcribe all spoken speech accurately. Output a JSON array of speech segments where each object has: \"start\" (start time in seconds as a float number), \"end\" (end time in seconds as a float number), and \"text\" (exact spoken words string). Example format: [{\"start\": 3.2, \"end\": 4.8, \"text\": \"click on playground\"}].")
                            })
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "audio/wav") // Standardized MIME for PCM/WAV payload
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
                
                // Parse transcripts using NativeTimelineZipManager logic
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
                }
                
                // Clean up native temp file
                pcmFile.delete()

                addLog("========================================", LogType.INFO)

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    addLog("❌ PIPELINE EXCEPTION: ${e.message}", LogType.ERROR)
                    _uiState.update { it.copy(isProcessing = false, statusText = "Transcription Failed", statusColorHex = "#ef4444") }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackJob?.cancel()
        frameExtractor.clearWorkspace()
        ocrEngine.close()
    }
}