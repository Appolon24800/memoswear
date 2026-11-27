package dev.appolon.memoswear.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.LaunchIntents
import dev.appolon.memoswear.LaunchRequest
import dev.appolon.memoswear.MemosWearApplication

/** The app's only activity. Tiles, the complication and shortcuts reach it through [LaunchIntents]. */
class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as MemosWearApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MemosWearRoot(container) }
        take(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        take(intent)
    }

    /** Leaves a launch request for whichever screen is on top to take. */
    private fun take(intent: Intent?) {
        when (intent?.action) {
            LaunchIntents.ACTION_NEW_NOTE -> container.launchRequest.value =
                LaunchRequest.NewNote(voice = intent.getBooleanExtra(LaunchIntents.EXTRA_VOICE, false))

            LaunchIntents.ACTION_OPEN_MEMO -> intent.getStringExtra(LaunchIntents.EXTRA_MEMO_NAME)?.let { name ->
                container.launchRequest.value = LaunchRequest.OpenMemo(name)
            }
        }
        // Clearing the action stops a recreated activity from handling the request twice.
        intent?.action = null
    }
}
