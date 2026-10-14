package jp.eldia.hotel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import jp.eldia.hotel.core.AppUpdater

/**
 * アプリ内アップデートの表示。アプリ全体の上に重ねる
 * - 帯：ダウンロード中 / 「再起動して更新」
 * - 全画面：更新が必須の版（app_min_version より古い）
 */
@Composable
fun UpdateOverlay() {
    val banner by AppUpdater.banner.collectAsState()
    val required by AppUpdater.required.collectAsState()
    val ctx = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = banner != AppUpdater.Banner.NONE && required == null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 96.dp)
                    .fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (banner == AppUpdater.Banner.DOWNLOADING) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.inverseOnSurface)
                        Spacer(Modifier.width(12.dp))
                        Text("新しいバージョンをダウンロードしています", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("新しいバージョンの準備ができました", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { AppUpdater.completeUpdate() }) {
                            Text("再起動して更新", color = MaterialTheme.colorScheme.inversePrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // 更新が必須：更新するまで使えない
        required?.let { message ->
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("アップデートが必要です", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Button(onClick = {
                        if (banner == AppUpdater.Banner.READY) AppUpdater.completeUpdate() else AppUpdater.openStore(ctx)
                    }) {
                        Text(if (banner == AppUpdater.Banner.READY) "再起動して更新" else "Google Play で更新する")
                    }
                }
            }
        }
    }
}
