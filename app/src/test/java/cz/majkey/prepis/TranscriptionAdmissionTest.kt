package cz.majkey.prepis

import android.content.Context
import android.annotation.SuppressLint
import android.database.sqlite.SQLiteDatabase
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
import androidx.work.impl.WorkManagerImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    private var workError: String? = null

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
                            return Result.success(workError?.let { workDataOf(TranscriptionWorker.KEY_ERROR to it) }
                                ?: androidx.work.Data.EMPTY)
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
    fun `local metadata changes remain suppressed after completion and pruning`() =
        verifyAdmission(TranscriptionProfile.LOCAL_CZECH)

    @Test
    fun `cloud metadata changes remain suppressed after completion and pruning`() =
        verifyAdmission(TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH))

    @Test
    fun `failed cloud work stays suppressed after pruning and allows an explicitly confirmed retry`() {
        workError = "Synthetic failure"
        verifyAdmission(TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH))
    }

    @Test
    fun `full permanent history allows a known manual retry but rejects a new identity`() = runBlocking {
        val profile = TranscriptionProfile.LOCAL_CZECH
        val recordings = (0 until MAX_FOLDER_DOCUMENTS).map {
            Recording(Uri.parse("content://test/full/$it"), "$it.m4a", 1, 1)
        }
        FolderStore(context).save(Uri.parse("content://test/tree/recordings"))
        TranscriptionAdmissions(context).baseline(recordings)
        TranscriptionQueue(context).enqueue(recordings.first(), profile)
        val new = Recording(Uri.parse("content://test/full/new"), "new.m4a", 1, 1)
        assertFailsWith<TranscriptionAdmissionLimitException> { TranscriptionQueue(context).enqueue(new, profile) }
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosByTag(TranscriptionWorker.GLOBAL_TAG).get().size)
    }

    @Test
    fun `saved transcripts get receipts before the skip and cannot be reused for changed metadata`() = runBlocking {
        val local = TranscriptionProfile.LOCAL_CZECH
        val recording = Recording(Uri.parse("content://test/saved"), "saved.m4a", 1, 1)
        TranscriptionAdmissions(context).initialize()
        TranscriptStore(context).write(recording.key, "Original transcript", local)
        TranscriptionQueue(context).enqueueMissing(listOf(recording), local)
        val changed = Recording(recording.uri, recording.name, 2, 2)
        TranscriptionQueue(context).enqueueMissing(listOf(changed), local)
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(TranscriptionWorker.GLOBAL_TAG).get().isEmpty())
        assertEquals("Original transcript", TranscriptStore(context).read(recording.key, local))
        assertEquals(null, TranscriptStore(context).read(changed.key, local))
    }

    @Test
    fun `failed receipt writes enqueue nothing in either queue and retry only after storage recovers`() = runBlocking {
        val local = TranscriptionProfile.LOCAL_CZECH
        val cloud = TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH)
        val recording = Recording(Uri.parse("content://test/receipt-failure"), "test.m4a", 1, 1)
        TranscriptionAdmissions(context).initialize()
        val path = context.getDatabasePath("transcription-admissions.db").path
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER fail_receipt BEFORE INSERT ON receipts BEGIN SELECT RAISE(ABORT, 'write failed'); END")
        }
        assertFailsWith<TranscriptionAdmissionStorageException> { TranscriptionQueue(context).enqueue(recording, local) }
        assertFailsWith<TranscriptionAdmissionStorageException> {
            CloudTranscriptionQueue(context).enqueue(recording, cloud, userConfirmed = true)
        }
        val manager = WorkManager.getInstance(context)
        assertTrue(manager.getWorkInfosByTag(TranscriptionWorker.GLOBAL_TAG).get().isEmpty())
        assertTrue(manager.getWorkInfosByTag(CloudTranscriptionWorker.GLOBAL_TAG).get().isEmpty())
        SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER fail_receipt") }
        TranscriptionQueue(context).enqueueMissing(listOf(recording), local)
        assertEquals(1, manager.getWorkInfosByTag(recording.admissionTag(local)).get().size)
    }

    @Test
    fun `manual cloud admission requires explicit confirmation even with automatic consent`() = runBlocking {
        val profile = TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH)
        val recording = Recording(Uri.parse("content://test/confirmed"), "test.m4a", 1, 1)
        TranscriptionSettingsStore(context).allowCloud(CloudProvider.OPENAI)
        assertFailsWith<IllegalArgumentException> { CloudTranscriptionQueue(context).enqueue(recording, profile) }
        assertFalse(TranscriptionAdmissions(context).contains(recording, profile))
        CloudTranscriptionQueue(context).enqueue(recording, profile, userConfirmed = true)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosByTag(recording.admissionTag(profile)).get().size)
    }

    @Test
    @SuppressLint("RestrictedApi") // Inject an actual WorkManager database write failure; production code uses the public API.
    fun `enqueue insertion failure keeps the durable receipt and requires manual retry`() = runBlocking {
        val profile = TranscriptionProfile.LOCAL_CZECH
        val recording = Recording(Uri.parse("content://test/enqueue-failure"), "test.m4a", 1, 1)
        val manager = WorkManager.getInstance(context)
        // Room and SQLite support APIs are runtime-only WorkManager dependencies. Reflection avoids adding a test dependency.
        val workDatabase = (manager as WorkManagerImpl).workDatabase
        val helper = Class.forName("androidx.room.RoomDatabase").getMethod("getOpenHelper").invoke(workDatabase)
        val database = Class.forName("androidx.sqlite.db.SupportSQLiteOpenHelper").getMethod("getWritableDatabase").invoke(helper)
        val execSql = Class.forName("androidx.sqlite.db.SupportSQLiteDatabase").getMethod("execSQL", String::class.java)
        execSql.invoke(database, "CREATE TRIGGER fail_enqueue BEFORE INSERT ON WorkSpec BEGIN SELECT RAISE(ABORT, 'write failed'); END")
        try {
            assertFailsWith<TranscriptionAdmissionStorageException> {
                TranscriptionQueue(context).enqueue(recording, profile, automatic = true)
            }
        } finally {
            execSql.invoke(database, "DROP TRIGGER fail_enqueue")
        }
        assertTrue(manager.getWorkInfosByTag(TranscriptionWorker.GLOBAL_TAG).get().isEmpty())
        assertTrue(TranscriptionAdmissions(context).contains(recording, profile))
        TranscriptionQueue(context).enqueueMissing(listOf(recording), profile)
        assertTrue(manager.getWorkInfosByTag(TranscriptionWorker.GLOBAL_TAG).get().isEmpty())
        TranscriptionQueue(context).enqueue(recording, profile)
        assertEquals(1, manager.getWorkInfosByTag(recording.admissionTag(profile)).get().size)
    }

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
                    else CloudTranscriptionQueue(context).enqueue(recording, profile, userConfirmed = true)
                    true
                } catch (_: TranscriptionQueueFullException) {
                    false
                }
            }
        }.awaitAll()
        assertEquals(1, admitted.count { it })
        val winner = listOf(local, cloud)[admitted.indexOf(true)]
        if (winner.model.isLocal) TranscriptionQueue(context).enqueue(recording, winner)
        else CloudTranscriptionQueue(context).enqueue(recording, winner, userConfirmed = true)

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
                if (profile.model.isLocal) TranscriptionQueue(context).enqueue(changed, profile, automatic = true)
                else CloudTranscriptionQueue(context).enqueue(changed, profile, automatic = true)
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
        manager.pruneWork().result.get()
        assertTrue(manager.getWorkInfosByTag(recording.admissionTag(profile)).get().isEmpty())

        val latest = recording.copy(size = 99, lastModified = 99,
            key = recordingKey(recording.uri.toString(), 99, 99))
        val tag = if (profile.model.isLocal) {
            TranscriptionQueue(context).enqueueMissing(listOf(latest), profile)
            TranscriptionWorker.recordingTag(latest.key, profile)
        } else {
            CloudTranscriptionQueue(context).enqueueMissing(listOf(latest), profile)
            CloudTranscriptionWorker.recordingTag(latest.key, profile)
        }
        assertTrue(manager.getWorkInfosByTag(tag).get().isEmpty())
        assertTrue(manager.getWorkInfosByTag(recording.admissionTag(profile)).get().isEmpty())
        assertTrue(TranscriptionAdmissions(context).contains(latest, profile))
        assertEquals(RecordingStatus.NOT_QUEUED,
            latest.toRow(emptyList(), emptyList(), profile, CloudProvider.entries.toSet(), false, false).status)
        if (profile.model.isLocal) TranscriptionQueue(context).enqueue(latest, profile)
        else CloudTranscriptionQueue(context).enqueue(latest, profile, userConfirmed = true)
        assertEquals(1, manager.getWorkInfosByTag(tag).get().size)
    }
}
