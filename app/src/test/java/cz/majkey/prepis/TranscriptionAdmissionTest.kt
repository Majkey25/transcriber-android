package cz.majkey.prepis

import android.content.Context
import android.net.Uri
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
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
                            finishWork.await()
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
