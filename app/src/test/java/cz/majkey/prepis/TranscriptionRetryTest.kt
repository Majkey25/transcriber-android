package cz.majkey.prepis

import android.content.Context
import android.net.Uri
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.assertContentEquals
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
class TranscriptionRetryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val recording = Recording(Uri.parse("content://test/audio"), "test.m4a", 1, 1)

    @Before
    fun queueWithFailingWorkers() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): Worker =
                    object : Worker(appContext, workerParameters) {
                        override fun doWork(): Result = Result.success(workDataOf(TranscriptionWorker.KEY_ERROR to "Retry failed"))
                    }
            }).build())
    }

    @After
    fun closeQueue() { WorkManagerTestInitHelper.closeWorkDatabase() }

    @Test
    fun `local and cloud failed retries keep the saved transcript readable`() = runBlocking {
        val store = TranscriptStore(context)
        val profiles = listOf(TranscriptionProfile.LOCAL_CZECH,
            TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH))
        for (profile in profiles) {
            val name = legacyTranscriptFileName(recording.key, profile) ?: transcriptFileName(recording.key, profile)
            val original = context.filesDir.resolve("transcripts/$name")
            check(original.parentFile!!.mkdirs() || original.parentFile!!.isDirectory)
            val bytes = "Původní přepis.\n".toByteArray(Charsets.UTF_8)
            original.writeBytes(bytes)
            if (profile.model.isLocal) {
                TranscriptionQueue(context).enqueue(recording, profile)
            } else {
                CloudTranscriptionQueue(context).enqueue(recording, profile)
            }
            assertContentEquals(bytes, original.readBytes())
            assertEquals(String(bytes, Charsets.UTF_8), store.read(recording.key, profile))
            val tag = if (profile.model.isLocal) TranscriptionWorker.recordingTag(recording.key, profile)
                else CloudTranscriptionWorker.recordingTag(recording.key, profile)
            val work = WorkManager.getInstance(context).getWorkInfosByTag(tag).get()
            assertTrue(work.isNotEmpty())
            if (!profile.model.isLocal) {
                WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(work.single().id)
            }
            val finished = WorkManager.getInstance(context).getWorkInfosByTag(tag).get()
            assertEquals(RecordingStatus.ERROR_WITH_TRANSCRIPT, row(profile, finished).status)

            assertFailsWith<IllegalArgumentException> { store.write(recording.key, " ", profile) }
            assertContentEquals(bytes, original.readBytes())
            store.write(recording.key, "Replacement succeeded.", profile)
            assertEquals("Replacement succeeded.", store.read(recording.key, profile))
        }
    }

    @Test
    fun `retry progress and failure take priority over saved text and setup`() {
        for (profile in listOf(TranscriptionProfile.LOCAL_CZECH,
            TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH))) {
            var enqueued = 0L
            fun work(state: WorkInfo.State, error: String? = null, phase: String? = null): WorkInfo = WorkInfo(
                id = UUID.randomUUID(), state = state,
                tags = setOf(if (profile.model.isLocal) TranscriptionWorker.recordingTag(recording.key, profile)
                    else CloudTranscriptionWorker.recordingTag(recording.key, profile), "$ENQUEUED_TAG_PREFIX${++enqueued}"),
                outputData = if (error == null) Data.EMPTY else workDataOf(TranscriptionWorker.KEY_ERROR to error),
                progress = if (phase == null) Data.EMPTY else workDataOf(TranscriptionWorker.KEY_PHASE to phase),
            )
            val failed = work(WorkInfo.State.SUCCEEDED, "Retry failed")
            assertEquals(RecordingStatus.WAITING, row(profile, listOf(failed, work(WorkInfo.State.ENQUEUED))).status)
            assertEquals(RecordingStatus.TRANSCRIBING, row(profile,
                listOf(failed, work(WorkInfo.State.RUNNING, phase = TranscriptionWorker.PHASE_TRANSCRIBING))).status)
            assertEquals(RecordingStatus.ERROR_WITH_TRANSCRIPT, row(profile, listOf(failed)).status)
            assertEquals(RecordingStatus.DONE, row(profile, listOf(failed, work(WorkInfo.State.SUCCEEDED))).status)
            assertEquals(RecordingStatus.DONE, row(profile, listOf(failed, work(WorkInfo.State.CANCELLED))).status)
            assertEquals(RecordingStatus.DONE, row(profile, listOf(work(WorkInfo.State.SUCCEEDED), failed)).status)
            assertEquals(RecordingStatus.DONE, row(profile, emptyList()).status)
            assertTrue(recording.toRow(emptyList(), emptyList(), profile, emptySet(), false, true).hasTranscript)
        }
    }

    private fun row(profile: TranscriptionProfile, work: List<WorkInfo>): RecordingRow = recording.toRow(
        localWork = work, cloudWork = work, profile = profile, configured = emptySet(),
        hasTranscript = true, hasProfileTranscript = true,
    )
}
