package jp.eldia.hotel.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.eldia.hotel.core.StatusColor
import jp.eldia.hotel.feature.aqua.AquaViewModel
import jp.eldia.hotel.feature.cleaning.CleaningViewModel
import jp.eldia.hotel.feature.facility.FacilityViewModel
import jp.eldia.hotel.feature.fridge.FridgeViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 電波がなく端末にためている記録（清掃・水槽・冷蔵庫・設備）の件数と「今すぐ送信」。
 * ホーム・清掃・水槽・冷蔵庫のどこでも同じ表示にする（0 件のときは何も出さない。iOS版 PendingSendCard と同じ）
 */
@Composable
fun PendingSendCard(
    modifier: Modifier = Modifier,
    cleaningVm: CleaningViewModel = viewModel(),
    aquaVm: AquaViewModel = viewModel(),
    fridgeVm: FridgeViewModel = viewModel(),
    facilityVm: FacilityViewModel = viewModel(),
    onSent: suspend () -> Unit = {},
) {
    val cleaning by cleaningVm.pendingCount.collectAsState()
    val aqua by aquaVm.pendingCount.collectAsState()
    val fridgePending by fridgeVm.pending.collectAsState()
    val fridgeRemovedOps by fridgeVm.removedOps.collectAsState()
    val facility by facilityVm.pendingCount.collectAsState()
    val parts = listOf(
        "清掃" to cleaning, "水槽" to aqua,
        "冷蔵庫" to fridgePending.size + fridgeRemovedOps.size, "設備" to facility,
    ).filter { it.second > 0 }
    val total = parts.sumOf { it.second }
    if (total == 0) return

    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    EldiaCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = StatusColor.progress)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("送信待ち ${total}件", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    parts.joinToString("・") { "${it.first} ${it.second}" } + "　電波が戻ると自動で送信します",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (sending) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = {
                    sending = true
                    scope.launch {
                        try {
                            cleaningVm.flushPending()
                            aquaVm.flushPending()
                            facilityVm.flushPendingIssues()
                            fridgeVm.syncNow(silent = false)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // 送れなかった分は電波が戻ったときに自動で送られる
                        } finally {
                            sending = false
                        }
                        onSent()
                    }
                }) { Text("今すぐ送信", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
