package com.kodastream.app.ui.home

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kodastream.app.engine.Engine
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

fun sectionKey(provider: String, rowKey: String): String = "$provider|$rowKey"

@Immutable
data class ExpandedSection(
    val key: String,
    val providerName: String,
    val title: String,
    val pageIndex: Int,
    val page: Int,
    val hasNext: Boolean,
    val horizontal: Boolean,
    val items: List<SearchResponse>,
    val loading: Boolean = false,
    /** A source page failed; its cursor remains available for an explicit retry. */
    val failed: Boolean = false,
    val sourceCursors: List<HomePageCursor> = emptyList(),
    val catalogCategoryId: String? = null,
    val inFlight: Int = 0,
    val requestId: Long = 0L,
)

/**
 * Pagination progress is measured against this source's returned URLs, not
 * the merged card count. A different source may add a new card in the same
 * batch; that must not keep this source alive when it repeats old pages.
 */
internal fun hasNewSourceItems(
    current: List<SearchResponse>,
    incoming: List<SearchResponse>,
): Boolean {
    if (incoming.isEmpty()) return false
    val currentUrls = current.asSequence().map { it.url }.toHashSet()
    return incoming.any { it.url !in currentUrls }
}

private fun mergePagedItems(
    current: List<SearchResponse>,
    incoming: List<SearchResponse>,
): List<SearchResponse> {
    if (incoming.isEmpty()) return current
    val currentIndex = current.mapIndexed { index, item -> item.url to index }.toMap()
    val currentUrls = currentIndex.keys
    val groups = deduplicateCatalog(current + incoming)
    return groups
        .sortedBy { group ->
            group.sources.mapNotNull { currentIndex[it.url] }.minOrNull() ?: Int.MAX_VALUE
        }
        .map { group ->
            // Keep already-visible cards and their order stable as new sources arrive.
            group.sources.firstOrNull { it.url in currentUrls } ?: group.primary
        }
}

private fun pageItemsForSection(
    section: ExpandedSection,
    cursor: HomePageCursor,
    response: Engine.MainPageResult,
): List<SearchResponse> {
    val categoryId = section.catalogCategoryId
    if (categoryId != null) {
        return response.lists.flatMap { pageList ->
            pageList.list.filter { item ->
                categoryId in homeCategoryIdsFor(
                    item = item,
                    providerLanguage = cursor.source.api.lang,
                    sourceMetadata = "${cursor.source.metadata} ${pageList.name}",
                )
            }
        }
    }

    val matchingList = response.lists.firstOrNull {
        it.name == section.title || (it.name.isBlank() && cursor.source.pageData.name == section.title)
    } ?: response.lists.singleOrNull()
    return matchingList?.list.orEmpty()
}

/**
 * Paged copies of Home categories. Each category owns its own cursor state,
 * while HomePageSource shares the actual network page cache across categories.
 */
class ExpandedHomeViewModel : ViewModel() {
    private val _sections = MutableStateFlow<Map<String, ExpandedSection>>(emptyMap())
    val sections: StateFlow<Map<String, ExpandedSection>> = _sections.asStateFlow()

    private val _selected = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private var provider: String? = null
    private var requestCounter = 0L
    private val activeJobs = mutableMapOf<String, List<Job>>()
    private val activeWatchdogs = mutableMapOf<String, Job>()

    private fun cancelSectionJobs(key: String) {
        activeJobs.remove(key)?.forEach { it.cancel() }
        activeWatchdogs.remove(key)?.cancel()
        update(key) { current ->
            if (!current.loading) current else current.copy(
                loading = false,
                inFlight = 0,
                requestId = ++requestCounter,
                hasNext = current.sourceCursors.any { it.hasNext && !it.failed },
            )
        }
    }

    /** A Home refresh replaces the source lists, so every page and request is stale. */
    fun reset() {
        activeJobs.values.flatten().forEach { it.cancel() }
        activeJobs.clear()
        activeWatchdogs.values.forEach { it.cancel() }
        activeWatchdogs.clear()
        _sections.value = emptyMap()
        _selected.value = null
    }

    fun setProvider(name: String?) {
        if (name == provider) return
        provider = name
        reset()
    }

    fun open(providerName: String, row: HomeRow) {
        val key = sectionKey(providerName, row.key)
        val previous = _selected.value
        if (previous != null && previous != key) {
            cancelSectionJobs(previous)
        }
        _sections.update { current ->
            if (current.containsKey(key)) {
                current
            } else {
                val cursors = if (row.pagingSources.isNotEmpty()) {
                    row.pagingSources
                } else {
                    // Compatibility for a row created before source cursors were attached.
                    val api = Engine.provider(providerName)
                    val source = api?.let { createHomePageSources(listOf(it)).getOrNull(row.pageIndex) }
                    if (source != null && row.hasNext) {
                        listOf(HomePageCursor(source, nextPage = row.page + 1, hasNext = true))
                    } else {
                        emptyList()
                    }
                }
                current + (key to ExpandedSection(
                    key = key,
                    providerName = providerName,
                    title = row.title,
                    pageIndex = row.pageIndex,
                    page = row.page,
                    hasNext = cursors.any { it.hasNext && !it.failed },
                    horizontal = row.horizontal,
                    items = row.items.distinctBy { it.url },
                    sourceCursors = cursors,
                    catalogCategoryId = row.catalogCategoryId,
                ))
            }
        }
        _selected.value = key
    }

