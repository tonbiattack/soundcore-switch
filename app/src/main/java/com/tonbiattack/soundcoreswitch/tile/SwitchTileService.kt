package com.tonbiattack.soundcoreswitch.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.tonbiattack.soundcoreswitch.MainActivity

/**
 * クイック設定タイルから接続先の切り替え画面を開く。
 *
 * タイルのタップでアプリの画面を開く方式にしている。理由:
 * - 接続先の選択には一覧の取得（RFCOMM 通信）が必要で、タイル上では表現しきれない
 * - 誤操作を防ぐには、対象を確認してから切り替える必要がある
 * - 常駐サービスを持たずに済む（仕様書の「バックグラウンド常駐をMVP必須としない」に沿う）
 *
 * タイルの状態表示は「アプリが利用可能か」だけに留める。接続先の状態は
 * 通信しないと分からないため、タイルには出さない（古い情報を最新と誤認させない）。
 */
@RequiresApi(Build.VERSION_CODES.N)
class SwitchTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        // 画面を開く。ロック画面からは開けないため、その場合は案内しない（OS が処理する）。
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        if (isLocked) {
            // ロック中はアプリを開けない。タイルの状態だけ更新して戻る。
            updateTile()
            return
        }

        // startActivityAndCollapse のシグネチャは API 34 で変わった。
        // それ未満は Intent を受け取る版しか無いため、非推奨警告を抑制して使う。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }
    }

    /** タイルの表示を更新する。 */
    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = "Soundcore"
        // 接続先の状態は通信しないと分からないため、タイルには出さない。
        // サブタイトルで「タップで開く」ことだけ伝える。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = "接続先を切り替える"
        }
        tile.updateTile()
    }
}
