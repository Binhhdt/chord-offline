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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.platform.LocalView
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

    // Giữ màn hình sáng khi đang quét / tải / chạy tự động (ở bất kỳ tab nào) để Android không tạm dừng app
    val busy = vm.batch.collectAsState().value.running || vm.discover.collectAsState().value.running ||
        vm.auto.collectAsState().value.active
    val view = LocalView.current
    DisposableEffect(busy) { view.keepScreenOn = busy; onDispose { view.keepScreenOn = false } }

    // Xin quyền thông báo (Android 13+) để hiện tiến độ khi tải chạy nền
    val ctx = LocalContext.current
    var askedNotif by rememberSaveable { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(busy) {
        if (busy && !askedNotif && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askedNotif = true
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val focus = LocalFocusManager.current
    val open: (String, Version) -> Unit = { key, ver -> focus.clearFocus(); viewing = Viewing(key, ver) }

    // Trình xem mở CHỒNG LÊN danh sách (không thay thế) -> quay lại vẫn giữ từ khoá tìm kiếm, gợi ý, vị trí cuộn.
    Box(Modifier.fillMaxSize()) {
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
            0 -> CheckScreen(vm, m, onOpen = open)
            1 -> LibraryScreen(vm, m, onOpen = open)
            else -> BatchScreen(vm, m, onOpen = open)
        }
    }

    val v = viewing
    if (v != null) {
        BackHandler { viewing = null }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (v.version) {
                Version.COMPACT -> CompactViewer(vm, v.key, onBack = { viewing = null })
                Version.PAGE -> PageViewer(vm, v.key, onBack = { viewing = null })
            }
        }
    }
    }
}
