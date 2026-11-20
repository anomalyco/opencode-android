package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The `LinkPreviewTests` case that detects bare URLs: link detection runs the framework's
 * `Patterns.WEB_URL`, which only Robolectric provides on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkPreviewFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun extractionFindsLinksInOrderOnceAndSkipsPrivateOnes() {
        val blocks = with(LinkBlocks) {
            listOf(
                paragraph(
                    0,
                    text("Read "), link("this post", "https://x.com/example_dev/status/1"), text(" and the "),
                    link("same one", "https://twitter.com/example_dev/status/1?s=20"),
                    text(".\nThe dev server is at http://localhost:5173."),
                ),
                paragraph(1, link("A guide", "https://blog.example.com/guide")),
                paragraph(2, text("Bare: https://docs.example.org/springs.")),
                other(3),
                paragraph(4, text("See https://github.com/anomalyco/opencode/pull/12 too.")),
            )
        }
        assertEquals(
            listOf(
                "https://x.com/example_dev/status/1",
                "https://blog.example.com/guide",
                "https://docs.example.org/springs",
                "https://github.com/anomalyco/opencode/pull/12",
            ),
            LinkPreviewExtractor.targets(blocks).map { it.key },
        )
        // Bare and Markdown links keep their reading order; a URL in inline code is code.
        val mixed = with(LinkBlocks) {
            listOf(
                paragraph(
                    0,
                    text("First https://one.example.com/a, then "), link("two", "https://two.example.com/b"), text(", and "),
                    code("https://code.example.com/c"), text(", then https://three.example.com/d."),
                ),
            )
        }
        assertEquals(
            listOf("https://one.example.com/a", "https://two.example.com/b", "https://three.example.com/d"),
            LinkPreviewExtractor.targets(mixed).map { it.key },
        )
        assertTrue(LinkPreviewExtractor.targets(listOf(LinkBlocks.paragraph(0, LinkBlocks.text("Run "), LinkBlocks.code("curl https://api.example.com/v1")))).isEmpty())
        val many = LinkBlocks.paragraph(
            0,
            *(1..10).flatMap { listOf(LinkBlocks.link("$it", "https://example.com/$it"), LinkBlocks.text(" ")) }.toTypedArray(),
        )
        assertEquals(4, LinkPreviewExtractor.targets(listOf(many), limit = 4).size)
    }

    @Test
    fun labeledTargetsKeepEachLinksFirstWholeLabel() {
        val docs = "https://docs.example.com/start"
        val block = with(LinkBlocks) {
            paragraph(
                0,
                text("See "), link("the ", docs), link("OpenCode", docs), link(" docs", docs), text(", "),
                link("  Guide \n", "https://guide.example.com/a"), text(", "),
                link("https://url.example.com/b", "https://url.example.com/b"), text(", "),
                link("host.example.com", "https://host.example.com/c"), text(", "),
                link(" ", "https://blank.example.com/d"), text(", "),
                link("again", docs), text(" and https://bare.example.com/e."),
            )
        }
        assertEquals(
            listOf(
                docs to "the OpenCode docs",
                "https://guide.example.com/a" to "Guide",
                "https://url.example.com/b" to null,
                "https://host.example.com/c" to null,
                "https://blank.example.com/d" to null,
                "https://bare.example.com/e" to null,
            ),
            LinkPreviewExtractor.labeledTargets(listOf(block)).map { it.first.key to it.second },
        )
    }
}

/** The `LinkPreviewStoreTests` case that extracts bare URLs (`Patterns.WEB_URL`, under Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LinkPreviewStoreFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    private val post = LinkPreviewTarget.XPost(handle = "example_dev", id = "1844")

    @Test
    fun targetsAreExtractedOncePerReply() = runTest {
        onMain {
            val store = LinkPreviewStore(client = LinkPreviewClient())
            val blocks = listOf(LinkBlocks.paragraph(0, LinkBlocks.text("See https://x.com/example_dev/status/1844")))
            assertEquals(listOf(post), store.targets(blocks, entryID = "reply-end-1"))
            assertEquals(listOf(post), store.targets(blocks, entryID = "reply-end-1"))
            assertTrue("Different blocks extract again", store.targets(emptyList(), entryID = "reply-end-1").isEmpty())
            store.close()
        }
    }
}
