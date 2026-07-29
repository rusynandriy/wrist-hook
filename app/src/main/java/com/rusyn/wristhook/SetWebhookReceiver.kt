package com.rusyn.wristhook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rusyn.wristhook.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Lets you set the webhook URL over adb instead of typing it on the watch keyboard:
 *   adb shell am broadcast -n com.rusyn.wristhook/.SetWebhookReceiver \
 *     -a com.rusyn.wristhook.SET_WEBHOOK --es url "https://example.com/hook"
 * Optional auth header:  --es auth "Bearer xyz"
 *
 * Exported only in debug builds (see the debug manifest overlay): in a release
 * build no other app can rewrite the webhook URL out from under the user.
 */
class SetWebhookReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        val auth = intent.getStringExtra("auth")
        val pending = goAsync()
        val repository = SettingsRepository(context.applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repository.setWebhookUrl(url)
                auth?.let { repository.setAuthHeader(it) }
            } finally {
                pending.finish()
            }
        }
    }
}
