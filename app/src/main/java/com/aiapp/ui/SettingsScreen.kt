package com.aiapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aiapp.llm.OfflineModel
import com.aiapp.llm.ProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "API keys are stored encrypted on this phone only. Tip: You can paste multiple keys separated by commas (,) for automatic rotation when one key hits a rate limit!",
                style = MaterialTheme.typography.bodyMedium,
            )
            ProviderId.entries.filter { it.needsKey }.forEach { KeyField(it, vm) }
            NineRouterSection(vm)
            GithubTokenField(vm)
            OfflineSection(vm)
        }
    }
}

private fun keyUrl(p: ProviderId): String = when (p) {
    ProviderId.CLAUDE -> "console.anthropic.com/settings/keys"
    ProviderId.GEMINI -> "aistudio.google.com/apikey"
    ProviderId.OPENAI -> "platform.openai.com/api-keys"
    ProviderId.OPEN -> "openrouter.ai/keys (one key for Kimi, DeepSeek, GLM, Qwen, Dolphin; many are free)"
    ProviderId.FREE -> ""
    ProviderId.OFFLINE -> ""
}

@Composable
private fun KeyField(p: ProviderId, vm: ChatViewModel) {
    var text by remember(p) { mutableStateOf(vm.key(p)) }
    var note by remember(p) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                note = null
            },
            label = { Text("${p.label} API key(s) (comma-separated for auto-rotation)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Get one at ${keyUrl(p)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                vm.saveKey(p, text)
                note = "Saved"
            }) { Text("Save") }
            OutlinedButton(onClick = {
                vm.resetProviderLimits(p)
                note = "Limit flag reset ✓"
            }) { Text("Reset Limit Flag") }
            TextButton(onClick = {
                text = ""
                vm.saveKey(p, "")
                note = "Cleared"
            }) { Text("Clear") }
            note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun OfflineSection(vm: ChatViewModel) {
    val recommended = vm.recommendedOffline()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider()
        Text("Offline models (14 Built-in LiteRT-LM Models)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run on this phone with no internet or API key. Your phone has about ${vm.deviceRamGb()} GB " +
                "of RAM, so ${recommended.name} is recommended. Keep the app open while it downloads; " +
                "an interrupted download resumes where it stopped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vm.offlineModels().forEach { OfflineRow(it, vm) }
        AddOfflineModel(vm)
    }
}

@Composable
private fun OfflineRow(m: OfflineModel, vm: ChatViewModel) {
    val progress = vm.downloads[m.id]
    val downloaded = vm.offlineDownloaded(m)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.name, style = MaterialTheme.typography.bodyLarge)
        if (m.description.isNotBlank()) {
            Text(
                m.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when {
                downloaded -> "Downloaded · ready offline"
                progress != null -> "Downloading… ${(progress * 100).toInt()}%"
                m.approxSizeGb == 0.0 -> "From huggingface.co/${m.repo}"
                else -> "About ${m.approxSizeGb} GB · needs ${m.minRamGb} GB+ RAM · ${m.repo}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                downloaded -> {
                    Button(onClick = { vm.useOffline(m) }) { Text("Use") }
                    OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Delete") }
                }
                progress != null -> OutlinedButton(onClick = { vm.cancelDownload(m) }) { Text("Pause") }
                else -> {
                    Button(onClick = { vm.downloadOffline(m) }) { Text("Download") }
                    if (m.id.startsWith("hf-")) OutlinedButton(onClick = { vm.deleteOffline(m) }) { Text("Remove") }
                }
            }
        }
        vm.downloadErrors[m.id]?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun GithubTokenField(vm: ChatViewModel) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var showAdvanced by remember { mutableStateOf(false) }
    var clientId by remember { mutableStateOf(vm.githubClientId()) }
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("GitHub (for Build app & REA CI)", style = MaterialTheme.typography.titleMedium)
        val code = vm.githubCode
        when {
            vm.githubToken().isNotBlank() -> {
                Text(
                    "Signed in as ${vm.githubUser.ifBlank { "your GitHub account" }}. " +
                        "New apps get their own repo on your account automatically.",
                )
                OutlinedButton(onClick = vm::signOutGithub) { Text("Sign out") }
            }
            code != null -> {
                Text("Enter this code on GitHub, then come back:")
                Text(code.userCode, style = MaterialTheme.typography.headlineMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode)) }) { Text("Copy code") }
                    OutlinedButton(onClick = { uri.openUri(code.verificationUri) }) { Text("Open GitHub") }
                }
                Text("Waiting for you to approve…", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Button(onClick = { vm.signInGithub { link -> uri.openUri(link) } }) { Text("Sign in with GitHub") }
                Text(
                    "You approve once on github.com; the app can then create repos and build APKs for you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        vm.githubLoginError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Advanced") }
        if (showAdvanced) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it },
                label = { Text("GitHub OAuth Client ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "From github.com/settings/developers → OAuth Apps (tick \"Enable Device Flow\").",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.saveGithubClientId(clientId.trim()) }) { Text("Save Client ID") }
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Or paste a personal access token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Classic token with public_repo and workflow scopes, from github.com/settings/tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                vm.saveGithubToken(token)
                token = ""
            }, enabled = token.isNotBlank()) { Text("Save token") }
        }
    }
}

