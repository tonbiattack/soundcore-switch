package com.tonbiattack.soundcoreswitch.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tonbiattack.soundcoreswitch.presentation.AppViewModel

/**
 * 画面と [AppViewModel] をつなぐ。
 *
 * 権限要求はここで行う。要求する権限は Android バージョンで異なる（仕様書 §6
 * 「Bluetooth権限はAndroidバージョン別に最小限で要求する」）。
 * - Android 12 (API 31) 以降: `BLUETOOTH_CONNECT`
 * - それ未満: インストール時の権限で足りるため、実行時要求は不要
 *
 * @param viewModel 画面の状態を持つ ViewModel。
 */
@Composable
fun AppRoute(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // 1つでも拒否されたら権限不足として扱う。要求していない場合は空なので true。
        val granted = results.values.all { it }
        viewModel.onPermissionResult(granted)
    }

    LaunchedEffect(Unit) { viewModel.start() }

    AppScreen(
        state = state,
        onSelectDevice = viewModel::selectDevice,
        onSwitch = viewModel::switchTo,
        onRefresh = viewModel::refresh,
        onRequestPermission = {
            val permissions = requiredPermissions()
            if (permissions.isEmpty()) {
                // 要求する権限が無い世代では、状態を確認し直すだけにする。
                viewModel.onPermissionResult(granted = true)
            } else {
                permissionLauncher.launch(permissions)
            }
        },
        onDismissSwitchResult = viewModel::dismissSwitchResult,
        onToggleFavorite = viewModel::toggleFavorite,
        onToggleProtection = viewModel::toggleProtection,
    )
}

/** 実行時に要求すべき Bluetooth 権限。不要な世代では空配列。 */
private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        emptyArray()
    }
