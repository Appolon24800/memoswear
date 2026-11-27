package dev.appolon.memoswear.tiles

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import dev.appolon.memoswear.AppContainer
import dev.appolon.memoswear.LaunchIntents
import dev.appolon.memoswear.MemosWearApplication
import dev.appolon.memoswear.data.SessionStore
import dev.appolon.memoswear.data.SyncWorker
import dev.appolon.memoswear.ui.MainActivity
import dev.appolon.memoswear.ui.capture.VoiceInput
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Common behavior of the app's tiles: they read the copy saved on the watch, ask for a reload of
 * that copy, and offer a way in when the user is signed out. What they show is in TileLayouts.kt.
 */
abstract class MemosTileService : Material3TileService() {
    protected val container: AppContainer get() = (applicationContext as MemosWearApplication).container

    final override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        // A tile can be drawn while the process is still checking a saved session.
        withTimeoutOrNull(RESTORE_WAIT) { container.session.state.first { it !is SessionStore.State.Restoring } }
        if (!container.session.isSignedIn) return respond(signOutTile { launch() })
        SyncWorker.scheduleRefresh(applicationContext)
        return render()
    }

    protected abstract suspend fun MaterialScope.render(): Tile

    /** A click that opens the app on a note, or on a new one. */
    protected fun launch(openMemo: String? = null, voice: Boolean? = null): Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(applicationContext.packageName)
            .setClassName(MainActivity::class.java.name)
        openMemo?.let { name ->
            activity.addKeyToExtraMapping(LaunchIntents.EXTRA_MEMO_NAME, ActionBuilders.AndroidStringExtra.Builder().setValue(name).build())
        }
        voice?.let { spoken ->
            activity.addKeyToExtraMapping(LaunchIntents.EXTRA_VOICE, ActionBuilders.AndroidBooleanExtra.Builder().setValue(spoken).build())
        }
        return click(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
    }

    private fun click(action: ActionBuilders.Action): Clickable = clickable(action = action, id = "launch")

    protected fun respond(content: LayoutElement): Tile =
        tile(timeline = timeline(timelineEntry(content)), freshness = FRESHNESS)

    private companion object {
        val FRESHNESS: Duration = 30.minutes
        val RESTORE_WAIT: Duration = 5.seconds
    }
}

/** The newest notes. Tapping one opens it. */
class NotesTileService : MemosTileService() {
    override suspend fun MaterialScope.render(): Tile {
        val entries = container.repository.recentEntries(3)
        return respond(notesTile(entries) { name -> launch(openMemo = name) })
    }
}

/** One note: the first pinned one, or else the newest. Tapping it opens it. */
class PinnedNoteTileService : MemosTileService() {
    override suspend fun MaterialScope.render(): Tile {
        val entry = container.repository.pinnedEntry()
        return respond(pinnedNoteTile(entry) { name -> launch(openMemo = name) })
    }
}

/** A single button that starts a new note. */
class QuickCaptureTileService : MemosTileService() {
    override suspend fun MaterialScope.render(): Tile {
        val voice = VoiceInput.isAvailable(applicationContext)
        return respond(captureTile(voice) { launch(voice = voice) })
    }
}
