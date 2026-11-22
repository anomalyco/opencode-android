package ai.opencode.feature.drawer.overview

import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.icons.OpenCodeProjectLookup
import ai.opencode.feature.drawer.statusSnapshot
import ai.opencode.server.model.OpenCodeProject
import java.time.Instant

/**
 * What the Overview shows, computed from Home's state in one pass: the banner's totals and the
 * two cards' rows.
 *
 * A plain value, so the view reads numbers rather than walking Home's state.
 */
data class OverviewSummary(
    /** The server's sessions. */
    val sessions: Int,
    /** The sessions with something running in them. */
    val running: Int,
    /** The sessions waiting on the reader. */
    val attention: Int,
    /** The sessions the reader hasn't seen. */
    val unread: Int,
    /** The spend across the server's sessions, in USD. */
    val spend: Double,
    /** The projects, busiest first. */
    val projects: List<OverviewProject>,
    /** The most recently touched sessions. */
    val recents: List<OverviewSession>,
) {
    companion object {
        val empty = OverviewSummary(0, 0, 0, 0, 0.0, emptyList(), emptyList())
    }
}

/** One project on the Overview. */
data class OverviewProject(
    val project: OpenCodeProject,
    val sessions: Int,
    val running: Int,
    val spend: Double,
    /** The project's most recently touched session: what its row opens. */
    val latestSessionID: String,
)

/** One session on the Overview. */
data class OverviewSession(
    val id: String,
    val title: String,
    val running: Boolean,
    val attention: Boolean,
    val unread: Boolean,
)

/**
 * Home's state as the Overview shows it: the totals, the projects (busiest first) and the most
 * recently touched sessions.
 *
 * The spend is the server's own `Session.Info.cost` per session, summed here; nothing is read
 * again from the network.
 */
fun HomeFeature.State.overviewSummary(projectLimit: Int = 6, sessionLimit: Int = 6): OverviewSummary {
    val status = statusSnapshot()
    val lookup = OpenCodeProjectLookup(projects)
    val live = sessions.filter { !it.isArchived }
    var running = 0
    var attention = 0
    var unread = 0
    var spend = 0.0
    val byProject = LinkedHashMap<String, OverviewProject>()
    // The project's most recently touched session, and when it was touched.
    val latest = HashMap<String, Pair<String, Instant>>()
    for (session in live) {
        when (status.indicator(session)) {
            HomeFeature.State.Indicator.running -> running++
            HomeFeature.State.Indicator.attention -> attention++
            HomeFeature.State.Indicator.unread -> unread++
            HomeFeature.State.Indicator.none -> Unit
        }
        session.cost?.let { spend += it }
        val project = lookup.resolved(session)
        val existing = byProject[project.id]
        val projectRunning = if (status.indicator(session) == HomeFeature.State.Indicator.running) 1 else 0
        val seen = latest[project.id]
        val newest = if (seen == null || session.updatedAt.isAfter(seen.second)) session.id to session.updatedAt else seen
        latest[project.id] = newest
        byProject[project.id] = OverviewProject(
            project = project,
            sessions = (existing?.sessions ?: 0) + 1,
            running = (existing?.running ?: 0) + projectRunning,
            spend = (existing?.spend ?: 0.0) + (session.cost ?: 0.0),
            latestSessionID = newest.first,
        )
    }
    val projects = byProject.values
        .sortedWith(compareByDescending<OverviewProject> { it.running }.thenByDescending { it.sessions })
        .take(projectLimit)
    val recents = live
        .sortedByDescending { it.updatedAt }
        .take(sessionLimit)
        .map { session ->
            val indicator = status.indicator(session)
            OverviewSession(
                id = session.id,
                title = session.title,
                running = indicator == HomeFeature.State.Indicator.running,
                attention = indicator == HomeFeature.State.Indicator.attention,
                unread = indicator == HomeFeature.State.Indicator.unread,
            )
        }
    return OverviewSummary(
        sessions = live.size,
        running = running,
        attention = attention,
        unread = unread,
        spend = spend,
        projects = projects,
        recents = recents,
    )
}
