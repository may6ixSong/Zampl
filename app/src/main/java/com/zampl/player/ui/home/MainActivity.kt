package com.zampl.player.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zampl.player.ZamplApp
import com.zampl.player.ui.theme.ZamplTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZamplTheme {
                PermissionGate { partial, requestMore ->
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        if (partial) PartialAccessBanner(requestMore)
                        HomeScreen(
                            Modifier
                                .weight(1f)
                                .then(if (partial) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
                        )
                    }
                }
            }
        }
    }
}

private enum class Access { NONE, PARTIAL, FULL }

private val requestedPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun checkAccess(ctx: Context): Access {
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return when {
        Build.VERSION.SDK_INT >= 33 && has(Manifest.permission.READ_MEDIA_VIDEO) -> Access.FULL
        Build.VERSION.SDK_INT < 33 && has(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.FULL
        Build.VERSION.SDK_INT >= 34 && has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.PARTIAL
        else -> Access.NONE
    }
}

@Composable
private fun PermissionGate(content: @Composable (partial: Boolean, requestMore: () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as ZamplApp).repo
    var access by remember { mutableStateOf(checkAccess(ctx)) }
    var askedCount by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = checkAccess(ctx)
        askedCount++
        repo.refresh()
    }
    LaunchedEffect(Unit) {
        if (access == Access.NONE) launcher.launch(requestedPermissions)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        access = checkAccess(ctx)
        repo.refresh()
    }
    if (access == Access.NONE) {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("갤러리의 동영상을 보려면\n동영상 접근 권한이 필요해요", textAlign = TextAlign.Center, fontSize = 17.sp)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { launcher.launch(requestedPermissions) }) { Text("권한 허용") }
            if (askedCount > 0) {
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                    )
                }) { Text("설정에서 직접 허용하기") }
            }
        }
    } else {
        content(access == Access.PARTIAL) { launcher.launch(requestedPermissions) }
    }
}

@Composable
private fun PartialAccessBanner(onClick: () -> Unit) {
    Text(
        "일부 동영상만 접근이 허용되어 있어요 · 눌러서 전체 허용",
        fontSize = 13.sp,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3A3220))
            .statusBarsPadding()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
