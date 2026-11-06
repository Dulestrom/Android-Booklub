package io.github.dulestrom.booklub

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.booklub_mobile.Computer
import uniffi.booklub_mobile.SyncFailure
import uniffi.booklub_mobile.SyncListener
import uniffi.booklub_mobile.SyncResult
import uniffi.booklub_mobile.syncPollSeconds
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** First-session progress (REQ-31a). */
data class Progress(val books: Int, val images: Int, val imagesTotal: Int)

/**
 * Sync with the paired computer (Android SRS 3.6 to 3.8). [run] is called only while the app is
 * in the foreground, so nothing touches the network in the background (QA-26). A session runs
 * every few seconds, and straight after each change made here (REQ-30a).
 */
class SyncController(private val state: AppState, private val context: Context) {
    var computer by mutableStateOf<Computer?>(null)
        private set
    /** REQ-34b: why the last session failed, until one succeeds. */
    var problem by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf<Progress?>(null)
        private set
    /** REQ-20d: the computer name the Reader is being asked to confirm. */
    var confirming by mutableStateOf<String?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val answers = LinkedBlockingQueue<Boolean>()
    private val nudges = Channel<Unit>(Channel.CONFLATED)
    private val oneAtATime = Mutex()
    /** Set when the Reader has already confirmed the computer, as with a scanned QR code. */
    @Volatile private var confirmed = false

    init {
        state.library.setDeviceName(deviceName())
        refresh()
    }

    fun refresh() {
        computer = state.library.computer()
    }

    /** A change was stored here, so the computer should hear of it soon (REQ-30a). */
    fun nudge() {
        nudges.trySend(Unit)
    }

    fun answer(pair: Boolean) {
        confirming = null
        answers.offer(pair)
    }

    /** Runs until the app leaves the foreground, when the calling coroutine is cancelled. */
    suspend fun run() {
        refresh()
        while (true) {
            if (computer != null) syncOnce()
            withTimeoutOrNull(syncPollSeconds().toLong() * 1000) { nudges.receive() }
        }
    }

    suspend fun syncOnce() {
        val outcome = oneAtATime.withLock {
            withContext(Dispatchers.IO) { withMulticast { runCatching { state.library.syncNow(listener) } } }
        }
        outcome.onSuccess { result ->
            problem = null
            if (result.changed) state.reloadedBySync()
        }.onFailure { problem = it.failureText() }
        refresh()
    }

    /** REQ-20: the QR code was scanned and the Reader confirmed the computer. */
    suspend fun pairByQr(text: String): Result<SyncResult> = pair(preconfirmed = true) { state.library.pairByQr(text, it) }

    /** REQ-20f: the computer's name arrives only once connected, so [confirming] asks then. */
    suspend fun pairByCode(address: String, code: String): Result<SyncResult> = pair(preconfirmed = false) { state.library.pairByCode(address, code, it) }

    private suspend fun pair(preconfirmed: Boolean, work: (SyncListener) -> SyncResult): Result<SyncResult> {
        progress = Progress(0, 0, 0)
        val outcome = oneAtATime.withLock {
            confirmed = preconfirmed
            answers.clear()
            withContext(Dispatchers.IO) { withMulticast { runCatching { work(listener) } } }
        }
        progress = null
        confirming = null
        refresh()
        outcome.onSuccess {
            problem = null
            state.reloadedBySync()
        }
        return outcome
    }

    /** REQ-21: the books stay on the phone. */
    suspend fun forget() {
        oneAtATime.withLock { withContext(Dispatchers.IO) { state.library.forgetComputer() } }
        problem = null
        refresh()
    }

    /** Holds every other sync call off while [work] runs, as moving the image folder needs. */
    suspend fun <T> paused(work: suspend () -> T): T = oneAtATime.withLock { work() }

    private val listener = object : SyncListener {
        override fun confirmComputer(name: String): Boolean {
            if (confirmed) return true
            main.post { confirming = name }
            return answers.poll(170, TimeUnit.SECONDS) ?: false
        }

        override fun progress(booksReceived: UInt, booksTotal: UInt, imagesReceived: UInt, imagesTotal: UInt) {
            main.post {
                val now = progress ?: Progress(0, 0, 0)
                progress = if (booksTotal > 0u) now.copy(books = booksReceived.toInt()) else now.copy(images = imagesReceived.toInt(), imagesTotal = imagesTotal.toInt())
            }
        }

        override fun freeBytes(): ULong = File(state.imagesDir).let { it.mkdirs(); it.usableSpace.toULong() }
    }

    /** DNS-SD needs multicast, which Android filters unless an app asks for it (REQ-22). */
    private fun <T> withMulticast(work: () -> T): T {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("booklub-sync")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            return work()
        } finally {
            lock?.release()
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    companion object {
        fun Throwable.failureText(): String = when (this) {
            is SyncFailure.Unreachable -> reason
            is SyncFailure.IdentityChanged -> reason
            is SyncFailure.NotPaired -> reason
            is SyncFailure.PairingClosed -> reason
            is SyncFailure.WrongCode -> reason
            is SyncFailure.Declined -> reason
            is SyncFailure.Incompatible -> reason
            is SyncFailure.NotAnInvitation -> reason
            is SyncFailure.NotHomeNetwork -> reason
            is SyncFailure.Other -> reason
            else -> message ?: "Something went wrong."
        }
    }
}

/** Where images can live (P13): Booklub's own storage, or its folder on a memory card. */
data class StorageOption(val label: String, val path: String, val freeBytes: Long)

fun storageOptions(context: Context): List<StorageOption> {
    val phone = context.filesDir.resolve("images")
    val options = mutableListOf(StorageOption("This phone", phone.path, context.filesDir.usableSpace))
    context.getExternalFilesDirs(null).drop(1).filterNotNull()
        .filter { android.os.Environment.getExternalStorageState(it) == android.os.Environment.MEDIA_MOUNTED }
        .firstOrNull()?.let { card -> options += StorageOption("Memory card", card.resolve("images").path, card.usableSpace) }
    return options
}
