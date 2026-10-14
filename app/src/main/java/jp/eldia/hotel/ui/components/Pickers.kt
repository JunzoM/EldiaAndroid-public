package jp.eldia.hotel.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import jp.eldia.hotel.core.DateUtil
import java.time.LocalDate

/** 日付を選ぶボタン（タップでカレンダー） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateButton(date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    var showPicker by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { showPicker = true }, modifier = modifier) {
        Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(18.dp))
        Text(DateUtil.label(date), Modifier.padding(start = 8.dp))
    }
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = DateUtil.toPickerMillis(date))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(DateUtil.fromPickerMillis(it)) }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("キャンセル") } },
        ) { DatePicker(state) }
    }
}

/** 時刻を選ぶダイアログ（"HH:mm" を返す） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: String?, title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = (initial ?: DateUtil.hhmm()).split(":").mapNotNull { it.toIntOrNull() }
    val state = rememberTimePickerState(
        initialHour = parts.getOrElse(0) { 9 },
        initialMinute = parts.getOrElse(1) { 0 },
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = { onConfirm("%02d:%02d".format(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
    )
}

/** 横に並ぶ絞り込みチップ */
@Composable
fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, tint: Color, onSelect: (T) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
    ) {
        items(items) { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = tint.copy(alpha = 0.18f),
                    selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }
    }
}

/** 短いお知らせ（iOS版の toast に相当） */
@Composable
fun rememberToast(): (String) -> Unit {
    val ctx = LocalContext.current
    return remember(ctx) { { msg: String -> Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() } }
}

/** 見出し＋値の1行 */
@Composable
fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
