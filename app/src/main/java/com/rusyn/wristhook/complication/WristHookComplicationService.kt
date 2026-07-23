package com.rusyn.wristhook.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.rusyn.wristhook.MainActivity
import com.rusyn.wristhook.R

/**
 * Watch-face complication that opens MainActivity (which auto-starts recording).
 * Static content — the complication never needs a data refresh.
 */
class WristHookComplicationService : ComplicationDataSourceService() {

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        listener.onComplicationData(buildData(request.complicationType, tapAction()))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        buildData(type, tapAction = null)

    private fun tapAction(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildData(type: ComplicationType, tapAction: PendingIntent?): ComplicationData? {
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_mic)
        ).build()
        val description =
            PlainComplicationText.Builder(getString(R.string.complication_description)).build()

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    PlainComplicationText.Builder(getString(R.string.complication_label)).build(),
                    description,
                )
                    .setMonochromaticImage(icon)
                    .setTapAction(tapAction)
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(icon, description)
                    .setTapAction(tapAction)
                    .build()

            else -> null
        }
    }
}
