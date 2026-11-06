package play.ott.nativeapp

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.data.*
import play.ott.nativeapp.playback.PlaybackTestApplication
import play.ott.nativeapp.ui.AppAction
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class LocalVideoAutoplayViewModelTest {
    private class SourceReadGate(private val delegate: Mutex = Mutex()) : Mutex by delegate {
        @Volatile var observe = false
        val entered = CountDownLatch(1)
        override suspend fun lock(owner: Any?) {
            if (observe) entered.countDown()
            delegate.lock(owner)
        }
        suspend fun hold() { delegate.lock(); observe = true }
        fun release() { observe = false; delegate.unlock() }
    }

    private class Documents(val lateRows: Boolean) : LocalVideoDocuments {
        val firstEntered = CountDownLatch(1)
        val firstCancelled = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val firstDrained = CountDownLatch(1)
        val moreRows = CountDownLatch(1)
        val opens = Collections.synchronizedList(mutableListOf<String>())
        private fun document(uri: String) = LocalDocument(uri, uri.substringAfterLast('/'), "video/mp4", false, "")
        override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) {
            emit(document("content://autoplay.test/tree/root/document/blocked.mp4"))
            if (lateRows) {
                check(moreRows.await(10, TimeUnit.SECONDS))
                owner.check()
                emit(document("content://autoplay.test/tree/root/document/ready.mp4"))
                emit(document("content://autoplay.test/tree/root/document/unrequested.mp4"))
            }
        }
        override suspend fun document(source: SourceConfig, uri: String, owner: LocalVideoOwner) = document(uri)
        override suspend fun readable(source: SourceConfig, document: LocalDocument, owner: LocalVideoOwner): Boolean {
            opens.add(document.name)
            if (document.name == "blocked.mp4") {
                owner.onCancel { firstCancelled.countDown() }
                firstEntered.countDown()
                try { check(firstRelease.await(10, TimeUnit.SECONDS)) }
                finally { firstDrained.countDown() }
            }
            owner.check()
            return true
        }
    }

    private suspend fun fixture(lateRows: Boolean = false, start: Boolean = true,
        test: suspend (PlayerViewModel, Documents, SourceReadGate, CoroutineDispatcher, String?) -> Unit) {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val demand = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
        val discovery = Executors.newFixedThreadPool(4).asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val context = ApplicationProvider.getApplicationContext<Application>()
        val repository = NativeRepository(context)
        val vault: SourceVault = ReflectionHelpers.getField(repository, "vault")
        val database: CatalogDatabase = ReflectionHelpers.getField(repository, "db")
        val originalScope: CoroutineScope = ReflectionHelpers.getField(repository, "localScope")
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        val gate = SourceReadGate()
        ReflectionHelpers.setField(repository, "sourceMutex", gate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val documents = Documents(lateRows)
        val library = LocalVideoLibrary(scope, documents, discovery, demand) { _, _, _, _ -> }
        ReflectionHelpers.setField(repository, "localVideos", library)
        val model = withContext(main) { PlayerViewModel(context, repository) }
        try {
            withTimeout(5000) { model.state.first { !it.isBusy } }
            val root = Uri.parse("content://autoplay.test/tree/root")
            val registered = if (start) {
                withContext(main) { model.importLocal(listOf(root), true) }.join()
                assertTrue(documents.firstEntered.await(5, TimeUnit.SECONDS))
                null
            } else repository.addLocal(listOf(root), true).id
            test(model, documents, gate, main, registered)
        } finally {
            documents.moreRows.countDown(); documents.firstRelease.countDown()
            withContext(main) { model.viewModelScope.cancel() }
            scope.coroutineContext[Job]?.cancelAndJoin()
            originalScope.cancel()
            database.close()
            Dispatchers.resetMain()
            main.close(); demand.close(); discovery.close()
        }
    }

    @Test fun blockedFirstOriginalDoesNotHoldLaterReadyMetadataOrAutoplay() = runBlocking {
        fixture(lateRows = true) { model, documents, _, _, _ ->
            assertEquals(listOf("blocked.mp4"), model.state.value.entries.map { it.name })
            documents.moreRows.countDown()
            val state = withTimeout(5000) { model.state.first { it.playingEntry?.name == "ready.mp4" } }
            assertEquals(3, state.entries.size)
            assertTrue(documents.firstCancelled.await(5, TimeUnit.SECONDS))
            assertEquals(1L, documents.firstDrained.count)
            assertEquals(setOf("blocked.mp4", "ready.mp4"), documents.opens.toSet())
        }
    }

    @Test fun cancelScanRetiresAutomaticSelectionBeforeItsOriginalReturns() = runBlocking {
        fixture { model, documents, _, main, _ ->
            val jobs = withContext(main) { pendingJobs(model) }
            withContext(main) { model.dispatch(AppAction.CancelLocalScan) }
            assertTrue(documents.firstCancelled.await(2, TimeUnit.SECONDS))
            assertEquals(1L, documents.firstDrained.count)
            documents.firstRelease.countDown()
            assertTrue(documents.firstDrained.await(5, TimeUnit.SECONDS))
            awaitSettledPlayback(model, main, jobs)
            withContext(main) { assertNull(model.state.value.playingEntry) }
        }
    }

    @Test fun stopWhileFinalSavedSourceReadIsSuspendedCannotPublishAutoplay() = runBlocking {
        fixture { model, documents, gate, main, _ ->
            gate.hold()
            var jobs = emptyList<Job>()
            try {
                documents.firstRelease.countDown()
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
                jobs = withContext(main) { pendingJobs(model) }
                withContext(main) { model.dispatch(AppAction.StopPlayback) }
            } finally { gate.release() }
            awaitSettledPlayback(model, main, jobs)
            withContext(main) { assertNull(model.state.value.playingEntry) }
        }
    }
    private suspend fun awaitSettledPlayback(model: PlayerViewModel, main: CoroutineDispatcher, captured: List<Job>) {
        withTimeout(5000) {
            captured.forEach { it.join() }
            // A regressed load continuation could create these after the old
            // snapshot. Observe and join them before asserting no playback.
            withContext(main) { ReflectionHelpers.getField<Job?>(model, "localAutoplayJob") }?.join()
            withContext(main) { ReflectionHelpers.getField<Job?>(model, "playJob") }?.join()
        }
    }

    private fun pendingJobs(model: PlayerViewModel): List<Job> =
        listOf("loadJob", "localAutoplayJob", "playJob").mapNotNull { ReflectionHelpers.getField<Job?>(model, it) }

    private suspend fun cancelBeforeSourceRead(action: AppAction) {
        fixture(start = false) { model, documents, gate, main, id ->
            gate.hold()
            var jobs = emptyList<Job>()
            try {
                withContext(main) {
                    ReflectionHelpers.callInstanceMethod<Unit>(model, "load", ClassParameter.from(String::class.java, requireNotNull(id)),
                        ClassParameter.from(java.lang.Boolean.TYPE, true), ClassParameter.from(java.lang.Boolean.TYPE, true))
                }
                assertTrue(gate.entered.await(5, TimeUnit.SECONDS))
                jobs = withContext(main) { pendingJobs(model) }
                withContext(main) { model.dispatch(action) }
            } finally { gate.release() }
            awaitSettledPlayback(model, main, jobs)
            withContext(main) { assertNull(model.state.value.playingEntry) }
            assertTrue(documents.opens.isEmpty())
        }
    }

    @Test fun stopBeforeInitialSourceReadPreventsLaterAutostartRegistration() = runBlocking {
        cancelBeforeSourceRead(AppAction.StopPlayback)
    }

    @Test fun cancelScanBeforeInitialSourceReadPreventsLaterAutostartRegistration() = runBlocking {
        cancelBeforeSourceRead(AppAction.CancelLocalScan)
    }

}
