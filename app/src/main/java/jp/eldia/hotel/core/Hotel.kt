package jp.eldia.hotel.core

import androidx.compose.ui.graphics.Color

/** 施設（店舗）。DB には rawValue（"luxury" / "modern"）で保存されている */
enum class Hotel(val rawValue: String) {
    LUXURY("luxury"),
    MODERN("modern");

    /** 日本語表記 */
    val label: String get() = if (this == LUXURY) "ラグジュアリー" else "モダン"
    val english: String get() = if (this == LUXURY) "Luxury" else "Modern"

    /** 店舗別アクセントカラー（iOS版・Web版と同じ） */
    val accent: Color get() = if (this == LUXURY) Color(0xFFB8860B) else Color(0xFF0277BD)

    /** 文字に使うアクセント（金色は白背景だと薄いので濃くする。iOS版と同じ値） */
    fun accentText(dark: Boolean): Color = when (this) {
        LUXURY -> if (dark) Color(0xFFE0B44A) else Color(0xFF7A5806)
        MODERN -> if (dark) Color(0xFF6CC3F5) else Color(0xFF01579B)
    }

    companion object {
        fun from(raw: String?): Hotel? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** 状態色（iOS版の StatusColor と対応） */
object StatusColor {
    val open = Color(0xFFE53935)
    val progress = Color(0xFFFB8C00)
    val done = Color(0xFF43A047)
    val overdue = Color(0xFFF97316)
}
