package jp.eldia.hotel.feature.fridge

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.FlashlightOff
import androidx.compose.material.icons.outlined.FlashlightOn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import jp.eldia.hotel.core.DateUtil
import java.time.LocalDate
import java.util.concurrent.Executors

// MARK: - 日付の読み取り（テキスト → 期限日）

/**
 * パッケージに印字された賞味期限・消費期限の文字列から日付を取り出す（iOS版 ExpiryDateParser と同じ）。
 * 例: 2026.11.30 / 2026/11/30 / 2026-11-30 / 2026年11月30日 / 26.11.30 / 26 11 30
 *     2026 11 30 / 30.11.2026（日.月.年） / 2026.11・2026年11月・26.11（年月のみ → その月の末日）
 */
object ExpiryDateParser {
    data class Candidate(val date: LocalDate, val text: String, val score: Int)

    private enum class Kind { YMD, DMY, YYMD, YM, YYM }

    private val rules = listOf(
        Triple(Regex("""(20\d{2})\s*[年./\-]\s*(\d{1,2})\s*[月./\-]\s*(\d{1,2})"""), Kind.YMD, 100),
        // 区切りが空白だけの年月日（例: 2026 11 30）
        Triple(Regex("""(?<!\d)(20\d{2})\s+(\d{1,2})\s+(\d{1,2})(?!\d)"""), Kind.YMD, 95),
        Triple(Regex("""(?<!\d)(\d{1,2})[./\-](\d{1,2})[./\-](20\d{2})(?!\d)"""), Kind.DMY, 80),
        Triple(Regex("""(?<![\d.])(\d{2})[./\-\s](\d{1,2})[./\-\s](\d{1,2})(?![\d.])"""), Kind.YYMD, 70),
        Triple(Regex("""(20\d{2})\s*[年./\-]\s*(\d{1,2})(?!\s*[月./\-]?\s*\d)"""), Kind.YM, 60),
        Triple(Regex("""(?<![\d.])(\d{2})[./](\d{1,2})(?![./]?\d)"""), Kind.YYM, 40),
    )

    private val keyword = Regex("賞味|消費|期限|BEST|EXP|USE BY", RegexOption.IGNORE_CASE)

    /** 全角の英数字・記号を半角に */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            append(
                when (c) {
                    in '！'..'～' -> c - 0xFEE0
                    '　' -> ' '
                    else -> c
                },
            )
        }
    }

    fun candidates(raw: String, today: LocalDate = DateUtil.today()): List<Candidate> {
        val text = normalize(raw)
        val bonus = if (keyword.containsMatchIn(text)) 20 else 0
        val thisYear = today.year
        val earliest = today.minusYears(1)
        // 1か月以上前の日付は製造日・ロット番号の可能性が高いので点数を下げる
        val stale = today.minusMonths(1)
        val out = mutableListOf<Candidate>()
        for ((regex, kind, score) in rules) {
            for (m in regex.findAll(text)) {
                fun n(i: Int) = m.groupValues[i].toIntOrNull()
                val (y, mo, d) = when (kind) {
                    Kind.YMD -> Triple(n(1) ?: 0, n(2) ?: 0, n(3))
                    Kind.DMY -> Triple(n(3) ?: 0, n(2) ?: 0, n(1))
                    Kind.YYMD -> Triple(2000 + (n(1) ?: 0), n(2) ?: 0, n(3))
                    Kind.YM -> Triple(n(1) ?: 0, n(2) ?: 0, null)
                    Kind.YYM -> Triple(2000 + (n(1) ?: 0), n(2) ?: 0, null)
                }
                if (y !in (thisYear - 1)..(thisYear + 5) || mo !in 1..12) continue
                val days = LocalDate.of(y, mo, 1).lengthOfMonth()
                val day = d ?: days // 年月のみはその月の末日
                if (day !in 1..days) continue
                val date = LocalDate.of(y, mo, day)
                if (date.isBefore(earliest)) continue
                val penalty = if (date.isBefore(stale)) 50 else 0
                out += Candidate(date, m.value, score + bonus - penalty)
            }
        }
        return out
    }

    /** 画面に映っている文字の中から、いちばん期限らしい日付を選ぶ */
    fun best(texts: List<String>, today: LocalDate = DateUtil.today()): Candidate? =
        texts.flatMap { candidates(it, today) }.maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.date })

    private val weekdays = listOf("月", "火", "水", "木", "金", "土", "日")

    /** "2026年11月30日（月）" */
    fun label(d: LocalDate) = "${d.year}年${d.monthValue}月${d.dayOfMonth}日（${weekdays[d.dayOfWeek.value - 1]}）"
}

/**
 * 読み取り結果のブレ止め。直近 3 回の読み取りのうち 2 回以上同じ日付が出たときだけ採用する
 * （1 回だけの読み違いで表示が揺れたり、誤った日付を保存したりしないように）
 */
