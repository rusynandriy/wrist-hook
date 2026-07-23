package com.rusyn.wristhook

import android.Manifest
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.PackageManager
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
import com.rusyn.wristhook.data.SettingsRepository
import com.rusyn.wristhook.net.Pairing
import com.rusyn.wristhook.net.Uploader
import com.rusyn.wristhook.ui.WristHookApp
import com.rusyn.wristhook.util.Haptics
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private val recorder = Recorder()

    private var screen by mutableStateOf<Screen>(Screen.Loading)
    private var showSettings by mutableStateOf(false)
    private var webhookUrl by mutableStateOf(SettingsRepository.DEFAULT_WEBHOOK_URL)
    private var authHeader by mutableStateOf("")
    private var recordingStartedAt by mutableStateOf(0L)
    private var amplitude by mutableStateOf(0)
    private var autoStopOnSilence by mutableStateOf(SettingsRepository.DEFAULT_AUTO_STOP)
    private var lastFile: File? = null
    private var prefsLoaded = false
    private var amplitudeJob: Job? = null
    private var pendingSendJob: Job? = null

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
        settings = SettingsRepository(applicationContext)
        // Short-lived app: keep the screen on so recording isn't interrupted by sleep.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Start capturing IMMEDIATELY. The webhook URL is only needed at send
        // time (seconds later), so we never block the mic on the settings disk
        // read — that read was the main tap-to-record lag.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startRecording()
        } else {
            screen = Screen.NeedsPermission
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        // Pick up any stored overrides in the background (ready well before stop).
        lifecycleScope.launch {
            val loaded = settings.load()
            webhookUrl = loaded.webhookUrl
            authHeader = loaded.authHeader
            autoStopOnSilence = loaded.autoStopOnSilence
            prefsLoaded = true
        }

        setContent {
            WristHookApp(
                screen = screen,
                showSettings = showSettings,
                webhookUrl = webhookUrl,
                authHeader = authHeader,
                autoStopOnSilence = autoStopOnSilence,
                recordingStartedAt = recordingStartedAt,
                onStopAndSend = ::stopAndArm,
                onCancelRecording = ::cancelRecording,
                onSendNow = ::sendNow,
                onCancelPendingSend = ::cancelPendingSend,
                onRecordAgain = ::startRecording,
                onRetry = ::retryUpload,
                onDiscard = ::discardAndFinish,
                onOpenSettings = { showSettings = true },
                onClose = { finish() },
                onEditUrl = { launchRemoteInput(getString(R.string.label_webhook_url), urlInputLauncher) },
                onEditAuth = { launchRemoteInput(getString(R.string.label_auth_header), authInputLauncher) },
                onPairCode = { launchRemoteInput(getString(R.string.pair_input_label), codeInputLauncher) },
                onToggleAutoStop = ::toggleAutoStop,
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
        recorder.cancel()
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
            lastFile = recorder.start(this) { runOnUiThread { stopAndArm() } }
            recordingStartedAt = SystemClock.elapsedRealtime()
            amplitude = 0
            Haptics.recordingStarted(this)
            screen = Screen.Recording
            startAmplitudeMonitor()
        } catch (e: Exception) {
            screen = Screen.Error(getString(R.string.error_recorder, e.message ?: ""))
        }
    }

    /**
     * Single consumer of the recorder's amplitude: drives the on-screen level
     * meter and, when enabled, auto-stops after a pause once speech is heard.
     */
    private fun startAmplitudeMonitor() {
        amplitudeJob?.cancel()
        amplitudeJob = lifecycleScope.launch {
            var speechStarted = false
            var silentSince = 0L
            while (screen is Screen.Recording) {
                val amp = recorder.amplitude()
                amplitude = amp
                if (autoStopOnSilence) {
                    val now = SystemClock.elapsedRealtime()
                    val recordedFor = now - recordingStartedAt
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
            screen = Screen.Error(getString(R.string.error_too_short))
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
        lastFile?.delete()
        lastFile = null
        Haptics.recordingCancelled(this)
        screen = Screen.Idle
    }

    private fun toggleAutoStop() {
        val next = !autoStopOnSilence
        autoStopOnSilence = next
        lifecycleScope.launch { settings.setAutoStopOnSilence(next) }
    }

    private fun sendFile(file: File) {
        screen = Screen.Sending
        lifecycleScope.launch {
            Uploader.upload(webhookUrl, authHeader, file)
                .onSuccess {
                    file.delete()
                    lastFile = null
                    Haptics.sendSucceeded(this@MainActivity)
                    screen = Screen.Success
                    delay(1500)
                    finish()
                }
                .onFailure { e ->
                    // Keep the file on disk so the note isn't lost; offer retry.
                    Haptics.sendFailed(this@MainActivity)
                    screen = Screen.Error(e.message ?: getString(R.string.error_upload))
                }
        }
    }

    private fun retryUpload() {
        val file = lastFile
        if (file == null || !file.exists()) {
            screen = Screen.Error(getString(R.string.error_file_gone))
            return
        }
        sendFile(file)
    }

    private fun discardAndFinish() {
        lastFile?.delete()
        lastFile = null
        finish()
    }

    /** Long-press during recording: drop the take and show the idle menu. */
    private fun cancelRecording() {
        amplitudeJob?.cancel()
        recorder.cancel()
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
