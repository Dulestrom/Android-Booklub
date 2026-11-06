package io.github.dulestrom.booklub

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.github.dulestrom.booklub.SyncController.Companion.failureText
import kotlinx.coroutines.launch
import uniffi.booklub_mobile.Invite
import java.util.Locale
import java.util.concurrent.Executors

private sealed interface Step {
    data object Start : Step
    data object Scan : Step
    data class ConfirmQr(val text: String, val invite: Invite) : Step
    data object Code : Step
    /** E4: only when a memory card offers a second place for images (REQ-42, P13). */
    data class Folder(val then: Step) : Step
    data object Working : Step
    data class Done(val name: String, val books: Int) : Step
    data class Failed(val reason: String) : Step
}

/** Screen E1 to E8: pairing with the computer, then the first session (UC-01). */
@Composable
fun PairScreen(state: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync = state.sync
    var step by remember { mutableStateOf<Step>(Step.Start) }
    var pendingCode by remember { mutableStateOf<Pair<String, String>?>(null) }
    val options = remember { storageOptions(context) }

    fun pair(qr: Step.ConfirmQr?) {
        step = Step.Working
        scope.launch {
            val outcome = if (qr != null) sync.pairByQr(qr.text) else pendingCode!!.let { (address, code) -> sync.pairByCode(address, code) }
            step = outcome.fold(
                onSuccess = { Step.Done(sync.computer?.name ?: "your computer", state.library.books().size) },
                onFailure = { Step.Failed(it.failureText()) },
            )
        }
    }

    /** Asks for the image folder first when there is a choice to make. */
    fun proceed(qr: Step.ConfirmQr?) {
        if (options.size > 1) step = Step.Folder(qr ?: Step.Code) else pair(qr)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TitleBar("Pair with Computer", onBack = { if (step != Step.Working) state.back() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            when (val now = step) {
                Step.Start -> StartStep(state, onScan = { step = Step.Scan }, onType = { step = Step.Code })
                Step.Scan -> ScanStep(
                    onText = { text ->
                        runCatching { state.library.readInvitation(text) }
                            .onSuccess { step = Step.ConfirmQr(text, it) }
                            .onFailure { step = Step.Failed(it.failureText()) }
                    },
                    onType = { step = Step.Code },
                )
                is Step.ConfirmQr -> {
                    // REQ-20d on the phone: the name comes from the QR code.
                    Spacer(Modifier.height(8.dp))
                    Text("Pair with “${now.invite.name}”?", style = Type.title2, color = ink.ink)
                    Text(now.invite.address, style = Type.caption, color = ink.ink3, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        "This phone's books will be replaced by the library on this computer. Then changes on either side reach the other while Booklub is open on both.",
                        style = Type.body, color = ink.ink2, modifier = Modifier.padding(top = 12.dp),
                    )
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CommitButton("Pair") { proceed(now) }
                        SecondaryButton("Cancel") { step = Step.Start }
                    }
                }
                Step.Code -> CodeStep(pendingCode) { address, code ->
                    pendingCode = address to code
                    proceed(null)
                }
                is Step.Folder -> FolderStep(state, options) { pair(now.then as? Step.ConfirmQr) }
                Step.Working -> WorkingStep(sync.progress)
                is Step.Done -> {
                    Spacer(Modifier.height(8.dp))
                    Feedback("SUCCESS", "Paired with ${now.name}. ${booksText(now.books).replaceFirstChar { it.uppercase() }} on this phone.")
                    Text("Changes now reach your computer while Booklub is open on both.", style = Type.body, color = ink.ink2, modifier = Modifier.padding(top = 12.dp))
                    CommitButton("Done", Modifier.padding(top = 20.dp)) { state.select(Tab.Home) }
                }
                is Step.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    Feedback("ERROR", now.reason)
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CommitButton("Try Again") { step = Step.Start }
                        SecondaryButton("Back") { state.back() }
                    }
                }
            }
        }
    }

    // REQ-20d for a typed code: the computer's name is known only once connected.
    sync.confirming?.let { name ->
        AlertDialog(
            onDismissRequest = { sync.answer(false) },
            containerColor = ink.card,
            title = { Text("Pair with “$name”?", style = Type.title2, color = ink.ink) },
            text = { Text("This phone's books will be replaced by the library on this computer.", style = Type.body, color = ink.ink2) },
            confirmButton = { TextButton(onClick = { sync.answer(true) }) { Text("Pair", color = ink.accentInk, style = Type.bodyStrong) } },
            dismissButton = { TextButton(onClick = { sync.answer(false) }) { Text("Cancel", color = ink.ink2, style = Type.bodyStrong) } },
        )
    }
}