class ExpiryStabilizer(private val window: Int = 3, private val need: Int = 2) {
    private val recent = ArrayDeque<LocalDate?>()

    /** 今回の読み取り結果を渡す。安定していればその候補、まだなら null */
    fun offer(c: ExpiryDateParser.Candidate?): ExpiryDateParser.Candidate? {
        recent.addLast(c?.date)
        while (recent.size > window) recent.removeFirst()
        if (c == null) return null
        return if (recent.count { it == c.date } >= need) c else null
    }

    fun reset() = recent.clear()
}

// MARK: - カメラ（文字の読み取り）

/** ライト（トーチ）の操作。カメラが動いている間だけ controller が入る */
@Stable
class TorchControl {
    var controller by mutableStateOf<LifecycleCameraController?>(null)
    var on by mutableStateOf(false)
    /** 端末にライトがない・点けられなかったとき false */
    var supported by mutableStateOf(true)

    fun toggle() {
        val c = controller ?: return
        // カメラの準備前は cameraInfo が null。ライトがないと分かったときだけボタンを隠す
        if (c.cameraInfo?.hasFlashUnit() == false) { supported = false; on = false; return }
        val next = !on
        runCatching { c.enableTorch(next) }
            .onSuccess { on = next }
            .onFailure { on = false }
    }
}

@Composable
private fun rememberTorchControl(): TorchControl {
    val ctx = LocalContext.current
    return remember {
        TorchControl().apply { supported = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }
    }
}

/** ライトの切替ボタン（ライトのない端末では表示しない） */
@Composable
private fun TorchButton(torch: TorchControl) {
    if (!torch.supported || torch.controller == null) return
    IconButton(onClick = { torch.toggle() }) {
        Icon(
            if (torch.on) Icons.Outlined.FlashlightOn else Icons.Outlined.FlashlightOff,
            if (torch.on) "ライトを消す" else "ライトを点ける",
            tint = if (torch.on) Color(0xFFFFD54F) else Color.White,
        )
    }
}

/** カメラの使用許可。許可されていれば content、なければ案内を表示 */
@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) {
        content()
    } else {
        Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (asked) "カメラで読み取れません" else "カメラを準備しています…", color = Color.White, fontWeight = FontWeight.Bold)
            if (asked) {
                Text("カメラの使用を許可してください（設定 → アプリ → ELDIA → 権限）", color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) {
                    Text("もう一度許可を求める", color = Color.White)
                }
            }
        }
    }
}

/**
 * カメラ映像から文字を読み取り続ける（端末内で処理・通信なし）。タップでピント合わせ・ピンチでズーム。
 * onTexts は画面に映っている文字のまとまりごとの一覧（メインスレッドで呼ばれる）
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun TextScannerCamera(onTexts: (List<String>) -> Unit, torch: TorchControl, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val callback by rememberUpdatedState(onTexts)
    val previewView = remember {
        PreviewView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(owner) {
        val executor = Executors.newSingleThreadExecutor()
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        var last = 0L
        val controller = LifecycleCameraController(ctx).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            setImageAnalysisAnalyzer(executor) { proxy ->
                val media = proxy.image
                val now = System.currentTimeMillis()
                // 読み取りは 0.25 秒に1回まで（電池の消耗を抑える）
                if (media == null || now - last < 250) { proxy.close(); return@setImageAnalysisAnalyzer }
                last = now
                // 例外で proxy を閉じ損ねると次の画像が届かなくなるため、失敗時は必ず閉じる
                try {
                    val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                    recognizer.process(input)
                        .addOnSuccessListener { r ->
                            val texts = r.textBlocks.flatMap { b -> listOf(b.text) + b.lines.map { it.text } }
                            if (texts.isNotEmpty()) callback(texts)
                        }
                        .addOnCompleteListener { proxy.close() }
                } catch (e: Exception) {
                    proxy.close()
                }
            }
            bindToLifecycle(owner)
        }
        previewView.controller = controller
        torch.controller = controller
        onDispose {
            torch.controller = null
            torch.on = false
            previewView.controller = null
            controller.unbind()
            recognizer.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** 下のパネル（半透明の角丸） */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), tonalElevation = 3.dp) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            content()
        }
    }
}

// MARK: - 1品の読み取り（期限の入力画面から）

