package dev.appolon.memoswear.ui.capture

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent

/** Spoken entry through the watch's own speech recognizer. */
object VoiceInput {

    fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    /** True when this watch has something that can take free-form dictation. */
    fun isAvailable(context: Context): Boolean = runCatching {
        context.packageManager.resolveActivity(intent(), PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)

    /** The recognized text of a result, or null when the user backed out or nothing was heard. */
    fun textFrom(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull { it.isNotBlank() }
}
