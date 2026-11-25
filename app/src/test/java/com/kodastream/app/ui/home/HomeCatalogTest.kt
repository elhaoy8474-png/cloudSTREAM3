package com.kodastream.app.ui.home

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("DEPRECATION_ERROR")
class HomeCatalogTest {
    private fun movie(name: String, source: String, url: String, year: Int? = 2020) =
        MovieSearchResponse(name, url, source, TvType.Movie, year = year)

    @Test
    fun punctuationAndReleaseTagsDoNotCreateDuplicateCards() {
        val groups = deduplicateCatalog(
            listOf(
                movie("The.Last-Kingdom (2020)", "Source A", "a"),
                movie("Last Kingdom 2020 1080p WEB-DL", "Source B", "b"),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("Source A", "Source B"), groups.single().sources.map { it.apiName })
    }

    @Test
    fun differentReleaseYearsRemainSeparate() {
        val groups = deduplicateCatalog(
            listOf(
                movie("The Journey", "Source A", "a", 2010),
                movie("The Journey", "Source B", "b", 2022),
            ),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun differentMediaFamiliesRemainSeparate() {
        val groups = deduplicateCatalog(
            listOf(
                movie("The Office", "Movies", "movie"),
                TvSeriesSearchResponse("The Office", "series", "Series", TvType.TvSeries, year = 2020),
            ),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun diacriticsAndMinorSpellingDifferencesAreNormalized() {
        assertEquals(normalizeCatalogTitle("Amélie"), normalizeCatalogTitle("Amelie"))
        val groups = deduplicateCatalog(
            listOf(
                movie("Amélie", "Source A", "a", null),
                movie("Amelie", "Source B", "b", null),
            ),
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun minorSpellingDifferencesCanMatchWhenReleaseYearAgrees() {
        val groups = deduplicateCatalog(
            listOf(
                movie("The Last Kingdom", "Source A", "a", 2020),
                movie("Last Kingdm", "Source B", "b", 2020),
            ),
        )
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().sources.size)
    }

    @Test
    fun arabicDiacriticsDoNotChangeTheNormalizedTitle() {
        assertEquals(normalizeCatalogTitle("المُدَرِّس"), normalizeCatalogTitle("المدرس"))
    }

    @Test
    fun onlyTrailingYearsAreRemovedFromTitles() {
        assertEquals("1917", normalizeCatalogTitle("1917"))
        assertEquals("2020 a new beginning", normalizeCatalogTitle("2020: A New Beginning"))
        assertEquals("quiet place", normalizeCatalogTitle("A Quiet Place"))
        assertEquals("film a new beginning", normalizeCatalogTitle("Film: A New Beginning"))
        assertEquals("film name", normalizeCatalogTitle("Film Name (2020)"))
    }

    @Test
    fun trailingYearSuffixKeepsRemakesSeparateWithoutProviderYearFields() {
        val groups = deduplicateCatalog(
            listOf(
                movie("Film Name (2010)", "Source A", "a", null),
                movie("Film Name (2022)", "Source B", "b", null),
            ),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun unrelatedTitlesAreNotMergedByLooseSubstringMatching() {
        val groups = deduplicateCatalog(
            listOf(
                movie("Batman", "Source A", "a"),
                movie("Batman Begins", "Source B", "b"),
            ),
        )
        assertEquals(2, groups.size)
    }

    @Test
    fun catalogueHandlesManyProvidersWithoutLosingDistinctItems() {
        val rows = (0 until 100).map { provider ->
            val items = (0 until 12).map { item ->
                val value = (provider * 7919 + item * 104729) xor 0x5A5A5A5A
                movie("q${Integer.toHexString(value)}", "Provider $provider", "$provider-$item", null)
            }
            HomeRow("provider-$provider", "Shelf $provider", items, false, 0, 1, false)
        }

        val accumulator = HomeCatalogAccumulator()
        rows.forEach { row -> accumulator.add(listOf(row)) }
        val unified = accumulator.snapshot()

        assertEquals(1200, unified.rows.sumOf { it.items.size })
        assertEquals(1200, unified.sourcesByUrl.size)
    }

    @Test
    fun unifiedShelvesKeepOneCardAndExposeEveryProviderUrl() {
        val unified = mergeHomeRows(
            listOf(
                HomeRow("a", "Trending", listOf(movie("Film One", "A", "a")), false, 0, 1, true),
                HomeRow("b", "New Releases", listOf(movie("Film One (2020)", "B", "b")), false, 0, 1, true),
            ),
        )
        assertEquals(1, unified.rows.size)
        assertEquals(1, unified.rows.single().items.size)
        assertEquals(setOf("a", "b"), unified.sourcesByUrl.keys)
        assertEquals(2, unified.sourcesByUrl.getValue("a").size)
        assertFalse(unified.rows.single().hasNext)
        assertTrue(unified.rows.single().key.startsWith("unified-"))
    }
    @Test
    fun incrementalUpdatesPublishEarlyRowsAndMergeLaterDuplicatesInPlace() {
        val accumulator = HomeCatalogAccumulator()
        accumulator.add(
            listOf(
                HomeRow("a", "Trending", listOf(movie("Film One", "Source A", "a")), false, 0, 1, false),
            ),
        )

        val early = accumulator.snapshot()
        assertEquals(listOf("Film One"), early.rows.single().items.map { it.name })
        assertEquals(listOf("Source A"), early.sourcesByUrl.getValue("a").map { it.apiName })

        accumulator.add(
            listOf(
                HomeRow(
                    "b",
                    "New Releases",
                    listOf(
                        movie("Film One (2020)", "Source B", "b"),
                        movie("Film Two", "Source B", "c"),
                    ),
                    false,
                    0,
                    1,
                    false,
                ),
            ),
        )

        val updated = accumulator.snapshot()
        assertEquals(2, updated.rows.sumOf { it.items.size })
        assertEquals(2, updated.sourcesByUrl.getValue("a").size)
        assertEquals(2, updated.sourcesByUrl.getValue("b").size)
        assertEquals("Trending", updated.rows.first().title)
        assertEquals(listOf("Film One"), updated.rows.first().items.map { it.name })
        assertEquals(listOf("Film Two"), updated.rows.last().items.map { it.name })
    }

    @Test
    fun indianMoviesAlsoBelongToTheGlobalMoviesCategory() {
        val ids = homeCategoryIdsFor(movie("Pathaan", "Provider", "pathaan"), "hi", "Hindi catalogue")
        assertTrue(ids.contains(HomeCategoryIds.MOVIES))
        assertTrue(ids.contains(HomeCategoryIds.INDIAN_MOVIES))
        assertFalse(ids.contains(HomeCategoryIds.FOREIGN_MOVIES))
    }

    @Test
    fun regionalSeriesUseLanguageAndExplicitAsianDramaType() {
        val arabic = TvSeriesSearchResponse(
            "A", "a", "Arabic", TvType.TvSeries, year = 2024,
        )
        val turkish = TvSeriesSearchResponse(
            "B", "b", "Provider", TvType.TvSeries, year = 2024,
        )
        val asian = TvSeriesSearchResponse(
            "C", "c", "Provider", TvType.AsianDrama, year = 2024,
        )

        assertTrue(HomeCategoryIds.ARABIC_SERIES in homeCategoryIdsFor(arabic, "ar", ""))
        assertTrue(HomeCategoryIds.TURKISH_SERIES in homeCategoryIdsFor(turkish, "tr", ""))
        assertTrue(HomeCategoryIds.ASIAN_SERIES in homeCategoryIdsFor(asian, "en", ""))
    }

    @Test
    fun animeCanBelongToDubbedAndSubtitledCrossCategoriesAtOnce() {
        val item = AnimeSearchResponse(
            name = "Example Anime",
            url = "anime",
            apiName = "Anime provider",
            type = TvType.Anime,
            dubStatus = mutableSetOf(DubStatus.Dubbed, DubStatus.Subbed),
        )
        val ids = homeCategoryIdsFor(item, "ja", "Japanese anime")
        assertTrue(HomeCategoryIds.ANIME in ids)
        assertTrue(HomeCategoryIds.DUBBED in ids)
        assertTrue(HomeCategoryIds.SUBTITLED in ids)
    }

    @Test
    fun arabicDubMarkersWorkEvenWhenTheItemIsNotAnAnimeResponse() {
        val item = movie("فيلم مدبلج", "Source", "dubbed-film")
        val ids = homeCategoryIdsFor(item, "en", "أفلام مترجمة ومدبلجة")
        assertTrue(HomeCategoryIds.DUBBED in ids)
        assertTrue(HomeCategoryIds.SUBTITLED in ids)
    }

    @Test
    fun unknownProviderLanguageDoesNotInventAForeignCategory() {
        val ids = homeCategoryIdsFor(movie("Unknown film", "Provider", "unknown"), "xx", "Unknown source")
        assertTrue(HomeCategoryIds.MOVIES in ids)
        assertFalse(HomeCategoryIds.FOREIGN_MOVIES in ids)
        assertFalse(HomeCategoryIds.ARABIC_MOVIES in ids)
    }

}
