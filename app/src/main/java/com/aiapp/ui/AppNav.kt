package com.aiapp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * One bottom navigation bar shared by every main screen, so the sections no longer sit in a crowded
 * top menu. Every destination the app already had is still reachable: the four main tabs here, and
 * the rest (Code, Chats, Error log, Settings) under "More". Nothing about the screens themselves
 * changes — this only switches which one is shown.
 */
@Composable
fun AppBottomBar(current: String, onSelect: (String) -> Unit) {
    val selected = when (current) {
        "chat", "build", "create", "reverse" -> current
        else -> "more"
    }
    var moreOpen by remember { mutableStateOf(false) }
    NavigationBar {
        NavigationBarItem(
            selected = selected == "chat",
            onClick = { onSelect("chat") },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
            label = { Text("Chat") },
        )
        NavigationBarItem(
            selected = selected == "build",
            onClick = { onSelect("build") },
            icon = { Icon(Icons.Filled.Build, contentDescription = null) },
            label = { Text("Build") },
        )
        NavigationBarItem(
            selected = selected == "create",
            onClick = { onSelect("create") },
            icon = { Icon(Icons.Filled.Brush, contentDescription = null) },
            label = { Text("Create") },
        )
        NavigationBarItem(
            selected = selected == "reverse",
            onClick = { onSelect("reverse") },
            icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
            label = { Text("Reverse") },
        )
        NavigationBarItem(
            selected = selected == "more",
            onClick = { moreOpen = true },
            icon = {
                Box {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = null)
                    DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                        DropdownMenuItem(text = { Text("Code") }, onClick = { moreOpen = false; onSelect("codespace") })
                        DropdownMenuItem(text = { Text("Chats") }, onClick = { moreOpen = false; onSelect("history") })
                        DropdownMenuItem(text = { Text("Error log") }, onClick = { moreOpen = false; onSelect("log") })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { moreOpen = false; onSelect("settings") })
                    }
                }
            },
            label = { Text("More") },
        )
    }
}