@Composable
fun ExpiryScannerDialog(onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var best by remember { mutableStateOf<ExpiryDateParser.Candidate?>(null) }
    val stabilizer = remember { ExpiryStabilizer() }
    val torch = rememberTorchControl()
    val haptic = LocalHapticFeedback.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            CameraPermissionGate {
                TextScannerCamera(onTexts = { texts ->
                    // 連続して同じ日付が読めたときだけ表示する
                    val b = stabilizer.offer(ExpiryDateParser.best(texts)) ?: return@TextScannerCamera
                    if (b.date != best?.date) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    best = b
                }, torch = torch, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "閉じる", tint = Color.White) }
                Text("期限を読み取る", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                TorchButton(torch)
            }
            Panel(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
                val b = best
                if (b != null) {
                    Text("読み取った期限", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(ExpiryDateParser.label(b.date), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace)
                    Text("「${b.text}」から読み取り", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onPick(b.date); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                        Text("この日付を使う", fontWeight = FontWeight.Bold)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.DocumentScanner, null)
                        Text("賞味期限・消費期限の日付にカメラを向けてください", fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                    Text("文字が小さいときは近づけるか、画面をタップしてピントを合わせてください", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

// MARK: - 連続読み取り（部屋の品目を順番に）

/**
 * 1品読み取って保存すると、次の段（A1 → A2 → …）へ自動で進む（iOS版 ContinuousExpiryScanView と同じ）。
 * 取り出し中・品目名のないマスは飛ばす。保存は端末に先に反映し、送信は裏で行う。
 */
@Composable
fun ContinuousExpiryScanScreen(vm: FridgeViewModel, rn: String, onClose: () -> Unit) {
    val queue = remember(rn) {
        val slots = vm.slots(rn)
        val removed = vm.removed.value
        slots.indices.filter { "$rn-$it" !in removed && slots[it].name.isNotEmpty() }
    }
    var position by remember { mutableIntStateOf(0) }
    var best by remember { mutableStateOf<ExpiryDateParser.Candidate?>(null) }
    var lastSaved by remember { mutableStateOf<LocalDate?>(null) }
    /** 直前に保存した日付（カメラに前の品が映ったままでも同じ日付を拾い直さないため） */
    var ignoreDate by remember { mutableStateOf<LocalDate?>(null) }
    var savedCount by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val stabilizer = remember { ExpiryStabilizer() }
    val torch = rememberTorchControl()
    val haptic = LocalHapticFeedback.current
    val currentIdx = queue.getOrNull(position)

    fun advance() { best = null; stabilizer.reset(); position++ }
    fun save(d: LocalDate) {
        val idx = currentIdx ?: return
        vm.updateExpiryQuiet(rn, idx, DateUtil.ymd(d))
        message = "${FridgeConst.slotLabel(idx)} → ${DateUtil.md(d)}"
        lastSaved = d
        ignoreDate = d
        savedCount++
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        advance()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (currentIdx != null) {
            CameraPermissionGate {
                TextScannerCamera(onTexts = { texts ->
                    val raw = ExpiryDateParser.best(texts)
                    val ig = ignoreDate
                    if (raw != null && ig != null) {
                        if (raw.date == ig) { stabilizer.offer(null); return@TextScannerCamera }
                        ignoreDate = null
                    }
                    // 連続して同じ日付が読めたときだけ採用する
                    val c = stabilizer.offer(raw) ?: return@TextScannerCamera
                    if (c.date != best?.date) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    best = c
                }, torch = torch, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "閉じる", tint = Color.White) }
                Text("${rn}号室 連続読み取り", color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (currentIdx != null) TorchButton(torch)
            }
            if (currentIdx != null) {
                val slot = vm.slots(rn)[currentIdx]
                Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(FridgeConst.slotLabel(currentIdx), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                                modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 4.dp))
                            Text(slot.name, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            Text("${position + 1}/${queue.size}", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Row {
                            Text(if (slot.exp.isEmpty()) "現在の期限：未入力" else "現在の期限：${slot.exp.replace("-", "/")}",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            message?.let { Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = FridgeStatus.OK.color) }
                        }
                    }
                }
            }
        }
        if (currentIdx != null) {
            Panel(Modifier.align(Alignment.BottomCenter)) {
                val b = best
                if (b != null) {
                    Text(ExpiryDateParser.label(b.date), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace)
                    Text("「${b.text}」から読み取り", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("この品の期限にカメラを向けてください", fontWeight = FontWeight.SemiBold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { advance() }) { Text("スキップ") }
                    val ls = lastSaved
                    if (b == null && ls != null) {
                        OutlinedButton(onClick = { save(ls) }) { Text("前と同じ（${DateUtil.md(ls)}）") }
                    }
                    Button(onClick = { b?.let { save(it.date) } }, enabled = b != null, modifier = Modifier.weight(1f)) {
                        Text("保存して次へ", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            Surface(Modifier.align(Alignment.Center).padding(24.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = FridgeStatus.OK.color, modifier = Modifier.size(56.dp))
                    Text(if (queue.isEmpty()) "読み取る品目がありません" else "${rn}号室の読み取りが終わりました", fontWeight = FontWeight.Bold)
                    Text("${savedCount}品の期限を更新しました", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onClose) { Text("閉じる") }
                }
            }
        }
    }
}
