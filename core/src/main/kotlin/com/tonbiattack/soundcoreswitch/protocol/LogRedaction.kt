package com.tonbiattack.soundcoreswitch.protocol

/**
 * 診断ログに出す値を匿名化する。
 *
 * 仕様書 §6「通信ログには秘密情報を記録しない」に対応する。
 * Bluetooth アドレスと端末名は個人を特定し得るため、そのまま出さない。
 *
 * **原因調査に必要な情報は残す。** 何件あったか、接続中だったか、
 * どのコマンドが失敗したかは分かるようにする。識別情報だけを伏せる。
 */
object LogRedaction {

    /**
     * Bluetooth アドレスを短い匿名 ID に置き換える。
     *
     * 同じアドレスは常に同じ ID になるため、ログ内で対応を追える。
     * 元のアドレスは復元できない（ハッシュではなく、末尾だけを使う）。
     *
     * @return 例 `"dev-3f2a"`。null や空なら `"dev-?"`。
     */
    fun deviceId(mac: String?): String {
        if (mac.isNullOrBlank()) return "dev-?"
        // 末尾4文字だけを使う。MAC 全体は復元できない。
        val tail = mac.filter { it.isLetterOrDigit() }.takeLast(4).lowercase()
        return if (tail.length == 4) "dev-$tail" else "dev-?"
    }

    /**
     * 端末名を伏せる。
     *
     * 名前は個人を特定し得る（PC 名・スマホ名がそのまま出る）。
     * 長さだけ残すことで「空でない」「長すぎる」は分かる。
     */
    fun deviceName(name: String?): String {
        if (name.isNullOrBlank()) return "name-?"
        return "name(len=${name.length})"
    }

    /**
     * プロトコル応答の body を伏せる。
     *
     * 生のバイト列には MAC・端末名が含まれる。長さだけ残す。
     */
    fun body(bytes: ByteArray?): String {
        if (bytes == null) return "body(len=0)"
        return "body(len=${bytes.size})"
    }

    /**
     * 例外メッセージを安全化する。
     *
     * 例外には MAC や内部パスが混ざり得る。種類だけを残す。
     */
    fun error(e: Throwable?): String {
        if (e == null) return "error(unknown)"
        return "error(${e.javaClass.simpleName})"
    }

    /**
     * 接続先の一覧を件数だけで表す。
     *
     * どの端末が接続中かは出さない。件数だけでは個人を特定できない。
     */
    fun deviceList(devices: List<RawDevice>?): String {
        if (devices == null) return "devices(unavailable)"
        val connected = devices.count { it.connected }
        return "devices(total=${devices.size}, connected=$connected)"
    }
}
