package com.aiapp.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

/**
 * In-app workspace for GitHub Codespaces and ChatGPT Codex, in a persistent WebView.
 *
 * You sign in to GitHub / OpenAI inside the page (cookies persist). The WebView instance is kept
 * across tab switches so the session and editor state survive leaving and coming back.
 */
object CodespaceSession {
    const val CODESPACES = "https://github.com/codespaces"
    const val CODEX = "https://chatgpt.com/codex"
    const val GITHUB = "https://github.com/login"

    // A desktop UA so VS Code Web / Codespaces serve the full editor instead of a mobile stub.
    const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

    var webView: WebView? = null
    var lastUrl: String = CODESPACES
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CodespaceScreen(onBack: () -> Unit) {
    var progress by remember { mutableIntStateOf(100) }
    var current by remember { mutableStateOf(CodespaceSession.lastUrl) }

    fun load(url: String) {
        current = url
        CodespaceSession.lastUrl = url
        CodespaceSession.webView?.loadUrl(url)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Codespaces / Codex") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(current.startsWith(CodespaceSession.CODESPACES), { load(CodespaceSession.CODESPACES) }, { Text("GitHub Codespaces") })
                FilterChip(current.startsWith(CodespaceSession.CODEX), { load(CodespaceSession.CODEX) }, { Text("ChatGPT Codex") })
                FilterChip(current.startsWith(CodespaceSession.GITHUB), { load(CodespaceSession.GITHUB) }, { Text("GitHub sign-in") })
            }
            if (progress in 0..99) LinearProgressIndicator(Modifier.fillMaxWidth())

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val existing = CodespaceSession.webView
                    if (existing != null) {
                        (existing.parent as? ViewGroup)?.removeView(existing)
                        existing
                    } else {
                        WebView(ctx).apply {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                            settings.userAgentString = CodespaceSession.DESKTOP_UA
                            settings.setSupportMultipleWindows(true)
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String) {
                                    CodespaceSession.lastUrl = url
                                }
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                            }
                            CodespaceSession.webView = this
                            loadUrl(CodespaceSession.lastUrl)
                        }
                    }
                },
            )
        }
    }

    BackHandler {
        val wv = CodespaceSession.webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }
}
