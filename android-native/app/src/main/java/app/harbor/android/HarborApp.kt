package app.harbor.android

import android.app.Application
import app.harbor.android.data.Repository
import app.harbor.android.data.Tmdb
import app.harbor.android.ui.HarborDisplay
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class HarborApp : Application(), coil.ImageLoaderFactory {
    // Posters and backdrops stay on disk between launches: art at a given address never changes,
    // so the cache is used as is instead of asking each server whether it still may.
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient(app.harbor.android.data.Net.client)
        .memoryCache { coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizeBytes(768L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .components { add(coil.decode.SvgDecoder.Factory()) }
        .build()

    lateinit var repository: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this)
        app.harbor.android.ui.HarborFonts.assets = assets
        app.harbor.android.data.Curated.init(this)
        // The curated lists and last launch's Home rows are parsed now, off the main thread,
        // so the first Home frame doesn't wait on them.
        Thread({
            app.harbor.android.ui.HomeCache.preload(this)
            app.harbor.android.data.Curated.lists()
        }, "harbor-warmup").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        // Look-and-feel settings reach every screen, the player's too, through HarborDisplay.
        val main = MainScope()
        main.launch {
            repository.settings.collect {
                HarborDisplay.settings = it
                Tmdb.languageOverride = it.metadataLanguage
                Tmdb.imageLanguage = it.imageLanguage
                Tmdb.titleLanguage = it.titleLanguage
                Tmdb.region = it.region
                Tmdb.favourRegion = it.favourRegion
                Tmdb.translateTitles = it.translateTitles
                Tmdb.translateOverviews = it.translateOverviews
            }
        }
        main.launch { repository.rpdbKey.collect { HarborDisplay.rpdbKey = it } }
        main.launch {
            // In Nuvio mode My List is the Nuvio profile's library.
            kotlinx.coroutines.flow.combine(repository.myList, repository.accounts.nuvioLibrary, repository.accounts.nuvio, repository.accounts.shared) { list, nuvio, _, _ ->
                (if (repository.accounts.nuvioActive) nuvio else list).map { it.id }.toSet()
            }.collect { HarborDisplay.myListIds = it }
        }
        main.launch {
            // Watched: the Stremio library's, films marked watched on their page, and PublicMetaDB's history.
            kotlinx.coroutines.flow.combine(repository.library, repository.shelves, repository.accounts.pmdbWatched) { items, shelves, pmdb ->
                items.filter { !it.removed && ((it.state?.timesWatched ?: 0) > 0 || (it.state?.flaggedWatched ?: 0) > 0) }.map { it.id }.toSet() + shelves.watched + pmdb
            }.collect { HarborDisplay.watchedIds = it }
        }
        main.launch { repository.providerKeys.collect { app.harbor.android.data.SkipSegments.theIntroDbKey = it[app.harbor.android.data.ProviderKey.THEINTRODB] } }
        main.launch { repository.providerKeys.collect { HarborDisplay.customPosters = it[app.harbor.android.data.ProviderKey.POSTERS] } }
    }
}
