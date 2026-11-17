package com.tonbiattack.soundcoreswitch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tonbiattack.soundcoreswitch.domain.adapter.BluetoothAvailability
import com.tonbiattack.soundcoreswitch.domain.model.ConnectionStatus
import com.tonbiattack.soundcoreswitch.domain.model.EarbudsDevice
import com.tonbiattack.soundcoreswitch.domain.model.MultipointTarget
import com.tonbiattack.soundcoreswitch.domain.model.SupportLevel
import com.tonbiattack.soundcoreswitch.domain.model.SwitchUiState
import com.tonbiattack.soundcoreswitch.domain.model.TargetSnapshot
import com.tonbiattack.soundcoreswitch.domain.model.TargetsUiState
import com.tonbiattack.soundcoreswitch.presentation.AppUiState
import com.tonbiattack.soundcoreswitch.presentation.FailureMessage
import com.tonbiattack.soundcoreswitch.presentation.toMessage
import com.tonbiattack.soundcoreswitch.ui.theme.StatusColors
import kotlinx.coroutines.delay

/**
 * アプリの唯一の画面。
 *
 * 仕様書 §9 の想定画面を1画面で実装する。画面遷移と確認ダイアログを持たない
 * （仕様書 §5「画面遷移・ローディング・確認ダイアログを最小化」）。
 *
 * @param state 表示状態。
 * @param onSelectDevice イヤホンを選び直す。
 * @param onSwitch 接続先へ切り替える。
 * @param onRefresh 接続先一覧を再取得する。
 * @param onRequestPermission Bluetooth 権限を要求する。
 * @param onDismissSwitchResult 切り替え結果の表示を閉じる。
 */
@Composable
fun AppScreen(
    state: AppUiState,
    onSelectDevice: (EarbudsDevice) -> Unit,
    onSwitch: (MultipointTarget) -> Unit,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onDismissSwitchResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Soundcore Switch",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(12.dp))

            // 更新中は控えめなバーだけ出す。全画面ローディングは体感速度を損なう。
            if (state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            BluetoothBanner(
                availability = state.bluetooth,
                onRequestPermission = onRequestPermission,
            )

            DeviceSelector(
                devices = state.devices,
                selected = state.selectedDevice,
                onSelect = onSelectDevice,
                onRefresh = onRefresh,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Text(
                text = "接続先",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))

            TargetsSection(
                state = state,
                onSwitch = onSwitch,
                onRefresh = onRefresh,
            )

            SwitchResultBar(
                switch = state.switch,
                onDismiss = onDismissSwitchResult,
            )
        }
    }
}

