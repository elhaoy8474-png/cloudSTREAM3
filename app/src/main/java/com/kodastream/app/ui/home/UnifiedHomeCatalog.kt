package com.kodastream.app.ui.home

import com.kodastream.app.engine.Engine
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.LinkedHashMap
import java.util.Locale

internal object HomeCategoryIds {
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val ANIME = "anime"
    const val INDIAN_MOVIES = "indian_movies"
    const val INDIAN_SERIES = "indian_series"
    const val ASIAN_MOVIES = "asian_movies"
    const val ASIAN_SERIES = "asian_series"
    const val TURKISH_MOVIES = "turkish_movies"
    const val TURKISH_SERIES = "turkish_series"
    const val ARABIC_MOVIES = "arabic_movies"
    const val ARABIC_SERIES = "arabic_series"
    const val FOREIGN_MOVIES = "foreign_movies"
    const val FOREIGN_SERIES = "foreign_series"
    const val ANIMATION = "animation"
    const val DUBBED = "dubbed"
    const val SUBTITLED = "subtitled"
}

internal data class HomeCatalogCategory(val id: String, val title: String, val order: Int)

internal val homeCatalogCategories = listOf(
    HomeCatalogCategory(HomeCategoryIds.MOVIES, "Movies", 0),
    HomeCatalogCategory(HomeCategoryIds.INDIAN_MOVIES, "Indian Movies", 1),
    HomeCatalogCategory(HomeCategoryIds.ASIAN_MOVIES, "Asian Movies", 2),
    HomeCatalogCategory(HomeCategoryIds.FOREIGN_MOVIES, "Foreign Movies", 3),
    HomeCatalogCategory(HomeCategoryIds.ARABIC_MOVIES, "Arabic Movies", 4),
    HomeCatalogCategory(HomeCategoryIds.TURKISH_MOVIES, "Turkish Movies", 5),
    HomeCatalogCategory(HomeCategoryIds.SERIES, "Series", 6),
    HomeCatalogCategory(HomeCategoryIds.ARABIC_SERIES, "Arabic Series", 7),
    HomeCatalogCategory(HomeCategoryIds.TURKISH_SERIES, "Turkish Series", 8),
    HomeCatalogCategory(HomeCategoryIds.INDIAN_SERIES, "Indian Series", 9),
    HomeCatalogCategory(HomeCategoryIds.ASIAN_SERIES, "Asian Series", 10),
    HomeCatalogCategory(HomeCategoryIds.FOREIGN_SERIES, "Foreign Series", 11),
    HomeCatalogCategory(HomeCategoryIds.ANIME, "Anime", 12),
    HomeCatalogCategory(HomeCategoryIds.ANIMATION, "Animation", 13),
    HomeCatalogCategory(HomeCategoryIds.DUBBED, "Dubbed", 14),
    HomeCatalogCategory(HomeCategoryIds.SUBTITLED, "Subtitled", 15),
)

/**
 * One MainPageData request shared by every category it contributes to.
 * Successful pages are cached so two category screens never refetch the
 * same provider/request/page combination during one Home session.
 */
