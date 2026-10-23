package cz.majkey.prepis

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LocalProcessTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val source = File(context.cacheDir, "local-audio-fixture.wav")
    private val mainPid = Process.myPid()

    @Before
    fun prepare() {
        LocalAudioTestProvider.reset()
        File(context.cacheDir, "local-bind-fixture").delete()
        writeWave()
    }

    @After
    fun cleanup() {
        LocalAudioTestProvider.reset()
        source.delete()
        File(context.cacheDir, "local-bind-fixture").delete()
    }

    @Test
    fun exactSnapshotUsesOneProviderOpenForTwoRealDecodingPasses() = runBlocking {
        assertEquals("Decoded 416000 samples.", decode("normal", size = source.length(), maxBytes = source.length()))
        assertEquals(1, LocalAudioTestProvider.opens.get())
        assertStopped()
    }

    @Test
    fun oversizedDeclaredInputRejectsBeforeProviderOpen() = runBlocking {
        expectFailure { decode("normal", size = 1025, maxBytes = 1024) }
        assertEquals(0, LocalAudioTestProvider.opens.get())
        assertStopped()
        assertEquals("Decoded 416000 samples.", decode("normal"))
    }

    @Test
    fun unknownAndLyingSizesCannotExceedTheActualCopyLimit() = runBlocking {
        for (size in listOf(-1L, 1L)) {
            val error = expectFailure { decode("oversize-stream", size = size, maxBytes = 1024) }
            assertTrue(error.message.orEmpty().contains("input exceeds the allowed size"))
            assertStopped()
        }
        assertEquals("Decoded 416000 samples.", decode("normal"))
    }

    @Test
    fun assetOffsetIsPreservedDuringSnapshotCopy() = runBlocking {
        val audio = source.readBytes()
        source.writeBytes(ByteArray(16) + audio)
        assertEquals("Decoded 416000 samples.", decode("offset"))
        assertEquals(1, LocalAudioTestProvider.opens.get())
    }

    @Test
    fun blockedProviderOpenAndPipeReadAreTerminatedAndNextJobWorks() = runBlocking {
        for (path in listOf("blocked-open", "blocked-read")) {
            val started = SystemClock.elapsedRealtime()
            expectFailure { decode(path, timeoutMs = 3000) }
            assertTrue("Deadline did not return promptly", SystemClock.elapsedRealtime() - started < 10_000)
            assertStopped()
            assertEquals(mainPid, Process.myPid())
            LocalAudioTestProvider.reset()
            assertEquals("Decoded 416000 samples.", decode("normal"))
        }
    }

    @Test
    fun cancellationKillsBlockedReadAndSerialWaitingJobUsesFreshProcess() = runBlocking {
        val blocked = async { decode("blocked-read") }
        withTimeout(10_000) { while (LocalAudioTestProvider.opens.get() == 0) delay(20) }
        val next = async { decode("normal") }
        blocked.cancelAndJoin()
        assertEquals("Decoded 416000 samples.", next.await())
        assertStopped()
    }

    @Test
    fun oneMiBResultTravelsThroughPrivateFileWithoutBinderOverflow() = runBlocking {
        val text = decode("normal", language = TranscriptionLanguage.FRENCH)
        assertEquals(MAX_TRANSCRIPT_BYTES, text.length)
        assertStopped()
    }

    @Test
    fun excessResultIsRejectedWithoutPublishingPartialOutput() = runBlocking {
        expectFailure { decode("normal", language = TranscriptionLanguage.GERMAN) }
        assertStopped()
    }

    @Test
    fun cancellationBeforeConnectedFinishesTeardownBeforeTheNextBind() = runBlocking {
        val pending = async {
            transcribeLocal(
                context, Uri.fromFile(source), source.length(), TranscriptionLanguage.AUTO,
                PendingBindTestService::class.java, timeoutMs = 20_000,
            )
        }
        withTimeout(10_000) {
            while (!File(context.cacheDir, "local-bind-fixture").exists()) delay(20)
        }
        pending.cancelAndJoin()
        assertStopped()
        assertEquals("Decoded 416000 samples.", decode("normal"))
    }

    @Test
    fun childWatchdogKillsBlockedReadWithoutAParentTimeoutOrStop() = runBlocking {
        withBoundService(CodecTestService::class.java) { remote, died ->
            remote.send(Message.obtain(null, LocalTranscriptionProtocol.START).apply {
                replyTo = Messenger(Handler(Looper.getMainLooper()))
                data.putString(LocalTranscriptionProtocol.ID, UUID.randomUUID().toString())
                data.putString(LocalTranscriptionProtocol.URI, "content://${context.packageName}.audio-test/blocked-read")
                data.putLong(LocalTranscriptionProtocol.SIZE, -1)
                data.putString(LocalTranscriptionProtocol.LANGUAGE, TranscriptionLanguage.AUTO.id)
                data.putLong(LocalTranscriptionProtocol.MAX_BYTES, 1024 * 1024)
                data.putLong(LocalTranscriptionProtocol.STARTED, SystemClock.elapsedRealtime())
                data.putLong(LocalTranscriptionProtocol.TIMEOUT, 3000)
            })
            withTimeout(10_000) { died.await() }
            assertEquals(1, LocalAudioTestProvider.opens.get())
        }
        LocalAudioTestProvider.reset()
        assertEquals("Decoded 416000 samples.", decode("normal"))
    }

    @Test
    fun ownerProcessDeathTerminatesItsBlockedAudioProcess() = runBlocking {
        withBoundService(CodecTestService::class.java) { _, childDied ->
            withBoundService(LocalOwnerTestService::class.java) { owner, ownerDied ->
                owner.send(Message.obtain(null, LocalTranscriptionProtocol.START).apply {
                    data.putString(LocalTranscriptionProtocol.URI, "content://${context.packageName}.audio-test/blocked-read")
                })
                withTimeout(10_000) { while (LocalAudioTestProvider.opens.get() == 0) delay(20) }
                owner.send(Message.obtain(null, LocalTranscriptionProtocol.STOP))
                withTimeout(10_000) {
                    ownerDied.await()
                    childDied.await()
                }
                assertEquals(mainPid, Process.myPid())
            }
        }
        LocalAudioTestProvider.reset()
        assertEquals("Decoded 416000 samples.", decode("normal"))
    }

    private suspend fun withBoundService(
        service: Class<out Service>,
        block: suspend (Messenger, CompletableDeferred<Unit>) -> Unit,
    ) = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Messenger>()
        val died = CompletableDeferred<Unit>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                binder.linkToDeath({ died.complete(Unit) }, 0)
                ready.complete(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, service), connection, Context.BIND_AUTO_CREATE))
        try {
            withTimeout(15_000) { block(ready.await(), died) }
        } finally {
            withContext(NonCancellable) { context.unbindService(connection) }
        }
    }

    private suspend fun decode(
        path: String,
        size: Long = -1,
        maxBytes: Long = 1024 * 1024,
        timeoutMs: Long = 20_000,
        language: TranscriptionLanguage = TranscriptionLanguage.AUTO,
    ): String = transcribeLocal(
        context, Uri.parse("content://${context.packageName}.audio-test/$path"), size, language,
        CodecTestService::class.java, timeoutMs, maxBytes,
    )

    private suspend fun expectFailure(block: suspend () -> Unit): IOException {
        try {
            block()
            fail("Untrusted audio should have failed")
        } catch (exception: IOException) {
            return exception
        }
        throw AssertionError("Untrusted audio should have failed")
    }

    private suspend fun assertStopped() {
        assertEquals(mainPid, Process.myPid())
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // Process-list bookkeeping can lag the Binder death already required by the client.
        withTimeout(5000) {
            while (manager.runningAppProcesses.orEmpty().any {
                    it.processName == "${context.packageName}:localTranscription" && it.pid != mainPid
                }
            ) delay(20)
        }
        assertFalse(LocalTranscriptionProtocol.directory(context).resolve(LocalTranscriptionProtocol.AUDIO).exists())
        assertFalse(LocalTranscriptionProtocol.directory(context).resolve(LocalTranscriptionProtocol.RESULT).exists())
    }

    private fun writeWave() {
        val dataBytes = 8_000 * 26 * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(8_000)
            putInt(16_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }
        source.outputStream().use {
            it.write(header.array())
            it.write(ByteArray(dataBytes))
        }
    }
}
