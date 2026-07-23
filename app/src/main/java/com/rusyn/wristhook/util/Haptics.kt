package com.rusyn.wristhook.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object Haptics {

    private fun vibrator(context: Context): Vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator

    /** Short tap when recording starts. */
    fun recordingStarted(context: Context) {
        vibrator(context).vibrate(
            VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    /** Double buzz on successful send. */
    fun sendSucceeded(context: Context) {
        vibrator(context).vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1)
        )
    }

    /** Long buzz on failure. */
    fun sendFailed(context: Context) {
        vibrator(context).vibrate(
            VibrationEffect.createOneShot(400, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    /** Two quick taps when a take is cancelled. */
    fun recordingCancelled(context: Context) {
        vibrator(context).vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 50, 60, 50), -1)
        )
    }
}
