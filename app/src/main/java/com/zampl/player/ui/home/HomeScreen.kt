package com.zampl.player.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zampl.player.ZamplApp
import com.zampl.player.data.ALL_GROUP_ID
import com.zampl.player.data.GroupSummary
import com.zampl.player.data.Library
import com.zampl.player.data.LibraryRepository
import com.zampl.player.data.CleanupReport
import com.zampl.player.data.MAX_COMBO_SIZE
import com.zampl.player.data.PlayItem
import com.zampl.player.data.PlayMode
import com.zampl.player.data.SortOrder
import com.zampl.player.data.Video
import com.zampl.player.ui.common.ConfirmDialog
import com.zampl.player.ui.common.GroupCheckList
import com.zampl.player.ui.common.NameDialog
import com.zampl.player.ui.common.PlayItemCell
import com.zampl.player.ui.common.VideoCell
import com.zampl.player.ui.common.VideoThumbImage
import com.zampl.player.ui.player.PlayerActivity
import com.zampl.player.ui.theme.StarColor
import com.zampl.player.ui.theme.SubText

/** 접은 화면: 그룹 리스트 ↔ 그룹 안 이동. 펼친 화면(폭 600dp 이상): 좌우 2단. */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val repo = (ctx.applicationContext as ZamplApp).repo
    val lib by repo.library.collectAsState()

    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickerFor by rememberSaveable { mutableStateOf<Long?>(null) }
    // 조합 만들기: 동영상을 고를 그룹 (커스텀 그룹에서는 먼저 그룹을 고름)
    var comboSourceFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var choosingComboSource by remember { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf(SortOrder.LATEST) }

    // 삭제된 그룹을 보고 있었다면 선택 해제
    LaunchedEffect(lib, selectedId) {
        val id = selectedId
        if (id != null && lib.loaded && lib.group(id) == null) selectedId = null
    }

    val openItem: (Long, PlayItem) -> Unit = { groupId, item ->
        ctx.startActivity(PlayerActivity.intent(ctx, groupId, item.key, sort))
    }
    val makeCombo: (GroupSummary) -> Unit = { g ->
        if (g.isCustom) choosingComboSource = true else comboSourceFor = g.id
    }
    // 랜덤 재생: 평점과 상관없이 하나 골라 재생. 전체 동영상이면 목록 없이 '다음 랜덤'만.
    val playRandom: (Long) -> Unit = { groupId ->
        val item = lib.randomItem(groupId)
        if (item == null) {
            Toast.makeText(ctx, "재생할 동영상이 없어요", Toast.LENGTH_SHORT).show()
        } else {
            val mode = if (groupId == ALL_GROUP_ID) PlayMode.RANDOM_ALL else PlayMode.RANDOM_GROUP
            ctx.startActivity(PlayerActivity.intent(ctx, groupId, item.key, sort, mode))
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val listWidth = (maxWidth * 0.36f).coerceIn(280.dp, 420.dp)
        val picker = pickerFor?.let { lib.group(it) }
        val comboSource = comboSourceFor?.let { lib.group(it) }
        when {
            comboSource != null -> ComboBuilderScreen(
                source = comboSource,
                lib = lib,
                sort = sort,
                onClose = { comboSourceFor = null },
                onCreate = { name, vids ->
                    repo.createCombo(name, vids)
                    comboSourceFor = null
                    Toast.makeText(ctx, "'$name' 조합을 커스텀에 추가했어요", Toast.LENGTH_SHORT).show()
                },
            )
            picker != null -> PickerScreen(
                group = picker,
                lib = lib,
                onClose = { pickerFor = null },
                onDone = { ids -> repo.setGroupMembers(picker.id, ids); pickerFor = null },
            )
            wide -> Row(Modifier.fillMaxSize()) {
                GroupListPane(
                    lib = lib,
                    repo = repo,
                    selectedId = selectedId ?: ALL_GROUP_ID,
                    onSelect = { selectedId = it },
                    onRandomAll = { playRandom(ALL_GROUP_ID) },
                    modifier = Modifier.width(listWidth).fillMaxHeight(),
                )
                VerticalDivider(color = Color(0xFF2A2E36))
                val g = lib.group(selectedId ?: ALL_GROUP_ID)
                if (g != null) {
                    GroupDetailPane(
                        group = g, lib = lib, repo = repo, sort = sort, onSort = { sort = it },
                        showBack = false, onBack = {},
                        onOpenPicker = { pickerFor = g.id },
                        onOpenItem = { openItem(g.id, it) },
                        onRandom = { playRandom(g.id) },
                        onMakeCombo = { makeCombo(g) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
            selectedId == null -> GroupListPane(
                lib = lib, repo = repo, selectedId = null,
                onSelect = { selectedId = it },
                onRandomAll = { playRandom(ALL_GROUP_ID) },
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val g = lib.group(selectedId!!)
                if (g != null) GroupDetailPane(
                    group = g, lib = lib, repo = repo, sort = sort, onSort = { sort = it },
                    showBack = true, onBack = { selectedId = null },
                    onOpenPicker = { pickerFor = g.id },
                    onOpenItem = { openItem(g.id, it) },
                    onRandom = { playRandom(g.id) },
                    onMakeCombo = { makeCombo(g) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        BackHandler(enabled = pickerFor != null) { pickerFor = null }
        BackHandler(enabled = comboSourceFor != null) { comboSourceFor = null }
        BackHandler(enabled = pickerFor == null && comboSourceFor == null && !wide && selectedId != null) { selectedId = null }
    }

    // 커스텀 그룹에서 '새 조합 만들기': 동영상을 고를 그룹 선택
    if (choosingComboSource) {
        AlertDialog(
            onDismissRequest = { choosingComboSource = false },
            title = { Text("어느 그룹에서 고를까요?") },
            text = {
                LazyColumn {
                    items(lib.groups.filter { !it.isCustom }, key = { it.id }) { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { choosingComboSource = false; comboSourceFor = g.id }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text("${g.count}개", color = SubText, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingComboSource = false }) { Text("취소") } },
        )
    }
}

// ---------------- 그룹 리스트 ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupListPane(
    lib: Library,
    repo: LibraryRepository,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onRandomAll: () -> Unit,
    modifier: Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<GroupSummary?>(null) }
    var renaming by remember { mutableStateOf<GroupSummary?>(null) }
    var deleting by remember { mutableStateOf<GroupSummary?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // 데이터 정리: null = 닫힘, Checking = 확인 중, Ready = 결과 보여주는 중
    var cleanup by remember { mutableStateOf<CleanupState?>(null) }
    val startCleanup = {
        if (!hasFullVideoAccess(ctx)) {
            Toast.makeText(ctx, "모든 동영상 접근을 허용해야 데이터 정리를 할 수 있어요", Toast.LENGTH_LONG).show()
        } else {
            cleanup = CleanupState.Checking
            scope.launch {
                val r = repo.cleanup(apply = false)
                cleanup = if (r == null) {
                    Toast.makeText(ctx, "갤러리를 읽지 못해서 정리할 수 없어요", Toast.LENGTH_SHORT).show(); null
                } else CleanupState.Ready(r)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("그룹", fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = onRandomAll) {
                        Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(18.dp))
                        Text(" 전체 랜덤")
                    }
                    Box {
                        IconButton(onClick = { listMenu = true }) { Icon(Icons.Default.MoreVert, "더보기") }
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("데이터 정리") },
                                leadingIcon = { Icon(Icons.Default.CleaningServices, null) },
                                onClick = { listMenu = false; startCleanup() },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("새 그룹") },
                containerColor = MaterialTheme.colorScheme.primary,
            )
        },
    ) { pad ->
        LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = 96.dp)) {
            items(lib.groups, key = { it.id }) { g ->
                Box {
                    GroupRow(
                        group = g,
                        selected = g.id == selectedId,
                        modifier = Modifier.combinedClickable(
                            onClick = { onSelect(g.id) },
                            onLongClick = { if (!g.isDefault) menuFor = g },
                        ),
                    )
                    DropdownMenu(expanded = menuFor?.id == g.id, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("이름 변경") }, onClick = { renaming = g; menuFor = null })
                        DropdownMenuItem(text = { Text("그룹 삭제") }, onClick = { deleting = g; menuFor = null })
                    }
                }
            }
        }
    }

    if (creating) NameDialog("새 그룹 만들기", onDismiss = { creating = false }) {
        repo.createGroup(it); creating = false
    }
    renaming?.let { g ->
        NameDialog("이름 변경", initial = g.name, confirmLabel = "변경", onDismiss = { renaming = null }) {
            repo.renameGroup(g.id, it); renaming = null
        }
    }
    deleting?.let { g ->
        ConfirmDialog(
            "그룹 삭제", "'${g.name}' 그룹을 삭제할까요?\n동영상 파일은 삭제되지 않아요.",
            onDismiss = { deleting = null },
        ) { repo.deleteGroup(g.id) }
    }
    when (val c = cleanup) {
        null -> {}
        CleanupState.Checking -> AlertDialog(
            onDismissRequest = {},
            title = { Text("데이터 정리") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("   갤러리와 비교하는 중…")
                }
            },
            confirmButton = {},
        )
        is CleanupState.Ready -> {
            val r = c.report
            AlertDialog(
                onDismissRequest = { cleanup = null },
                title = { Text("데이터 정리") },
                text = {
                    if (r.isEmpty) Text("정리할 데이터가 없어요.\n모든 데이터가 갤러리에 있는 동영상을 가리키고 있어요.")
                    else Text(
                        "갤러리에 더 이상 없는 동영상 ${r.missingVideos}개의 데이터를 지웁니다.\n\n" +
                            "• 그룹 소속 ${r.memberships}건\n" +
                            "• 평점 ${r.ratings}건\n" +
                            "• 플래그 ${r.flags}건\n" +
                            "• 커스텀 조합 ${r.combos}개 (빠진 동영상이 들어있는 조합)\n\n" +
                            "휴지통에 있는 동영상도 없는 것으로 처리돼요. 지운 데이터는 되돌릴 수 없어요."
                    )
                },
                confirmButton = {
                    if (r.isEmpty) TextButton(onClick = { cleanup = null }) { Text("확인") }
                    else TextButton(onClick = {
                        cleanup = CleanupState.Checking
                        scope.launch {
                            val done = repo.cleanup(apply = true)
                            cleanup = null
                            Toast.makeText(
                                ctx,
                                if (done == null) "갤러리를 읽지 못해서 정리하지 않았어요" else "데이터를 정리했어요",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }) { Text("정리하기") }
                },
                dismissButton = { if (!r.isEmpty) TextButton(onClick = { cleanup = null }) { Text("취소") } },
            )
        }
    }
}

private sealed interface CleanupState {
    data object Checking : CleanupState
    data class Ready(val report: CleanupReport) : CleanupState
}

/** 부분 허용(일부 동영상만 선택)이면 나머지를 '없는 동영상'으로 오해하므로 정리하지 않음 */
private fun hasFullVideoAccess(ctx: android.content.Context): Boolean {
    val perm = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE
    return androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

@Composable
private fun GroupRow(group: GroupSummary, selected: Boolean, modifier: Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x334F8CFF) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 2x2 미리보기
        Column(
            Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF242830)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (r in 0..1) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (c in 0..1) {
                    val v = group.previewVideos.getOrNull(r * 2 + c)
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1C1F25))) {
                        if (v != null) VideoThumbImage(v, Modifier.fillMaxSize())
                    }
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (group.isDefault) Pill("기본")
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (group.isCustom) "조합 ${group.count}개" else "${group.count}개", color = SubText, fontSize = 13.sp)
                if (!group.isAll) {
                    val avg = group.averageRating
                    if (avg != null) {
                        Text("  ·  ", color = SubText, fontSize = 13.sp)
                        Text("★ %.1f".format(avg), color = StarColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Pill("${group.ratedCount}개 평가")
                    } else {
                        Text("  ·  평가 없음", color = SubText, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text, fontSize = 11.sp, color = SubText,
        modifier = Modifier
            .padding(start = 6.dp)
            .background(Color(0xFF242830), RoundedCornerShape(10.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// ---------------- 그룹 안 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetailPane(
    group: GroupSummary,
    lib: Library,
    repo: LibraryRepository,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenItem: (PlayItem) -> Unit,
    onRandom: () -> Unit,
    onMakeCombo: () -> Unit,
    modifier: Modifier,
) {
    var comboMenu by remember { mutableStateOf<PlayItem.Custom?>(null) }
    var renamingCombo by remember { mutableStateOf<PlayItem.Custom?>(null) }
    var deletingCombo by remember { mutableStateOf<PlayItem.Custom?>(null) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var videoMenu by remember { mutableStateOf<Video?>(null) }
    var groupSheetFor by remember { mutableStateOf<Video?>(null) }
    var createFor by remember { mutableStateOf<Video?>(null) }
    val items = remember(lib, group.id, sort) { lib.itemsOf(group.id, sort) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(group.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        group.averageRating?.let {
                            Text("  ★ %.1f".format(it), color = StarColor, fontSize = 15.sp)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRandom) { Icon(Icons.Default.Shuffle, "랜덤 재생") }
                    // 조합 만들기: 이 그룹의 동영상을 순서대로 골라 이어 붙임 (커스텀이면 그룹부터 고름)
                    IconButton(onClick = onMakeCombo) {
                        Icon(Icons.Default.LibraryAdd, if (group.isCustom) "새 조합 만들기" else "조합 만들기")
                    }
                    if (!group.isDefault) {
                        IconButton(onClick = onOpenPicker) { Icon(Icons.Default.Add, "동영상 추가") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "더보기") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("동영상 선택/해제") }, onClick = { menu = false; onOpenPicker() })
                                DropdownMenuItem(text = { Text("이름 변경") }, onClick = { menu = false; renaming = true })
                                DropdownMenuItem(text = { Text("그룹 삭제") }, onClick = { menu = false; deleting = true })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(top = pad.calculateTopPadding()).fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SortOrder.entries) { s ->
                    FilterChip(selected = s == sort, onClick = { onSort(s) }, label = { Text(s.label) })
                }
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (!lib.loaded) "불러오는 중…"
                            else if (group.isAll) "동영상이 없어요"
                            else if (group.isUngrouped) "모든 동영상이 그룹에 들어가 있어요"
                            else if (group.isCustom) "아직 조합이 없어요"
                            else "아직 동영상이 없어요", color = SubText,
                        )
                        if (!group.isDefault) TextButton(onClick = onOpenPicker) { Text("＋ 동영상 추가하기") }
                        if (group.isCustom) TextButton(onClick = onMakeCombo) { Text("＋ 새 조합 만들기") }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 108.dp),
                    contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.key }) { pi ->
                        if (pi is PlayItem.Custom) Box {
                            // 커스텀 조합: 그룹 지정 불가, 이름 변경·지우기만
                            PlayItemCell(
                                item = pi,
                                rating = lib.ratingOf(pi),
                                onClick = { onOpenItem(pi) },
                                onLongClick = { comboMenu = pi },
                            )
                            DropdownMenu(expanded = comboMenu?.key == pi.key, onDismissRequest = { comboMenu = null }) {
                                DropdownMenuItem(text = { Text("이름 변경") }, onClick = { renamingCombo = pi; comboMenu = null })
                                DropdownMenuItem(text = { Text("조합 지우기") }, onClick = { deletingCombo = pi; comboMenu = null })
                            }
                        }
                        val v = (pi as? PlayItem.Single)?.video ?: return@items
                        Box {
                            VideoCell(
                                video = v,
                                rating = lib.ratings[v.id],
                                onClick = { onOpenItem(pi) },
                                onLongClick = { videoMenu = v },
                            )
                            DropdownMenu(expanded = videoMenu?.id == v.id, onDismissRequest = { videoMenu = null }) {
                                DropdownMenuItem(text = { Text("그룹에 추가") }, onClick = { groupSheetFor = v; videoMenu = null })
                                if (!group.isDefault) DropdownMenuItem(
                                    text = { Text("이 그룹에서 빼기") },
                                    onClick = { repo.removeFromGroup(group.id, listOf(v.id)); videoMenu = null },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    groupSheetFor?.let { v ->
        ModalBottomSheet(onDismissRequest = { groupSheetFor = null }) {
            Text("그룹에 추가", fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            GroupCheckList(
                groups = lib.userGroups(),
                checkedIds = lib.groupsOfVideo[v.id].orEmpty(),
                onToggle = { g, on -> if (on) repo.addToGroup(g.id, listOf(v)) else repo.removeFromGroup(g.id, listOf(v.id)) },
                onCreateNew = { createFor = v },
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
    createFor?.let { v ->
        NameDialog("새 그룹 만들기", onDismiss = { createFor = null }) { repo.createGroup(it, thenAdd = v); createFor = null }
    }
    if (renaming) NameDialog("이름 변경", initial = group.name, confirmLabel = "변경", onDismiss = { renaming = false }) {
        repo.renameGroup(group.id, it); renaming = false
    }
    if (deleting) ConfirmDialog(
        "그룹 삭제", "'${group.name}' 그룹을 삭제할까요?\n동영상 파일은 삭제되지 않아요.",
        onDismiss = { deleting = false },
    ) { repo.deleteGroup(group.id); onBack() }
    renamingCombo?.let { c ->
        NameDialog("조합 이름 변경", initial = c.name, confirmLabel = "변경", onDismiss = { renamingCombo = null }) {
            repo.renameCombo(c.combo.id, it); renamingCombo = null
        }
    }
    deletingCombo?.let { c ->
        ConfirmDialog(
            "조합 지우기", "'${c.name}' 조합을 지울까요?\n원본 동영상은 지워지지 않아요.",
            onDismiss = { deletingCombo = null },
        ) { repo.deleteCombo(c.combo.id) }
    }
}

// ---------------- 조합 만들기: 동영상을 순서대로 선택 (최대 10개) ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComboBuilderScreen(
    source: GroupSummary,
    lib: Library,
    sort: SortOrder,
    onClose: () -> Unit,
    onCreate: (String, List<Video>) -> Unit,
) {
    val ctx = LocalContext.current
    val order = remember(source.id) { mutableStateListOf<Long>() }
    var naming by remember { mutableStateOf(false) }
    val videos = remember(lib, source.id, sort) { lib.videosOf(source.id, sort) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기") } },
                title = {
                    Column {
                        Text("조합 만들기", maxLines = 1)
                        Text("${source.name} · 재생할 순서대로 선택 (최대 ${MAX_COMBO_SIZE}개)", fontSize = 12.sp, color = SubText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    TextButton(onClick = { naming = true }, enabled = order.size >= 2) {
                        Text("다음 (${order.size})", fontSize = 15.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.id }) { v ->
                val n = order.indexOf(v.id) + 1
                Box {
                    VideoCell(
                        video = v,
                        rating = lib.ratings[v.id],
                        highlighted = n > 0,
                        onClick = {
                            when {
                                n > 0 -> order.remove(v.id)
                                order.size >= MAX_COMBO_SIZE ->
                                    Toast.makeText(ctx, "최대 ${MAX_COMBO_SIZE}개까지 고를 수 있어요", Toast.LENGTH_SHORT).show()
                                else -> order.add(v.id)
                            }
                        },
                    )
                    // 선택 순서 번호
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (n > 0) MaterialTheme.colorScheme.primary else Color(0x55000000))
                            .border(2.dp, if (n > 0) MaterialTheme.colorScheme.primary else Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (n > 0) Text("$n", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    if (naming) {
        NameDialog(
            "조합 이름", initial = "조합 ${lib.combos.size + 1}", confirmLabel = "만들기",
            onDismiss = { naming = false },
        ) { name ->
            naming = false
            onCreate(name, order.mapNotNull { lib.video(it) })
        }
    }
}

// ---------------- 그룹 안에서 전체 동영상 중 선택 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerScreen(
    group: GroupSummary,
    lib: Library,
    onClose: () -> Unit,
    onDone: (Set<Long>) -> Unit,
) {
    var selected by remember(group.id) {
        mutableStateOf(group.videos.map { it.id }.toSet())
    }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, "닫기") } },
                title = { Text("${group.name}에 추가", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { TextButton(onClick = { onDone(selected) }) { Text("완료 (${selected.size})", fontSize = 15.sp) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = pad.calculateTopPadding() + 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(lib.videos, key = { it.id }) { v ->
                val on = v.id in selected
                VideoCell(
                    video = v,
                    rating = lib.ratings[v.id],
                    selected = on,
                    onClick = { selected = if (on) selected - v.id else selected + v.id },
                )
            }
        }
    }
}
