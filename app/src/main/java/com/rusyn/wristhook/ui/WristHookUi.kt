package com.rusyn.wristhook.ui

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.rusyn.wristhook.R
import com.rusyn.wristhook.Screen
import kotlinx.coroutines.delay

@Composable
fun WristHookApp(
    screen: Screen,
    showSettings: Boolean,
    webhookUrl: String,
    authHeader: String,
    autoStopOnSilence: Boolean,
    recordingStartedAt: Long,
    onStopAndSend: () -> Unit,
    onCancelRecording: () -> Unit,
    onSendNow: () -> Unit,
    onCancelPendingSend: () -> Unit,
    onRecordAgain: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    onEditUrl: () -> Unit,
    onEditAuth: () -> Unit,
    onPairCode: () -> Unit,
    onToggleAutoStop: () -> Unit,
    onSettingsDone: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    MaterialTheme {
        Scaffold(timeText = { TimeText() }) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
            ) {
                when {
                    showSettings -> SettingsScreen(
                        webhookUrl, authHeader, autoStopOnSilence,
                        onEditUrl, onEditAuth, onPairCode, onToggleAutoStop, onSettingsDone
                    )
                    screen is Screen.Loading -> CenteredMessage(
                        stringResource(R.string.status_loading)
                    )
                    screen is Screen.Idle -> IdleMenu(onRecordAgain, onOpenSettings, onClose)
                    screen is Screen.NeedsPermission -> PermissionScreen(onRequestPermission)
                    screen is Screen.Recording -> RecordingScreen(
                        recordingStartedAt, onStopAndSend, onCancelRecording
                    )
                    screen is Screen.PendingSend -> PendingSendScreen(onSendNow, onCancelPendingSend)
                    screen is Screen.Sending -> SendingScreen()
                    screen is Screen.Success -> SuccessScreen()
                    screen is Screen.Error -> ErrorScreen(
                        screen.message, onRetry, onDiscard, onOpenSettings
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordingScreen(
    startedAt: Long,
    onStopAndSend: () -> Unit,
    onCancel: () -> Unit,
) {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val elapsedSec = ((now - startedAt).coerceAtLeast(0) / 1000).toInt()

    // One breathing dot: a single animated element via graphicsLayer (alpha is
    // applied in the draw phase, so it never triggers recomposition or relayout).
    // Deliberately cheap — the old 28-bar amplitude waveform rebuilt itself ~12x/s
    // and stole the main thread right as recording started, making launch feel laggy.
    val pulse = rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "recAlpha",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(onClick = onStopAndSend, onLongClick = onCancel),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(Color.Red, CircleShape),
        )
        Text(
            text = "%d:%02d".format(elapsedSec / 60, elapsedSec % 60),
            style = MaterialTheme.typography.title2,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            text = stringResource(R.string.hint_tap_to_send),
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingSendScreen(onSendNow: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(onClick = onSendNow, onLongClick = onCancel),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(36.dp))
        Text(
            text = stringResource(R.string.status_pending_send),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            text = stringResource(R.string.hint_pending_send),
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun SendingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(40.dp))
        Text(
            text = stringResource(R.string.status_sending),
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun SuccessScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFF2E7D32), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = stringResource(R.string.status_sent),
                tint = Color.White,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = stringResource(R.string.status_sent),
            style = MaterialTheme.typography.title3,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun ErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.status_failed),
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.error,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Text(
                text = message,
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        item { FullWidthChip(stringResource(R.string.action_retry), onRetry) }
        item { FullWidthChip(stringResource(R.string.action_settings), onOpenSettings, secondary = true) }
        item { FullWidthChip(stringResource(R.string.action_discard), onDiscard, secondary = true) }
    }
}

@Composable
private fun IdleMenu(
    onRecordAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.status_cancelled),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
        }
        item { FullWidthChip(stringResource(R.string.action_record_again), onRecordAgain) }
        item { FullWidthChip(stringResource(R.string.action_settings), onOpenSettings, secondary = true) }
        item { FullWidthChip(stringResource(R.string.action_close), onClose, secondary = true) }
    }
}

@Composable
private fun PermissionScreen(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.permission_rationale),
            style = MaterialTheme.typography.body2,
            textAlign = TextAlign.Center,
        )
        Chip(
            onClick = onRequestPermission,
            label = { Text(stringResource(R.string.action_allow_mic)) },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )
    }
}

@Composable
private fun SettingsScreen(
    webhookUrl: String,
    authHeader: String,
    autoStopOnSilence: Boolean,
    onEditUrl: () -> Unit,
    onEditAuth: () -> Unit,
    onPairCode: () -> Unit,
    onToggleAutoStop: () -> Unit,
    onDone: () -> Unit,
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 24.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
        }
        item {
            Chip(
                onClick = onPairCode,
                label = { Text(stringResource(R.string.label_pair_code)) },
                secondaryLabel = {
                    Text(
                        text = stringResource(R.string.value_pair_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = onEditUrl,
                label = { Text(stringResource(R.string.label_webhook_url)) },
                secondaryLabel = {
                    Text(
                        text = webhookUrl.ifBlank { stringResource(R.string.value_not_set) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = onEditAuth,
                label = { Text(stringResource(R.string.label_auth_header)) },
                secondaryLabel = {
                    Text(
                        text = authHeader.ifBlank { stringResource(R.string.value_optional) },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = onToggleAutoStop,
                label = { Text(stringResource(R.string.label_auto_stop)) },
                secondaryLabel = {
                    Text(
                        text = stringResource(
                            if (autoStopOnSilence) R.string.value_on else R.string.value_off
                        ),
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = onDone,
                enabled = webhookUrl.isNotBlank(),
                label = { Text(stringResource(R.string.action_done)) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (webhookUrl.isBlank()) {
            item {
                Text(
                    text = stringResource(R.string.settings_hint_url_required),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun FullWidthChip(label: String, onClick: () -> Unit, secondary: Boolean = false) {
    Chip(
        onClick = onClick,
        label = { Text(label) },
        colors = if (secondary) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
    }
}
