package com.binh.chordoffline

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.binh.chordoffline.core.Version
import com.binh.chordoffline.ui.AppViewModel
import com.binh.chordoffline.ui.BatchScreen
import com.binh.chordoffline.ui.ChordTheme
import com.binh.chordoffline.ui.CheckScreen
import com.binh.chordoffline.ui.CompactViewer
import com.binh.chordoffline.ui.LibraryScreen
import com.binh.chordoffline.ui.PageViewer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChordTheme { AppRoot() }
        }
    }
}

private data class Viewing(val key: String, val version: Version)

@Composable
private fun AppRoot(vm: AppViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var viewing by remember { mutableStateOf<Viewing?>(null) }

    val v = viewing
    if (v != null) {
        BackHandler { viewing = null }
        when (v.version) {
            Version.COMPACT -> CompactViewer(vm, v.key, onBack = { viewing = null })
            Version.PAGE -> PageViewer(vm, v.key, onBack = { viewing = null })
        }
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                    icon = { Text("🔍") }, label = { Text("Kiểm tra URL") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1; vm.refreshLibrary() },
                    icon = { Text("📚") }, label = { Text("Đã lưu") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                    icon = { Text("📥") }, label = { Text("Tải nhiều") })
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            0 -> CheckScreen(vm, m, onOpen = { key, ver -> viewing = Viewing(key, ver) })
            1 -> LibraryScreen(vm, m, onOpen = { key, ver -> viewing = Viewing(key, ver) })
            else -> BatchScreen(vm, m, onOpen = { key, ver -> viewing = Viewing(key, ver) })
        }
    }
}
