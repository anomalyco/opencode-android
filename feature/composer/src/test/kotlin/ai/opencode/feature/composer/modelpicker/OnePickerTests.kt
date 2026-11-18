package ai.opencode.feature.composer.modelpicker

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.max

/** Catalog and provider-mark tests for One Picker. */
class OnePickerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val opusZen = OpenCodeModel("claude-opus-5-5", "opencode", "Claude Opus 5.5", variants = listOf("low", "medium", "high", "max"))
    private val sonnetAnthropic = OpenCodeModel("claude-sonnet-5-5", "anthropic", "Claude Sonnet 5.5", variants = listOf("low", "medium", "high"))
    private val astra = OpenCodeModel("gpt-6-astra", "openai", "GPT-6 Astra", variants = listOf("minimal", "low", "medium", "high", "xhigh"))
    private val carbon = OpenCodeModel("gemini-4-carbon", "console-google", "Gemini 4 Carbon", variants = listOf("low", "high"))
    private val haiku = OpenCodeModel("claude-haiku-5-5", "opencode", "Claude Haiku 5.5")
    private val halcyonLumen = OpenCodeModel("lumen-r1", "halcyon", "Lumen R1", variants = listOf("low", "high"))

    private val catalog: List<OpenCodeModel>
        get() = listOf(opusZen, haiku, sonnetAnthropic, astra, carbon, halcyonLumen)

    @Test
    fun idleSectionsPlaceFavoritesThenFrozenRecentsWithCurrentModelThenProviders() {
        val sections = OnePickerCatalog.sections(
            models = catalog,
            favorites = setOf(sonnetAnthropic.id),
            recents = listOf(sonnetAnthropic.id, astra.id, carbon.id),
            currentModelID = opusZen.id,
            filter = "",
            recentLimit = 3,
            showsAllModels = true,
        )
        assertEquals(
            listOf("Favorites", "Recent", "OpenCode Zen", "Anthropic", "OpenAI", "Console Google", "Halcyon"),
            sections.map { it.title },
        )
        assertEquals(listOf(sonnetAnthropic), sections[0].models)
        assertEquals(
            "Current model stays visible at the top of Recent when not in Favorites, excluding favorited models",
            listOf(opusZen, astra, carbon),
            sections[1].models,
        )
        assertEquals(listOf(opusZen, haiku), sections[2].models)
    }

    @Test
    fun curatedModeHidesProviderSectionsOnlyWhenFavoritesAndRecentHaveEnoughPicks() {
        val curated = OnePickerCatalog.sections(
            models = catalog,
            favorites = setOf(opusZen.id),
            recents = listOf(astra.id),
            currentModelID = opusZen.id,
            filter = "",
            recentLimit = 3,
            showsAllModels = false,
        )
        assertEquals(listOf("Favorites", "Recent"), curated.map { it.title })

        val unconfigured = OnePickerCatalog.sections(
            models = catalog,
            favorites = emptySet(),
            recents = emptyList(),
            currentModelID = opusZen.id,
            filter = "",
            recentLimit = 3,
            showsAllModels = false,
        )
        assertTrue("Falls back to full provider catalog when fewer than 2 curated picks exist", unconfigured.size > 2)
    }

    @Test
    fun searchDropsSectionsMatchesWordsInAnyOrderAndParsesLevelWords() {
        val results = OnePickerCatalog.sections(
            models = catalog,
            favorites = setOf(opusZen.id),
            recents = listOf(astra.id),
            currentModelID = opusZen.id,
            filter = "astra extra high",
            recentLimit = 3,
            showsAllModels = false,
        )
        assertEquals(1, results.size)
        assertEquals(ModelPickerSection.Kind.SearchResults, results[0].kind)
        assertEquals(astra, results[0].models.first())

        val parsedAstra = OnePickerCatalog.parsedSearchLevel("astra extra high", astra)
        assertTrue(parsedAstra.matched && parsedAstra.level == "xhigh")
        val parsedHaiku = OnePickerCatalog.parsedSearchLevel("haiku high", haiku)
        assertTrue("Non-adjustable models never invent a level", !parsedHaiku.matched && parsedHaiku.level == null)
    }

    // Provider marks & filled fallback. The Compose mark draws the same squircle directly.

    @Test
    fun providerMarksUseRealAssetsAndFilledFallbackForUnknownProviders() {
        assertEquals("provider-anthropic", OnePickerProviderMark.resolvedAssetName("anthropic", null))
        assertEquals("provider-openai", OnePickerProviderMark.resolvedAssetName("openai", null))
        assertEquals("provider-opencode", OnePickerProviderMark.resolvedAssetName("opencode", null))
        assertEquals("provider-google", OnePickerProviderMark.resolvedAssetName("console-google", null))
        assertEquals("provider-ollama-cloud", OnePickerProviderMark.resolvedAssetName("ollama", null))
        // Unknown/fictional providers return null so the filled monogram renders instead of stroked sparkles.
        assertNull(OnePickerProviderMark.resolvedAssetName("halcyon", null))
        assertNull(OnePickerProviderMark.resolvedAssetName("meridian", null))
        assertEquals("H", OnePickerProviderMark.fallbackInitial("halcyon"))
        assertEquals("M", OnePickerProviderMark.fallbackInitial("meridian"))
    }
}
