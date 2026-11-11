package com.aiapp.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiapp.data.Downloads
import com.aiapp.llm.ChatMessage
import com.aiapp.llm.Effort
import com.aiapp.llm.ProviderId
import com.aiapp.llm.Role
import com.aiapp.reverse.CraftAnythingEngine
import kotlin.math.roundToInt

/**
 * Unified **Craft Anything (`storytold/craft-anything`) + REA (`morluto/rea`) + Clozed Code** Workbench Screen.
 *
 * Implements all 3 levels + bonus from Maitis's Craft Anything + Claude Code workflow alongside
 * SUN-LEY CODER's Clozed Code + MT Manager + REA workflow:
 * - **Level 1 (Architect + 300+ Inspection Tools)**: Studies any Website URL (e.g. `https://excalidraw.com`)
 *   or Android APK across **4 Stages (`1. Study → 2. Build → 3. Check → 4. Ready`)** in **Clean Room** or **Deep Mode**.
 * - **Level 2 (Setup & Human Oversight Safety)**: Includes `Auto Mode: OFF (Ask First)` approval card so the
 *   Architect shows you the study plan, colors, fonts, and open questions before the Builder writes code.
 * - **Level 3 (Build & Custom Branding)**: Set **Likeness Target (`95%`)**, **New App Name** (e.g. `MaitisBoard`),
 *   **Top-Left Logo Text** (`MAITIS`), and **Brand Color Theme** (`💜 Maitis Purple #7C3AED`, etc.), then inspect
 *   the **Multi-Viewport Likeness Check (`Phone %`, `Tablet %`, `Laptop %`)** and **Ask for Changes**.
 * - **Bonus ($0 Free AI Builders)**: Works with all Free Cloud / 9Router / Radox AI / Token Harbor models,
 *   OpenRouter free models, Claude, Gemini, ChatGPT, and 14 Offline LiteRT-LM models.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReverseScreen(chat: ChatViewModel, vm: ReverseViewModel, onBack: () -> Unit, onOpenBuild: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var input by remember { mutableStateOf("") }
    // File names decide how a picked file is used; pickers accept every type because many file
    // managers label .zip / .xapk / .apks as "octet-stream" and would grey them out otherwise.
    fun nameOf(uri: android.net.Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    fun openTarget(uri: android.net.Uri) {
        val n = nameOf(uri).lowercase()
        if (n.endsWith(".zip")) vm.loadZip(uri) else vm.loadApk(uri) // .apk, .apks, .xapk, .apkm
    }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) openTarget(uri) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) openTarget(uri) }
    // Attach in the chat box: an app file loads as the new target; any other file (build log,
    // notes, code) is added to the message as text.
    val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val n = nameOf(uri)
        val lower = n.lowercase()
        if (listOf(".apk", ".apks", ".xapk", ".apkm", ".zip").any { lower.endsWith(it) }) {
            openTarget(uri)
        } else {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { b ->
                    String(b.copyOf(minOf(b.size, 60_000)), Charsets.UTF_8)
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                android.widget.Toast.makeText(context, "Couldn't read $n as text.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                input = (input.trimEnd() + "\n\n[$n]\n" + text).trim()
            }
        }
    }
    var url by remember { mutableStateOf("") }
    var showAdvanced by remember { mutableStateOf(false) }
    var showCraftConfig by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(vm.openBuild) {
        if (vm.openBuild) { vm.consumeOpenBuild(); onOpenBuild() }
    }

    androidx.compose.runtime.LaunchedEffect(vm.transcript.size, vm.liveAnswer.length) {
        val count = vm.transcript.size + if (vm.answering && vm.liveAnswer.isNotEmpty()) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    val title = vm.report?.let { it.appLabel.ifBlank { it.packageName } } ?: "Craft Anything & REA"
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (vm.report != null) {
                        TextButton(onClick = { showCraftConfig = !showCraftConfig }) {
                            Text(if (showCraftConfig) "Hide Architect" else "Architect")
                        }
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(if (showAdvanced) "Hide Tools" else "Android Tools")
                        }
                        TextButton(onClick = { vm.unloadTarget() }) {
                            Text("New Target")
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (vm.report != null) {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        vm.status?.let {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text("  $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        // Quick action chips (Maitis Branding, Exact UI Clone, Paste Error Log to Fix, Compact Context, Reset Limit)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (vm.canResumeInterrupted) {
                                AssistChip(
                                    onClick = { vm.resetLimitAndContinue(chat.provider, chat.model, chat.effort) },
                                    label = { Text("🔄 Reset Limit & Continue", fontWeight = FontWeight.Bold) },
                                )
                            }
                            AssistChip(
                                onClick = {
                                    vm.selectedBrandPreset = CraftAnythingEngine.brandPresets.first() // Maitis Purple
                                    val logo = vm.customLogoText.ifBlank { vm.cloneAppName.ifBlank { "MaitisBoard" } }
                                    input = "Add a big \"$logo\" logo text in the top-left corner and make the main color purple (#7C3AED)."
                                },
                                label = { Text("💜 Add Logo + Purple Theme") },
                            )
                            AssistChip(
                                onClick = {
                                    val clipText = clipboard.getText()?.text.orEmpty().trim()
                                    if (clipText.isNotBlank()) {
                                        input = "Fix these build/compile errors in the project and update the source files:\n\n$clipText"
                                    } else {
                                        input = "Fix this build error in the cloned Android Studio project: "
                                    }
                                },
                                label = { Text("🔧 Paste & Fix Error Log") },
                            )
                            AssistChip(
                                onClick = {
                                    input = "Make the UI identical (95% likeness) to the original across Phone, Tablet & Landscape, including all tools and Dark Mode toggle."
                                },
                                label = { Text("🎨 95% Likeness + Dark Mode") },
                            )
                            AssistChip(
                                onClick = { vm.compactContextNow() },
                                label = { Text("⚡ Compact Context") },
                            )
                        }
                        // A plan is on screen: nothing is built until it is approved.
                        if (vm.pendingPlan != null) {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { vm.approvePendingPlan(chat.provider, chat.model, chat.effort) },
                                    enabled = !vm.answering,
                                    modifier = Modifier.weight(1f),
                                ) { Text("✅ Approve & build", maxLines = 1) }
                                OutlinedButton(
                                    onClick = { vm.keepDiscussing() },
                                    enabled = !vm.answering,
                                    modifier = Modifier.weight(1f),
                                ) { Text("✏️ Keep discussing", maxLines = 1) }
                            }
                        }
                        // Always visible, however much is typed below.
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    vm.startCraftAnythingWorkflow(input, chat.provider, chat.model, chat.effort)
                                    input = ""
                                },
                                enabled = !vm.answering,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            ) { Text("🛠️ Start Craft Anything", maxLines = 1, style = MaterialTheme.typography.labelLarge, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                            OutlinedButton(
                                onClick = {
                                    vm.reconstructAsProject(input, chat.provider, chat.model, chat.effort)
                                    input = ""
                                },
                                enabled = !vm.answering,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            ) { Text("🔍 JADX Reconstruct", maxLines = 1, style = MaterialTheme.typography.labelLarge, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { attachPicker.launch(arrayOf("*/*")) }) {
                                Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file or load another app")
                            }
                            OutlinedTextField(
                                value = input,
                                onValueChange = { input = it },
                                placeholder = {
                                    Text(
                                        when {
                                            vm.answering -> "Say something to steer…"
                                            else -> "Describe changes or ask a question — say \"build\" to start building"
                                        },
                                    )
                                },
                                maxLines = 3,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { vm.send(input, chat.provider, chat.model, chat.effort); input = "" },
                                enabled = input.isNotBlank(),
                            ) { Text(if (vm.answering) "Steer" else "Send") }
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.report == null) {
                LoadTargetPanel(
                    vm = vm,
                    url = url,
                    onUrlChange = { url = it },
                    onPickApk = { apkPicker.launch(arrayOf("*/*")) },
                    onPickZip = { zipPicker.launch(arrayOf("*/*")) },
                )
            } else {
                // Model picker — supports ALL providers (Free Cloud / 9Router / Radox AI, OpenRouter, Claude, Gemini, ChatGPT, Offline)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dropdown(label = chat.provider.label, options = ProviderId.entries.toList(), display = { it.label }, onSelect = chat::selectProvider)
                    val modelLabel = when {
                        chat.loadingModels -> "Loading models…"
                        chat.model.isBlank() -> "Pick model"
                        else -> chat.models.firstOrNull { it.id == chat.model }?.name ?: chat.model
                    }
                    Dropdown(
                        label = modelLabel, options = chat.models, display = { it.name },
                        onSelect = { chat.selectModel(it.id) }, onOpen = { if (chat.models.isEmpty()) chat.refreshModels() },
                    )
                    Dropdown(label = "Effort: ${chat.effort.label}", options = Effort.entries.toList(), display = { it.label }, onSelect = chat::selectEffort)
                }

                // 4-Stage Craft Anything Stepper Bar (1. Study -> 2. Build -> 3. Check -> 4. Ready)
                CraftStageStepperBar(currentStage = vm.craftStage, likenessReport = vm.craftCheck)

                // Context Compacted Badge (Clozed Code style)
                vm.contextCompactedBadge?.let { badge ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                vm.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
                }

                // Human Oversight Approval Card (shown when Auto Mode is OFF and Study finished)
                if (vm.craftStage == CraftAnythingEngine.CraftStage.AWAITING_APPROVAL && vm.craftStudy != null) {
                    HumanOversightApprovalCard(
                        study = vm.craftStudy!!,
                        newAppName = vm.cloneAppName,
                        likenessTarget = vm.likenessTargetPercent,
                        brandLabel = vm.selectedBrandPreset.label,
                        onApprove = { vm.approveAndContinueCraftBuild(chat.provider, chat.model, chat.effort) },
                    )
                }

                // Multi-Viewport Likeness Check Card (shown once Stage 3 Check completes)
                vm.craftCheck?.let { check ->
                    CraftLikenessCheckCard(check = check)
                }

                // Collapsible Craft Anything Architect & Branding Config Card
                if (showCraftConfig) {
                    CraftArchitectConfigCard(vm = vm)
                }

                // Generated Android Studio Clone Project (.zip) Download & APK Build Card
                vm.clonedZipAttachment?.let { zipAtt ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "🎉 Crafted Android Studio Project: ${zipAtt.name}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                vm.clonedZipSavedNote ?: "Saved to Downloads/AIApp (includes craft.json, investigation.cany/ & checks/)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Button(
                                    onClick = { vm.saveClonedZipToDownloads() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("📥 Save .zip")
                                }
                                OutlinedButton(
                                    onClick = { Downloads.shareAttachment(context, zipAtt) },
                                ) {
                                    Text("📤 Share .zip")
                                }
                                Button(
                                    onClick = { vm.compileClonedZipToApk(chat.provider, chat.model, chat.effort) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("🔨 Build APK")
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vm.transcript) { m -> MessageBubble(m) }
                    if (vm.answering && (vm.liveThinking.isNotBlank() || vm.liveAnswer.isNotBlank())) {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (vm.liveThinking.isNotBlank()) {
                                    ThinkingBox(vm.liveThinking, seconds = 0, live = vm.liveAnswer.isEmpty())
                                }
                                if (vm.liveAnswer.isNotBlank()) {
                                    MessageBubble(ChatMessage(Role.ASSISTANT, vm.liveAnswer))
                                }
                            }
                        }
                    }
                    if (vm.localInstallReady != null) {
                        item { Button(onClick = { vm.installLocal() }) { Text("📲 Install Rebuilt APK") } }
                    }
                }

                if (showAdvanced) AdvancedPanel(vm)
            }
        }
    }
}

