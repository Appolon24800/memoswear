package dev.appolon.memoswear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.appolon.memoswear.ui.MainActivity

/** Intents that open the app at a specific place, used by tiles, the complication and shortcuts. */
object LaunchIntents {
    const val ACTION_NEW_NOTE: String = "dev.appolon.memoswear.action.NEW_NOTE"
    const val ACTION_OPEN_MEMO: String = "dev.appolon.memoswear.action.OPEN_MEMO"
    const val EXTRA_MEMO_NAME: String = "dev.appolon.memoswear.extra.MEMO_NAME"
    const val EXTRA_VOICE: String = "dev.appolon.memoswear.extra.VOICE"

    fun openApp(context: Context): Intent = Intent(context, MainActivity::class.java)

    fun openMemo(context: Context, name: String): Intent =
        openApp(context).setAction(ACTION_OPEN_MEMO).putExtra(EXTRA_MEMO_NAME, name)

    fun newNote(context: Context, voice: Boolean): Intent =
        openApp(context).setAction(ACTION_NEW_NOTE).putExtra(EXTRA_VOICE, voice)

    /** Opens the app on a new note. The request code tells the intents apart. */
    fun newNotePendingIntent(context: Context, voice: Boolean, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            newNote(context, voice),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