@Composable
private fun StartStep(state: AppState, onScan: () -> Unit, onType: () -> Unit) {
    val current = state.sync.computer
    var replacing by remember { mutableStateOf<(() -> Unit)?>(null) }
    // REQ-20i: one computer at a time.
    fun guarded(next: () -> Unit) = if (current != null) replacing = next else next()
    Spacer(Modifier.height(8.dp))
    Text("On your computer, open Booklub, then Settings, then Devices, and choose Pair a Phone.", style = Type.body, color = ink.ink2)
    Text("Both devices need to be on the same home network.", style = Type.caption, color = ink.ink3, modifier = Modifier.padding(top = 8.dp))
    CommitButton("Scan the QR Code", Modifier.padding(top = 20.dp).fillMaxWidth()) { guarded(onScan) }
    SecondaryButton("Type the Code Instead", modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) { guarded(onType) }
    replacing?.let { next ->
        ConfirmDialog(
            title = "Replace ${current?.name}?",
            body = "This phone syncs with one computer. Pairing with another stops syncing with ${current?.name}, and this phone's books are replaced by the new computer's library.",
            confirm = "Continue",
            onDismiss = { replacing = null },
            onConfirm = { replacing = null; next() },
        )
    }
}

@Composable
private fun ScanStep(onText: (String) -> Unit, onType: () -> Unit) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var refused by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        refused = !granted
    }
    Spacer(Modifier.height(8.dp))
    if (allowed) {
        Text("Point the camera at the code on your computer's screen.", style = Type.body, color = ink.ink2)
        QrScanner(Modifier.padding(top = 16.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)), onText)
    } else {
        Text(
            if (refused) "Booklub can't use the camera. You can allow it in the phone's settings, or type the code instead." else "Booklub needs the camera to scan the code. It isn't used for anything else.",
            style = Type.body, color = ink.ink2,
        )
        if (!refused) CommitButton("Allow Camera", Modifier.padding(top = 16.dp)) { ask.launch(Manifest.permission.CAMERA) }
    }
    SecondaryButton("Type the Code Instead", modifier = Modifier.padding(top = 16.dp)) { onType() }
}

/** CameraX frames through ZXing, reading QR codes only, from the luminance plane. */
@Composable
private fun QrScanner(modifier: Modifier, onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = context as LifecycleOwner
    val analyzer = remember { Executors.newSingleThreadExecutor() }
    var found by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            analyzer.shutdown()
        }
    }
    AndroidView(modifier = modifier.background(ink.raised), factory = { viewContext ->
        PreviewView(viewContext).also { view ->
            val future = ProcessCameraProvider.getInstance(viewContext)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val reader = MultiFormatReader().apply { setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(analyzer) { image ->
                    try {
                        val plane = image.planes[0]
                        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                        val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
                        val text = runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text }.getOrNull()
                        reader.reset()
                        if (text != null && text.startsWith("booklub:pair") && !found) {
                            found = true
                            ContextCompat.getMainExecutor(viewContext).execute { onText(text) }
                        }
                    } finally {
                        image.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(viewContext))
        }
    })
}

