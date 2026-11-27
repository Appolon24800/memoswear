package dev.appolon.memoswear

import android.app.Application
import android.content.Context
import androidx.wear.tiles.TileService
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.appolon.memoswear.data.MemoRepository
import dev.appolon.memoswear.data.SessionStore
import dev.appolon.memoswear.data.SettingsStore
import dev.appolon.memoswear.data.SyncWorker
import dev.appolon.memoswear.tiles.NotesTileService
import dev.appolon.memoswear.tiles.PinnedNoteTileService
import dev.appolon.memoswear.tiles.QuickCaptureTileService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** What an external entry point (tile, complication, launcher shortcut) asks the app to open. */
sealed interface LaunchRequest {
    data class OpenMemo(val name: String) : LaunchRequest

    /** Start a new note; [voice] goes straight to speaking when the watch can. */
    data class NewNote(val voice: Boolean) : LaunchRequest
}

/** A confirmation shown once on a screen. */
data class Flash(val message: String, val memoName: String? = null)

/** The long-lived objects of the app. One instance per process, also used by tiles and workers. */
class AppContainer(private val app: Application) {
    val context: Context get() = app

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var immediateSyncJob: Job? = null

    val settings = SettingsStore(app)

    lateinit var repository: MemoRepository
        private set

    val session = SessionStore(settings, app.filesDir, appScope, onForget = { repository.clear() })

    init {
        repository = MemoRepository(
            session = session,
            settings = settings,
            cacheFile = File(app.filesDir, "memos.json"),
            outboxFile = File(app.filesDir, "outbox.json"),
            scope = appScope,
            requestSync = {
                SyncWorker.scheduleSync(app)
                // The worker covers the case of the app going away; this one is usually faster.
                requestImmediateSync()
            },
            requestTiles = { refreshTiles() },
        )
        session.restore()
    }

    /**
     * Coalesces rapid edits into one in-process sync. The durable WorkManager request above still
     * covers process death and offline retry.
     */
    private fun requestImmediateSync() {
        synchronized(this) {
            if (immediateSyncJob?.isActive == true) return
            immediateSyncJob = appScope.launch {
                try {
                    var result: MemoRepository.SyncResult
                    do {
                        result = repository.syncPending()
                    } while (result == MemoRepository.SyncResult.SUCCESS && repository.hasSendableChanges())
                } finally {
                    synchronized(this@AppContainer) {
                        immediateSyncJob = null
                    }
                }
            }
        }
    }

    /** The navigation request waiting for a screen to take it, from a tile or a shortcut. */
    val launchRequest = MutableStateFlow<LaunchRequest?>(null)

    /** True while a screen needs the whole display, such as a sign-in web page. */
    val chromeHidden = MutableStateFlow(false)

    /** A short confirmation for the person, such as "Saved". [memoName] names the detail screen
     *  that should show it; null means the list. It is set just before navigating. */
    val flash = MutableStateFlow<Flash?>(null)

    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(app)
            .components { add(OkHttpNetworkFetcherFactory(session.client)) }
            .build()
    }

    /** Asks the system to redraw the app's tiles from the freshly saved notes. */
    fun refreshTiles() {
        val updater = TileService.getUpdater(app)
        updater.requestUpdate(NotesTileService::class.java)
        updater.requestUpdate(PinnedNoteTileService::class.java)
        updater.requestUpdate(QuickCaptureTileService::class.java)
    }
}

class MemosWearApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = container.imageLoader
}