@Composable
private fun CraftStageStepperBar(
    currentStage: CraftAnythingEngine.CraftStage,
    likenessReport: CraftAnythingEngine.CraftCheckReport?,
) {
    val stages = listOf(
        1 to "🔍 Study",
        2 to "🛠️ Build",
        3 to "✅ Check",
        4 to "🎉 Ready",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        stages.forEach { (num, label) ->
            val active = currentStage.stepNumber == num
            val done = currentStage.stepNumber > num || (currentStage == CraftAnythingEngine.CraftStage.READY && num == 4)
            FilterChip(
                selected = active || done,
                onClick = {},
                label = {
                    Text(
                        if (done && !active) "✓ $label" else label,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }
        likenessReport?.let { rep ->
            AssistChip(
                onClick = {},
                label = { Text("🎯 Likeness: ${rep.overallLikenessPercent}%", fontWeight = FontWeight.Bold) },
            )
        }
    }
}

@Composable
private fun HumanOversightApprovalCard(
    study: CraftAnythingEngine.CraftStudyEvidence,
    newAppName: String,
    likenessTarget: Int,
    brandLabel: String,
    onApprove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "🛡️ Human Oversight Approval (`Auto Mode: OFF — Ask First`)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Architect finished studying ${study.targetName} (${study.toolsRunCount} inspection checks). " +
                    "Colors: ${study.extractedColors.take(5).joinToString(", ")}. " +
                    "Target: `$newAppName` at `${likenessTarget}%` likeness with `$brandLabel`.",
                style = MaterialTheme.typography.bodySmall,
            )
            study.openQuestions.forEach { q ->
                Text("• $q", style = MaterialTheme.typography.labelSmall)
            }
            Button(
                onClick = onApprove,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("✅ Approve Architect Plan & Start Builder")
            }
        }
    }
}