class HomePageSource(
    val id: String,
    val api: MainAPI,
    val pageData: MainPageData,
    val providerOrder: Int,
    val requestIndex: Int,
    private val globalGate: Semaphore,
    private val providerGate: Semaphore,
) {
    private val pageMutex = Mutex()
    private val pageCache = LinkedHashMap<Int, Engine.MainPageResult>(8, 0.75f, true)

    val metadata: String = listOf(
        api.name, api.lang, api.sourcePlugin.orEmpty(), api.mainUrl, pageData.name, pageData.data,
    ).joinToString(" ")

    suspend fun loadPage(pageNumber: Int): Result<Engine.MainPageResult> {
        require(pageNumber >= 1) { "Home page numbers start at 1" }
        pageMutex.lock()
        try {
            pageCache[pageNumber]?.let { return Result.success(it) }
            val result = try {
                providerGate.withPermit {
                    // Never occupy a global network slot while waiting for this
                    // provider's own concurrency limit.
                    globalGate.withPermit {
                        val waitMs = if (pageNumber == 1) api.sequentialMainPageDelay
                        else api.sequentialMainPageScrollDelay
                        withTimeoutOrNull(12_000L) {
                            if (waitMs > 0L) delay(waitMs)
                            Engine.mainPage(api, pageData, pageNumber)
                        } ?: Result.failure(IllegalStateException("Main page timed out"))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            result.getOrNull()?.let {
                pageCache[pageNumber] = it
                while (pageCache.size > 8) {
                    pageCache.remove(pageCache.keys.first())
                }
            }
            return result
        } finally {
            pageMutex.unlock()
        }
    }
}

data class HomePageCursor(
    val source: HomePageSource,
    val nextPage: Int,
    val hasNext: Boolean,
    val failed: Boolean = false,
    val emptyPageCount: Int = 0,
)

/** One cursor per source request and category; the fetched page itself is cached per source. */
internal fun createHomePageSources(apis: List<MainAPI>): List<HomePageSource> {
    val globalGate = Semaphore(12)
    val providerGates = HashMap<String, Semaphore>()
    return apis.flatMapIndexed { providerOrder, api ->
        val providerKey = listOf(
            api.sourcePlugin?.takeIf { it.isNotBlank() } ?: api.javaClass.name,
            api.javaClass.name,
            api.mainUrl.trimEnd('/').lowercase(Locale.ROOT),
            api.name,
        ).joinToString("|")
        val providerGate = providerGates.getOrPut(providerKey) {
            Semaphore(if (api.sequentialMainPage) 1 else 2)
        }
        api.mainPage.mapIndexed { requestIndex, pageData ->
            val requestKey = listOf(providerKey, requestIndex.toString(), pageData.name, pageData.data)
                .joinToString("|")
            HomePageSource(
                id = requestKey,
                api = api,
                pageData = pageData,
                providerOrder = providerOrder,
                requestIndex = requestIndex,
                globalGate = globalGate,
                providerGate = providerGate,
            )
        }
    }
}

private enum class HomeRegion { ARABIC, INDIAN, ASIAN, TURKISH, FOREIGN }

private fun languageRegion(language: String): HomeRegion? {
    val code = language.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
    return when (code) {
        "ar" -> HomeRegion.ARABIC
        "hi", "bn", "ta", "te", "ml", "kn", "mr", "gu", "pa", "or", "as" -> HomeRegion.INDIAN
        "tr" -> HomeRegion.TURKISH
        "zh", "ja", "ko", "th", "id", "ms", "vi", "tl", "km", "lo", "my" -> HomeRegion.ASIAN
        "en", "fr", "es", "de", "it", "pt", "ru", "nl", "pl", "sv", "da", "no", "fi" ->
            HomeRegion.FOREIGN
        else -> null
    }
}

/**
 * Region is a hint, not a claim about a title's production country.
 * Explicit provider/section labels win; an unknown language stays unclassified.
 */
private fun homeRegion(language: String, metadata: String): HomeRegion? {
    val text = metadata.lowercase(Locale.ROOT)
    return when {
        Regex("""\b(arabic|egyptian|levant|gulf)\b|عربي|عربية|مصري|مصرية|شامي|خليجي""")
            .containsMatchIn(text) -> HomeRegion.ARABIC
        Regex("""\b(turkish|turkey|türkiye)\b|تركي|تركية|تركيا""")
            .containsMatchIn(text) -> HomeRegion.TURKISH
        Regex("""\b(indian|india|hindi|bollywood|tollywood|kollywood)\b|هندي|هندية|الهند""")
            .containsMatchIn(text) -> HomeRegion.INDIAN
        Regex("""\b(asian|korean|japanese|chinese|thai|k-drama|c-drama|j-drama)\b|آسيوي|آسيوية|كوري|كورية|ياباني|يابانية|صيني|صينية""")
            .containsMatchIn(text) -> HomeRegion.ASIAN
        Regex("""\b(foreign|international|western)\b|أجنبي|أجنبية""")
            .containsMatchIn(text) -> HomeRegion.FOREIGN
        else -> languageRegion(language)
    }
}

private fun supportedMedia(api: MainAPI): Triple<Boolean, Boolean, Boolean> {
    val types = api.supportedTypes
    val movies = types.any { it == TvType.Movie || it == TvType.AnimeMovie }
    val series = types.any {
        it == TvType.TvSeries || it == TvType.AsianDrama || it == TvType.Cartoon
    }
    val anime = types.any { it == TvType.Anime || it == TvType.OVA || it == TvType.AnimeMovie }
    return Triple(movies, series, anime)
}

internal fun homePotentialCategoryIds(
    source: HomePageSource,
    additionalMetadata: String = "",
): Set<String> {
    val (movies, series, anime) = supportedMedia(source.api)
    val region = homeRegion(source.api.lang, "${source.metadata} $additionalMetadata")
    val ids = linkedSetOf<String>()
    if (movies) {
        ids += HomeCategoryIds.MOVIES
        when (region) {
            HomeRegion.INDIAN -> ids += HomeCategoryIds.INDIAN_MOVIES
            HomeRegion.ASIAN -> ids += HomeCategoryIds.ASIAN_MOVIES
            HomeRegion.TURKISH -> ids += HomeCategoryIds.TURKISH_MOVIES
            HomeRegion.ARABIC -> ids += HomeCategoryIds.ARABIC_MOVIES
            HomeRegion.FOREIGN -> ids += HomeCategoryIds.FOREIGN_MOVIES
            null -> Unit
        }
    }
    if (series) {
        ids += HomeCategoryIds.SERIES
        when (region) {
            HomeRegion.INDIAN -> ids += HomeCategoryIds.INDIAN_SERIES
            HomeRegion.ASIAN -> ids += HomeCategoryIds.ASIAN_SERIES
            HomeRegion.TURKISH -> ids += HomeCategoryIds.TURKISH_SERIES
            HomeRegion.ARABIC -> ids += HomeCategoryIds.ARABIC_SERIES
            HomeRegion.FOREIGN -> ids += HomeCategoryIds.FOREIGN_SERIES
            null -> Unit
        }
        if (TvType.Cartoon in source.api.supportedTypes) ids += HomeCategoryIds.ANIMATION
    }
    if (anime) ids += HomeCategoryIds.ANIME
    if (movies || series || anime) {
        ids += HomeCategoryIds.DUBBED
        ids += HomeCategoryIds.SUBTITLED
    }
    return ids
}

internal fun homeCategoryIdsFor(
    item: SearchResponse,
    providerLanguage: String,
    sourceMetadata: String,
): Set<String> {
    val type = item.type ?: when (item) {
        is MovieSearchResponse -> TvType.Movie
        is TvSeriesSearchResponse -> TvType.TvSeries
        is AnimeSearchResponse -> TvType.Anime
        else -> null
    }
    val metadata = "$sourceMetadata ${item.name}"
    val region = if (type == TvType.AsianDrama) HomeRegion.ASIAN
    else homeRegion(providerLanguage, metadata)
    val ids = linkedSetOf<String>()

    val isMovie = type == TvType.Movie || type == TvType.AnimeMovie
    val isSeries = type == TvType.TvSeries || type == TvType.AsianDrama || type == TvType.Cartoon
    val isAnime = type == TvType.Anime || type == TvType.OVA || type == TvType.AnimeMovie

    if (isMovie) {
        ids += HomeCategoryIds.MOVIES
        when (region) {
            HomeRegion.INDIAN -> ids += HomeCategoryIds.INDIAN_MOVIES
            HomeRegion.ASIAN -> ids += HomeCategoryIds.ASIAN_MOVIES
            HomeRegion.TURKISH -> ids += HomeCategoryIds.TURKISH_MOVIES
            HomeRegion.ARABIC -> ids += HomeCategoryIds.ARABIC_MOVIES
            HomeRegion.FOREIGN -> ids += HomeCategoryIds.FOREIGN_MOVIES
            null -> Unit
        }
    }
    if (isSeries) {
        ids += HomeCategoryIds.SERIES
        when (region) {
            HomeRegion.INDIAN -> ids += HomeCategoryIds.INDIAN_SERIES
            HomeRegion.ASIAN -> ids += HomeCategoryIds.ASIAN_SERIES
            HomeRegion.TURKISH -> ids += HomeCategoryIds.TURKISH_SERIES
            HomeRegion.ARABIC -> ids += HomeCategoryIds.ARABIC_SERIES
            HomeRegion.FOREIGN -> ids += HomeCategoryIds.FOREIGN_SERIES
            null -> Unit
        }
        if (type == TvType.Cartoon) ids += HomeCategoryIds.ANIMATION
    }
    if (isAnime) ids += HomeCategoryIds.ANIME
    if (type == TvType.AsianDrama) ids += HomeCategoryIds.ASIAN_SERIES

    val animeStatus = (item as? AnimeSearchResponse)?.dubStatus.orEmpty()
    val metadataText = metadata.lowercase(Locale.ROOT)
    val dubbedByLabel = Regex("""\b(dub|dubbed|dubbing)\b|مدبلج|مدبلجة|دبلجة""")
        .containsMatchIn(metadataText)
    val subbedByLabel = Regex("""\b(sub|subbed|subtitled|subtitle|subtitles)\b|مترجم|مترجمة|ترجمة""")
        .containsMatchIn(metadataText)
    if (DubStatus.Dubbed in animeStatus || dubbedByLabel) ids += HomeCategoryIds.DUBBED
    if (DubStatus.Subbed in animeStatus || subbedByLabel) ids += HomeCategoryIds.SUBTITLED

    return ids
}

private data class CatalogOrder(
    val provider: Int,
    val request: Int,
    val list: Int,
    val item: Int,
) : Comparable<CatalogOrder> {
    override fun compareTo(other: CatalogOrder): Int {
        if (provider != other.provider) return provider.compareTo(other.provider)
        if (request != other.request) return request.compareTo(other.request)
        if (list != other.list) return list.compareTo(other.list)
        return item.compareTo(other.item)
    }
}

private data class RankedSearchResponse(val item: SearchResponse, val order: CatalogOrder)

/** Incremental, single-writer reducer for every unified Home category. */
internal class UnifiedHomeCatalogAccumulator {
    private class Group(first: RankedSearchResponse) {
        val members = mutableListOf(first)
        val primary: RankedSearchResponse
            get() = members.minBy { it.order }
    }

    private class CategoryBucket {
        val groups = mutableListOf<Group>()
        val ngramIndex = HashMap<String, MutableList<Int>>()

        fun add(entry: RankedSearchResponse) {
            val title = normalizeCatalogTitle(entry.item.name)
            if (title.isBlank() || entry.item.url.isBlank()) return
            val candidates = linkedSetOf<Int>()
            titleNgrams(title).forEach { ngram -> ngramIndex[ngram]?.let(candidates::addAll) }
            val matchIndex = candidates.sortedBy { groups[it].primary.order }
                .firstOrNull { index -> sameWork(groups[index].primary.item, entry.item) }

            if (matchIndex == null) {
                val index = groups.size
                groups += Group(entry)
                titleNgrams(title).forEach { ngram ->
                    ngramIndex.getOrPut(ngram) { mutableListOf() }.add(index)
                }
            } else {
                val group = groups[matchIndex]
                if (group.members.none { it.item.apiName == entry.item.apiName && it.item.url == entry.item.url }) {
                    group.members += entry
                    titleNgrams(title).forEach { ngram ->
                        ngramIndex.getOrPut(ngram) { mutableListOf() }.add(matchIndex)
                    }
                }
            }
        }

        fun orderedGroups(): List<Group> = groups.sortedBy { it.primary.order }
    }

    private data class SourceState(
        val source: HomePageSource,
        val hasNext: Boolean,
        val eligibleCategories: Set<String>,
    )

    private val buckets = linkedMapOf<String, CategoryBucket>()
    private val sources = linkedMapOf<String, SourceState>()
    private var nextArrivalOrder = 0

    fun addPage(source: HomePageSource, response: Engine.MainPageResult) {
        val observedCategories = linkedSetOf<String>()
        // A card keeps the order and representation of the first result shown.
        // Later duplicates enrich its alternatives; they never move the card.
        val arrivalOrder = nextArrivalOrder++
        response.lists.forEachIndexed { listIndex, pageList ->
            pageList.list.forEachIndexed { itemIndex, item ->
                val categories = homeCategoryIdsFor(
                    item = item,
                    providerLanguage = source.api.lang,
                    sourceMetadata = "${source.metadata} ${pageList.name}",
                )
                observedCategories += categories
                val entry = RankedSearchResponse(
                    item = item,
                    order = CatalogOrder(arrivalOrder, source.requestIndex, listIndex, itemIndex),
                )
                categories.forEach { categoryId ->
                    buckets.getOrPut(categoryId) { CategoryBucket() }.add(entry)
                }
            }
        }
        val listMetadataCategories = response.lists
            .flatMap { pageList -> homePotentialCategoryIds(source, pageList.name) }
            .toSet()
        sources[source.id] = SourceState(
            source = source,
            hasNext = response.hasNext,
            eligibleCategories = homePotentialCategoryIds(source) + listMetadataCategories + observedCategories,
        )
    }

    fun snapshot(): UnifiedHomeCatalog {
        val alternatives = linkedMapOf<String, List<SearchResponse>>()
        val rows = homeCatalogCategories.mapNotNull { category ->
            val bucket = buckets[category.id] ?: return@mapNotNull null
            val groups = bucket.orderedGroups()
            if (groups.isEmpty()) return@mapNotNull null

            val cursors = sources.values
                .asSequence()
                .filter { it.hasNext && category.id in it.eligibleCategories }
                .sortedWith(compareBy<SourceState>({ it.source.providerOrder }, { it.source.requestIndex }))
                .map { HomePageCursor(it.source, nextPage = 2, hasNext = true) }
                .toList()

            val items = groups.map { group ->
                val groupItems = group.members.sortedBy { it.order }.map { it.item }
                groupItems.forEach { alternatives[it.url] = groupItems }
                groupItems.first()
            }
            HomeRow(
                key = "catalog-${category.id}",
                title = category.title,
                items = items,
                horizontal = false,
                pageIndex = -1,
                page = 1,
                hasNext = cursors.any { it.hasNext },
                pagingSources = cursors,
                catalogCategoryId = category.id,
            )
        }
        return UnifiedHomeCatalog(rows = rows, sourcesByUrl = alternatives)
    }
}

