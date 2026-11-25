package com.kodastream.app.ui.home

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
    fun unifiedShelvesKeepOneCardAndExposeEveryProviderUrl() {
        val unified = mergeHomeRows(
            listOf(
                HomeRow("a", "Trending", listOf(movie("Film One", "A", "a")), false, 0, 1, true),
                HomeRow("b", "Trending now", listOf(movie("Film One (2020)", "B", "b")), false, 0, 1, true),
            ),
        )
        assertEquals(1, unified.rows.size)
        assertEquals(1, unified.rows.single().items.size)
        assertEquals(setOf("a", "b"), unified.sourcesByUrl.keys)
        assertEquals(2, unified.sourcesByUrl.getValue("a").size)
        assertFalse(unified.rows.single().hasNext)
        assertTrue(unified.rows.single().key.startsWith("unified-"))
    }
}