@Composable
private fun CraftLikenessCheckCard(check: CraftAnythingEngine.CraftCheckReport) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "✅ Craft Anything Likeness Check: ${check.overallLikenessPercent}% (Target ${check.targetLikenessPercent}%)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "📱 Compact Phone (360dp): ${check.compactPhoneScore}%  ·  📲 Foldable (600dp): ${check.foldablePhoneScore}%  ·  📟 Android Tablet (840dp): ${check.androidTabletScore}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun CraftArchitectConfigCard(vm: ReverseViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Mode selector: Clean room vs Deep mode + Auto Mode (Ask First) safety toggle
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CraftAnythingEngine.StudyMode.entries.forEach { mode ->
                    FilterChip(
                        selected = vm.studyMode == mode,
                        onClick = { vm.studyMode = mode },
                        label = { Text(mode.label) },
                    )
                }
                FilterChip(
                    selected = !vm.autoMode,
                    onClick = { vm.autoMode = !vm.autoMode },
                    label = { Text(if (!vm.autoMode) "🛡️ Ask First (Auto: OFF)" else "⚡ Auto Mode: ON") },
                )
            }

            // App Name, Package ID & Custom Logo Text
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = vm.cloneAppName,
                    onValueChange = { vm.updateCloneAppName(it) },
                    label = { Text("App name (required)") },
                    placeholder = { Text("Enter a name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vm.customLogoText,
                    onValueChange = { vm.customLogoText = it },
                    label = { Text("Top-Left Logo Text") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }

            // Brand Color Preset Picker (Maitis Purple, Match Original, Emerald, Ocean, Amber, Crimson)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CraftAnythingEngine.brandPresets.forEach { preset ->
                    FilterChip(
                        selected = vm.selectedBrandPreset.id == preset.id,
                        onClick = { vm.selectedBrandPreset = preset },
                        label = { Text(preset.label) },
                    )
                }
            }

            // Likeness Target Slider (default 95%)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Likeness Target: ${vm.likenessTargetPercent}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Slider(
                    value = vm.likenessTargetPercent.toFloat(),
                    onValueChange = { vm.likenessTargetPercent = it.roundToInt().coerceIn(75, 99) },
                    valueRange = 75f..99f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun LoadTargetPanel(
    vm: ReverseViewModel,
    url: String,
    onUrlChange: (String) -> Unit,
    onPickApk: () -> Unit,
    onPickZip: () -> Unit,
) {
    var showInstalledPicker by remember { mutableStateOf(false) }

    Column(
        Modifier
            .padding(16.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "🏛️ Craft Anything (`storytold/craft-anything`) + REA Studio",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Point the Architect at any Website/Web App (e.g. excalidraw.com → MaitisBoard) or Android APK. " +
                "It studies layout, colors, fonts & logic, then builds your own Android Studio version (.zip + APK).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 3 Craft Anything Dashboard Actions (Make my own version, Work on my project, Just look inside)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CraftAnythingEngine.DashboardAction.entries.forEach { act ->
                FilterChip(
                    selected = vm.dashboardAction == act,
                    onClick = { vm.dashboardAction = act },
                    label = { Text(act.title) },
                )
            }
        }

        // Clean room vs Deep mode + Safety Auto Mode OFF ("Ask First")
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CraftAnythingEngine.StudyMode.entries.forEach { mode ->
                FilterChip(
                    selected = vm.studyMode == mode,
                    onClick = { vm.studyMode = mode },
                    label = { Text(mode.label) },
                )
            }
            FilterChip(
                selected = !vm.autoMode,
                onClick = { vm.autoMode = !vm.autoMode },
                label = { Text(if (!vm.autoMode) "🛡️ Auto Mode: OFF (Ask First)" else "⚡ Auto Mode: ON") },
            )
        }

        // Three equal-width, equal-height buttons so they line up on any screen size.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val btn = Modifier.weight(1f).heightIn(min = 64.dp)
            val pad = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
            Button(onClick = onPickApk, enabled = !vm.loading, modifier = btn, contentPadding = pad) {
                UploadLabel("📂", "Upload APK")
            }
            OutlinedButton(onClick = onPickZip, enabled = !vm.loading, modifier = btn, contentPadding = pad) {
                UploadLabel("📦", "Upload .zip")
            }
            OutlinedButton(
                onClick = {
                    showInstalledPicker = !showInstalledPicker
                    if (showInstalledPicker && vm.installedApps.isEmpty()) vm.refreshInstalledApps()
                },
                enabled = !vm.loading,
                modifier = btn,
                contentPadding = pad,
            ) {
                UploadLabel("📱", "Installed apps")
            }
        }

        Text(
            "Or paste ANY Website URL (like https://excalidraw.com), MT Manager APK path (/sdcard/MT2/apks/...), or APK link:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                placeholder = { Text("https://excalidraw.com or /sdcard/MT2/apks/app.apk") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                enabled = !vm.loading,
            )
            Button(onClick = { vm.loadUrl(url) }, enabled = !vm.loading && url.isNotBlank()) { Text("Study") }
        }

        // 1-Tap Quick Demo Presets (Excalidraw -> MaitisBoard from the video!)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = {
                    onUrlChange("https://excalidraw.com")
                    vm.loadUrl("https://excalidraw.com")
                },
                label = { Text("🎨 Demo: Study Excalidraw.com → MaitisBoard (95% Likeness)") },
            )
        }

        if (showInstalledPicker) {
            Card(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Pick an Installed App on This Phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { vm.refreshInstalledApps() }) { Text("Refresh") }
                    }
                    if (vm.loadingInstalledApps) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("  Scanning installed apps…", style = MaterialTheme.typography.bodySmall)
                        }
                    } else if (vm.installedApps.isEmpty()) {
                        Text("No user apps found.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(vm.installedApps) { item ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.loadInstalledApp(item) }
                                        .padding(vertical = 6.dp),
                                ) {
                                    Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${item.packageName} · ${"%.1f".format(item.sizeMb)} MB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }

        vm.status?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("  $it", style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun AdvancedPanel(vm: ReverseViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "🛠️ Built-in On-Device Android REA + Craft Anything Tools (100% On Phone · No PC)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${vm.reaInfo ?: "On-Device Engine Ready"} · Tap any tool below to run it directly on your Android phone:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                vm.reaTools.forEach { tool ->
                    AssistChip(
                        onClick = { vm.runOnDeviceReaTool(tool.name) },
                        label = { Text(tool.name) },
                    )
                }
            }
        }
    }
}

/** Icon above a short label, so the three upload buttons have the same shape. */
@Composable
private fun UploadLabel(icon: String, text: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon)
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
        )
    }
}
