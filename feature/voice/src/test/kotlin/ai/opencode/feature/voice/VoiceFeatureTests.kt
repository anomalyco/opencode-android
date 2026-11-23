package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.call
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.date
import ai.opencode.feature.voice.VoiceFeatureTestSupport.delegating
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.response
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The tests whose store is `VoiceFeature`. */
class VoiceFeatureTests {
    private lateinit var h: Harness

    private fun record(state: State, role: VoiceTranscriptTail.Role, text: String, id: String? = null): State =
        state.copy(tail = state.tail.record(role, text, id).tail)

    /**
     * "Yes, let it run the tests, pick Postgres, and after that also update
     * the docs. Actually send that now. Thanks, bye." Requests are answered,
     * a queued message is switched to steering, and the call ends once the
     * goodbye has played.
     */
    @Test fun delegatorAnswersRequestsSteersQueuedWorkAndEndsTheCall() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
        voice = record(voice, VoiceTranscriptTail.Role.user, "Yes let it run the tests, pick Postgres, then also update the docs, actually send that now. Bye", id = "u1")
        val replies = LockIsolated(listOf<String>())
        val deliveries = LockIsolated(listOf<String>())
        val listed = LockIsolated("")
        val form = Json { ignoreUnknownKeys = true }.decodeFromString<OpenCodeFormRequest>(
            """
            {"id":"form_1","sessionID":"ses_voice","title":"Which database?","fields":[
              {"key":"db","type":"string","title":"Database","options":[{"value":"pg","label":"Postgres"},{"value":"sqlite","label":"SQLite"}]}]}
            """.trimIndent(),
        )
        val store = h.store(voice) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true)
            val respond: suspend (List<VoiceDelegator.InputItem>) -> VoiceDelegator.Response = respond@{ input ->
                val outputs = input.filterIsInstance<VoiceDelegator.InputItem.ToolOutput>()
                val last = outputs.lastOrNull() ?: return@respond response("r1", listOf(call("t1", "list_requests", "{}")))
                when (last.callID) {
                    "t1" -> {
                        listed.setValue(last.output)
                        response(
                            "r2",
                            listOf(
                                call("t2", "reply_permission", """{"request_id":"perm_1","decision":"once"}"""),
                                call("t3", "answer_question", """{"form_id":"form_1","answers":{"db":"pg"}}"""),
                                call("t4", "send_message", """{"text":"Update the docs","delivery":"queue"}"""),
                            ),
                        )
                    }
                    "t4" -> response(
                        "r3",
                        listOf(
                            call("t5", "steer_queued", """{"message_id":"msg_docs"}"""),
                            call("t6", "end_call", "{}"),
                        ),
                    )
                    else -> response("r4", text = "Done. Talk soon!")
                }
            }
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, ids, _ ->
                    assertEquals(listOf(session.id), ids)
                    OpenCodePendingRequests(
                        permissions = listOf(OpenCodePermissionRequest(id = "perm_1", sessionID = session.id, action = "bash", resources = listOf("swift test"), save = null)),
                        forms = listOf(form),
                    )
                },
                replyPermission = { _, _, id, reply, _ -> replies.append("$id:${reply.name.lowercase()}") },
                replyForm = { _, _, id, answers -> replies.append("$id:${answers["db"] == OpenCodeFormValue.Str("pg")}") },
                sendDeliveredPrompt = { _, _, _, delivery ->
                    deliveries.append("send:${delivery?.rawValue ?: "-"}")
                    "msg_docs"
                },
                setPendingInputDelivery = { _, _, id, delivery -> deliveries.append("$id:${delivery.rawValue}") },
                moveToBackground = { _, id -> deliveries.append("background:$id") },
            )
            voiceCall { copy(completeFunction = { _, _ -> }, end = {}).delegating(respond) }
        }
        store.exhaustive = false

        store.send(Action.CallEvent(VoiceCallEvent.ServerDelegation(callID = "d1")))
        store.receive(Action.DelegatorFinished::class)
        // It read the requests before acting on them.
        assertTrue(listed.value.contains("perm_1") && listed.value.contains("swift test") && listed.value.contains("Postgres"))
        assertEquals(listOf("perm_1:once", "form_1:true"), replies.value)
        // Queuing leaves the running work be; steering moves it to the background first.
        assertEquals(listOf("send:queue", "background:${session.id}", "msg_docs:steer"), deliveries.value)
        // The goodbye plays, then the call ends.
        assertEquals(State.Phase.EndingAfterGoodbye(spoken = false), store.state.phase)
        store.send(Action.CallEvent(VoiceCallEvent.AssistantDone(itemID = "a1", text = "Done. Talk soon!")))
        store.send(Action.CallEvent(VoiceCallEvent.AssistantPlaybackFinished))
        store.receive(Action.EndTapped::class)
        assertTrue(store.state.phase.isTerminal)
    }

    private fun delegatorCall(on: OpenCodeSession): State =
        h.state { State(server, on) }.live().copy(usesDelegator = true, connectedAt = date(0))

    /**
     * A switch keeps the call's conversation, and the voice model is told
     * only about the new session: never this call's own lines as "previous".
     */
    @Test fun aSwitchKeepsTheConversationWithoutReplayingIt() = voiceTest { harness ->
        h = harness
        val a = VoiceFeatureTestSupport.session("ses_keep_a", "Upload", "/Users/kit/a")
        val b = VoiceFeatureTestSupport.session("ses_keep_b", "Docs", "/Users/kit/b")
        // An earlier call on B.
        h.state {
            shared(VoiceSharedKeys.voiceTranscripts).update {
                it + ("${server.id}:${b.id}" to VoiceTranscriptRecord(endedAt = date(-500), tail = listOf("user: Old docs question"), log = emptyList()))
            }
        }
        var voice = delegatorCall(on = a).copy(connectedAt = date(0))
        voice = record(voice, VoiceTranscriptTail.Role.user, "Check the upload test", id = "u1")
        voice = record(voice, VoiceTranscriptTail.Role.assistant, "Checking.")
        voice = voice.copy(
            captions = listOf(State.Caption(id = "user-u1", role = VoiceTranscriptTail.Role.user, text = "Check the upload test")),
            liveAssistantText = "Checking",
            liveAssistantItemID = "a1",
        )

        voice = h.state { voice.retarget(b, endedAt = date(10)) }
        assertEquals(listOf("user: Check the upload test", "assistant: Checking."), voice.tail.lines)
        assertEquals(1, voice.captions.size)
        assertTrue("A reply being spoken carries on", voice.liveAssistantText == "Checking" && voice.liveAssistantItemID == "a1")
        // Neither B's earlier call nor this call's lines are replayed on a switch.
        assertTrue(voice.instructionsContext.previousTail.isEmpty())
        val delta = VoiceInstructions.buildRetarget(voice.instructionsContext, engine = VoiceEngine.gptLive)
        assertTrue(delta.contains("\"Docs\""))
        assertFalse(delta.contains("Old docs question"))
        assertFalse(delta.contains("Check the upload test"))

        // Back to A, which this call already wrote to: nothing is replayed.
        voice = h.state { voice.retarget(a, endedAt = date(20)) }
        assertTrue(voice.instructionsContext.previousTail.isEmpty())
        val back = VoiceInstructions.buildRetarget(voice.instructionsContext, engine = VoiceEngine.gptLive)
        assertFalse(back.contains("Previous voice call tail"))
        assertEquals(listOf("user: Check the upload test", "assistant: Checking."), voice.tail.lines.takeLast(2))
        // Each session it visited keeps only user turns for the next call's continuity.
        val transcripts = h.state { shared(VoiceSharedKeys.voiceTranscripts).value }
        assertEquals(listOf("user: Check the upload test"), transcripts["${server.id}:${b.id}"]?.tail)
    }

    @Test fun aTopicFromOneSessionFollowsTheCallAndIsNeverReplayed() = voiceTest { harness ->
        h = harness
        val home = VoiceFeatureTestSupport.session("ses_topic_home", "Home", "/Users/kit/home")
        val ios = VoiceFeatureTestSupport.session("ses_topic_ios", "OpenCode iOS", "/Users/kit/ios")
        var voice = delegatorCall(on = home)
        voice = record(voice, VoiceTranscriptTail.Role.user, "Which 1Password item has the Mac rental checkout password?", id = "u1")
        voice = record(voice, VoiceTranscriptTail.Role.assistant, "The rental login is in the Personal vault.")
        val captured = voice.tail.capture()
        voice = voice.copy(tail = captured.tail.accept(captured.capture))
        voice = h.state { voice.retarget(ios, endedAt = date(50)) }
        // Nothing from this call is handed back to the voice model as history.
        assertFalse(VoiceInstructions.buildRetarget(voice.instructionsContext, engine = VoiceEngine.gptLive).contains("1Password"))

        // The topic from Home is still the conversation on the other session.
        assertTrue(voice.tail.lines.contains("user: Which 1Password item has the Mac rental checkout password?"))

        // Back on Home: no replay of what this call already said.
        val back = h.state { voice.retarget(home, endedAt = date(60)) }
        assertTrue(back.instructionsContext.previousTail.isEmpty())
        assertFalse(VoiceInstructions.buildRetarget(back.instructionsContext, engine = VoiceEngine.gptLive).contains("1Password"))
        assertEquals(1, back.tail.lines.count { it.contains("1Password") })
    }

    /**
     * Build 685, 22:56:57Z (14:56:57 PT): opening HEX mid-call replayed a stale
     * `VoiceTranscriptRecord.tail` containing Voice's own unbacked lines ("And
     * that text to Olive went through, delivered"), causing Voice to answer
     * "No. Just to Olive." nine seconds later without delegating.
     */
    @Test fun oliveHexRetargetNeverReplaysPastVoiceClaimsOrPreviousCallTail() = voiceTest { harness ->
        h = harness
        val ios = OpenCodeSession(id = "ses_olive_ios", serverTitle = "OPENCODE IOS", projectID = "p", directory = "/tmp/ios", updatedAt = VoiceFeatureTestSupport.distantPast)
        val hex = OpenCodeSession(id = "ses_olive_hex", serverTitle = "HEX", projectID = "p", directory = "/tmp/hex", updatedAt = VoiceFeatureTestSupport.distantPast)

        val staleEndedAt = Instant.parse("2026-11-01T22:00:00Z")
        val connectedAt = Instant.parse("2026-11-01T22:50:00Z")
        val retargetAt = Instant.parse("2026-11-01T22:56:57Z")

        val staleOliveTail = listOf(
            "user: Did you check notifications?",
            "assistant: Still waiting on it. I don't have an answer yet on the text to your wife or the notifications.",
            "assistant: Quick recap, we've got that text still unconfirmed, and your feedback already sent to the OPENCODE IOS session.",
            "assistant: And that text to Olive went through,",
            "assistant: delivered. I'm watching for her reply, and if she answers, I'll send it to you. That's the update.",
            "Voice: No. Just to Olive.",
        )

        h.state {
            shared(VoiceSharedKeys.voiceTranscripts).update {
                it + ("${server.id}:${hex.id}" to VoiceTranscriptRecord(endedAt = staleEndedAt, tail = staleOliveTail, log = emptyList()))
            }
        }

        // 1. Mid-call retarget to HEX at 22:56:57Z (14:56:57 PT): neither the delta nor full instructions replay the previous call's raw lines.
        var voice = delegatorCall(on = ios).copy(connectedAt = connectedAt)
        voice = h.state { voice.retarget(hex, reason = "you opened it", endedAt = retargetAt) }
        assertTrue(voice.instructionsContext.previousTail.isEmpty())

        val contextWithRecent = voice.instructionsContext.copy(recentMessages = listOf("assistant: HEX build is green."))
        val retargetDelta = VoiceInstructions.buildRetarget(contextWithRecent, engine = VoiceEngine.gptLive)
        val retargetFull = VoiceInstructions.build(contextWithRecent, engine = VoiceEngine.gptLive)
        assertTrue(retargetDelta.contains("\"HEX\""))
        assertTrue(retargetDelta.contains("HEX build is green."))
        assertFalse(retargetDelta.contains("Olive"))
        assertFalse(retargetDelta.contains("went through"))
        assertFalse(retargetDelta.contains("Did you check notifications?"))
        assertFalse(retargetDelta.contains("Previous voice call tail"))
        assertFalse(retargetFull.contains("Olive"))
        assertFalse(retargetFull.contains("# Previous Voice Call"))

        // Even if a caller passes raw `previousTail` directly to `buildRetarget`, switching sessions never replays it.
        val rawContext = contextWithRecent.copy(previousTail = staleOliveTail)
        assertFalse(VoiceInstructions.buildRetarget(rawContext, engine = VoiceEngine.gptLive).contains("Olive"))
        assertFalse(VoiceInstructions.buildRetarget(rawContext, engine = VoiceEngine.realtime).contains("Olive"))

        // 2. Starting a brand-new call directly on HEX strips Voice's own `assistant:` / `Voice:` lines from continuity.
        val freshOnHex = h.state { State(server, hex) }
        assertEquals(listOf("user: Did you check notifications?"), freshOnHex.previousCallTail)
        val startPrompt = VoiceInstructions.build(freshOnHex.instructionsContext, engine = VoiceEngine.gptLive)
        assertTrue(startPrompt.contains("# Previous Voice Call"))
        assertTrue(startPrompt.contains("silent memory"))
        assertTrue(startPrompt.contains("never completed outcomes or facts"))
        assertTrue(startPrompt.contains("user: Did you check notifications?"))
        assertFalse(startPrompt.contains("Olive"))
        assertFalse(startPrompt.contains("went through"))

        // 3. Saving a transcript record strips `assistant:` and `Voice:` lines from `tail` while keeping `log`.
        val saved = VoiceTranscriptRecord(endedAt = staleEndedAt, tail = emptyList(), log = emptyList())
            .appending(tail = staleOliveTail, log = staleOliveTail, endedAt = retargetAt)
        assertEquals(listOf("user: Did you check notifications?"), saved.tail)
        assertEquals(staleOliveTail.size, saved.log.size)
    }
}
