package com.rusyn.wristhook

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.wear.input.RemoteInputIntentHelper
import com.rusyn.wristhook.audio.Recorder
import com.rusyn.wristhook.data.HeldTakes
import com.rusyn.wristhook.data.Outbox
import com.rusyn.wristhook.data.SettingsRepository
import com.rusyn.wristhook.net.Pairing
import com.rusyn.wristhook.net.Uploader
import com.rusyn.wristhook.ui.WristHookApp
import com.rusyn.wristhook.util.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Loading : Screen
    data object NeedsPermission : Screen
    data object Idle : Screen
    data object Recording : Screen
    data object PendingSend : Screen
    data object Sending : Screen
    data object Success : Screen
    data class Error(val message: String) : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository
    private lateinit var heldTakes: HeldTakes
    private val recorder = Recorder()

    private var screen by mutableStateOf<Screen>(Screen.Loading)
    private var showSettings by mutableStateOf(false)
    private var webhookUrl by mutableStateOf(SettingsRepository.DEFAULT_WEBHOOK_URL)
    private var authHeader by mutableStateOf("")
    private var recordingStartedAt by mutableStateOf(0L)
    private var autoStopOnSilence by mutableStateOf(SettingsRepository.DEFAULT_AUTO_STOP)
    private var maxRecordSec by mutableStateOf(SettingsRepository.DEFAULT_MAX_RECORD_SEC)
    private var accent by mutableStateOf(SettingsRepository.DEFAULT_ACCENT)
    private var lastFile: File? = null
    private var prefsLoaded = false
    private var amplitudeJob: Job? = null
    private var pendingSendJob: Job? = null
    // Newest cancelled/discarded take, offered as Undo on the idle screen.
    private var heldTake by mutableStateOf<HeldTakes.Take?>(null)
    private var heldTakeSec by mutableStateOf(0)
    private var heldTakeJob: Job? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) proceed() else screen = Screen.NeedsPermission
        }

    private val urlInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            remoteInputResult(result.data)?.let { value ->
                webhookUrl = value.trim()
                lifecycleScope.launch { settings.setWebhookUrl(value) }
            }
        }

    private val authInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            remoteInputResult(result.data)?.let { value ->
                authHeader = value.trim()
                lifecycleScope.launch { settings.setAuthHeader(value) }
            }
        }

    private val codeInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            remoteInputResult(result.data)?.let { value -> redeemPairingCode(value) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start capturing IMMEDIATELY — nothing runs before this except the
        // permission check. The webhook URL is only needed at send time
        // (seconds later), so we never block the mic on the settings disk
        // read — that read was the main tap-to-record lag.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            screen = Screen.NeedsPermission
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        settings = SettingsRepository(applicationContext)
        heldTakes = HeldTakes.forApp(applicationContext)
        // Short-lived app: keep the screen on so recording isn't interrupted by sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Pick up any stored overrides in the background (ready well before stop),
        // and build the OkHttp client off-main so the first send doesn't pay for it.
        lifecycleScope.launch {
            val loaded = settings.load()
            webhookUrl = loaded.webhookUrl
            authHeader = loaded.authHeader
            autoStopOnSilence = loaded.autoStopOnSilence
            maxRecordSec = loaded.maxRecordSec
            accent = loaded.accent
            prefsLoaded = true
        }
        lifecycleScope.launch { Uploader.prewarm() }
        // Deliver anything left over from earlier failed sends (and pull in
        // notes stranded by older builds). Off-main: WorkManager init hits disk.
        lifecycleScope.launch(Dispatchers.IO) { Outbox.schedule(applicationContext) }
        // Age out old cancelled takes; whatever is left can still be undone.
        refreshHeldTake(prune = true)

        setContent {
            WristHookApp(
                screen = screen,
                showSettings = showSettings,
                webhookUrl = webhookUrl,
                authHeader = authHeader,
                autoStopOnSilence = autoStopOnSilence,
                maxRecordSec = maxRecordSec,
                accent = accent,
                recordingStartedAt = recordingStartedAt,
                onStopAndSend = ::stopAndArm,
                onCancelRecording = ::cancelRecording,
                onSendNow = ::sendNow,
                onCancelPendingSend = ::cancelPendingSend,
                onRecordAgain = ::startRecording,
                undoTakeSec = if (heldTake != null) heldTakeSec else null,
                onUndoCancel = ::undoCancel,
                onRetry = ::retryUpload,
                onDiscard = ::discard,
                onOpenSettings = { showSettings = true },
                onClose = { finish() },
                onEditUrl = { launchRemoteInput(getString(R.string.label_webhook_url), urlInputLauncher) },
                onEditAuth = { launchRemoteInput(getString(R.string.label_auth_header), authInputLauncher) },
                onPairCode = { launchRemoteInput(getString(R.string.pair_input_label), codeInputLauncher) },
                onToggleAutoStop = ::toggleAutoStop,
                onCycleMaxRecord = ::cycleMaxRecord,
                onCycleAccent = ::cycleAccent,
                onSettingsDone = ::settingsDone,
                onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-entry from the tile/complication while already alive: start a fresh
        // note if we're not busy recording or sending.
        if (screen is Screen.Success || screen is Screen.Idle) proceed()
    }

    override fun onDestroy() {
        amplitudeJob?.cancel()
        pendingSendJob?.cancel()
        // Killed mid-recording: keep the take for undo if anything valid was captured.
        recorder.cancel()?.let { heldTakes.hold(it) }
        super.onDestroy()
    }

    /** Decides the next step once prefs are loaded: permission -> URL -> record. */
    private fun proceed() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED -> {
                screen = Screen.NeedsPermission
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            webhookUrl.isBlank() -> {
                screen = Screen.Idle
                showSettings = true
            }
            else -> startRecording()
        }
    }

    private fun startRecording() {
        if (recorder.isRecording) return
        pendingSendJob?.cancel()
        try {
            // The prepare()-time cap is the hard ceiling; the user's configured
            // max length is enforced by the monitor below (settings may still be
            // loading at this point — recording never waits for them).
            lastFile = recorder.start(this, Recorder.HARD_CAP_MS) { runOnUiThread { stopAndArm() } }
            recordingStartedAt = SystemClock.elapsedRealtime()
            Haptics.recordingStarted(this)
            screen = Screen.Recording
            startAmplitudeMonitor()
        } catch (e: Exception) {
            screen = Screen.Error(getString(R.string.error_recorder, e.message ?: ""))
        }
    }

    /**
     * Single consumer of the recorder's amplitude: enforces the configured max
     * length and, when enabled, auto-stops after a pause once speech is heard.
     */
    private fun startAmplitudeMonitor() {
        amplitudeJob?.cancel()
        amplitudeJob = lifecycleScope.launch {
            var speechStarted = false
            var silentSince = 0L
            while (screen is Screen.Recording) {
                val amp = recorder.amplitude()
                val now = SystemClock.elapsedRealtime()
                val recordedFor = now - recordingStartedAt
                if (recordedFor >= maxRecordSec * 1000L) {
                    stopAndArm()
                    break
                }
                if (autoStopOnSilence) {
                    when {
                        amp >= SPEECH_THRESHOLD -> { speechStarted = true; silentSince = 0L }
                        speechStarted && amp < SILENCE_THRESHOLD -> {
                            if (silentSince == 0L) silentSince = now
                            else if (now - silentSince >= SILENCE_HOLD_MS &&
                                recordedFor >= MIN_RECORD_MS
                            ) {
                                stopAndArm()
                                break
                            }
                        }
                        else -> silentSince = 0L
                    }
                }
                delay(AMPLITUDE_POLL_MS)
            }
        }
    }

    /** Stop recording and open the brief tap-to-cancel window before uploading. */
    private fun stopAndArm() {
        if (screen !is Screen.Recording) return
        amplitudeJob?.cancel()
        val file = recorder.stop()
        if (file == null) {
            // Nothing usable captured — almost always a misfired gesture right
            // after launch. Land on the idle menu, not the error screen: the
            // error screen's Settings chip sits where a lingering finger can
            // hit it, which read as "long-press opened settings".
            Haptics.recordingCancelled(this)
            screen = Screen.Idle
            return
        }
        lastFile = file
        screen = Screen.PendingSend
        pendingSendJob?.cancel()
        pendingSendJob = lifecycleScope.launch {
            delay(PENDING_SEND_MS)
            sendNow()
        }
    }

    /** Send immediately (auto after the window, or on a tap). */
    private fun sendNow() {
        pendingSendJob?.cancel()
        val file = lastFile
        if (file == null || !file.exists()) {
            screen = Screen.Error(getString(R.string.error_file_gone))
            return
        }
        sendFile(file)
    }

    /** Abort the pending send during the tap-to-cancel window. */
    private fun cancelPendingSend() {
        pendingSendJob?.cancel()
        lastFile?.let(::holdTake)
        lastFile = null
        Haptics.recordingCancelled(this)
        screen = Screen.Idle
    }

    private fun toggleAutoStop() {
        val next = !autoStopOnSilence
        autoStopOnSilence = next
        lifecycleScope.launch { settings.setAutoStopOnSilence(next) }
    }

    /** Tap the max-length chip to advance to the next option (wraps around). */
    private fun cycleMaxRecord() {
        val options = SettingsRepository.MAX_RECORD_OPTIONS_SEC
        val next = options[(options.indexOf(maxRecordSec) + 1).mod(options.size)]
        maxRecordSec = next
        lifecycleScope.launch { settings.setMaxRecordSec(next) }
    }

    /** Tap the accent chip to advance to the next colour preset (wraps around). */
    private fun cycleAccent() {
        val options = SettingsRepository.ACCENT_OPTIONS
        val next = options[(options.indexOf(accent) + 1).mod(options.size)]
        accent = next
        lifecycleScope.launch { settings.setAccent(next) }
    }

    private fun sendFile(file: File) {
        screen = Screen.Sending
        // Into the durable outbox before the first attempt: from here on the note
        // survives a failed send, the app closing, or the watch rebooting.
        // Claim first so the background worker never picks up the same note.
        val claimed = Outbox.claim(file)
        val queued = Outbox.adopt(applicationContext, file)
        lastFile = queued
        lifecycleScope.launch {
            val result = when {
                !queued.exists() -> Result.success(Unit) // the worker already delivered it
                !claimed -> Result.failure(java.io.IOException(getString(R.string.error_upload)))
                else -> try {
                    Uploader.upload(applicationContext, webhookUrl, authHeader, queued)
                } finally {
                    Outbox.release(queued)
                }
            }
            result
                .onSuccess {
                    queued.delete()
                    lastFile = null
                    // Flush anything older still waiting in the outbox.
                    launch(Dispatchers.IO) { Outbox.schedule(applicationContext) }
                    Haptics.sendSucceeded(this@MainActivity)
                    screen = Screen.Success
                    delay(1500)
                    finish()
                }
                .onFailure { e ->
                    // The note stays in the outbox and the worker keeps retrying it
                    // in the background; Retry here just tries again right now.
                    launch(Dispatchers.IO) { Outbox.schedule(applicationContext, replace = true) }
                    Haptics.sendFailed(this@MainActivity)
                    screen = Screen.Error(
                        getString(R.string.error_queued, e.message ?: getString(R.string.error_upload))
                    )
                }
        }
    }

    private fun retryUpload() {
        val file = lastFile
        if (file != null && !file.exists()) {
            // The background worker delivered it while the error screen was up.
            lastFile = null
            Haptics.sendSucceeded(this)
            screen = Screen.Success
            lifecycleScope.launch { delay(1500); finish() }
            return
        }
        if (file == null) {
            screen = Screen.Error(getString(R.string.error_file_gone))
            return
        }
        sendFile(file)
    }

    /**
     * Discard on the error screen: take the note out of the outbox and hold it,
     * landing on the idle menu so it can still be undone. With no note to hold
     * (e.g. a recorder error) this just closes, as before. A worker upload that
     * is already in flight can't be stopped, same as when Discard deleted it.
     */
    private fun discard() {
        val file = lastFile
        lastFile = null
        if (file == null || !file.exists()) {
            finish()
            return
        }
        // Keep the background worker off it while it moves out of the outbox.
        val claimed = Outbox.claim(file)
        try {
            holdTake(file)
        } finally {
            if (claimed) Outbox.release(file)
        }
        screen = Screen.Idle
    }

    /** Idle menu Undo: send the newest held take through the normal send path. */
    private fun undoCancel() {
        val take = heldTake ?: return
        heldTake = null
        val file = heldTakes.release(take, Outbox.recordingDir(this))
        if (file == null) {
            // Aged out while the menu was up; offer the next one, if any.
            refreshHeldTake()
            return
        }
        lastFile = file
        sendFile(file)
    }

    /** Moves a thrown-away take into the holding area and offers it as Undo. */
    private fun holdTake(file: File) {
        heldTakes.hold(file) ?: return
        refreshHeldTake()
    }

    /**
     * Re-reads the newest held take (and its length) from disk for the Undo chip.
     * The latest call wins, so a slow start-up read can't replace a fresh cancel.
     */
    private fun refreshHeldTake(prune: Boolean = false) {
        heldTakeJob?.cancel()
        heldTakeJob = lifecycleScope.launch {
            val take = withContext(Dispatchers.IO) {
                if (prune) heldTakes.prune()
                heldTakes.newest()
            }
            heldTakeSec = if (take != null) durationSec(take.file) else 0
            heldTake = take
        }
    }

    private suspend fun durationSec(file: File): Int = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            (((ms ?: 0L) + 500) / 1000).toInt()
        } catch (_: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    /** Long-press during recording: hold the take for undo and show the idle menu. */
    private fun cancelRecording() {
        amplitudeJob?.cancel()
        val take = recorder.cancel()
        // The app records on launch, so "open, hold" is how you reach the menu.
        // A take that short is just that gesture: dropping it keeps Undo on the
        // note you actually cancelled earlier instead of on an empty one.
        if (SystemClock.elapsedRealtime() - recordingStartedAt < MIN_RECORD_MS) take?.delete()
        else take?.let(::holdTake)
        lastFile = null
        Haptics.recordingCancelled(this)
        showSettings = false
        screen = Screen.Idle
    }

    /** Redeem a short pairing code (from andriy.lol/pair) into the webhook URL. */
    private fun redeemPairingCode(code: String) {
        if (code.isBlank()) return
        Toast.makeText(this, R.string.status_pairing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            Pairing.redeem(code)
                .onSuccess { config ->
                    webhookUrl = config.url
                    authHeader = config.authHeader
                    settings.setWebhookUrl(config.url)
                    settings.setAuthHeader(config.authHeader)
                    Haptics.sendSucceeded(this@MainActivity)
                    Toast.makeText(this@MainActivity, R.string.status_paired, Toast.LENGTH_SHORT).show()
                }
                .onFailure { e ->
                    Haptics.sendFailed(this@MainActivity)
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_pair, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun settingsDone() {
        showSettings = false
        if (screen is Screen.Idle || screen is Screen.Loading) proceed()
    }

    private fun launchRemoteInput(
        label: String,
        launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
    ) {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val remoteInput = RemoteInput.Builder(KEY_INPUT).setLabel(label).build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
        launcher.launch(intent)
    }

    private fun remoteInputResult(data: Intent?): String? =
        data?.let { RemoteInput.getResultsFromIntent(it)?.getCharSequence(KEY_INPUT)?.toString() }

    companion object {
        private const val KEY_INPUT = "wristhook_input"

        // Live level + silence detection. MediaRecorder amplitude is 0..32767;
        // thresholds are conservative and may want on-device tuning.
        private const val AMPLITUDE_POLL_MS = 80L
        private const val SPEECH_THRESHOLD = 2500   // above this = someone's talking
        private const val SILENCE_THRESHOLD = 1200  // below this = quiet
        // Safety net, not an active stop: only fires if you genuinely wander off
        // mid-note. Long thinking pauses must not trigger it.
        private const val SILENCE_HOLD_MS = 30_000L // quiet this long -> stop
        private const val MIN_RECORD_MS = 1500L     // never auto-stop before this
        private const val PENDING_SEND_MS = 2500L   // tap-to-cancel window
    }
}
