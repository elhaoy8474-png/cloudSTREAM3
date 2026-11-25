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
    val titleTokens = if (tokens.firstOrNull() in setOf("the", "a", "an")) {
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
internal fun deduplicateCatalog(items: List<SearchResponse>): List<CatalogGroup> {
    val groups = mutableListOf<MutableList<SearchResponse>>()
    items.filter { it.url.isNotBlank() }.forEach { item ->
        val existing = groups.firstOrNull { group -> sameWork(group.first(), item) }
        if (existing == null) {
            groups += mutableListOf(item)
        } else if (existing.none { it.apiName == item.apiName && it.url == item.url }) {
            existing += item
        }
    }
    return groups.map { group -> CatalogGroup(group.first(), group.toList()) }
}

/**
 * Combine similarly named shelves from all extensions, then expose each work
 * once in that shelf while retaining every provider URL for the source chooser.
 */
internal fun mergeHomeRows(rows: List<HomeRow>): UnifiedHomeCatalog {
    val shelves = linkedMapOf<Pair<String, Boolean>, MutableList<HomeRow>>()
    rows.forEach { row ->
        val key = normalizeCatalogTitle(row.title).ifBlank { row.title.lowercase(Locale.ROOT) } to row.horizontal
        shelves.getOrPut(key) { mutableListOf() }.add(row)
    }

    val shelfEntries = shelves.entries.toList()
    val shelfIndexByUrl = linkedMapOf<String, Int>()
    shelfEntries.forEachIndexed { index, (_, matchingRows) ->
        matchingRows.flatMap { it.items }.forEach { item ->
            shelfIndexByUrl.putIfAbsent(item.url, index)
        }
    }

    // Cluster globally, not just inside a matching shelf: the same film cannot
    // reappear under a second provider's differently named Home category.
    val groupsByShelf = Array(shelfEntries.size) { mutableListOf<CatalogGroup>() }
    val alternatives = linkedMapOf<String, List<SearchResponse>>()
    deduplicateCatalog(rows.flatMap { it.items }).forEach { group ->
        val owner = group.sources.mapNotNull { shelfIndexByUrl[it.url] }.minOrNull() ?: 0
        groupsByShelf[owner] += group
        group.sources.forEach { source -> alternatives[source.url] = group.sources }
    }

    val mergedRows = shelfEntries.mapIndexedNotNull { index, (key, matchingRows) ->
        val groups = groupsByShelf[index]
        if (groups.isEmpty()) return@mapIndexedNotNull null
        HomeRow(
            key = "unified-$index-${key.first}-${key.second}",
            title = matchingRows.first().title,
            items = groups.map { it.primary },
            horizontal = key.second,
            pageIndex = -1,
            page = 1,
            hasNext = false,
        )
    }
    return UnifiedHomeCatalog(mergedRows, alternatives)
}
