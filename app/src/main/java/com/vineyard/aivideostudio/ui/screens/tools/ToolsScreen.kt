package com.vineyard.aivideostudio.ui.screens.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.vineyard.aivideostudio.media.tools.DetectedTargetBox
import com.vineyard.aivideostudio.media.tools.SpatialClusterer
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

// Tool items mapping
val ALL_EDITORA_TOOLS = listOf(
    "blur_gaussian" to "Gaussian Optical Blur",
    "blur_mosaic" to "Pixelated Mosaic Blur",
    "privacy_box" to "Solid Privacy Box",
    "emoji_pill" to "Privacy Lock Emoji Pill (🔒)",
    "button_highlight" to "Button Highlight (Pulsing Brackets)",
    "flashing_arrow" to "Flashing / Bouncing Arrow",
    "spotlight" to "Spotlight (Dim Background)",
    "highlight_circle" to "Highlight Circle / Ring",
    "highlight_box" to "Standard Highlight Box",
    "vertical_column" to "Vertical Pillar / Sidebar Frame"
)

val ALL_CATEGORIES = listOf(
    "global_anywhere" to "Category: Global (Anywhere)",
    "sidebar_menu" to "Category: Sidebar Menu",
    "top_header" to "Category: Top Header Bar",
    "settings_drawer" to "Category: Settings Drawer"
)

