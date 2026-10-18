package dev.davplayer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.davplayer.app.AppViewModel
import dev.davplayer.app.HomeTab
import dev.davplayer.app.data.DemoLibrary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DavPlayerApp(model: AppViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val playback by model.engine.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(model) { model.notifications.collect { snackbars.showSnackbar(it) } }
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.engine.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(editingId != null || state.video != null || state.browser != null) {
        if (editingId != null) editingId = null else model.back()
    }

    when {
        editingId != null && !state.initializing -> ConnectionEditor(
            initial = state.servers.firstOrNull { it.id == editingId },
            onDismiss = { editingId = null },
            onSave = { model.saveConnection(it) },
        )
        state.video != null -> PlayerScreen(
            target = state.video!!, engine = model.engine, state = playback, onBack = model::back,
        )
        else -> Scaffold(
            containerColor = CanvasColor,
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                if (state.browser == null) TopAppBar(
                    title = { Text("DAV Player", fontWeight = FontWeight.SemiBold) },
                    actions = { Tag("mpv"); Spacer(Modifier.width(20.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
                )
            },
            bottomBar = {
                if (state.browser == null) NavigationBar(containerColor = CanvasColor, tonalElevation = 0.dp) {
                    listOf(
                        Triple(HomeTab.LIBRARY, Icons.Outlined.VideoLibrary, "媒体库"),
                        Triple(HomeTab.RECENT, Icons.Outlined.History, "最近"),
                        Triple(HomeTab.SETTINGS, Icons.Outlined.Tune, "设置"),
                    ).forEach { (tab, icon, label) ->
                        NavigationBarItem(
                            selected = state.tab == tab, onClick = { model.setTab(tab) },
                            icon = { Icon(icon, label) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MintColor, selectedTextColor = MintColor,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f),
                                unselectedIconColor = MutedColor, unselectedTextColor = MutedColor,
                            ),
                        )
                    }
                }
            },
        ) { insets ->
            Box(Modifier.fillMaxSize().padding(insets)) {
                state.browser?.let { browser ->
                    BrowserScreen(browser, model::back, model::refreshDirectory, model::openEntry, model::leaveBrowser)
                } ?: when (state.tab) {
                    HomeTab.LIBRARY -> LibraryScreen(
                        state = state,
                        onAdd = { editingId = "new" },
                        onEdit = { editingId = it.id },
                        onDelete = model::deleteConnection,
                        onOpen = model::openServer,
                        onDemo = { model.openServer(DemoLibrary.server) },
                        onRetry = model::reloadConnections,
                    )
                    HomeTab.RECENT -> RecentScreen()
                    HomeTab.SETTINGS -> SettingsScreen(state, model::setPreferHardware, model::setRememberPosition)
                }
            }
        }
    }
}