@Composable
private fun CodeStep(previous: Pair<String, String>?, onPair: (String, String) -> Unit) {
    var address by remember { mutableStateOf(previous?.first ?: "") }
    var code by remember { mutableStateOf(previous?.second ?: "") }
    val ready = address.isNotBlank() && code.filter { it.isLetterOrDigit() }.length == 8
    Spacer(Modifier.height(8.dp))
    Text("Type the address and code shown under the QR code on your computer.", style = Type.body, color = ink.ink2)
    OutlinedTextField(
        value = address, onValueChange = { address = it.trim() }, label = { Text("Address") }, placeholder = { Text("192.168.1.20:47120") },
        singleLine = true, colors = fieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
    )
    OutlinedTextField(
        value = code, onValueChange = { typed -> code = typed.uppercase(Locale.ROOT).take(9) }, label = { Text("Pairing code") }, placeholder = { Text("K7Q4 9XMP") },
        singleLine = true, colors = fieldColors(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
    )
    CommitButton("Pair", Modifier.padding(top = 20.dp), enabled = ready) { onPair(address, code) }
}

@Composable
private fun FolderStep(state: AppState, options: List<StorageOption>, onChosen: () -> Unit) {
    val scope = rememberCoroutineScope()
    Spacer(Modifier.height(8.dp))
    Text("Where should Booklub keep book images?", style = Type.title2, color = ink.ink)
    Text("Each image is a small thumbnail. You can change this later in Settings.", style = Type.body, color = ink.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
    for (option in options) {
        val current = option.path == state.imagesDir
        Card(Modifier.padding(bottom = 10.dp).clickable(onClickLabel = "Keep images on ${option.label}") {
            scope.launch {
                if (!current) state.moveImages(option)
                onChosen()
            }
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(option.label, style = Type.bodyStrong, color = ink.ink)
                    Text("${megabytes(option.freeBytes)} free", style = Type.caption, color = ink.ink3)
                }
                if (current) Glyph(R.drawable.ic_check, "Current", ink.accentInk)
            }
        }
    }
}

@Composable
private fun WorkingStep(progress: Progress?) {
    Spacer(Modifier.height(8.dp))
    Text("Receiving your library", style = Type.title2, color = ink.ink)
    // REQ-31a.
    val books = progress?.books ?: 0
    val (images, total) = (progress?.images ?: 0) to (progress?.imagesTotal ?: 0)
    Text(
        when {
            total > 0 -> "${booksText(books).replaceFirstChar { it.uppercase() }} received. Images: $images of $total."
            books > 0 -> "${booksText(books).replaceFirstChar { it.uppercase() }} received."
            else -> "Connecting to your computer…"
        },
        style = Type.body, color = ink.ink2, modifier = Modifier.padding(top = 8.dp),
    )
    if (total > 0) {
        LinearProgressIndicator(progress = { images.toFloat() / total }, color = ink.accent, trackColor = ink.raised, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
    } else {
        LinearProgressIndicator(color = ink.accent, trackColor = ink.raised, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
    }
    Text("Keep Booklub open on both devices until this finishes.", style = Type.caption, color = ink.ink3, modifier = Modifier.padding(top = 12.dp))
}

fun megabytes(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024)) else "${bytes / (1024 * 1024)} MB"

/** REQ-34: "just now", "5 minutes ago", "today at 14:05", "yesterday at 09:12", or a date. */
fun lastSyncedText(rfc3339: String?): String {
    val at = rfc3339?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()) }.getOrNull() } ?: return "Not synced yet"
    val now = java.time.ZonedDateTime.now()
    val minutes = java.time.Duration.between(at, now).toMinutes()
    val time = at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return "Last synced " + when {
        minutes < 1 -> "just now"
        minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        at.toLocalDate() == now.toLocalDate() -> "today at $time"
        at.toLocalDate() == now.toLocalDate().minusDays(1) -> "yesterday at $time"
        else -> "on " + at.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK))
    }
}

