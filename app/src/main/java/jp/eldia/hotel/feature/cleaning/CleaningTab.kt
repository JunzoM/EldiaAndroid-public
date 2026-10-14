package jp.eldia.hotel.feature.cleaning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.eldia.hotel.core.TabRequests
import jp.eldia.hotel.feature.aqua.AquaScreen
import jp.eldia.hotel.feature.special.SpecialCleaningScreen
import jp.eldia.hotel.ui.components.EmptyState

/** 清掃タブの切り替え（iOS版 CleaningSegment と同じ色：清掃記録=ローズ／特別清掃=ゴールド／水槽=ティール） */
enum class CleaningSegment(val label: String, val color: Color) {
    CLEANING("清掃記録", Color(0xFFE11D48)),
    SPECIAL("特別清掃", Color(0xFFB8860B)),
    AQUA("水槽", Color(0xFF009688)),
}

@Composable
fun CleaningTab(modifier: Modifier = Modifier) {
    var segment by rememberSaveable { mutableStateOf(CleaningSegment.CLEANING) }
    // ホームなど外から区分を指定されたら切り替えて、指定を消す
    val requested by TabRequests.cleaningSegment.collectAsState()
    LaunchedEffect(requested) {
        val name = requested ?: return@LaunchedEffect
        CleaningSegment.entries.firstOrNull { it.name == name }?.let { segment = it }
        TabRequests.cleaningSegment.value = null
    }
    Column(modifier) {
        SegmentBar(segment) { segment = it }
        Box(Modifier.fillMaxSize()) {
            when (segment) {
                CleaningSegment.CLEANING -> CleaningLogScreen()
                CleaningSegment.SPECIAL -> SpecialCleaningScreen()
                CleaningSegment.AQUA -> AquaScreen()
            }
        }
    }
}

/** 色付きのカプセル型切り替え（iOS版と同じ見た目） */
@Composable
private fun SegmentBar(selected: CleaningSegment, onSelect: (CleaningSegment) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CleaningSegment.entries.forEach { s ->
            val on = s == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (on) s.color else Color.Transparent)
                    .clickable { onSelect(s) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s.label,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
