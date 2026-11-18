package ai.opencode.feature.composer.modelpicker

import ai.opencode.server.model.ModelSearch
import ai.opencode.server.model.OpenCodeModel
import kotlin.math.max

/** One Picker's sections and its search's level word. */
object OnePickerCatalog {
    /**
     * Builds truthful One Picker sections:
     * - Searching: flat ranked `searchResults` across [models] (words in any order, provider
     *   matching, and optional trailing level words such as `"opus high"`).
     * - Idle: Favorites, then Recent (frozen at open, excluding favorites, always keeping
     *   [currentModelID] visible at the top if not already in Favorites or Recent), then
     *   per-provider sections in the server's order (unless [showsAllModels] is off and
     *   Favorites + Recent already offer ≥ 2 picks).
     */
    fun sections(
        models: List<OpenCodeModel>,
        favorites: Set<String>,
        recents: List<String>,
        currentModelID: String?,
        filter: String,
        recentLimit: Int = 3,
        showsAllModels: Boolean = true,
    ): List<ModelPickerSection> {
        val needle = filter.trim()
        if (needle.isNotEmpty()) {
            val ranked = ModelSearch.rank(models, needle, favorites = favorites, recents = recents, parseLevel = true)
            if (ranked.isEmpty()) return emptyList()
            return listOf(ModelPickerSection(ModelPickerSection.Kind.SearchResults, ranked))
        }

        val byID = LinkedHashMap<String, OpenCodeModel>()
        models.forEach { byID.putIfAbsent(it.id, it) }
        val favModels = models.filter { it.id in favorites }
        val favIDs = favModels.mapTo(HashSet()) { it.id }

        var recentModels = mutableListOf<OpenCodeModel>()
        val seenRecent = HashSet<String>()
        val cap = max(0, recentLimit)
        if (cap > 0) {
            for (id in recents) {
                if (id in favIDs) continue
                if (!seenRecent.add(id)) continue
                val model = byID[id] ?: continue
                recentModels.add(model)
                if (recentModels.size >= cap) break
            }
            val current = currentModelID?.let(byID::get)
            if (currentModelID != null && currentModelID !in favIDs && recentModels.none { it.id == currentModelID } && current != null) {
                recentModels.add(0, current)
                if (recentModels.size > cap) recentModels = recentModels.take(cap).toMutableList()
            }
        }

        val out = mutableListOf<ModelPickerSection>()
        if (favModels.isNotEmpty()) out += ModelPickerSection(ModelPickerSection.Kind.Favorites, favModels)
        if (recentModels.isNotEmpty()) out += ModelPickerSection(ModelPickerSection.Kind.Recent, recentModels)

        val curatedCount = favModels.size + recentModels.size
        if (showsAllModels || curatedCount < 2) {
            val byProvider = LinkedHashMap<String, MutableList<OpenCodeModel>>()
            for (model in models) byProvider.getOrPut(model.providerID) { mutableListOf() }.add(model)
            byProvider.forEach { (provider, list) -> out += ModelPickerSection(ModelPickerSection.Kind.Provider(provider), list) }
        }
        return out
    }

    /** The search's explicit level (`"astra high"` → `"high"`), resolved against the model's variants. */
    data class ParsedSearchLevel(val level: String?, val matched: Boolean)

    /**
     * An explicit level parsed from the search [query], resolved against [model]'s variants, or
     * `(null, false)` when no level word was typed (or the model has no levels to adjust).
     */
    fun parsedSearchLevel(query: String, model: OpenCodeModel): ParsedSearchLevel {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || !ModelLevelScale(model.variants).isAdjustable) return ParsedSearchLevel(null, false)
        val requested = ModelSearch.parseLevelQuery(trimmed).level ?: return ParsedSearchLevel(null, false)
        return ParsedSearchLevel(ModelSearch.resolveVariant(requested, model), true)
    }
}
