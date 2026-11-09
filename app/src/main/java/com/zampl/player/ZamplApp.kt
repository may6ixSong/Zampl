package com.zampl.player

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.zampl.player.data.LibraryRepository
import com.zampl.player.data.MediaStoreSource
import com.zampl.player.data.ZamplDatabase
import com.zampl.player.ui.common.VideoThumbFetcher
import com.zampl.player.ui.common.VideoThumbKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class ZamplApp : Application(), ImageLoaderFactory {

    val appScope = CoroutineScope(SupervisorJob())
    lateinit var repo: LibraryRepository
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { repo.refresh() }

    override fun onCreate() {
        super.onCreate()
        repo = LibraryRepository(this, ZamplDatabase.create(this).dao(), appScope)
        // 갤러리에 동영상이 추가/삭제되면 자동으로 다시 읽음
        contentResolver.registerContentObserver(
            MediaStoreSource.observeUri, true,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    handler.removeCallbacks(refreshRunnable)
                    handler.postDelayed(refreshRunnable, 800)
                }
            },
        )
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoThumbKeyer())
                add(VideoThumbFetcher.Factory())
            }
            .crossfade(true)
            .build()
}
