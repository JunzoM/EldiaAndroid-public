package jp.eldia.hotel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.eldia.hotel.core.Hotel

/** 白いカード（iOS版の eldiaCard に相当） */
@Composable
fun EldiaCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** 店舗の切り替え（モダン神戸店／ラグジュアリー神戸店）。画面上部に置く */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HotelSegmented(selected: Hotel, onSelect: (Hotel) -> Unit, modifier: Modifier = Modifier) {
    val order = listOf(Hotel.MODERN, Hotel.LUXURY)
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        order.forEachIndexed { i, h ->
            SegmentedButton(
                selected = selected == h,
                onClick = { onSelect(h) },
                shape = SegmentedButtonDefaults.itemShape(i, order.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = h.accent.copy(alpha = 0.18f),
                    activeContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            ) { Text("${h.label} 神戸店", maxLines = 1) }
        }
    }
}

/** 上部バー用の店舗切り替えメニュー（タップで2店舗から選ぶ） */
@Composable
fun HotelMenuButton(selected: Hotel, onSelect: (Hotel) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    TextButton(onClick = { menuOpen = true }) {
        Text(selected.english, color = selected.accent, fontWeight = FontWeight.Bold)
    }
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        Hotel.entries.forEach { h ->
            DropdownMenuItem(
                text = { Text("${h.label} 神戸店") },
                leadingIcon = { if (h == selected) Icon(Icons.Default.Check, null, tint = h.accent) },
                onClick = { onSelect(h); menuOpen = false },
            )
        }
    }
}

/** データがないとき・準備中の画面 */
@Composable
fun EmptyState(icon: ImageVector, title: String, message: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** 画面右下のボタン（ピル型・店舗色で塗りつぶし。テーマの半透明色は使わない） */
@Composable
fun PillFab(text: String, icon: ImageVector, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        icon = { Icon(icon, null) },
        text = { Text(text, fontWeight = FontWeight.Bold) },
        shape = CircleShape,
        containerColor = color,
        contentColor = Color.White,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 8.dp),
        modifier = modifier,
    )
}
