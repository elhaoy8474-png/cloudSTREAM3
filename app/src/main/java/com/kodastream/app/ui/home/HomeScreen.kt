package com.kodastream.app.ui.home

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row as ComposeRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kodastream.app.R
import com.kodastream.app.data.AppPrefs
import com.kodastream.app.data.DetailsArgs
import com.kodastream.app.data.HistoryStore
import com.kodastream.app.data.HistoryStore.HistoryEntry
import com.kodastream.app.data.PlayerArgs
import com.kodastream.app.engine.Engine
import com.kodastream.app.engine.readableMessage
import com.kodastream.app.ui.components.EmptyBox
import com.kodastream.app.ui.components.ErrorBox
import com.kodastream.app.ui.components.ExpressivePullToRefresh
import com.kodastream.app.ui.components.LoadingBox
import com.kodastream.app.ui.components.PosterActionsSheet
import com.kodastream.app.ui.components.PosterCard
import com.kodastream.app.ui.components.PosterShelfSkeleton
import com.kodastream.app.ui.components.SectionHeader
import com.kodastream.app.ui.components.SkeletonBox
import com.kodastream.app.ui.components.SkeletonPulseProvider
import com.kodastream.app.ui.components.staggeredEnter
import com.kodastream.app.ui.home.hero.HeroStyle
import com.kodastream.app.ui.home.hero.HomeHeroReservedSpace
import com.kodastream.app.ui.home.hero.HomeHeroSection
import com.kodastream.app.ui.home.hero.LocalScreenActive
import com.kodastream.app.ui.home.hero.rememberHeroStretchState
import com.kodastream.app.ui.home.hero.rememberHomeHeroState
import com.kodastream.app.ui.home.hero.toHeroItem
import com.kodastream.app.ui.sources.SourceAvatar
import com.kodastream.app.ui.sources.SourceChip
import com.kodastream.app.ui.sources.SourceViewModel
import com.kodastream.app.ui.theme.ArtworkStatusBarIcons
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.plugins.PluginManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class HomeRow(
    val key: String,
    val title: String,
    val items: List<SearchResponse>,
    val horizontal: Boolean,
    /** Index in `api.mainPage`, so an expanded page can request the same section. */
    val pageIndex: Int,
    /** The last page loaded into [items]. */
    val page: Int,
    val hasNext: Boolean,
    /** All eligible source requests that can advance this unified category. */
    val pagingSources: List<HomePageCursor> = emptyList(),
    /** Non-null only when this row is a taxonomy-backed unified category. */
    val catalogCategoryId: String? = null,
)

