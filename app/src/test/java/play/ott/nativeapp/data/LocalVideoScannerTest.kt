package play.ott.nativeapp.data

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

@OptIn(ExperimentalCoroutinesApi::class)
class LocalVideoScannerTest {
    private val source = SourceConfig("local", "Videos", SourceKind.LOCAL, "content://videos/tree/root", localFolder = true)
    private fun video(name: String) = LocalDocument("content://videos/tree/root/document/$name", "$name.mp4", "video/mp4", false, "")
    private fun directory(name: String) = video(name).copy(directory = true)
    private open class Documents : LocalVideoDocuments {
        override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) = Unit
        override suspend fun document(source: SourceConfig, uri: String, owner: LocalVideoOwner) = LocalDocument(uri, "video.mp4", "video/mp4", false, "")
        override suspend fun readable(source: SourceConfig, document: LocalDocument, owner: LocalVideoOwner) = true
    }

    @Test fun firstFileIsPersistedWhileThreeDirectoryCallsRemainBlocked() = runTest {
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Pair<Boolean, List<MediaEntry>>>()
        var active = 0
        var maximum = 0
        val docs = object : Documents() {
            override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) {
                active++; maximum = maxOf(maximum, active)
                try {
                    when (uri) {
                        source.url -> emit(directory("a"))
                        directory("a").uri -> emit(directory("b"))
                        else -> { emit(directory("not-admitted")); emit(video("first")) }
                    }
                    release.await()
                    owner.check()
                } finally { active-- }
            }
            override suspend fun readable(source: SourceConfig, document: LocalDocument, owner: LocalVideoOwner): Boolean {
                active++; maximum = maxOf(maximum, active)
                active--; return true
            }
        }
        val scanner = LocalVideoScanner(backgroundScope, StandardTestDispatcher(testScheduler), docs) { _, entries, reset, owner ->
            owner.publish { writes.add(reset to entries) }
        }
        val scan = scanner.start(source, 1)
        runCurrent()
        assertTrue(scan.state.value.scanning)
        assertEquals(listOf("first.mp4"), scan.state.value.entries.map { it.name })
        assertEquals(scan.state.value.entries, writes.single().second)
        assertTrue(writes.single().first)
        assertEquals(4, maximum)
        assertEquals(3, active)
        scan.cancel(); release.complete(Unit); runCurrent()
        assertFalse(scan.state.value.scanning)
        assertTrue(scan.state.value.cancelled)
        assertEquals(1, writes.size)
    }

    @Test fun cancelledProviderCannotPublishItsLateFileAndRetainsOwnerUntilDrain() = runTest {
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val docs = object : Documents() {
            override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) {
                release.await() // Deliberately ignores cancellation, like a stuck provider.
                emit(video("late"))
            }
        }
        val scan = LocalVideoScanner(backgroundScope, StandardTestDispatcher(testScheduler), docs) { _, _, _, _ -> writes++ }.start(source, 1)
        runCurrent(); scan.cancel(); runCurrent()
        assertTrue(scan.state.value.scanning)
        release.complete(Unit); runCurrent()
        assertTrue(scan.state.value.cancelled)
        assertFalse(scan.state.value.scanning)
        assertTrue(scan.state.value.entries.isEmpty())
        assertEquals(0, writes)
    }

    @Test fun publicationFailureDoesNotExposeUnpersistedIds() = runTest {
        val docs = object : Documents() {
            override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) { emit(video("first")) }
        }
        val scan = LocalVideoScanner(backgroundScope, StandardTestDispatcher(testScheduler), docs) { _, _, _, _ -> error("disk unavailable") }.start(source, 1)
        runCurrent()
        assertTrue(scan.state.value.failed)
        assertFalse(scan.state.value.scanning)
        assertTrue(scan.state.value.entries.isEmpty())
    }

    @Test fun refreshReusesObservationButCancelledBlockedScanCannotAccumulateReplacements() = runTest {
        var opened = 0
        var release = CompletableDeferred<Unit>()
        val docs = object : Documents() {
            override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) {
                opened++; emit(video("first")); release.await(); owner.check()
            }
        }
        val library = LocalVideoLibrary(backgroundScope, docs, StandardTestDispatcher(testScheduler)) { _, _, _, _ -> }
        val observed = library.scan(source)
        runCurrent()
        library.cancel(source.id)
        repeat(10) { assertSame(observed, library.scan(source, true)) }
        runCurrent()
        assertEquals(1, opened)
        assertTrue(observed.value.cancelled)
        release.complete(Unit); runCurrent()
        release = CompletableDeferred()
        assertSame(observed, library.scan(source, true))
        runCurrent()
        assertEquals(2, opened)
        assertEquals(2L, observed.value.generation)
        assertFalse(observed.value.cancelled)
        library.cancel(source.id); release.complete(Unit); runCurrent()
    }

    @Test fun publishedFileValidatesWhileEveryBackgroundThreadIsBlocked() = runBlocking {
        val release = CountDownLatch(1)
        val published = CountDownLatch(1)
        val saturated = CountDownLatch(4)
        val background = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val demand = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val docs = object : Documents() {
            override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) {
                if (uri == source.url) {
                    emit(video("first"))
                    check(published.await(5, TimeUnit.SECONDS))
                    emit(directory("a")); emit(directory("b")); emit(video("blocked"))
                }
                saturated.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                owner.check()
            }
            override suspend fun document(source: SourceConfig, uri: String, owner: LocalVideoOwner) = video("first")
            override suspend fun readable(source: SourceConfig, document: LocalDocument, owner: LocalVideoOwner): Boolean {
                if (document.name == "blocked.mp4") {
                    saturated.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    owner.check()
                }
                return true
            }
        }
        val library = LocalVideoLibrary(scope, docs, background, demand) { _, _, _, _ -> published.countDown() }
        try {
            val state = library.scan(source)
            val ready = withTimeout(5000) { state.first { it.entries.isNotEmpty() }.entries.first() }
            assertTrue("all four background calls must be held", saturated.await(5, TimeUnit.SECONDS))
            assertTrue(withTimeout(2000) { library.validate(source, ready) })
            assertEquals(1L, release.count)
            library.cancel(source.id)
        } finally {
            release.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            background.close(); demand.close()
        }
    }

    @Test fun cancellingDemandSignalsProviderImmediatelyAndNeverReturnsLatePlayback() = runBlocking {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val docs = object : Documents() {
            override suspend fun document(source: SourceConfig, uri: String, owner: LocalVideoOwner): LocalDocument {
                owner.onCancel { cancelled.countDown() }
                entered.countDown()
                try {
                    check(release.await(10, TimeUnit.SECONDS)) // Ignore signal to exercise retained ownership.
                    return video("first")
                } finally { drained.countDown() }
            }
        }
        val library = LocalVideoLibrary(scope, docs, dispatcher, dispatcher) { _, _, _, _ -> }
        val ready = MediaEntry(localVideoId(source.id, video("first").uri), source.id, "First", video("first").uri)
        var returnedPlayback = false
        try {
            val caller = launch(Dispatchers.Default) {
                library.validate(source, ready)
                returnedPlayback = true
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(2000) { caller.cancelAndJoin() }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(1L, drained.count)
            assertTrue(scope.coroutineContext[Job]!!.children.any())
            release.countDown()
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertFalse(returnedPlayback)
        } finally {
            release.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            dispatcher.close()
        }
    }

    @Test fun uiCancellationDoesNotWaitForAdmittedDurableWrite() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val dispatcher = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val docs = object : Documents() {
            override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) { emit(video("first")) }
        }
        val library = LocalVideoLibrary(scope, docs, dispatcher) { _, _, _, owner ->
            try {
                owner.persist {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            } finally { drained.countDown() }
        }
        try {
            val observed = library.scan(source)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val retirement = scope.async { library.cancel(source.id) }
            withTimeout(2000) { retirement.await() }
            assertTrue(observed.value.cancelled)
            assertTrue(observed.value.entries.isEmpty())
            assertEquals(1L, drained.count)
            release.countDown()
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertTrue(observed.value.entries.isEmpty())
        } finally {
            release.countDown()
            scope.coroutineContext[Job]?.cancelAndJoin()
            dispatcher.close()
        }
    }

    @Test fun identityIsStableAndScopedToTheSelectedGrant() {
        val uri = video("first").uri
        assertEquals(localVideoId("folder", uri), localVideoId("folder", uri))
        assertNotEquals(localVideoId("folder", uri), localVideoId("file", uri))
        assertNotEquals(localVideoId("ab", "c"), localVideoId("a", "bc"))
    }
}
