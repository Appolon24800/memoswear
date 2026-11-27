package dev.appolon.memoswear.complication

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import dev.appolon.memoswear.LaunchIntents
import dev.appolon.memoswear.R
import dev.appolon.memoswear.ui.capture.VoiceInput

/** A watch face complication that starts a new note with one tap. */
class NewNoteComplicationSource : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? = build(
        type = request.complicationType,
        tap = LaunchIntents.newNotePendingIntent(this, VoiceInput.isAvailable(this), REQUEST_TAP),
    )

    override fun getPreviewData(type: ComplicationType): ComplicationData? = build(type, tap = null)

    private fun build(type: ComplicationType, tap: PendingIntent?): ComplicationData? {
        val label = if (VoiceInput.isAvailable(this)) "Record" else "Add"
        val description = PlainComplicationText.Builder(getString(R.string.complication_description)).build()
        val image = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_complication_note)).build()
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(label).build(),
                description,
            )
                .setMonochromaticImage(image)
                .setTapAction(tap)
                .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(image, description)
                .setTapAction(tap)
                .build()

            else -> null
        }
    }

    private companion object {
        const val REQUEST_TAP = 1
    }
}