/** Bluetooth が使えないときだけ出す案内。 */
@Composable
private fun BluetoothBanner(
    availability: BluetoothAvailability,
    onRequestPermission: () -> Unit,
) {
    if (availability == BluetoothAvailability.Ready) return

    val (title, detail) = when (availability) {
        BluetoothAvailability.Off -> "Bluetoothがオフです" to
            "端末の設定からBluetoothをオンにしてください。"
        BluetoothAvailability.PermissionDenied -> "Bluetoothの権限がありません" to
            "近くのデバイスへのアクセスを許可すると、イヤホンを検出できます。"
        BluetoothAvailability.NotSupported -> "この端末はBluetoothに対応していません" to
            "Bluetoothを搭載した端末でお試しください。"
        is BluetoothAvailability.Unknown -> "Bluetoothの状態を確認できませんでした" to
            "端末を再起動してから、もう一度お試しください。"
        BluetoothAvailability.Ready -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (availability == BluetoothAvailability.PermissionDenied) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onRequestPermission) {
                    Text("権限を許可")
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

/** イヤホンの選択行。 */
@Composable
private fun DeviceSelector(
    devices: List<EarbudsDevice>,
    selected: EarbudsDevice?,
    onSelect: (EarbudsDevice) -> Unit,
    onRefresh: () -> Unit,
) {
    if (devices.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Soundcoreイヤホンが見つかりません",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh) { Text("再検出") }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        devices.forEach { device ->
            FilterChip(
                selected = device.address == selected?.address,
                onClick = { onSelect(device) },
                label = {
                    Column {
                        Text(
                            text = device.displayName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val note = device.supportLevel.shortLabel()
                        if (note != null) {
                            Text(
                                text = note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                leadingIcon = {
                    StatusDot(
                        status = device.connection,
                        contentDescription = device.connection.label(),
                    )
                },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

/** 接続先セクション。状態ごとに出し分ける。 */
@Composable
private fun TargetsSection(
    state: AppUiState,
    onSwitch: (MultipointTarget) -> Unit,
    onRefresh: () -> Unit,
) {
    // イヤホンが未選択のまま「読み込み中」を出し続けると、いつまでも待たせているように見える。
    // 先に何をすればよいかを伝える。
    if (state.selectedDevice == null) {
        Text(
            text = if (state.devices.isEmpty()) {
                "イヤホンが見つからないため、接続先を表示できません。"
            } else {
                "イヤホンを選択すると接続先が表示されます。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    when (val targets = state.targets) {
        TargetsUiState.Loading -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "接続先を読み込んでいます",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is TargetsUiState.Unavailable -> {
            val message = targets.reason.toMessage()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = message.title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = message.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onRefresh) { Text("再試行") }
                }
            }
        }

        is TargetsUiState.Ready -> {
            TargetList(
                snapshot = targets.snapshot,
                canSwitch = state.canSwitch,
                switchState = state.switch,
                selectedDevice = state.selectedDevice,
                onSwitch = onSwitch,
                onRefresh = onRefresh,
            )
        }
    }
}

/** 接続先の一覧。 */
@Composable
private fun TargetList(
    snapshot: TargetSnapshot,
    canSwitch: Boolean,
    switchState: SwitchUiState,
    selectedDevice: EarbudsDevice?,
    onSwitch: (MultipointTarget) -> Unit,
    onRefresh: () -> Unit,
) {
    if (snapshot.targets.isEmpty()) {
        Text(
            text = "接続先が登録されていません",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(snapshot.targets, key = { it.id }) { target ->
            TargetRow(
                target = target,
                enabled = canSwitch,
                inFlight = switchState is SwitchUiState.InFlight &&
                    switchState.targetId == target.id,
                onSwitch = onSwitch,
            )
        }
        item {
            Spacer(Modifier.height(4.dp))
            LastUpdated(
                snapshot = snapshot,
                onRefresh = onRefresh,
                disabledReason = disabledReason(canSwitch, selectedDevice),
            )
        }
    }
}

/** 接続先1件。 */
@Composable
private fun TargetRow(
    target: MultipointTarget,
    enabled: Boolean,
    inFlight: Boolean,
    onSwitch: (MultipointTarget) -> Unit,
) {
    val isConnected = target.status == ConnectionStatus.CONNECTED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${target.displayName} ${target.status.label()}"
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        onClick = { if (enabled && !inFlight) onSwitch(target) },
        enabled = enabled && !inFlight,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(
                status = target.status,
                contentDescription = target.status.label(),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = target.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (target.isThisDevice) {
                        Spacer(Modifier.width(6.dp))
                        Badge(text = "この端末")
                    }
                }
                Text(
                    text = target.status.label(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/** 小さなラベル。 */
@Composable
private fun Badge(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 接続状態を表す丸。色だけに頼らないよう、必ずテキストを併記する。 */
@Composable
private fun StatusDot(status: ConnectionStatus, contentDescription: String) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> StatusColors.Connected
        ConnectionStatus.DISCONNECTED -> StatusColors.Disconnected
        ConnectionStatus.UNKNOWN -> StatusColors.Unknown
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color = color, shape = CircleShape)
            .semantics { this.contentDescription = contentDescription },
    )
}

/**
 * 最終更新と再取得。
 *
 * キャッシュ由来のときは「古い可能性がある」ことを必ず明示する（仕様書 §5）。
 */
@Composable
private fun LastUpdated(
    snapshot: TargetSnapshot,
    onRefresh: () -> Unit,
    disabledReason: String?,
) {
    // 経過時間の表示を更新する。1秒ごとに再コンポーズする必要はない。
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.observedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        val elapsed = ((now - snapshot.observedAt) / 1000).coerceAtLeast(0)
        val suffix = if (snapshot.fromCache) "（キャッシュ）" else ""
        Text(
            text = "最終更新: ${elapsed}秒前$suffix",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (snapshot.fromCache) {
            Text(
                text = "キャッシュを表示しています。最新の状態とは異なる可能性があります。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (disabledReason != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = disabledReason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRefresh) { Text("更新") }
    }
}

/** 切り替え結果の表示。 */
@Composable
private fun SwitchResultBar(
    switch: SwitchUiState,
    onDismiss: () -> Unit,
) {
    val message: FailureMessage? = when (switch) {
        is SwitchUiState.Failed -> switch.reason.toMessage()
        else -> null
    }
    val successName = (switch as? SwitchUiState.Succeeded)?.targetName

    if (message == null && successName == null) return

    Spacer(Modifier.height(8.dp))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message != null) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = if (message != null) message.title else "$successName に切り替えました",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (message != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.detail,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    }
}

/** 切り替えが無効な理由。押せない理由を必ず伝える。 */
private fun disabledReason(canSwitch: Boolean, device: EarbudsDevice?): String? {
    if (canSwitch) return null
    if (device == null) return "イヤホンを選択してください。"
    return when (device.supportLevel) {
        SupportLevel.SUPPORTED -> "イヤホンが接続されていないため、切り替えできません。"
        SupportLevel.PARTIAL -> "この機種は接続状態の表示のみ対応しています。"
        SupportLevel.UNSUPPORTED -> "この機種は対応していません。"
        SupportLevel.UNKNOWN -> "この機種は実機での動作確認が完了していないため、切り替えできません。"
    }
}

/** 接続状態の表示文言。不明を切断中と書かない。 */
private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.CONNECTED -> "接続中"
    ConnectionStatus.DISCONNECTED -> "切断中"
    ConnectionStatus.UNKNOWN -> "不明"
}

/** 対応レベルの短いラベル。対応済みなら何も出さない。 */
private fun SupportLevel.shortLabel(): String? = when (this) {
    SupportLevel.SUPPORTED -> null
    SupportLevel.PARTIAL -> "一部対応"
    SupportLevel.UNSUPPORTED -> "未対応"
    SupportLevel.UNKNOWN -> "未検証"
}
