package ai.opencode.feature.drawer

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.overview.overviewSummary
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The Overview: the totals it shows, the projects it groups, and the navigation it owns.
 */
class OverviewTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")

    private fun session(
        id: String,
        project: String = "p",
        cost: Double? = null,
        updated: Long = 0,
        idle: Long? = 2_000,
        viewed: Long? = 1_000,
    ) = OpenCodeSession(
        id = id,
        serverTitle = id,
        projectID = project,
        directory = "/$project",
        updatedAt = Instant.ofEpochSecond(updated),
        idleMilliseconds = idle,
        viewedMilliseconds = viewed,
        cost = cost,
    )

    private fun home(sessions: List<OpenCodeSession>, projects: List<OpenCodeProject> = emptyList()) = HomeFeature.State(
        server,
        OpenCodeHomeSnapshot(projects = projects, sessions = sessions, activeSessionIDs = emptySet()),
    )

    @Test fun overviewTotalsTheSessionsAndTheirSpend() {
        val state = home(
            listOf(
                session("a", cost = 1.25, updated = 10),
                session("b", cost = 2.75, updated = 20),
            ),
        )
        val summary = state.overviewSummary()
        assertEquals("the server's sessions", 2, summary.sessions)
        assertEquals("their spend", 4.0, summary.spend, 0.001)
        assertEquals("the most recent first", listOf("b", "a"), summary.recents.map { it.id })
    }

    @Test fun overviewLeavesArchivedSessionsOut() {
        val state = home(
            listOf(
                session("a", cost = 1.0),
                session("archived", cost = 9.0).copy(archivedMilliseconds = 1_000),
            ),
        )
        val summary = state.overviewSummary()
        assertEquals("only the live sessions", 1, summary.sessions)
        assertEquals("and their spend", 1.0, summary.spend, 0.001)
    }

    @Test fun overviewGroupsTheProjectsAndFindsTheirLatestSession() {
        val state = home(
            listOf(
                session("a", project = "p1", cost = 1.0, updated = 10),
                session("b", project = "p1", cost = 2.0, updated = 30),
                session("c", project = "p2", cost = 4.0, updated = 20),
            ),
            listOf(
                OpenCodeProject(id = "p1", name = "One", directory = "/p1"),
                OpenCodeProject(id = "p2", name = "Two", directory = "/p2"),
            ),
        )
        val summary = state.overviewSummary()
        assertEquals("both projects", 2, summary.projects.size)
        val one = summary.projects.first { it.project.id == "p1" }
        assertEquals("its sessions", 2, one.sessions)
        assertEquals("its spend", 3.0, one.spend, 0.001)
        assertEquals("its latest session", "b", one.latestSessionID)
    }

    @Test fun theOverviewOpensOverTheSessionAndClosesTheDrawer() {
        val state = home(emptyList())
        val opened = HomeFeature().reduce(state, HomeFeature.Action.OverviewTapped)
        assertTrue("the Overview is up", opened.state.showsOverview)
        assertEquals("the drawer closes as it opens", 1, opened.state.drawerCloseRequests)

        val closed = HomeFeature().reduce(opened.state, HomeFeature.Action.OverviewClosed)
        assertFalse("and it closes again", closed.state.showsOverview)
    }

    @Test fun theOverviewIsNotOpenedTwice() {
        val state = home(emptyList()).copy(showsOverview = true, drawerCloseRequests = 1)
        val reduced = HomeFeature().reduce(state, HomeFeature.Action.OverviewTapped)
        assertEquals("the drawer is not asked to close again", 1, reduced.state.drawerCloseRequests)
    }
}