    /** Fetches one next page from each eligible source independently. */
    fun loadNext(retry: Boolean = false) {
        val key = _selected.value ?: return
        val current = _sections.value[key] ?: return
        if (current.loading) return

        val targets = current.sourceCursors.filter { cursor ->
            if (retry) cursor.failed else cursor.hasNext && !cursor.failed
        }
        if (targets.isEmpty()) return

        val requestId = ++requestCounter
        update(key) { latest ->
            if (latest.loading) latest else latest.copy(
                loading = true,
                failed = false,
                inFlight = targets.size,
                requestId = requestId,
                sourceCursors = latest.sourceCursors.map { cursor ->
                    if (targets.any { it.source.id == cursor.source.id }) cursor.copy(failed = false)
                    else cursor
                },
                hasNext = latest.sourceCursors.any { cursor ->
                    cursor.hasNext && (!cursor.failed || targets.any { it.source.id == cursor.source.id })
                },
            )
        }

        activeWatchdogs.remove(key)?.cancel()
        val watchdogBudgetMs = (((targets.size + 11) / 12) * 12_000L + 5_000L)
            .coerceAtMost(95_000L)
        val watchdog = viewModelScope.launch {
            kotlinx.coroutines.delay(watchdogBudgetMs)
            val latest = _sections.value[key]
            if (latest != null && latest.requestId == requestId && latest.loading) {
                activeJobs[key]?.forEach { it.cancel() }
                update(key) { state ->
                    if (state.requestId != requestId) {
                        state
                    } else {
                        val timedOutCursors = state.sourceCursors.map { existing ->
                            val requested = targets.firstOrNull { it.source.id == existing.source.id }
                            if (
                                requested != null &&
                                existing.nextPage == requested.nextPage &&
                                !existing.failed
                            ) existing.copy(failed = true) else existing
                        }
                        state.copy(
                            sourceCursors = timedOutCursors,
                            hasNext = timedOutCursors.any { it.hasNext && !it.failed },
                            failed = timedOutCursors.any { it.failed },
                            loading = false,
                            inFlight = 0,
                            requestId = ++requestCounter,
                        )
                    }
                }
            }
            activeWatchdogs.remove(key)
        }
        activeWatchdogs[key] = watchdog

        val jobs = targets.map { cursor ->
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                val result = try {
                    cursor.source.loadPage(cursor.nextPage)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    Result.failure(failure)
                }

                val response = result.getOrNull()
                update(key) { latest ->
                    if (latest.requestId != requestId) {
                        latest
                    } else {
                        val incoming = response?.let { pageItemsForSection(latest, cursor, it) }.orEmpty()
                        val sourcePageHadNewItems = hasNewSourceItems(latest.items, incoming)
                        val mergedItems = mergePagedItems(latest.items, incoming)
                        val updatedCursors = latest.sourceCursors.map { existing ->
                            if (existing.source.id != cursor.source.id) {
                                existing
                            } else if (response == null) {
                                existing.copy(failed = true)
                            } else {
                                val emptyPages = if (sourcePageHadNewItems) 0 else existing.emptyPageCount + 1
                                existing.copy(
                                    nextPage = cursor.nextPage + 1,
                                    hasNext = response.hasNext && if (latest.catalogCategoryId != null) {
                                        emptyPages < 3
                                    } else {
                                        sourcePageHadNewItems
                                    },
                                    failed = false,
                                    emptyPageCount = emptyPages,
                                )
                            }
                        }
                        val remaining = (latest.inFlight - 1).coerceAtLeast(0)
                        latest.copy(
                            items = if (response == null) latest.items else mergedItems,
                            page = if (response == null) latest.page else maxOf(latest.page, cursor.nextPage),
                            sourceCursors = updatedCursors,
                            hasNext = updatedCursors.any { it.hasNext && !it.failed },
                            failed = updatedCursors.any { it.failed },
                            inFlight = remaining,
                            loading = remaining > 0,
                        )
                    }
                }
                val latest = _sections.value[key]
                if (latest?.requestId == requestId && !latest.loading) {
                    activeWatchdogs.remove(key)?.cancel()
                }
            }
        }
        activeJobs[key] = jobs
        jobs.forEach { it.start() }
    }

    private fun update(key: String, block: (ExpandedSection) -> ExpandedSection) {
        _sections.update { current ->
            current[key]?.let { current + (key to block(it)) } ?: current
        }
    }
}
