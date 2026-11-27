package ai.opencode.feature.transcript.widgets

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.transcript.widgets.AgentWidgetInstances.Change
import ai.opencode.server.model.OpenCodeWidgetCall
import ai.opencode.server.model.OpenCodeWidgetError
import ai.opencode.server.model.OpenCodeWidgetFailure
import ai.opencode.server.model.OpenCodeWidgetInstance
import ai.opencode.server.model.OpenCodeWidgetRef
import ai.opencode.server.model.OpenCodeWidgetState
import ai.opencode.server.model.OpenCodeWidgetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class AgentWidgetInstancesTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun state(message: String, status: OpenCodeWidgetStatus = OpenCodeWidgetStatus.Open, by: String? = null, version: String = "0000000000000001") =
        OpenCodeWidgetState(
            sessionID = "s",
            messageID = message,
            callID = "c",
            widgetTemplate = OpenCodeWidgetRef("chart", version),
            status = status,
            supersededBy = by?.let { OpenCodeWidgetCall(it, "c") },
        )

    private fun instance(message: String, replaced: List<String> = emptyList(), version: String = "0000000000000001") = OpenCodeWidgetInstance(
        state = state(message, version = version),
        data = "{}",
        sessionTitle = "Session",
        directory = "/project",
        updated = 1,
        replaced = replaced.map { OpenCodeWidgetCall(it, "c") },
    )

    @Test
    fun replacedKeysResolveOnLoad() {
        val instances = AgentWidgetInstances().loaded(listOf(instance("m3", replaced = listOf("m1", "m2")), instance("m9")))
        assertEquals("s:m3:c", instances.current("s:m1:c"))
        assertEquals("s:m3:c", instances["s:m2:c"]?.key)
        assertEquals("s:m9:c", instances["s:m9:c"]?.key)
        assertNull(instances["s:gone:c"])
        assertEquals("s:gone:c", instances.current("s:gone:c"))
    }

    @Test
    fun supersededByForwardsLiveAndAsksForARead() {
        val before = AgentWidgetInstances().loaded(listOf(instance("m1")))
        val (after, change) = before.changed("s", state("m1", OpenCodeWidgetStatus.Superseded, by = "m2"))
        assertEquals(Change.NeedsRefetch, change)
        assertEquals("s:m2:c", after.current("s:m1:c"))
        assertNull(after["s:m1:c"])
        // The read brings the replacement, which the old key then shows.
        val read = after.loaded(listOf(instance("m2", replaced = listOf("m1"), version = "0000000000000002")))
        assertEquals("0000000000000002", read["s:m1:c"]?.state?.widgetTemplate?.version)
    }

    @Test
    fun replacementsChain() {
        var instances = AgentWidgetInstances().loaded(listOf(instance("m1")))
        instances = instances.changed("s", state("m1", OpenCodeWidgetStatus.Superseded, by = "m2")).first
        instances = instances.changed("s", state("m2", OpenCodeWidgetStatus.Superseded, by = "m3")).first
        instances = instances.loaded(listOf(instance("m3")))
        assertEquals("s:m3:c", instances["s:m1:c"]?.key)
    }

    @Test
    fun aKnownWidgetChangesInPlace() {
        val before = AgentWidgetInstances().loaded(listOf(instance("m1")))
        val (after, change) = before.changed("s", state("m1", OpenCodeWidgetStatus.Submitted))
        assertEquals(Change.Updated, change)
        assertEquals(OpenCodeWidgetStatus.Submitted, after["s:m1:c"]?.state?.status)
        assertEquals("{}", after["s:m1:c"]?.data)
        assertEquals(Change.None, after.changed("s", state("m1", OpenCodeWidgetStatus.Submitted)).second)
    }

    @Test
    fun onlyUsableWidgetsAreOffered() {
        val failure = OpenCodeWidgetFailure(OpenCodeWidgetError("main", "boom"), client = "ios", sent = false)
        val open = instance("open")
        val submitted = instance("submitted").let { it.copy(state = it.state.copy(status = OpenCodeWidgetStatus.Submitted)) }
        val dismissed = instance("dismissed").let { it.copy(state = it.state.copy(status = OpenCodeWidgetStatus.Dismissed)) }
        val failed = instance("failed").let { it.copy(state = it.state.copy(failure = failure)) }
        // A chain whose newest widget was dismissed: neither it nor what it replaced is offered.
        val chainHead = instance("m3", replaced = listOf("m1", "m2")).let { it.copy(state = it.state.copy(status = OpenCodeWidgetStatus.Dismissed)) }
        val instances = AgentWidgetInstances().loaded(listOf(open, dismissed, submitted, failed, chainHead))
        assertEquals(listOf("s:open:c"), instances.usable.map { it.key })
        // Still resolvable, for a tile already on the dashboard.
        assertEquals("s:m3:c", instances["s:m1:c"]?.key)
        assertEquals("s:failed:c", instances["s:failed:c"]?.key)
        assertEquals("s:submitted:c", instances["s:submitted:c"]?.key)
    }

    @Test
    fun anUnknownWidgetAsksForARead() {
        val (_, change) = AgentWidgetInstances().changed("s", state("new"))
        assertEquals(Change.NeedsRefetch, change)
    }
}
