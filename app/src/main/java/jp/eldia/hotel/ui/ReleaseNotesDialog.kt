package jp.eldia.hotel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.eldia.hotel.core.ReleaseNotes

/** アップデートのお知らせ（更新後に1回だけ）。ログイン後の画面の上に出す */
@Composable
fun ReleaseNotesDialog() {
    val note by ReleaseNotes.note.collectAsState()
    val n = note ?: return
    val ctx = LocalContext.current
    AlertDialog(
        // 外側タップでは閉じない（読まずに消えるのを防ぐ）。OK で閉じる
        onDismissRequest = {},
        icon = { Icon(Icons.Outlined.NewReleases, null) },
        title = { Text(n.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                n.items.forEach { item ->
                    Row {
                        Text("・", style = MaterialTheme.typography.bodyMedium)
                        Text(item, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 2.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { ReleaseNotes.dismiss(ctx) }) { Text("OK") } },
    )
}
