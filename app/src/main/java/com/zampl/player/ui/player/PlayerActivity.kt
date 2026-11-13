package com.zampl.player.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.zampl.player.data.PlayMode
import com.zampl.player.data.SortOrder
import com.zampl.player.ui.theme.ZamplTheme
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {

    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()

        vm.init(
            groupId = intent.getLongExtra(EXTRA_GROUP, -1L),
            videoId = intent.getLongExtra(EXTRA_VIDEO, -1L),
            sort = SortOrder.entries.getOrElse(intent.getIntExtra(EXTRA_SORT, 0)) { SortOrder.LATEST },
            mode = PlayMode.entries.getOrElse(intent.getIntExtra(EXTRA_MODE, 0)) { PlayMode.NORMAL },
        )

        // 잠금 중에는 폰을 돌려도 화면이 회전하지 않게 함. 해제하면 폰 설정(자동 회전)을 따름.
        lifecycleScope.launch {
            vm.locked.collect { locked ->
                requestedOrientation = if (locked) ActivityInfo.SCREEN_ORIENTATION_LOCKED
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        setContent {
            ZamplTheme {
                PlayerScreen(vm = vm, onExit = {
                    finish()
                    // 그룹 화면으로 부드럽게 돌아가기
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, android.R.anim.fade_out)
                })
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStop() {
        super.onStop()
        vm.player.pause()
    }

    companion object {
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_SORT = "sort"
        private const val EXTRA_MODE = "mode"

        fun intent(ctx: Context, groupId: Long, videoId: Long, sort: SortOrder, mode: PlayMode = PlayMode.NORMAL) =
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_GROUP, groupId)
                .putExtra(EXTRA_VIDEO, videoId)
                .putExtra(EXTRA_SORT, sort.ordinal)
                .putExtra(EXTRA_MODE, mode.ordinal)
    }
}
