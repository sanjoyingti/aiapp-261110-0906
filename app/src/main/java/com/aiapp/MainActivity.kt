package com.aiapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiapp.ui.AppTheme
import com.aiapp.ui.BuildScreen
import com.aiapp.ui.BuildViewModel
import com.aiapp.ui.ChatScreen
import com.aiapp.ui.ChatViewModel
import com.aiapp.ui.CreateScreen
import com.aiapp.ui.CreateViewModel
import com.aiapp.ui.HistoryScreen
import com.aiapp.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val chatVm: ChatViewModel by viewModels()

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.aiapp.ui.ImageLoader.clear()
        }
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (!com.aiapp.builder.BuildRunner.running && !chatVm.streaming) {
                com.aiapp.llm.Providers.offlineIfLoaded?.release()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatVm.handleOAuthIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chatVm.handleOAuthIntent(intent)
        setContent {
            AppTheme {
                val vm: ChatViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("chat") }
                var previousScreen by rememberSaveable { mutableStateOf("chat") }

                BackHandler(enabled = screen != "chat") {
                    screen = "chat"
                }
                val buildVm: BuildViewModel = viewModel()
                val createVm: CreateViewModel = viewModel()
                when (screen) {
                    "chat" -> ChatScreen(
                        vm,
                        onOpenSettings = { screen = "settings" },
                        onOpenBuild = { screen = "build" },
                        onOpenHistory = { screen = "history" },
                        onOpenCreate = { screen = "create" },
                    )
                    "history" -> HistoryScreen(vm, onBack = { screen = "chat" })
                    "create" -> CreateScreen(createVm, onBack = { screen = "chat" }, onOpenSettings = { screen = "settings" })
                    "build" -> BuildScreen(
                        vm,
                        buildVm,
                        onBack = { screen = "chat" },
                        onOpenSettings = { screen = "settings" },
                    )
                    else -> SettingsScreen(
                        vm,
                        onBack = { screen = "chat" },
                    )
                }
            }
        }
    }
}
