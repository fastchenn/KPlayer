package dev.davplayer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.davplayer.app.AppViewModel
import dev.davplayer.app.HomeTab
import dev.davplayer.app.R
import dev.davplayer.app.data.DemoLibrary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DavPlayerApp(model: AppViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val playback by model.engine.state.collectAsStateWithLifecycle()
    val browserPages = rememberSaveableStateHolder()
    var retainedPageIds by remember { mutableStateOf(emptySet<String>()) }
    val activePageIds = model.retainedBrowserPageIds
    LaunchedEffect(activePageIds) {
        (retainedPageIds - activePageIds).forEach(browserPages::removeState)
        retainedPageIds = activePageIds
    }
    val snackbars = remember { SnackbarHostState() }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(model) { model.notifications.collect { snackbars.showSnackbar(it) } }
    DisposableEffect(lifecycleOwner, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) model.savePlaybackProgress()
            if (event == Lifecycle.Event.ON_STOP) model.onBackground()
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
            onLoadEpisodeDirectory = model::loadEpisodeDirectory, onSelectEpisode = model::selectEpisode,
        )
        else -> Scaffold(
            containerColor = CanvasColor,
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                if (state.browser == null) TopAppBar(
                    title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold) },
                    actions = { Tag("mpv"); Spacer(Modifier.width(20.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
                )
            },
            bottomBar = {
                if (state.browser == null) FloatingNavigationBar(state.tab, model::setTab)
            },
        ) { insets ->
            val direction = LocalLayoutDirection.current
            val contentInsets = if (state.browser != null) insets else PaddingValues(
                start = insets.calculateStartPadding(direction),
                top = insets.calculateTopPadding(),
                end = insets.calculateEndPadding(direction),
            )
            // Home lists scroll under the glass, but their final items can scroll clear of it.
            val navigationPadding = insets.calculateBottomPadding()
            Box(Modifier.fillMaxSize().padding(contentInsets)) {
                state.browser?.let { browser ->
                    // Keep saveable values per page without reusing another directory's
                    // layout/display nodes (including lazy-item clipping layers).
                    key(browser.pageId) {
                        browserPages.SaveableStateProvider(browser.pageId) {
                            BrowserScreen(browser, model::back, model::refreshDirectory, model::openEntry, model::leaveBrowser, model::openBreadcrumb)
                        }
                    }
                } ?: when (state.tab) {
                    HomeTab.LIBRARY -> LibraryScreen(
                        state = state,
                        onAdd = { editingId = "new" },
                        onEdit = { editingId = it.id },
                        onDelete = model::deleteConnection,
                        onOpen = model::openServer,
                        onDemo = { model.openServer(DemoLibrary.server) },
                        onRetry = model::reloadConnections,
                        navigationPadding = navigationPadding,
                    )
                    HomeTab.RECENT -> RecentScreen(
                        state, model::openHistory, model::locateHistoryFolder, model::removeHistory, model::clearHistory,
                        model::reloadHistory, model::retryHistorySave, navigationPadding,
                    )
                    HomeTab.SETTINGS -> SettingsScreen(
                        state, model::setLightTheme, model::setPreferHardware, model::setRememberPosition,
                        model::setPlaybackCacheEnabled, model::setPlaybackCacheSizeGb, model::clearPlaybackCache,
                        model::setLibraryText, navigationPadding,
                    )
                }
            }
        }
    }
}