@Composable
fun ToolsScreen(viewModel: ToolsViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedMainTab by remember { mutableIntStateOf(0) } // 0: Viewer, 1: Data, 2: Render

    // Modal Slot Capture Dialog Triggered on 2nd Pause
    if (uiState.slotStep == 3) {
        SlotCaptureDialog(viewModel = viewModel, state = uiState)
    }

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

// =========================================================================
// TAB 1: STUDIO VIEWER
// =========================================================================
@Composable
private fun StudioViewerTab(viewModel: ToolsViewModel, state: ToolsUiState) {
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.setVideoUri(it) }
    }

    // 1. VIDEO PLAYER & CONTROLS
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
                    val activeBoxes = remember(
                        currentFrame.index,
                        state.directBlurs,
                        state.extractedOcrData[currentFrame.index],
                        state.activeRules
                    ) {
                        val boxes = mutableListOf<DetectedTargetBox>()
                        boxes.addAll(state.directBlurs[currentFrame.index] ?: emptyList())

                        state.extractedOcrData[currentFrame.index]?.let { ocr ->
                            for (rule in state.activeRules) {
                                if (!rule.isZipSource) {
                                    val matched = SpatialClusterer.findMatchingBoundingBoxes(ocr.lines, rule.text)
                                    for (b in matched) {
                                        boxes.add(
                                            DetectedTargetBox(
                                                x0 = b.x0, y0 = b.y0, width = b.width, height = b.height,
                                                text = rule.text, tool = rule.tool, frame = currentFrame.index,
                                                time = currentFrame.timeSeconds
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        boxes
                    }

                    ToolsOverlayPreview(
                        baseBitmap = currentFrame.thumbBitmap,
                        activeBoxes = if (currentFrame.isHighlightEnabled) activeBoxes else emptyList(),
                        currentTimeMs = (currentFrame.timeSeconds * 1000).toLong(),
                        withArrow = state.isArrowPointerEnabled
                    )
                } else {
                    Text("No Video Loaded", color = BorderColor)
                }
            }

            // Slot Cycle Banner
            if (state.frames.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E1B4B))
                        .border(1.dp, PurpleAccent)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cycleText = when (state.slotStep) {
                        1 -> "Start: #${state.slotStartFrame} -> Resume to continue"
                        2 -> "Slot from #${state.slotStartFrame}... Press Pause to end"
                        else -> "Slot Cycle: Play video -> 1st Pause sets start"
                    }
                    Text(
                        text = cycleText,
                        color = if (state.slotStep == 1) SuccessGreen else if (state.slotStep == 2) PurpleAccent else AccentBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Reset",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.clickable { viewModel.resetSlotCycle() }
                    )
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
                        text = if (state.frames.isNotEmpty()) "${state.frames[state.currentFrameIndex].timeFormatted}s / ${state.frames.last().timeFormatted}s" else "00:00.0 / 00:00.0",
                        color = AccentBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
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
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text(if (state.isPlaying) "Pause" else "Play", color = if (state.isPlaying) Color.Black else Color.White, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { viewModel.stepFrame(-1) },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("-1 Frame", color = Color.White, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { viewModel.stepFrame(1) },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("+1 Frame", color = Color.White, fontSize = 11.sp)
                    }
                    Button(
                        onClick = { viewModel.scanCurrentFrame() },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("Scan #${state.currentFrameIndex}", color = Color.White, fontSize = 11.sp)
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
                    var fpsExpanded by remember { mutableStateOf(false) }
                    var selectedFps by remember { mutableIntStateOf(12) }

                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { fpsExpanded = true },
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("$selectedFps FPS", color = Color.White, fontSize = 12.sp)
                        }
                        DropdownMenu(expanded = fpsExpanded, onDismissRequest = { fpsExpanded = false }) {
                            listOf(1, 2, 4, 6, 10, 12).forEach { fps ->
                                DropdownMenuItem(
                                    text = { Text("$fps FPS ${if (fps == 12) "(Original)" else ""}") },
                                    onClick = { selectedFps = fps; fpsExpanded = false }
                                )
                            }
                        }
                    }

                    Button(
                        onClick = { viewModel.extractAllFrames(selectedFps) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Extract All", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                // In-Card Progress Bar during Frame Extraction
                if (state.isProcessing && state.progressPercent > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = AccentBlue,
                        trackColor = SurfaceVariant
                    )
                }
            }
        }
    }

    // 3. TIMELINE FILMSTRIP (HIGH-PERFORMANCE KEYED LIST WITH HARDWARE BITMAPS)
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
                    Text("All Video Frames (${state.frames.size}) - Uncut Original Length", fontSize = 11.sp, color = Color.Gray)
                    Text("Tap toggle to unmark box", fontSize = 11.sp, color = AccentBlue)
                }
                Spacer(modifier = Modifier.height(6.dp))

                val lazyListState = rememberLazyListState()

                // Auto-center filmstrip on active frame during playback and stepping
                LaunchedEffect(state.currentFrameIndex) {
                    if (state.frames.isNotEmpty()) {
                        val targetIndex = (state.currentFrameIndex - 2).coerceAtLeast(0)
                        lazyListState.scrollToItem(targetIndex)
                    }
                }
                
                LazyRow(
                    state = lazyListState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = state.frames,
                        key = { it.index },
                        contentType = { "frame_thumb" }
                    ) { frame ->
                        FrameThumbnail(
                            frame = frame,
                            isActive = frame.index == state.currentFrameIndex,
                            onToggle = { viewModel.toggleFrameHighlight(frame.index) },
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
        var selectedCategory by remember { mutableStateOf("global_anywhere") }
        var selectedTool by remember { mutableStateOf("blur_gaussian") }
        var categoryExpanded by remember { mutableStateOf(false) }
        var toolExpanded by remember { mutableStateOf(false) }
        var clusterExpanded by remember { mutableStateOf(false) }
        var selectedClusterId by remember { mutableStateOf("all") }

        Column(Modifier.padding(10.dp)) {
            OutlinedTextField(
                value = keyword,
                onValueChange = { 
                    keyword = it 
                    viewModel.updateTargetClusters(it)
                },
                placeholder = { Text("Target word (e.g. Playground, Dashboard, Email)") },
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

            // Dynamic Auto-Cluster Dropdown (Tab 1)
            if (state.detectedClusters.size > 1) {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    OutlinedButton(
                        onClick = { clusterExpanded = true },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, AccentBlue),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E1B4B))
                    ) {
                        Text(
                            text = if (selectedClusterId == "all") "🌐 All Locations (${state.detectedClusters.size} Found)" 
                                   else state.detectedClusters.find { it.id.toString() == selectedClusterId }?.displayName ?: "Selected Location",
                            color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1
                        )
                    }
                    DropdownMenu(expanded = clusterExpanded, onDismissRequest = { clusterExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("🌐 All Locations (${state.detectedClusters.size} Found)") },
                            onClick = { selectedClusterId = "all"; clusterExpanded = false }
                        )
                        state.detectedClusters.forEach { cl ->
                            DropdownMenuItem(
                                text = { Text(cl.displayName) },
                                onClick = { selectedClusterId = cl.id.toString(); clusterExpanded = false }
                            )
                        }
                    }
                }
            }

            // Category Selector
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { categoryExpanded = true },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(ALL_CATEGORIES.find { it.first == selectedCategory }?.second ?: "Category", color = Color.White, fontSize = 12.sp)
                }
                DropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                    ALL_CATEGORIES.forEach { (catId, catTitle) ->
                        DropdownMenuItem(
                            text = { Text(catTitle) },
                            onClick = { selectedCategory = catId; categoryExpanded = false }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Tool Selector & Lock Target Row
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { toolExpanded = true },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(ALL_EDITORA_TOOLS.find { it.first == selectedTool }?.second ?: "Tool", color = AccentBlue, fontSize = 11.sp, maxLines = 1)
                    }
                    DropdownMenu(expanded = toolExpanded, onDismissRequest = { toolExpanded = false }) {
                        ALL_EDITORA_TOOLS.forEach { (toolId, toolTitle) ->
                            DropdownMenuItem(
                                text = { Text(toolTitle) },
                                onClick = { selectedTool = toolId; toolExpanded = false }
                            )
                        }
                    }
                }

                Button(
                    onClick = { 
                        viewModel.addTargetRule(keyword, selectedCategory, selectedTool, selectedClusterId)
                        keyword = "" 
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.weight(0.9f)
                ) {
                    Text("Lock Target", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Arrow Checkbox & Discard Wrong Highlights Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = state.isArrowPointerEnabled,
                        onCheckedChange = { viewModel.setArrowPointerEnabled(it) },
                        colors = CheckboxDefaults.colors(checkedColor = AccentBlue)
                    )
                    Text("Arrow Pointer", color = Color.LightGray, fontSize = 12.sp)
                }

                Button(
                    onClick = { viewModel.discardDriftHighlights() },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningYellow),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("Discard Wrong Highlights", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Active Rules Chips List
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
                                Text("[${rule.tool.uppercase()}] ${rule.text} [${rule.category}]", fontSize = 11.sp, color = WarningYellow)
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
private fun FrameThumbnail(frame: ExtractedFrame, isActive: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val borderColor = if (isActive) AccentBlue else if (frame.isHighlightEnabled) WarningYellow else BorderColor
    
    Box(
        modifier = Modifier
            .width(95.dp)
            .height(65.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(if (isActive) 2.dp else 1.dp, borderColor, RoundedCornerShape(6.dp))
            .clickable { onClick() }
    ) {
        // Direct zero-latency hardware bitmap rendering (no Coil cache lookup overhead)
        Image(
            bitmap = frame.thumbBitmap.asImageBitmap(),
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
        
        // Interactive Toggle Badge
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .background(if (frame.isHighlightEnabled) WarningYellow else BorderColor, RoundedCornerShape(2.dp))
                .clickable { onToggle() }
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

// =========================================================================
// TAB 2: EXPORT DATA (3-STEP WIZARD)
// =========================================================================
@Composable
private fun ExportDataWizardTab(viewModel: ToolsViewModel, state: ToolsUiState) {
    // Stepper Navigation Header
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

// Step 1: Gemini AI Transcriber
@Composable
private fun WizardStep1(viewModel: ToolsViewModel, state: ToolsUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0C162D)),
        border = BorderStroke(1.dp, AccentBlue),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            var apiKey by remember { mutableStateOf(state.geminiApiKey) }
            var isKeyVisible by remember { mutableStateOf(false) }
            var modelExpanded by remember { mutableStateOf(false) }
            var selectedModel by remember { mutableStateOf(state.selectedGeminiModel) }

            // Header with Fetch Models Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🤖 Google Gemini AI Settings", color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Button(
                    onClick = { viewModel.fetchGeminiModels(apiKey) },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(4.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("🔄 Fetch Models", fontSize = 11.sp)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            // Password Field with Eye Visibility Toggle
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { 
                        apiKey = it
                        viewModel.setGeminiApiKey(it)
                    },
                    placeholder = { Text("Enter Gemini API Key (AIzaSy...)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = BgDark,
                        focusedContainerColor = BgDark,
                        unfocusedBorderColor = BorderColor,
                        focusedBorderColor = AccentBlue
                    )
                )
                Button(
                    onClick = { isKeyVisible = !isKeyVisible },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(if (isKeyVisible) "🙈" else "👁️")
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            // Gemini Model Selector Dropdown
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { modelExpanded = true },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(selectedModel, color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                    state.availableGeminiModels.forEach { modelName ->
                        DropdownMenuItem(
                            text = { Text(modelName) },
                            onClick = { 
                                selectedModel = modelName
                                viewModel.setSelectedGeminiModel(modelName)
                                modelExpanded = false 
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            // Transcribe and Download Transcript Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.transcribeAudioWithGemini(apiKey, selectedModel) },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🎙️ Transcribe Audio with Gemini AI", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                if (state.transcriptCues.isNotEmpty()) {
                    Button(
                        onClick = { viewModel.downloadTranscriptJson() },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("💾 Download JSON", fontSize = 11.sp)
                    }
                }
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

// Step 2: Auto-Scan ZIP with Spatial, Temporal & Audio Sync
@Composable
private fun WizardStep2(viewModel: ToolsViewModel, state: ToolsUiState) {
    val localZipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.processLocalZip(it) }
    }

    val externalTranscriptPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadExternalTranscriptFile(it) }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, PurpleAccent),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        var filterText by remember { mutableStateOf("") }
        var zipUrl by remember { mutableStateOf("https://raw.githubusercontent.com/josyvine/GitHub-zip/main/file/frames_timeline_data.zip") }
        var selectedZipTool by remember { mutableStateOf("button_highlight") }
        var audioFilterQuery by remember { mutableStateOf("") }

        var zipToolExpanded by remember { mutableStateOf(false) }
        var zipClusterExpanded by remember { mutableStateOf(false) }
        var zipTimeSlotExpanded by remember { mutableStateOf(false) }
        var audioCueExpanded by remember { mutableStateOf(false) }

        Column(Modifier.padding(10.dp)) {
            Text("Auto-Scan ZIP & Apply Editora4 Tool", color = Color(0xFFE9D5FF), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            // Target word input
            OutlinedTextField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = { Text("Text to target on screen (e.g. Playground)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = BgDark, focusedContainerColor = BgDark,
                    unfocusedBorderColor = BorderColor, focusedBorderColor = PurpleAccent
                )
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Dynamic Spatial Clusters Dropdown (Tab 2)
            if (state.detectedZipClusters.size > 1) {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    OutlinedButton(
                        onClick = { zipClusterExpanded = true },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, AccentBlue),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E1B4B))
                    ) {
                        Text(
                            text = if (state.selectedZipClusterId == "all") "🌐 All Locations (${state.detectedZipClusters.size} Found)"
                                   else state.detectedZipClusters.find { it.id.toString() == state.selectedZipClusterId }?.displayName ?: "Selected Location",
                            color = AccentBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1
                        )
                    }
                    DropdownMenu(expanded = zipClusterExpanded, onDismissRequest = { zipClusterExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("🌐 All Locations (${state.detectedZipClusters.size} Found)") },
                            onClick = { viewModel.setZipClusterFilter("all"); zipClusterExpanded = false }
                        )
                        state.detectedZipClusters.forEach { cl ->
                            DropdownMenuItem(
                                text = { Text(cl.displayName) },
                                onClick = { viewModel.setZipClusterFilter(cl.id.toString()); zipClusterExpanded = false }
                            )
                        }
                    }
                }
            }

            // Dynamic Timeline Session Windows Dropdown (Tab 2)
            if (state.detectedZipTimeSlots.size > 1) {
                Box(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    OutlinedButton(
                        onClick = { zipTimeSlotExpanded = true },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, WarningYellow),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E1B4B))
                    ) {
                        Text(
                            text = if (state.selectedZipTimeSlotId == "all") "🌐 All Time Slots (${state.detectedZipTimeSlots.size} Sessions)"
                                   else state.detectedZipTimeSlots.find { it.id.toString() == state.selectedZipTimeSlotId }?.displayName ?: "Selected Slot",
                            color = WarningYellow, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1
                        )
                    }
                    DropdownMenu(expanded = zipTimeSlotExpanded, onDismissRequest = { zipTimeSlotExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("🌐 All Time Slots (${state.detectedZipTimeSlots.size} Sessions)") },
                            onClick = { viewModel.setZipTimeSlotFilter("all"); zipTimeSlotExpanded = false }
                        )
                        state.detectedZipTimeSlots.forEach { slot ->
                            DropdownMenuItem(
                                text = { Text(slot.displayName) },
                                onClick = { viewModel.setZipTimeSlotFilter(slot.id.toString()); zipTimeSlotExpanded = false }
                            )
                        }
                    }
                }
            }

            // Audio Cue Sync Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1322)),
                border = BorderStroke(1.dp, Color(0xFF1E293B)),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎙️ Audio Cue Sync (Spoken Intent Trigger)", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "+ Optional File (SRT/VTT)",
                            color = Color.Gray,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable { externalTranscriptPicker.launch("*/*") }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = audioFilterQuery,
                            onValueChange = { 
                                audioFilterQuery = it
                                viewModel.filterAudioCues(it)
                            },
                            placeholder = { Text("Filter spoken words (e.g. 'click on') or '17s, 30s'") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = BgDark, focusedContainerColor = BgDark,
                                unfocusedBorderColor = BorderColor, focusedBorderColor = WarningYellow
                            )
                        )
                        Button(
                            onClick = { viewModel.filterAudioCues(audioFilterQuery) },
                            colors = ButtonDefaults.buttonColors(containerColor = WarningYellow),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text("Find Cues", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }

                    // Dynamic Audio Cue Dropdown
                    if (state.detectedAudioCues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { audioCueExpanded = true },
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, DangerRed),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF1E1B4B))
                            ) {
                                Text(
                                    text = if (state.selectedAudioCueId == "all") "🌐 All Spoken Cues (${state.detectedAudioCues.size} Cues)"
                                           else state.detectedAudioCues.find { it.id.toString() == state.selectedAudioCueId }?.let { "🎙️ Cue: ${it.timeStr} (\"${it.snippet}\")" } ?: "Selected Cue",
                                    color = DangerRed, fontWeight = FontWeight.Bold, fontSize = 11.sp, maxLines = 1
                                )
                            }
                            DropdownMenu(expanded = audioCueExpanded, onDismissRequest = { audioCueExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("🌐 All Spoken Cues (${state.detectedAudioCues.size} Cues)") },
                                    onClick = { viewModel.setAudioCueFilter("all"); audioCueExpanded = false }
                                )
                                state.detectedAudioCues.forEach { cue ->
                                    DropdownMenuItem(
                                        text = { Text("🎙️ Cue ${cue.id + 1}: ${cue.startTime}s ➔ ${cue.endTime}s (\"${cue.snippet}\")") },
                                        onClick = { viewModel.setAudioCueFilter(cue.id.toString()); audioCueExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Tool Selector
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { zipToolExpanded = true },
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(ALL_EDITORA_TOOLS.find { it.first == selectedZipTool }?.second ?: "Select Tool", color = AccentBlue, fontSize = 12.sp)
                }
                DropdownMenu(expanded = zipToolExpanded, onDismissRequest = { zipToolExpanded = false }) {
                    ALL_EDITORA_TOOLS.forEach { (toolId, toolTitle) ->
                        DropdownMenuItem(
                            text = { Text(toolTitle) },
                            onClick = { selectedZipTool = toolId; zipToolExpanded = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ZIP URL Input
            OutlinedTextField(
                value = zipUrl,
                onValueChange = { zipUrl = it },
                placeholder = { Text("ZIP Archive URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = BgDark, focusedContainerColor = BgDark,
                    unfocusedBorderColor = BorderColor, focusedBorderColor = AccentBlue
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Action Buttons: URL Fetch OR Device Pick
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { viewModel.fetchZipFromUrl(zipUrl, filterText, selectedZipTool) },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Fetch ZIP & Apply", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                Button(
                    onClick = { localZipPicker.launch("application/zip") },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Pick ZIP from Device", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
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
            Text("⬅ Back: Gemini AI", color = Color.White)
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

// Step 3: Coordinates JSON & Frame ZIP Exporter
@Composable
private fun WizardStep3(viewModel: ToolsViewModel, state: ToolsUiState) {
    val clipboardManager = LocalClipboardManager.current
    var pastedJson by remember { mutableStateOf(state.exportedCoordinatesJson) }

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
                Text("Active Highlight Coordinates (JSON)", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { viewModel.applyPastedJson(pastedJson) },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Apply to Video", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { clipboardManager.setText(AnnotatedString(pastedJson)) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Copy", fontSize = 10.sp)
                    }
                    Button(
                        onClick = { viewModel.downloadCoordinatesJson() },
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                        shape = RoundedCornerShape(4.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("Download", fontSize = 10.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = pastedJson,
                onValueChange = { pastedJson = it },
                placeholder = { Text("Paste or view highlight coordinates here...") },
                modifier = Modifier.fillMaxWidth().height(180.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = AccentBlue),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = BgDark, focusedContainerColor = BgDark,
                    unfocusedBorderColor = BorderColor, focusedBorderColor = AccentBlue
                )
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    // ZIP Packager Card
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, BorderColor),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("Export All Frames as ZIP", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = { viewModel.downloadTimelineZip() },
                colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Download All as ZIP (fr1.timeline.txt, ...)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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

// Full-Width Diagnostic Log Console
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

// =========================================================================
// TAB 3: RENDER VIDEO WITH REAL-TIME PROGRESS BAR
// =========================================================================
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
                onClick = { viewModel.renderFullVideo() },
                enabled = !state.isRendering,
                colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    if (state.isRendering) "Rendering in Progress..." else "Download Full Video With Highlights & Audio",
                    fontWeight = FontWeight.Bold
                )
            }

            // Real-Time Animated Rendering Progress Bar Container
            if (state.isRendering) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(state.renderProgressStatus, color = Color.White, fontSize = 11.sp)
                    Text("${state.renderPercent}%", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { state.renderPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = AccentBlue,
                    trackColor = SurfaceVariant
                )
            }
        }
    }
}

// Target Slot Capture Modal Dialog (Triggered on 2nd Pause)
@Composable
private fun SlotCaptureDialog(viewModel: ToolsViewModel, state: ToolsUiState) {
    val start = state.slotStartFrame ?: 0
    val end = state.slotEndFrame ?: 0
    val minF = minOf(start, end)
    val maxF = maxOf(start, end)
    val count = maxF - minF + 1

    Dialog(onDismissRequest = { viewModel.resetSlotCycle() }) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            border = BorderStroke(2.dp, PurpleAccent),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Target Slot Captured", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceVariant, RoundedCornerShape(6.dp))
                        .border(1.dp, BorderColor, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Frame #$minF -> #$maxF ($count frames)",
                        color = Color(0xFFE9D5FF),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Ultra-Fast Engine: 50 frames concurrently", color = AccentBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { viewModel.scanCapturedSlot() },
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleAccent),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ultra-Fast Scan Slot (50 at a time)", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(6.dp))
                Button(
                    onClick = { viewModel.resetSlotCycle() },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close and Continue Playback")
                }
            }
        }
    }
}