private data class HomeCatalogBatch(
    val source: HomePageSource,
    val result: Result<Engine.MainPageResult>,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val _providers = MutableStateFlow(Engine.homeProviders())
    val providers: StateFlow<List<MainAPI>> = _providers.asStateFlow()

    /** The provider currently loaded; set only by [setSource], never chosen here. */
    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _rows = MutableStateFlow<List<HomeRow>>(emptyList())
    val rows: StateFlow<List<HomeRow>> = _rows.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _sourceOptions = MutableStateFlow<Map<String, List<SearchResponse>>>(emptyMap())
    fun sourcesFor(item: SearchResponse): List<SearchResponse> =
        _sourceOptions.value[item.url].orEmpty().ifEmpty { listOf(item) }

    /**
     * The hero comes from the first shelf that has enough posters, preferring
     * a portrait row. Derived here so composition never walks the shelves,
     * and cached eagerly because the Home screen reads it right away.
     */
    val hero: StateFlow<List<SearchResponse>> = _rows
        .map { rows ->
            rows.sortedBy { it.horizontal }
                .firstOrNull { row -> row.items.count { !it.posterUrl.isNullOrBlank() } >= 3 }
                ?.items
                .orEmpty()
                .filter { !it.posterUrl.isNullOrBlank() }
                .distinctBy { it.url }
                .take(8)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var loadJob: Job? = null
    private var unifiedSession: Job? = null
    private var loadGeneration: Long = 0L

    private fun cancelActiveLoad() {
        loadGeneration += 1L
        unifiedSession?.cancel()
        unifiedSession = null
        loadJob?.cancel()
        loadJob = null
        _loading.value = false
    }

    init {
        viewModelScope.launch {
            // Runs again every time an extension is installed, updated or removed.
            PluginManager.revision.collect {
                _providers.value = Engine.homeProviders()
                if (_selected.value == null && PluginManager.startupDone.value) loadAllSources()
            }
        }
    }

    /**
     * Loads the name the source owner resolved. An equal name is a no-op, so a
     * revision that does not change the selection never cancels a running load.
     */
    fun setSource(name: String?) {
        if (name == _selected.value) return
        cancelActiveLoad()
        _selected.value = name
        if (name == null) {
            _rows.value = emptyList()
            _loading.value = false
            _error.value = null
            _sourceOptions.value = emptyMap()
            return
        }
        val api = Engine.provider(name)
        if (api == null || !api.hasMainPage) {
            _rows.value = emptyList()
            _sourceOptions.value = emptyMap()
            _error.value = ""
            _loading.value = false
            return
        }
        load(api)
    }

    fun setAllSources() {
        if (_selected.value == null && (_loading.value || _rows.value.isNotEmpty())) return
        _selected.value = null
        loadAllSources()
    }

    fun retry() {
        val selectedName = _selected.value
        if (selectedName == null) {
            loadAllSources()
        } else {
            Engine.provider(selectedName)?.let(::load)
        }
    }


    private fun loadAllSources() {
        cancelActiveLoad()
        val generation = loadGeneration
        val apis = _providers.value.filter { it.hasMainPage }
        if (apis.isEmpty()) {
            _rows.value = emptyList()
            _sourceOptions.value = emptyMap()
            _loading.value = false
            _error.value = null
            return
        }

        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _rows.value = emptyList()
            _sourceOptions.value = emptyMap()

            val sources = createHomePageSources(apis)
            if (sources.isEmpty()) {
                _loading.value = false
                return@launch
            }

            val accumulator = UnifiedHomeCatalogAccumulator()
            var lastFailure: String? = null
            var timedOut = false
            val batches = Channel<HomeCatalogBatch>(Channel.UNLIMITED)
            val supervisor = SupervisorJob(viewModelScope.coroutineContext[Job])
            val workerScope = CoroutineScope(
                viewModelScope.coroutineContext + supervisor + Dispatchers.IO,
            )
            unifiedSession = supervisor
            val remaining = AtomicInteger(sources.size)

            // All requests are scheduled immediately, but HomePageSource applies
            // a global eight-request limit and each provider's own concurrency gate.
            // Workers are not joined: a non-cooperative extension must never hold
            // the Home UI open after the session deadline.
            sources.forEach { source ->
                workerScope.launch {
                    try {
                        val result = withTimeoutOrNull(18_000L) {
                            source.loadPage(1)
                        } ?: Result.failure(IllegalStateException("Main page timed out"))
                        batches.trySend(HomeCatalogBatch(source, result))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        batches.trySend(HomeCatalogBatch(source, Result.failure(failure)))
                    } finally {
                        if (remaining.decrementAndGet() == 0) batches.close()
                    }
                }
            }

            val timeoutWatch = launch {
                delay(90_000L)
                if (generation == loadGeneration) {
                    timedOut = true
                    supervisor.cancel()
                    batches.close()
                }
            }

            try {
                for (batch in batches) {
                    if (generation != loadGeneration) break
                    val response = batch.result.getOrNull()
                    if (response == null) {
                        lastFailure = batch.source.api.name + ": " +
                            (batch.result.exceptionOrNull()?.readableMessage() ?: "Unknown error")
                        continue
                    }

                    val snapshot = withContext(Dispatchers.Default) {
                        accumulator.addPage(batch.source, response)
                        accumulator.snapshot()
                    }
                    if (generation == loadGeneration) {
                        _rows.value = snapshot.rows
                        _sourceOptions.value = snapshot.sourcesByUrl
                    }
                }
            } finally {
                timeoutWatch.cancel()
                supervisor.cancel()
                batches.close()
            }

            if (generation == loadGeneration) {
                unifiedSession = null
                _loading.value = false
                if (_rows.value.isEmpty()) {
                    _error.value = lastFailure ?: if (timedOut) {
                        getApplication<Application>().getString(R.string.home_all_sources_timeout)
                    } else {
                        ""
                    }
                }
            }
        }
    }

    private fun load(api: MainAPI) {
        cancelActiveLoad()
        val generation = loadGeneration
        loadJob = viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _rows.value = emptyList()
            _sourceOptions.value = emptyMap()

            val pages = createHomePageSources(listOf(api))
            val results = arrayOfNulls<List<HomeRow>>(pages.size)
            var lastFailure: String? = null

            coroutineScope {
                pages.forEachIndexed { index, source ->
                    launch {
                        val result = withTimeoutOrNull(18_000L) {
                            source.loadPage(1)
                        } ?: Result.failure(IllegalStateException("Main page timed out"))
                        result.onSuccess { response ->
                            if (generation == loadGeneration) {
                                results[index] = response.lists
                                .filter { it.list.isNotEmpty() }
                                .mapIndexed { rowIndex, list ->
                                    val cursors = if (response.hasNext) {
                                        listOf(HomePageCursor(source, nextPage = 2, hasNext = true))
                                    } else {
                                        emptyList()
                                    }
                                    HomeRow(
                                        key = "$index-$rowIndex-${list.name}",
                                        title = list.name.ifBlank { source.pageData.name },
                                        items = list.list,
                                        horizontal = list.isHorizontalImages,
                                        pageIndex = index,
                                        page = 1,
                                        hasNext = response.hasNext,
                                        pagingSources = cursors,
                                    )
                                }
                                _rows.value = results.filterNotNull().flatten()
                                _sourceOptions.value = _rows.value.flatMap { it.items }
                                    .associate { it.url to listOf(it) }
                            }
                        }
                        result.onFailure {
                            if (generation == loadGeneration) lastFailure = it.readableMessage()
                        }
                    }
                }
            }

            if (generation == loadGeneration) {
                _loading.value = false
                if (_rows.value.isEmpty()) {
                    _error.value = lastFailure ?: ""
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOpenItem: (SearchResponse) -> Unit,
    onOpenDetails: (DetailsArgs) -> Unit = {},
    onResume: (PlayerArgs) -> Unit = {},
    onOpenExtensions: () -> Unit,
    onOpenSources: () -> Unit,
    sourceViewModel: SourceViewModel,
    expandedViewModel: ExpandedHomeViewModel,
    onOpenSection: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    var history by remember { mutableStateOf(HistoryStore.current(context)) }
    LaunchedEffect(Unit) {
        HistoryStore.entries.collect { updated ->
            if (updated != null) history = updated
        }
    }
    val continueWatchingItems = remember(history) { continueWatching(history) }

    val providers by viewModel.providers.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val rows by viewModel.rows.collectAsState()
    val hero by viewModel.hero.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val sections by expandedViewModel.sections.collectAsState()
    val startupDone by PluginManager.startupDone.collectAsState()
    val sourceName by sourceViewModel.home.collectAsState()
    val allSources by sourceViewModel.homeAll.collectAsState()
    var actionsItem by remember { mutableStateOf<SearchResponse?>(null) }
    var sourceChoices by remember { mutableStateOf<List<SearchResponse>?>(null) }

    val openCatalogItem: (SearchResponse) -> Unit = { item ->
        val candidates = viewModel.sourcesFor(item)
        if (candidates.size > 1) sourceChoices = candidates else onOpenItem(item)
    }

    // New installs see one catalogue assembled from every installed home provider.
    LaunchedEffect(allSources, sourceName) {
        if (allSources) viewModel.setAllSources() else viewModel.setSource(sourceName)
    }
    // A different provider invalidates every paged section and its page indexes.
    LaunchedEffect(selected) { expandedViewModel.setProvider(selected) }

    Box(Modifier.fillMaxSize()) {
        when {
            providers.isEmpty() && !startupDone -> LoadingBox(Modifier.padding(contentPadding))

            providers.isEmpty() -> EmptyBox(
                title = stringResource(R.string.home_empty_title),
                hint = stringResource(R.string.home_empty_hint),
                modifier = Modifier.padding(contentPadding),
            ) {
                Button(onClick = onOpenExtensions) { Text(stringResource(R.string.open_extensions)) }
            }

            // The owner is still resolving the saved source; do not show a
            // different provider's shelves in the meantime.
            selected == null && !allSources -> LoadingBox(Modifier.padding(contentPadding))

            else -> ExpressivePullToRefresh(
                isRefreshing = loading,
                onRefresh = {
                    // Fresh source lists make every paged copy stale.
                    expandedViewModel.reset()
                    viewModel.retry()
                },
            ) {
                HomeShelves(
                    selectedSource = selected,
                    sourceLabel = if (allSources) stringResource(R.string.search_scope_all) else selected,
                    rows = rows,
                    hero = hero,
                    continueWatchingItems = continueWatchingItems,
                    loading = loading,
                    error = error,
                    sections = sections,
                    contentPadding = contentPadding,
                    onOpenSources = onOpenSources,
                    onOpenItem = openCatalogItem,
                    onOpenDetails = onOpenDetails,
                    onResume = onResume,
                    onRetry = viewModel::retry,
                    onPosterLongClick = { actionsItem = it },
                    onViewMore = { row ->
                        expandedViewModel.open(selected.orEmpty(), row)
                        onOpenSection()
                    },
                )
            }
        }
        actionsItem?.let { item ->
            PosterActionsSheet(item = item, onDismiss = { actionsItem = null })
        }
        sourceChoices?.let { candidates ->
            HomeSourceChooser(
                candidates = candidates,
                onSelect = { selectedItem ->
                    sourceChoices = null
                    onOpenItem(selectedItem)
                },
                onDismiss = { sourceChoices = null },
            )
        }
    }
}

/**
 * The shelves themselves, split out of the pull-to-refresh wrapper so the
 * refresh container is not recomposed on every provider switch.
 */
@Composable
private fun HomeShelves(
    selectedSource: String?,
    sourceLabel: String?,
    rows: List<HomeRow>,
    hero: List<SearchResponse>,
    continueWatchingItems: List<HistoryEntry>,
    loading: Boolean,
    error: String?,
    sections: Map<String, ExpandedSection>,
    contentPadding: PaddingValues,
    onOpenSources: () -> Unit,
    onOpenItem: (SearchResponse) -> Unit,
    onOpenDetails: (DetailsArgs) -> Unit,
    onResume: (PlayerArgs) -> Unit,
    onRetry: () -> Unit,
    onPosterLongClick: (SearchResponse) -> Unit,
    onViewMore: (HomeRow) -> Unit,
) {
    val context = LocalContext.current
    val screenActive = LocalScreenActive.current
    // Re-read on return, so a Settings change applies without an app restart.
    val heroStyle = remember(screenActive) { HeroStyle.parse(AppPrefs.homeHeroStyle(context)) }
    val isCinematic = heroStyle == HeroStyle.CINEMATIC
    val showCinematicHero = isCinematic && (hero.isNotEmpty() || loading)
    val heroItems = remember(hero) { hero.map { it.toHeroItem() } }
    val cinematicHeroItems = if (isCinematic) {
        rememberHomeHeroState(heroItems, selectedSource)
    } else {
        heroItems
    }
    val listState = rememberLazyListState()
    val topInset = with(LocalDensity.current) { contentPadding.calculateTopPadding().roundToPx() }
    val heroUnderBar by remember(listState, topInset) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == "hero" }
                ?.let { it.offset < topInset } == true
        }
    }
    ArtworkStatusBarIcons(overArtwork = heroUnderBar && !(isCinematic && hero.isEmpty()))

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stretchState = if (isCinematic) rememberHeroStretchState(listState) else null
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (stretchState != null) {
                        Modifier.nestedScroll(stretchState.nestedScrollConnection)
                    } else {
                        Modifier
                    },
                ),
            contentPadding = PaddingValues(
                top = if (showCinematicHero) 0.dp else contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (showCinematicHero) {
                item(key = "hero") {
                    Crossfade(
                        targetState = hero.isEmpty(),
                        animationSpec = tween(320),
                        label = "HomeHeroLoading",
                    ) { empty ->
                        if (empty) {
                            HomeHeroReservedSpace(viewportHeight = maxHeight)
                        } else {
                            HomeHeroSection(
                                items = cinematicHeroItems,
                                viewportHeight = maxHeight,
                                mobileBelowSectionHeightHint = null,
                                listState = listState,
                                stretchPx = { stretchState?.stretchPx ?: 0f },
                                onItemClick = { item -> item.source?.let(onOpenItem) },
                            )
                        }
                    }
                }
            }

            // The provider belongs to this page, not the app shell.
            if (sourceLabel != null) {
                item(key = "source") {
                    SourceChip(
                        label = sourceLabel,
                        onClick = onOpenSources,
                        leading = { SourceAvatar(name = selectedSource.orEmpty()) },
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    )
                }
            }

            if (!showCinematicHero && hero.isNotEmpty() && heroStyle != HeroStyle.OFF) {
                item(key = "hero") {
                    HomeHero(items = hero, onOpen = onOpenItem)
                }
            }

            if (continueWatchingItems.isNotEmpty()) {
                item(key = "continue_watching") {
                    ContinueWatchingShelf(
                        items = continueWatchingItems,
                        onOpenItem = onOpenDetails,
                        onResume = onResume,
                    )
                }
            }

            if (rows.isEmpty() && loading) {
                // The carousel needs its own placeholder; the cinematic hero
                // already reserved the exact same space above the source chip.
                item(key = "skeleton") {
                    SkeletonPulseProvider {
                        Column {
                            if (heroStyle == HeroStyle.CAROUSEL) {
                                SkeletonBox(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .fillMaxWidth()
                                        .height(480.dp),
                                    shape = MaterialTheme.shapes.extraLarge,
                                )
                                Spacer(Modifier.height(24.dp))
                            }
                            SkeletonBox(
                                modifier = Modifier
                                    .padding(start = 16.dp)
                                    .size(width = 160.dp, height = 24.dp),
                                shape = MaterialTheme.shapes.small,
                            )
                            Spacer(Modifier.height(12.dp))
                            PosterShelfSkeleton()
                        }
                    }
                }
            } else if (rows.isEmpty()) {
                item(key = "error") {
                    Box(Modifier.fillMaxWidth().height(360.dp)) {
                        ErrorBox(
                            message = error?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.nothing_found),
                            onRetry = onRetry,
                        )
                    }
                }
            }

            itemsIndexed(
                items = rows,
                key = { _, row -> row.key },
                contentType = { _, _ -> "shelf" },
            ) { index, row ->
                val displayTitle = when (row.catalogCategoryId) {
                    HomeCategoryIds.MOVIES -> stringResource(R.string.home_catalog_movies)
                    HomeCategoryIds.INDIAN_MOVIES -> stringResource(R.string.home_catalog_indian_movies)
                    HomeCategoryIds.ASIAN_MOVIES -> stringResource(R.string.home_catalog_asian_movies)
                    HomeCategoryIds.FOREIGN_MOVIES -> stringResource(R.string.home_catalog_foreign_movies)
                    HomeCategoryIds.ARABIC_MOVIES -> stringResource(R.string.home_catalog_arabic_movies)
                    HomeCategoryIds.TURKISH_MOVIES -> stringResource(R.string.home_catalog_turkish_movies)
                    HomeCategoryIds.SERIES -> stringResource(R.string.home_catalog_series)
                    HomeCategoryIds.ARABIC_SERIES -> stringResource(R.string.home_catalog_arabic_series)
                    HomeCategoryIds.TURKISH_SERIES -> stringResource(R.string.home_catalog_turkish_series)
                    HomeCategoryIds.INDIAN_SERIES -> stringResource(R.string.home_catalog_indian_series)
                    HomeCategoryIds.ASIAN_SERIES -> stringResource(R.string.home_catalog_asian_series)
                    HomeCategoryIds.FOREIGN_SERIES -> stringResource(R.string.home_catalog_foreign_series)
                    HomeCategoryIds.ANIME -> stringResource(R.string.home_catalog_anime)
                    HomeCategoryIds.ANIMATION -> stringResource(R.string.home_catalog_animation)
                    HomeCategoryIds.DUBBED -> stringResource(R.string.home_catalog_dubbed)
                    HomeCategoryIds.SUBTITLED -> stringResource(R.string.home_catalog_subtitled)
                    else -> row.title
                }
                val displayRow = remember(row, displayTitle) { row.copy(title = displayTitle) }
                // Each category has its own paging state, even when no provider is selected.
                val canViewMore = displayRow.hasNext &&
                    sections[sectionKey(selectedSource.orEmpty(), displayRow.key)]?.hasNext != false
                val viewMore: (() -> Unit)? = if (canViewMore) ({ onViewMore(displayRow) }) else null
                // The first three shelves rise in once; later ones just appear, and
                // a provider switch reuses the already-settled rows.
                if (index < 3) {
                    val entrance = remember { MutableTransitionState(false).apply { targetState = true } }
                    AnimatedVisibility(
                        visibleState = entrance,
                        enter = staggeredEnter(index),
                    ) {
                        Shelf(
                            row = displayRow,
                            onOpenItem = onOpenItem,
                            onPosterLongClick = onPosterLongClick,
                            onViewMore = viewMore,
                        )
                    }
                } else {
                    Shelf(
                        row = displayRow,
                        onOpenItem = onOpenItem,
                        onPosterLongClick = onPosterLongClick,
                        onViewMore = viewMore,
                    )
                }
            }

            if (rows.isNotEmpty() && loading) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HomeSourceChooser(
    candidates: List<SearchResponse>,
    onSelect: (SearchResponse) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.home_choose_source_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                text = stringResource(R.string.home_choose_source_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(candidates, key = { "${it.apiName}|${it.url}" }) { candidate ->
                    Surface(
                        onClick = { onSelect(candidate) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        ComposeRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SourceAvatar(name = candidate.apiName)
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(candidate.apiName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    candidate.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Shelf(
    row: HomeRow,
    onOpenItem: (SearchResponse) -> Unit,
    onPosterLongClick: (SearchResponse) -> Unit,
    onViewMore: (() -> Unit)?,
) {
    val longClickLabel = stringResource(R.string.cd_poster_actions)
    Column {
        SectionHeader(row.title, onViewMore = onViewMore)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // No keys here: providers sometimes return the same item twice.
            items(
                items = row.items,
                contentType = { if (row.horizontal) "horizontal" else "poster" },
            ) { item ->
                PosterCard(
                    item = item,
                    horizontal = row.horizontal,
                    onClick = { onOpenItem(item) },
                    onLongClick = { onPosterLongClick(item) },
                    onLongClickLabel = longClickLabel,
                    cardWidth = if (row.horizontal) 220.dp else 128.dp,
                )
            }
        }
    }
}
