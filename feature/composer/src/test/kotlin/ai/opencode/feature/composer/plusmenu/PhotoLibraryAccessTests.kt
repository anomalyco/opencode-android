package ai.opencode.feature.composer.plusmenu

import ai.opencode.core.testing.TestIsolationRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * iOS `PhotoLibraryAccessTests`: the photo library stays untouched until the photo sheet asks
 * for access. `livePhotoLibraryMakesNoManagerBeforeAFetch` and `lazyPhotoManagerIsMadeOnceOnFirstUse`
 * test iOS's lazily made `PHCachingImageManager` (`LazyPhotoManager`); the MediaStore client has
 * no manager to make, so they aren't ported.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhotoLibraryAccessTests {
    @get:Rule val isolation = TestIsolationRule()

    private val main = UnconfinedTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(main)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Watching the library for changes before access was asked raises the system's access alert, so
     * it waits for the sheet to ask (`load`).
     */
    @Test fun photoLibraryNeverTouchesPhotoKitBeforeTheSheetAsks() = runTest(main) {
        var currentAccess = PhotoLibraryAccess.NotDetermined
        var requests = 0
        var watches = 0
        var fetches = 0
        var stops = 0
        val client = PhotoLibraryClient(
            access = { currentAccess },
            requestAccess = {
                requests += 1
                currentAccess = PhotoLibraryAccess.Full
                PhotoLibraryAccess.Full
            },
            recent = {
                fetches += 1
                listOf(LibraryPhoto(id = "p1", created = Instant.ofEpochSecond(1_000), modified = null))
            },
            thumbnail = { _, _, _, _ -> null },
            stopCaching = { stops += 1 },
            imageData = { null },
            changes = {
                watches += 1
                emptyFlow()
            },
            manageLimitedSelection = {},
        )
        val library = PlusPhotoLibrary(client)
        // The screen appears: nothing that would raise the access alert.
        library.prime()
        assertEquals(0, requests)
        assertEquals("Watching the library before access is asked raises the alert", 0, watches)
        assertEquals(0, fetches)
        assertTrue(library.photos.isEmpty())

        // A memory warning (what its observer runs) and the screen going away.
        library.purgeCache()
        library.stop()
        library.prime()
        assertEquals("Stopping the library's caching before access is asked touches the library", 0, stops)
        assertEquals(0, requests)
        assertEquals(0, watches)

        // The sheet opens: it asks, then reads and watches.
        library.load()
        assertEquals(1, requests)
        assertEquals(1, watches)
        assertEquals(listOf("p1"), library.photos.map { it.id })

        // Once there's access, a memory warning stops the caching as before.
        library.purgeCache()
        assertEquals(1, stops)
        library.close()
    }
}
