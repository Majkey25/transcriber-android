package cz.majkey.prepis

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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val localProcessMutex = Mutex()
private var previousLocalTeardown: CompletableDeferred<Unit>? = null

internal suspend fun transcribeLocal(
    context: Context,
    uri: Uri,
    declaredSize: Long,
    language: TranscriptionLanguage,
    serviceClass: Class<out LocalTranscriptionService> = LocalTranscriptionService::class.java,
    timeoutMs: Long = LOCAL_JOB_TIMEOUT_MS,
    maxInputBytes: Long = MAX_LOCAL_INPUT_BYTES,
): String = localProcessMutex.withLock {
    if (previousLocalTeardown?.isCompleted == false) {
        throw IOException("The previous local process has not stopped. Restart the app before retrying.")
    }
    require(timeoutMs in 1..LOCAL_JOB_TIMEOUT_MS && maxInputBytes in 1..MAX_LOCAL_INPUT_BYTES)
    val appContext = context.applicationContext
    withContext(Dispatchers.Main.immediate) {
        val id = UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        val completed = CompletableDeferred<Unit>()
        val died = CompletableDeferred<Unit>()
        val connected = CompletableDeferred<Unit>()
        val teardown = CompletableDeferred<Unit>()
        val bindingReleased = AtomicBoolean()
        var remote: Messenger? = null
        var binder: IBinder? = null
        var closed = false
        var bound = false
        var reconnected = false
        lateinit var connection: ServiceConnection
        fun unbind() {
            if (bound) {
                appContext.unbindService(connection)
                bound = false
                bindingReleased.set(!reconnected)
            }
        }
        val handler = Handler(Looper.getMainLooper()) { message ->
            if (!closed && message.sendingUid == Process.myUid() &&
                message.data.getString(LocalTranscriptionProtocol.ID) == id
            ) {
                when (message.what) {
                    LocalTranscriptionProtocol.DONE -> completed.complete(Unit)
                    LocalTranscriptionProtocol.ERROR -> completed.completeExceptionally(
                        IOException(message.data.getString("error")?.take(500) ?: "Local transcription failed"),
                    )
                }
            }
            true
        }
        val reply = Messenger(handler)
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (binder != null) {
                    reconnected = true
                    completed.completeExceptionally(IOException("The local service unexpectedly reconnected"))
                    return
                }
                binder = service
                try {
                    service.linkToDeath({
                        died.complete(Unit)
                        if (bindingReleased.get()) teardown.complete(Unit)
                        completed.completeExceptionally(IOException("The local transcription process stopped"))
                    }, 0)
                    remote = Messenger(service)
                    connected.complete(Unit)
                    if (closed || completed.isCompleted) return
                    remote.send(Message.obtain(null, LocalTranscriptionProtocol.START).apply {
                        replyTo = reply
                        data.putString(LocalTranscriptionProtocol.ID, id)
                        data.putString(LocalTranscriptionProtocol.URI, uri.toString())
                        data.putLong(LocalTranscriptionProtocol.SIZE, declaredSize)
                        data.putString(LocalTranscriptionProtocol.LANGUAGE, language.id)
                        data.putLong(LocalTranscriptionProtocol.MAX_BYTES, maxInputBytes)
                        data.putLong(LocalTranscriptionProtocol.STARTED, started)
                        data.putLong(LocalTranscriptionProtocol.TIMEOUT, timeoutMs)
                    })
                } catch (exception: android.os.RemoteException) {
                    if (!service.isBinderAlive) died.complete(Unit)
                    connected.complete(Unit)
                    completed.completeExceptionally(IOException("Cannot start local transcription", exception))
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                completed.completeExceptionally(IOException("The local transcription process disconnected"))
            }

            override fun onBindingDied(name: ComponentName) {
                connected.complete(Unit)
                completed.completeExceptionally(IOException("The local transcription binding stopped"))
            }

            override fun onNullBinding(name: ComponentName) {
                connected.complete(Unit)
                completed.completeExceptionally(IOException("The local transcription service is unavailable"))
            }
        }
        var failure: Throwable? = null
        try {
            bound = appContext.bindService(Intent(appContext, serviceClass), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw IOException("Cannot bind the local transcription service")
            previousLocalTeardown = teardown
            while (true) {
                currentCoroutineContext().ensureActive()
                val remaining = timeoutMs - (SystemClock.elapsedRealtime() - started)
                if (remaining <= 0) throw LocalAudioLimitException("Local transcription timed out")
                if (withTimeoutOrNull(min(remaining, 1000)) { completed.await(); true } == true) break
            }
            if (SystemClock.elapsedRealtime() - started >= timeoutMs) {
                throw LocalAudioLimitException("Local transcription timed out")
            }
            val transcript = withContext(Dispatchers.IO) {
                val result = LocalTranscriptionProtocol.directory(appContext).resolve(LocalTranscriptionProtocol.RESULT)
                val bytes = ByteArrayOutputStream()
                result.inputStream().use { copyBounded(it, bytes, MAX_TRANSCRIPT_BYTES.toLong()) }
                bytes.toString(Charsets.UTF_8.name()).also {
                    if (it.isBlank()) throw IOException("The recording contains no recognizable speech")
                    transcriptBytes(it)
                }
            }
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() - started >= timeoutMs) {
                throw LocalAudioLimitException("Local transcription timed out")
            }
            transcript
        } catch (exception: Throwable) {
            failure = exception
            throw exception
        } finally {
            try {
                withContext(NonCancellable) {
                    closed = true
                    handler.removeCallbacksAndMessages(null)
                    try {
                        withTimeoutOrNull(5000) {
                            if (bound && binder == null) connected.await()
                            unbind()
                            try {
                                remote?.send(Message.obtain(null, LocalTranscriptionProtocol.STOP).apply {
                                    data.putString(LocalTranscriptionProtocol.ID, id)
                                })
                            } catch (_: android.os.RemoteException) {
                                // A dead Binder is also handled by the linked death callback.
                            }
                            if (binder != null) died.await()
                        }
                    } finally {
                        unbind()
                    }
                    if (binder == null || !died.isCompleted || reconnected) {
                        throw IOException("The local transcription process did not stop")
                    }
                    teardown.complete(Unit)
                    withContext(Dispatchers.IO) {
                        val directory = LocalTranscriptionProtocol.directory(appContext)
                        for (name in listOf(LocalTranscriptionProtocol.AUDIO, LocalTranscriptionProtocol.RESULT)) {
                            val file = directory.resolve(name)
                            if (file.exists() && !file.delete()) throw IOException("Cannot remove local transcription staging")
                        }
                    }
                }
            } catch (exception: Exception) {
                val original = failure
                if (original == null) throw exception
                original.addSuppressed(exception)
            }
        }
    }
}
