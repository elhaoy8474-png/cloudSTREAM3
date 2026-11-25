package com.kodastream.app.ui.home

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import java.text.Normalizer
import java.util.Locale

/** A title cluster keeps every playable provider result; the first result is its display card. */
internal data class CatalogGroup(
    val primary: SearchResponse,
    val sources: List<SearchResponse>,
)

/** Rows and source alternatives produced by combining the installed providers' first home pages. */
internal data class UnifiedHomeCatalog(
    val rows: List<HomeRow>,
    /** Every source URL points to the full set of provider alternatives for that work. */
    val sourcesByUrl: Map<String, List<SearchResponse>>,
)

internal fun normalizeCatalogTitle(value: String): String {
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "")
        .replace(Regex("\\b(2160p|1440p|1080p|720p|480p|4k|8k|hdrip|webrip|web-dl|bluray|brrip|hdtv)\\b"), " ")
        .replace(Regex("""(?:\s|\(|\[)+(?:19|20)\d{2}[\)\]]*\s*$"""), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
    val tokens = normalized.split(' ').filter { it.isNotBlank() }
    val titleTokens = if (tokens.firstOrNull()?.let { it == "the" || it == "a" || it == "an" } == true) {
        tokens.drop(1)
    } else {
        tokens
    }
    return titleTokens.joinToString(" ")
}

private fun mediaFamily(item: SearchResponse): String = when (item.type) {
    TvType.Movie, TvType.AnimeMovie -> "movie"
    TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama -> "series"
    TvType.Live -> "live"
    TvType.Torrent -> "torrent"
    TvType.Documentary -> "documentary"
    TvType.NSFW -> "nsfw"
    TvType.Music, TvType.AudioBook, TvType.Audio, TvType.Podcast -> "audio"
    TvType.CustomMedia, TvType.Video -> "video"
    TvType.Others, null -> "other"
}

private fun releaseYear(item: SearchResponse): Int? {
    val declared = when (item) {
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        is AnimeSearchResponse -> item.year
        else -> null
    }
    val trailingYear = Regex("""(?:\s|\(|\[)+((?:19|20)\d{2})[\)\]]*\s*$""")
        .find(item.name)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return declared ?: trailingYear
}

private fun editSimilarity(left: String, right: String): Double {
    if (left == right) return 1.0
    if (left.isEmpty() || right.isEmpty()) return 0.0
    var previous = IntArray(right.length + 1) { it }
    for (i in left.indices) {
        val current = IntArray(right.length + 1)
        current[0] = i + 1
        for (j in right.indices) {
            val cost = if (left[i] == right[j]) 0 else 1
            current[j + 1] = minOf(
                current[j] + 1,
                previous[j + 1] + 1,
                previous[j] + cost,
            )
        }
        previous = current
    }
    return 1.0 - previous[right.length].toDouble() / maxOf(left.length, right.length)
}

private fun titleSimilarity(left: String, right: String): Double {
    if (left == right) return 1.0
    if (left.isBlank() || right.isBlank()) return 0.0

    val leftTokens = left.split(' ').filter { it.isNotBlank() }
    val unmatchedRight = right.split(' ').filter { it.isNotBlank() }.toMutableList()
    var tokenScore = 0.0
    leftTokens.forEach { token ->
        val best = unmatchedRight.maxByOrNull { editSimilarity(token, it) } ?: return@forEach
        val similarity = editSimilarity(token, best)
        if (similarity >= 0.60) {
            tokenScore += similarity
            unmatchedRight.remove(best)
        }
    }
    val tokenF1 = if (leftTokens.isEmpty() && unmatchedRight.isEmpty()) {
        1.0
    } else {
        2.0 * tokenScore / (leftTokens.size + right.split(' ').count { it.isNotBlank() })
    }
    val characterScore = editSimilarity(left.replace(" ", ""), right.replace(" ", ""))
    return tokenF1 * 0.55 + characterScore * 0.45
}

private fun sameWork(left: SearchResponse, right: SearchResponse): Boolean {
    if (mediaFamily(left) != mediaFamily(right)) return false
    val leftYear = releaseYear(left)
    val rightYear = releaseYear(right)
    if (leftYear != null && rightYear != null && leftYear != rightYear) return false

    val leftTitle = normalizeCatalogTitle(left.name)
    val rightTitle = normalizeCatalogTitle(right.name)
    val threshold = if (leftYear != null && rightYear != null) 0.88 else 0.92
    return titleSimilarity(leftTitle, rightTitle) >= threshold
}

/**
 * Conservative, deterministic clustering. Exact names are cheap; fuzzy matches
 * require a high score and compatible media type/year to avoid merging remakes.
 */
