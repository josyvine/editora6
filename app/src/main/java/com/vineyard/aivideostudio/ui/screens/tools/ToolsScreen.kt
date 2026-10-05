package com.vineyard.aivideostudio.ui.screens.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vineyard.aivideostudio.media.video.ExtractedFrame
import com.vineyard.aivideostudio.ui.screens.tools.components.ToolsOverlayPreview

// Custom Colors matching Video OCR Studio theme
private val BgDark = Color(0xFF090D16)
private val SurfaceDark = Color(0xFF131B2E)
private val SurfaceVariant = Color(0xFF1E293B)
private val BorderColor = Color(0xFF334155)
private val AccentBlue = Color(0xFF38BDF8)
private val PrimaryBlue = Color(0xFF2563EB)
private val SuccessGreen = Color(0xFF10B981)
private val WarningYellow = Color(0xFFF59E0B)
private val DangerRed = Color(0xFFEF4444)
private val PurpleAccent = Color(0xFFA855F7)

@Composable
fun ToolsScreen(viewModel: ToolsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedMainTab by remember { mutableIntStateOf(0) } // 0: Viewer, 1: Data, 2: Render

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        // App Header & Status Badge
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Video OCR Studio", color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Surface(
                color = Color(android.graphics.Color.parseColor(uiState.statusColorHex)),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = uiState.statusText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        HorizontalDivider(color = BorderColor)

        // Internal Top Tab Row
        TabRow(
            selectedTabIndex = selectedMainTab,
            containerColor = SurfaceDark,
            contentColor = AccentBlue
        ) {
            Tab(selected = selectedMainTab == 0, onClick = { selectedMainTab = 0 }) {
                Text("Studio Viewer", modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedMainTab == 1, onClick = { selectedMainTab = 1 }) {
                Text("Export Data", modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Tab(selected = selectedMainTab == 2, onClick = { selectedMainTab = 2 }) {
                Text("Render Video", modifier = Modifier.padding(12.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Scrollable Content Body
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            when (selectedMainTab) {
                0 -> StudioViewerTab(viewModel, uiState)
                1 -> ExportDataWizardTab(viewModel, uiState)
                2 -> RenderVideoTab(viewModel, uiState)
            }
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun StudioViewerTab(viewModel: ToolsViewModel, state: ToolsUiState) {
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setVideoUri(it) }
    }

    // 1. VIDEO PLAYER (TOP)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.Black),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column {
            // Canvas Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center
            ) {
                val currentFrame = state.frames.getOrNull(state.currentFrameIndex)
                if (currentFrame != null) {
                    ToolsOverlayPreview(
                        baseBitmap = currentFrame.thumbBitmap,
                        activeBoxes = emptyList(),
                        currentTimeMs = (currentFrame.timeSeconds * 1000).toLong(),
                        withArrow = true
                    )
                } else {
                    Text("No Video Loaded", color = BorderColor)
                }
            }

            // Transport Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = state.currentFrameIndex.toFloat(),
                        onValueChange = { viewModel.seekToFrame(it.toInt()) },
                        valueRange = 0f..maxOf(1f, state.frames.size.toFloat() - 1f),
                        colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue),
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (state.frames.isNotEmpty()) "${state.frames[state.currentFrameIndex].timeFormatted}s" else "00:00.0",
                        color = AccentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        onClick = { viewModel.togglePlayPause() },
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.isPlaying) WarningYellow else PrimaryBlue),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.isPlaying) "Pause" else "Play", color = if (state.isPlaying) Color.Black else Color.White)
                    }
                    Button(
                        onClick = { viewModel.toggleAudioMute() },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(if (state.isAudioMuted) "🔇 OFF" else "🔊 ON", color = Color.White)
                    }
                    Button(
                        onClick = { viewModel.scanCurrentFrame() },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("Scan #${state.currentFrameIndex}", color = Color.White)
                    }
                }
            }
        }
    }

    // 2. VIDEO UPLOAD & EXTRACTION CONTROLS
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Button(
                onClick = { videoPickerLauncher.launch("video/*") },
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Choose Video or Screenshot", fontWeight = FontWeight.Bold)
            }

            if (state.videoUri != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { /* Default 12 FPS */ },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("12 FPS (Original)", color = Color.White)
                    }
                    Button(
                        onClick = { viewModel.extractAllFrames(12) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Extract All")
                    }
                }
            }
        }
    }

    // 3. TIMELINE FILMSTRIP
    if (state.frames.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, BorderColor),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("All Video Frames (${state.frames.size})", fontSize = 11.sp, color = Color.Gray)
                    Text("Tap toggle to unmark box", fontSize = 11.sp, color = AccentBlue)
                }
                Spacer(modifier = Modifier.height(6.dp))
                
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.frames) { frame ->
                        FrameThumbnail(
                            frame = frame,
                            isActive = frame.index == state.currentFrameIndex,
                            onClick = { viewModel.seekToFrame(frame.index) }
                        )
                    }
                }
            }
        }
    }

    // 4. TARGET PANEL & RULES CARD
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        var keyword by remember { mutableStateOf("") }
        Column(Modifier.padding(10.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { keyword = it },
                placeholder = { Text("Target word (e.g. Playground)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = BgDark,
                    focusedContainerColor = BgDark,
                    unfocusedBorderColor = BorderColor,
                    focusedBorderColor = AccentBlue
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { 
                        viewModel.addTargetRule(keyword, "Global", "button_highlight")
                        keyword = "" 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Lock Target", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BgDark)
                    .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                if (state.activeRules.isEmpty()) {
                    Text("No target added yet. Enter word and tap \"Lock Target\".", fontSize = 12.sp, color = Color.Gray)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.activeRules.forEach { rule ->
                            Row(
                                modifier = Modifier
                                    .background(SurfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("[${rule.tool.uppercase()}] ${rule.text}", fontSize = 11.sp, color = WarningYellow)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "[X]", 
                                    fontSize = 12.sp, 
                                    color = DangerRed, 
                                    fontWeight = FontWeight.Bold, 
                                    modifier = Modifier.clickable { viewModel.removeTargetRule(rule.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameThumbnail(frame: ExtractedFrame, isActive: Boolean, onClick: () -> Unit) {
    val borderColor = if (isActive) AccentBlue else if (frame.isHighlightEnabled) WarningYellow else BorderColor
    
    Box(
        modifier = Modifier
            .width(95.dp)
            .height(65.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(if (isActive) 2.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = frame.thumbBitmap,
            contentDescription = "Frame ${frame.index}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color(0xBF000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("#${frame.index}", color = Color.White, fontSize = 9.sp)
            Text("${frame.timeFormatted}s", color = Color.White, fontSize = 9.sp)
        }
        
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .background(if (frame.isHighlightEnabled) WarningYellow else BorderColor, RoundedCornerShape(2.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                if (frame.isHighlightEnabled) "ON" else "CLEAN", 
                color = if (frame.isHighlightEnabled) Color.Black else Color.LightGray, 
                fontSize = 8.sp, 
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ExportDataWizardTab(viewModel: ToolsViewModel, state: ToolsUiState) {
    // STEPPER HEADER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgDark, RoundedCornerShape(8.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(8.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        listOf("1. 🤖 Gemini AI", "2. 🔍 Auto-Scan ZIP", "3. 📋 Coordinates").forEachIndexed { index, title ->
            val stepNum = index + 1
            val isActive = state.wizardStep == stepNum
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) SurfaceVariant else Color.Transparent)
                    .border(1.dp, if (isActive) AccentBlue else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { viewModel.setWizardStep(stepNum) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(title, color = if (isActive) AccentBlue else Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    Spacer(modifier = Modifier.height(10.dp))

    when (state.wizardStep) {
        1 -> WizardStep1(viewModel, state)
        2 -> WizardStep2(viewModel, state)
        3 -> WizardStep3(viewModel, state)
    }
}

@Composable
private fun WizardStep1(viewModel: ToolsViewModel, state: ToolsUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C162D)),
        border = BorderStroke(1.dp, AccentBlue),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            var apiKey by remember { mutableStateOf("") }
            
            Text("🤖 Google Gemini AI Settings", color = AccentBlue, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                placeholder = { Text("Enter Gemini API Key (AIzaSy...)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = BgDark,
                    focusedContainerColor = BgDark,
                    unfocusedBorderColor = BorderColor,
                    focusedBorderColor = AccentBlue
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            Button(
                onClick = { viewModel.transcribeAudioWithGemini(apiKey, "gemini-2.5-flash") },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🎙️ Transcribe Audio with Gemini AI", fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))
            TerminalConsole(viewModel, state)
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Button(
        onClick = { viewModel.setWizardStep(2) },
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Next: Auto-Scan ZIP Tool ➔", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun WizardStep2(viewModel: ToolsViewModel, state: ToolsUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, PurpleAccent),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        var filterText by remember { mutableStateOf("") }

        Column(Modifier.padding(10.dp)) {
            Text("Auto-Scan ZIP & Apply Editora4 Tool", color = Color(0xFFE9D5FF), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text("Text to target on screen (e.g. Playground)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = BgDark,
                    focusedContainerColor = BgDark,
                    unfocusedBorderColor = BorderColor,
                    focusedBorderColor = PurpleAccent
                )
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { /* Action handled via NativeTimelineZipManager */ },
                colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Fetch ZIP & Apply Tool", fontWeight = FontWeight.Bold)
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { viewModel.setWizardStep(1) },
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
            modifier = Modifier.weight(1f)
        ) {
            Text("⬅ Back", color = Color.White)
        }
        Button(
            onClick = { viewModel.setWizardStep(3) },
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            modifier = Modifier.weight(1.2f)
        ) {
            Text("Next: JSON & Export ➔")
        }
    }
}

@Composable
private fun WizardStep3(viewModel: ToolsViewModel, state: ToolsUiState) {
    val clipboardManager = LocalClipboardManager.current

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderColor),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Active Highlight Coordinates (JSON)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                Button(
                    onClick = { clipboardManager.setText(AnnotatedString("{\n  \"frames\": []\n}")) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Copy", fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(BgDark, RoundedCornerShape(6.dp))
                    .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = "{\n  \"target\": \"Playground\",\n  \"total_highlighted_frames\": ${state.frames.count { it.isHighlightEnabled }}\n}",
                    color = AccentBlue,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))
    Button(
        onClick = { viewModel.setWizardStep(2) },
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("⬅ Back: Auto-Scan ZIP", color = Color.White)
    }
}

@Composable
private fun TerminalConsole(viewModel: ToolsViewModel, state: ToolsUiState) {
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF030712))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B1120))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🖥️ Diagnostic Log Console", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "📋 Copy",
                    color = AccentBlue,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        val fullLog = state.activeLogEntries.joinToString("\n") { "${it.timestamp} ${it.message}" }
                        clipboardManager.setText(AnnotatedString(fullLog))
                    }
                )
                Text(
                    text = "🗑️ Clear",
                    color = Color.Gray,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable { viewModel.clearLogs() }
                )
            }
        }
        HorizontalDivider(color = Color(0xFF1E293B))
        
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 260.dp)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(state.activeLogEntries) { entry ->
                val color = when (entry.type) {
                    LogType.INFO -> AccentBlue
                    LogType.SUCCESS -> SuccessGreen
                    LogType.WARNING -> WarningYellow
                    LogType.ERROR -> DangerRed
                    LogType.NET -> PurpleAccent
                }
                Text("${entry.timestamp} ${entry.message}", color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun RenderVideoTab(viewModel: ToolsViewModel, state: ToolsUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, BorderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("Render and Export Full Video", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Burns all selected Editora4 effects (blur, mosaic, boxes, arrows) directly into your exported video with full synchronized original audio at exact normal speed.",
                color = Color.Gray,
                fontSize = 12.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { /* Render Video Call via Media3 */ },
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Download Full Video With Highlights & Audio", fontWeight = FontWeight.Bold)
            }
        }
    }
}