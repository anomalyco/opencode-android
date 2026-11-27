package ai.opencode.feature.system.notifications

import ai.opencode.core.testing.TestIsolationRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The server's push messages (`specs/v2/push.md` in the opencode repo) as the notifications they become. */
class PushNotificationTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun shown(json: String): PushShown? = PushMessage.parse(json)?.let(PushShown::from)

    private fun question(data: String): String =
        """{"v":1,"type":"form","id":"frm_1","time":1000,"sessionID":"ses_a","title":"Pick a database","body":"Which database?","data":{"formID":"frm_1","replySessionID":"ses_a","kind":"question",""" + data + "}}"

    @Test fun permissionShowsItsCommandsAndTheDecisionsTheServerListed() {
        val shown = shown(
            """{"v":1,"type":"permission","id":"per_1","time":1000,"sessionID":"ses_a","directory":"/work/shop","attention":true,
            "title":"Fix the login redirect","body":"Needs permission: shell","data":{"requestID":"per_1","replySessionID":"ses_a",
            "action":"shell","resources":["git add .","git commit -m \"x\""],"save":["git add *","git commit *"],
            "decisions":["once","always","reject"]}}""",
        )!!
        assertEquals(PushShown.Kind.Permission, shown.kind)
        assertEquals("Fix the login redirect", shown.title)
        assertEquals("Wants to run 2 commands: git add .", shown.text)
        assertEquals("Wants to run 2 commands", shown.expandedText)
        assertEquals(listOf("git add .", "git commit -m \"x\""), shown.code)
        assertEquals("Always Allow covers git add *, git commit *", shown.note)
        assertEquals(listOf(PushShown.Action.Deny, PushShown.Action.AlwaysAllow, PushShown.Action.Allow), shown.actions)
    }

    @Test fun permissionWithoutAlwaysOffersOnlyDenyAndAllow() {
        val shown = shown(
            """{"v":1,"type":"permission","id":"per_1","title":"Edit","data":{"requestID":"per_1","replySessionID":"ses_a",
            "action":"edit","resources":["README.md"],"decisions":["once","reject"]}}""",
        )!!
        assertEquals("Wants to edit a file", shown.expandedText)
        assertNull(shown.note)
        assertEquals(listOf(PushShown.Action.Deny, PushShown.Action.Allow), shown.actions)
    }

    @Test fun truncatedPermissionCanOnlyBeOpened() {
        val shown = shown(
            """{"v":1,"type":"permission","id":"per_1","title":"Deploy","data":{"requestID":"per_1","replySessionID":"ses_a",
            "action":"shell","resources":[],"resourceCount":12,"decisions":["once","always","reject"],"truncated":true}}""",
        )!!
        assertEquals("Wants to run 12 commands", shown.text)
        assertTrue(shown.code.isEmpty())
        assertEquals(listOf(PushShown.Action.Open), shown.actions)

        val idsOnly = shown(
            """{"v":1,"type":"permission","id":"per_1","title":"Deploy","data":{"requestID":"per_1","replySessionID":"ses_a","truncated":true}}""",
        )!!
        assertEquals(PushShown.Kind.Permission, idsOnly.kind)
        assertEquals("Needs permission", idsOnly.text)
        assertEquals(listOf(PushShown.Action.Open), idsOnly.actions)
    }

    @Test fun shortQuestionOffersItsFirstOptionsAsRowsAndATypedAnswer() {
        val shown = shown(
            question(
                """"fieldCount":1,"inlineReply":true,"fields":[{"key":"q0","type":"string","title":"Database",
                "description":"Which database should the new service use?","custom":true,"options":[
                {"label":"Use Postgres (Recommended)","value":"pg","description":"Matches production."},
                {"label":"Use SQLite","description":"Simplest to run locally."},{"label":"Use MySQL"},{"label":"Use Redis"}]}]""",
            ),
        )!!
        assertEquals(PushShown.Kind.Question, shown.kind)
        assertEquals("Which database should the new service use?", shown.text)
        assertEquals(
            listOf(
                PushShown.Row("Use Postgres (Recommended)", "Matches production.", "pg"),
                PushShown.Row("Use SQLite", "Simplest to run locally.", "Use SQLite"),
                PushShown.Row("Use MySQL", null, "Use MySQL"),
            ),
            shown.rows,
        )
        assertEquals(1, shown.more)
        assertEquals(listOf(PushShown.Action.AnswerTyped), shown.actions)
    }

    @Test fun questionThatCannotBeAnsweredFromTheNotificationCanOnlyBeOpened() {
        val long = shown(
            question(
                """"fieldCount":1,"inlineReply":true,"fields":[{"key":"q0","type":"string",
                "description":"1. Commit the fix\n2. Commit the test\n\nDoes this look right?","custom":true,
                "options":[{"label":"Yes"},{"label":"No"}]}]""",
            ),
        )!!
        assertEquals("Does this look right?", long.text)
        assertEquals("1. Commit the fix\n2. Commit the test\n\nDoes this look right?", long.expandedText)
        assertEquals("2 options", long.note)
        assertTrue(long.rows.isEmpty())
        assertEquals(listOf(PushShown.Action.Open), long.actions)

        val several = shown(
            question(""""fieldCount":3,"fields":[{"key":"q0","type":"string","description":"Which database?","options":[{"label":"Postgres"}]}]"""),
        )!!
        assertEquals("Question 1 of 3", several.note)
        assertTrue(several.rows.isEmpty())
        assertEquals(listOf(PushShown.Action.Open), several.actions)

        val multiselect = shown(
            question(""""fieldCount":1,"inlineReply":true,"fields":[{"key":"q0","type":"multiselect","description":"Which checks?","options":[{"label":"Lint"}]}]"""),
        )!!
        assertTrue(multiselect.rows.isEmpty())
        assertEquals(listOf(PushShown.Action.Open), multiselect.actions)
    }

    @Test fun finishedTurnShowsTheEndOfTheAnswerAsPlainText() {
        val shown = shown(
            """{"v":1,"type":"turn","id":"ses_a","time":1000,"sessionID":"ses_a","title":"Fix the login redirect","body":"Finished",
            "data":{"outcome":"succeeded","text":"### Summary\n\n- **Fixed** the `login` redirect\n- Added a [test](https://example.com)\n\nLet me know if you want anything else.",
            "background":{"shell":2,"subagent":0}}}""",
        )!!
        assertEquals(PushShown.Kind.Finished, shown.kind)
        assertEquals("Let me know if you want anything else.", shown.text)
        assertEquals("Summary\n• Fixed the login redirect\n• Added a test\nLet me know if you want anything else.", shown.expandedText)
        assertEquals("2 background jobs still running", shown.note)
        assertEquals(listOf(PushShown.Action.ReplyToSession), shown.actions)
    }

    @Test fun finishedTurnKeepsOnlyTheLastLinesThatFit() {
        val paragraphs = (1..12).joinToString("\\n\\n") { "Paragraph " + it }
        val shown = shown("""{"v":1,"type":"turn","id":"ses_a","title":"Long","data":{"outcome":"succeeded","text":"""" + paragraphs + """"}}""")!!
        assertEquals((6..12).joinToString("\n") { "Paragraph " + it }, shown.expandedText)
    }

    @Test fun failedTurnSaysWhy() {
        fun failed(error: String): String =
            shown("""{"v":1,"type":"turn","id":"ses_a","title":"Fix","body":"Failed","data":{"outcome":"failed","error":""" + error + "}}")!!.text
        assertEquals("Rate limited. Try again in 30 seconds.", failed("""{"type":"provider.rate-limit","retryAfterMs":30000}"""))
        assertEquals("Rate limited. Try again in 5 minutes.", failed("""{"type":"provider.rate-limit","retryAfterMs":300000}"""))
        assertEquals("The provider didn’t accept the sign-in", failed("""{"type":"provider.auth","message":"401"}"""))
        assertEquals("Tool crashed", failed("""{"type":"tool.execution","message":"Tool crashed"}"""))
        assertEquals("Failed", failed("""{"type":"tool.execution"}"""))
    }

    @Test fun messageThisAppDoesNotKnowShowsItsTitleAndBodyWithoutActions() {
        val newer = shown(
            """{"v":2,"type":"permission","id":"per_1","title":"Fix","body":"Needs permission: shell","data":{"requestID":"per_1",
            "replySessionID":"ses_a","action":"shell","decisions":["once"]}}""",
        )!!
        assertEquals(PushShown(PushShown.Kind.Other, "Fix", "Needs permission: shell"), newer)
        assertEquals(PushShown(PushShown.Kind.Other, "OpenCode", "Something new"), shown("""{"v":1,"type":"mystery","body":"Something new"}"""))
        assertNull(shown("""{"v":1,"type":"mystery","title":"Fix"}"""))
        assertNull(shown("""{"v":1,"type":"clear","ids":["per_1"],"time":2000}"""))
    }

    @Test fun parsingIgnoresWhatIsNotAPushMessage() {
        assertNull(PushMessage.parse("not json"))
        assertNull(PushMessage.parse("""{"title":"Fix","body":"Finished","sessionID":"ses_a","status":"succeeded"}"""))
        assertNull(PushMessage.parse("""{"type":"turn"}"""))
        val clear = PushMessage.parse("""{"v":1,"type":"clear","ids":["per_1","frm_2",3],"time":2000,"later":true}""")!!
        assertEquals(listOf("per_1", "frm_2"), clear.ids)
        assertEquals(2000L, clear.time)
        assertNull(PushMessage.parse("""{"v":1,"type":"turn","sessionID":"../etc"}""")!!.sessionID)
    }

    @Test fun ledgerKeepsTheNewerOfTwoMessagesWithOneID() {
        val (first, shows) = PushLedger().message("ref:ses_a", time = 200, now = 0)
        assertTrue(shows)
        assertFalse(first.message("ref:ses_a", time = 100, now = 1).second)
        assertTrue(first.message("ref:ses_a", time = 200, now = 1).second)
        assertTrue(first.message("ref:ses_b", time = 100, now = 1).second)
    }

    @Test fun ledgerHidesAMessageThatArrivesAfterItsClear() {
        val (cleared, removes) = PushLedger().clear("ref:per_1", time = 300, now = 0)
        assertTrue(removes)
        assertFalse(cleared.message("ref:per_1", time = 200, now = 1).second)
        assertFalse(cleared.message("ref:per_1", time = 300, now = 1).second)
        assertTrue(cleared.message("ref:per_1", time = 400, now = 1).second)
    }

    @Test fun ledgerKeepsANotificationNewerThanTheClear() {
        val (shown, _) = PushLedger().message("ref:ses_a", time = 500, now = 0)
        val (after, removes) = shown.clear("ref:ses_a", time = 300, now = 1)
        assertFalse(removes)
        assertEquals(500L, after.shown["ref:ses_a"])
        assertTrue(after.clear("ref:ses_a", time = 500, now = 2).second)
    }

    @Test fun ledgerForgetsAClearAfterADay() {
        val (cleared, _) = PushLedger().clear("ref:per_1", time = 300, now = 0)
        val (later, shows) = cleared.message("ref:per_1", time = 200, now = PushLedger.KEEP_MILLISECONDS + 1)
        assertTrue(shows)
        assertTrue(later.cleared.isEmpty())
    }
}