private fun titleNgrams(value: String): Set<String> {
    if (value.length < 3) return setOf(value)
    return (0..value.length - 3).mapTo(linkedSetOf()) { value.substring(it, it + 3) }
}

internal fun deduplicateCatalog(items: List<SearchResponse>): List<CatalogGroup> {
    // Index likely fuzzy matches instead of comparing every item with every
    // existing group. This keeps large multi-extension catalogues practical.
    val groups = mutableListOf<MutableList<SearchResponse>>()
    val candidatesByNgram = HashMap<String, MutableList<Int>>()
    items.asSequence().filter { it.url.isNotBlank() }.forEach { item ->
        val title = normalizeCatalogTitle(item.name)
        val candidates = linkedSetOf<Int>()
        titleNgrams(title).forEach { gram ->
            candidatesByNgram[gram]?.let(candidates::addAll)
        }
        val existingIndex = candidates.firstOrNull { index ->
            sameWork(groups[index].first(), item)
        }

        if (existingIndex == null) {
            val index = groups.size
            groups += mutableListOf(item)
            titleNgrams(title).forEach { gram ->
                candidatesByNgram.getOrPut(gram) { mutableListOf() }.add(index)
            }
        } else {
            val existing = groups[existingIndex]
            if (existing.none { it.apiName == item.apiName && it.url == item.url }) {
                existing += item
            }
        }
    }
    return groups.map { group -> CatalogGroup(group.first(), group.toList()) }
}

/**
 * Stateful incremental catalogue merger.
 *
 * New provider/page results are indexed once and merged into existing groups.
 * This avoids re-running fuzzy matching over every previously received item
 * after each provider responds.
 */
internal class HomeCatalogAccumulator {
    private data class ShelfKey(val normalizedTitle: String, val horizontal: Boolean)
    private data class ShelfData(
        val title: String,
        val horizontal: Boolean,
        val groupIndexes: MutableList<Int> = mutableListOf(),
    )

    private val shelves = linkedMapOf<ShelfKey, ShelfData>()
    private val groups = mutableListOf<MutableList<SearchResponse>>()
    private val candidatesByNgram = HashMap<String, MutableList<Int>>()

    fun add(rows: List<HomeRow>) {
        rows.forEach { row ->
            val normalizedShelf = normalizeCatalogTitle(row.title)
                .ifBlank { row.title.lowercase(Locale.ROOT) }
            val shelfKey = ShelfKey(normalizedShelf, row.horizontal)
            val shelf = shelves.getOrPut(shelfKey) {
                ShelfData(row.title, row.horizontal)
            }

            row.items.forEach itemLoop@{ item ->
                if (item.url.isBlank()) return@itemLoop

                val title = normalizeCatalogTitle(item.name)
                val candidates = linkedSetOf<Int>()
                titleNgrams(title).forEach { gram ->
                    candidatesByNgram[gram]?.let(candidates::addAll)
                }
                val existingIndex = candidates.firstOrNull { index ->
                    sameWork(groups[index].first(), item)
                }

                if (existingIndex == null) {
                    val newIndex = groups.size
                    groups += mutableListOf(item)
                    shelf.groupIndexes += newIndex
                    titleNgrams(title).forEach { gram ->
                        candidatesByNgram.getOrPut(gram) { mutableListOf() }.add(newIndex)
                    }
                } else {
                    val existing = groups[existingIndex]
                    if (existing.none { it.apiName == item.apiName && it.url == item.url }) {
                        existing += item
                    }
                }
            }
        }
    }

    fun snapshot(): UnifiedHomeCatalog {
        val alternatives = linkedMapOf<String, List<SearchResponse>>()
        groups.forEach { group ->
            val sources = group.toList()
            sources.forEach { source -> alternatives[source.url] = sources }
        }

        val mergedRows = shelves.mapNotNull { (key, shelf) ->
            val shelfGroups = shelf.groupIndexes.map { groups[it].toList() }
            if (shelfGroups.isEmpty()) {
                null
            } else {
                HomeRow(
                    key = "unified-${key.normalizedTitle}-${key.horizontal}",
                    title = shelf.title,
                    items = shelfGroups.map { it.first() },
                    horizontal = shelf.horizontal,
                    pageIndex = -1,
                    page = 1,
                    hasNext = false,
                )
            }
        }
        return UnifiedHomeCatalog(mergedRows, alternatives)
    }
}

/** Batch helper retained for tests and callers that already have all rows. */
internal fun mergeHomeRows(rows: List<HomeRow>): UnifiedHomeCatalog =
    HomeCatalogAccumulator().apply { add(rows) }.snapshot()