@Composable
private fun AddOfflineModel(vm: ChatViewModel) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var token by remember { mutableStateOf(vm.hfToken()) }
    var tokenNote by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a model", style = MaterialTheme.typography.titleSmall)
        Text(
            "Paste a Hugging Face link to any LiteRT-LM model (.litertlm), for example from huggingface.co/litert-community. " +
                "Some conversions use a chat template this app can't run (you'll get a clear error if so) — try a different one.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link,
            onValueChange = {
                link = it
                error = null
            },
            label = { Text("Hugging Face link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            error = vm.addOfflineModel(link)
            if (error == null) link = ""
        }, enabled = link.isNotBlank()) { Text("Add and download") }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        OutlinedTextField(
            value = token,
            onValueChange = {
                token = it
                tokenNote = null
            },
            label = { Text("Hugging Face token (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Only needed for models that ask you to accept a licence. Create one at huggingface.co/settings/tokens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                vm.saveHfToken(token)
                tokenNote = "Saved"
            }) { Text("Save token") }
            tokenNote?.let { Text("  $it", style = MaterialTheme.typography.labelMedium) }
        }
    }
}


@Composable
private fun NineRouterSection(vm: ChatViewModel) {
    var routerUrl by remember { mutableStateOf(vm.customRouterUrl()) }
    var routerKey by remember { mutableStateOf(vm.customRouterKey()) }
    var routerModel by remember { mutableStateOf(vm.customRouterModel()) }
    var savedNote by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Free AI Doors: 9Router / Radox AI / Custom Endpoint (Optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Free Cloud already includes 10 built-in $0 gateways automatically. You can also connect a 9Router (Nine Router) or Radox AI OpenAI-compatible endpoint here (from Maitis's Craft Anything guide).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = routerUrl,
            onValueChange = { routerUrl = it; savedNote = null },
            label = { Text("9Router / Radox AI Base URL (e.g. https://api.radox.ai/v1)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = routerKey,
                onValueChange = { routerKey = it; savedNote = null },
                label = { Text("API Key (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = routerModel,
                onValueChange = { routerModel = it; savedNote = null },
                label = { Text("Model ID (optional)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                vm.saveCustomRouter(routerUrl, routerKey, routerModel)
                savedNote = "Saved 9Router / Radox endpoint ✓"
            }) { Text("Save Router") }
            TextButton(onClick = {
                routerUrl = ""; routerKey = ""; routerModel = ""
                vm.saveCustomRouter("", "", "")
                savedNote = "Cleared (using built-in Free Cloud)"
            }) { Text("Clear") }
            savedNote?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        }
    }
}
