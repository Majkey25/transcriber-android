package cz.majkey.prepis

import android.content.Context
import android.net.Uri
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TranscriptionAdmissionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val finishWork = CompletableDeferred<Unit>()

    @Before
    fun initializeQueue() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    object : CoroutineWorker(appContext, workerParameters) {
                        override suspend fun doWork(): Result {
                            if (!inputData.getBoolean("test_finished", false)) finishWork.await()
                            return Result.success()
                        }
                    }
            }).build())
    }

    @After
    fun closeQueue() {
        finishWork.complete(Unit)
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test
    fun `local metadata changes coalesce while active and enqueue on the next refresh`() =
        verifyAdmission(TranscriptionProfile.LOCAL_CZECH)

    @Test
    fun `cloud metadata changes coalesce while active and enqueue on the next refresh`() =
        verifyAdmission(TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH))

    @Test
    fun `both queues share the last slot and count legacy jobs once without counting finished work`() = runBlocking {
        val manager = WorkManager.getInstance(context)
        val seeds = (0 until MAX_FOLDER_DOCUMENTS - 2).map { index ->
            OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInitialDelay(1, TimeUnit.DAYS)
                .addTag(if (index % 2 == 0) TranscriptionWorker.GLOBAL_TAG else CloudTranscriptionWorker.GLOBAL_TAG)
                .apply { if (index == 0) addTag(CloudTranscriptionWorker.GLOBAL_TAG) }
                .build()
        }
        val blocked = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .addTag(TranscriptionWorker.GLOBAL_TAG).build()
        val finished = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf("test_finished" to true))
            .addTag(CloudTranscriptionWorker.GLOBAL_TAG).build()
        manager.enqueue(finished).result.get()
        withTimeout(5_000) {
            while (!manager.getWorkInfoById(finished.id).get()!!.state.isFinished) delay(10)
        }
        manager.beginWith(seeds.first()).then(blocked).enqueue().result.get()
        manager.enqueue(seeds.drop(1)).result.get()
        assertEquals(WorkInfo.State.BLOCKED, manager.getWorkInfoById(blocked.id).get()!!.state)

        val local = TranscriptionProfile.LOCAL_CZECH
        val cloud = TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH)
        val recording = Recording(Uri.parse("content://test/last-slot"), "last.m4a", 1, 1)
        val admitted = listOf(local, cloud).map { profile ->
            async(Dispatchers.IO) {
                try {
                    if (profile.model.isLocal) TranscriptionQueue(context).enqueue(recording, profile)
                    else CloudTranscriptionQueue(context).enqueue(recording, profile)
                    true
                } catch (_: TranscriptionQueueFullException) {
                    false
                }
            }
        }.awaitAll()
        assertEquals(1, admitted.count { it })
        val winner = listOf(local, cloud)[admitted.indexOf(true)]
        if (winner.model.isLocal) TranscriptionQueue(context).enqueue(recording, winner)
        else CloudTranscriptionQueue(context).enqueue(recording, winner)

        val next = Recording(Uri.parse("content://test/next-slot"), "next.m4a", 1, 1)
        assertFailsWith<TranscriptionQueueFullException> {
            TranscriptionQueue(context).enqueueMissing(listOf(next), local)
        }
        assertFailsWith<TranscriptionQueueFullException> {
            CloudTranscriptionQueue(context).enqueueMissing(listOf(next), cloud)
        }
        val activeIds = listOf(TranscriptionWorker.GLOBAL_TAG, CloudTranscriptionWorker.GLOBAL_TAG)
            .flatMap { manager.getWorkInfosByTag(it).get() }
            .filterNot { it.state.isFinished }.map { it.id }.toSet()
        assertEquals(MAX_FOLDER_DOCUMENTS, activeIds.size)
        assertTrue(activeIds.containsAll(seeds.map { it.id } + blocked.id))

        manager.cancelWorkById(seeds.last().id).result.get()
        TranscriptionQueue(context).enqueueMissing(listOf(next), local)
        assertEquals(1, manager.getWorkInfosByTag(next.admissionTag(local)).get().size)
    }

    private fun verifyAdmission(profile: TranscriptionProfile) = runBlocking {
        val recording = Recording(Uri.parse("content://test/changing-audio"), "test.m4a", 1, 1)
        val manager = WorkManager.getInstance(context)
        (1L..8L).map { version ->
            async(Dispatchers.IO) {
                val changed = recording.copy(size = version, lastModified = version,
                    key = recordingKey(recording.uri.toString(), version, version))
                if (profile.model.isLocal) TranscriptionQueue(context).enqueue(changed, profile)
                else CloudTranscriptionQueue(context).enqueue(changed, profile)
            }
        }.awaitAll()
        val admitted = manager.getWorkInfosByTag(recording.admissionTag(profile)).get()
        assertEquals(1, admitted.size)
        assertTrue(!admitted.single().state.isFinished)
        if (!profile.model.isLocal) {
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(admitted.single().id)
        }
        finishWork.complete(Unit)
        withTimeout(5_000) {
            while (!manager.getWorkInfoById(admitted.single().id).get()!!.state.isFinished) delay(10)
        }

        val latest = recording.copy(size = 99, lastModified = 99,
            key = recordingKey(recording.uri.toString(), 99, 99))
        val tag = if (profile.model.isLocal) {
            TranscriptionQueue(context).enqueueMissing(listOf(latest), profile)
            TranscriptionWorker.recordingTag(latest.key, profile)
        } else {
            CloudTranscriptionQueue(context).enqueueMissing(listOf(latest), profile)
            CloudTranscriptionWorker.recordingTag(latest.key, profile)
        }
        assertEquals(1, manager.getWorkInfosByTag(tag).get().size)
        assertEquals(2, manager.getWorkInfosByTag(recording.admissionTag(profile)).get().size)
    }
}
