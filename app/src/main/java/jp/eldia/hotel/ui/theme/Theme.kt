package jp.eldia.hotel.ui.theme

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import jp.eldia.hotel.core.Appearance
import jp.eldia.hotel.core.Hotel

/** 画面から参照する店舗と明暗（文字用アクセントの切り替えに使う） */
data class EldiaColors(val hotel: Hotel, val dark: Boolean) {
    val accent: Color get() = hotel.accent
    val accentText: Color get() = hotel.accentText(dark)
}

val LocalEldia = staticCompositionLocalOf { EldiaColors(Hotel.LUXURY, false) }

/**
 * Material 3 Expressive のテーマ。iOS版の Liquid Glass は使えないので、
 * 色（店舗のアクセント）と情報の並べ方だけ iOS版にそろえ、動きは Expressive（弾むような動き）にする。
 * 端末の壁紙から色を取る機能（Dynamic Color）は使わない（店舗の色で見分けるため）。
 * primaryContainer は半透明にしない（ボタンの背景が透けて見にくくなるため、背景色と混ぜた不透明色）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EldiaTheme(hotel: Hotel, appearance: Appearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.SYSTEM -> isSystemInDarkTheme()
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    val accent = hotel.accent
    val scheme = if (dark) {
        darkColorScheme(
            primary = hotel.accentText(true),
            onPrimary = Color(0xFF111111),
            primaryContainer = lerp(Color(0xFF0F1117), accent, 0.38f),
            onPrimaryContainer = Color(0xFFF2F2F2),
            secondary = hotel.accentText(true),
            background = Color(0xFF0F1117),
            onBackground = Color(0xFFE8E2D4),
            surface = Color(0xFF0F1117),
            onSurface = Color(0xFFE8E2D4),
            surfaceVariant = Color(0xFF232635),
            onSurfaceVariant = Color(0xFFA0A0B0),
            surfaceContainer = Color(0xFF1A1D27),
            surfaceContainerHigh = Color(0xFF232635),
            surfaceContainerLow = Color(0xFF161922),
            outline = Color(0xFF2E3248),
            outlineVariant = Color(0xFF2E3248),
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = lerp(Color(0xFFEEF2F6), accent, 0.20f),
            onPrimaryContainer = hotel.accentText(false),
            secondary = hotel.accentText(false),
            // iOS版と同じく、背景は青みのグレー
            background = Color(0xFFEEF2F6),
            onBackground = Color(0xFF1A1C1E),
            surface = Color(0xFFEEF2F6),
            onSurface = Color(0xFF1A1C1E),
            surfaceVariant = Color(0xFFE2E7ED),
            onSurfaceVariant = Color(0xFF5B6470),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFF6F8FA),
            surfaceContainerLow = Color(0xFFF2F5F8),
            outline = Color(0xFFCBD3DC),
            outlineVariant = Color(0xFFDDE3E9),
        )
    }
    // ステータスバー・ナビゲーションバーのアイコンの明暗を、端末設定ではなくアプリの外観に合わせる
    val context = LocalContext.current
    LaunchedEffect(dark) {
        val activity = context.findActivity() ?: return@LaunchedEffect
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent)
        activity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
    CompositionLocalProvider(LocalEldia provides EldiaColors(hotel, dark)) {
        MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
    }
}

/** Compose の Context から Activity を取り出す（ContextWrapper を外していく） */
private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
