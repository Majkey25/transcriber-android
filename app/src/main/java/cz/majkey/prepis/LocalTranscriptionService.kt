package cz.majkey.prepis

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.math.min

internal const val MAX_LOCAL_INPUT_BYTES = 4L * 1024 * 1024 * 1024
internal const val LOCAL_JOB_TIMEOUT_MS = 6L * 60 * 60 * 1000

internal object LocalTranscriptionProtocol {
    const val START = 1
    const val DONE = 2
    const val ERROR = 3
    const val STOP = 4
    const val ID = "id"
    const val URI = "uri"
    const val SIZE = "size"
    const val LANGUAGE = "language"
    const val MAX_BYTES = "maxBytes"
    const val STARTED = "started"
    const val TIMEOUT = "timeout"
    const val AUDIO = "audio"
    const val RESULT = "result"

    fun directory(context: Context): File = context.cacheDir.resolve("local-transcription")
}

open class LocalTranscriptionService : Service() {
    private val control = Handler(Looper.getMainLooper(), ::handleMessage)
    private val messenger = Messenger(control)
    private var session: String? = null

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handleMessage(message: Message): Boolean {
        if (message.sendingUid != Process.myUid()) return true
        val id = message.data.getString(LocalTranscriptionProtocol.ID) ?: return true
        if (message.what == LocalTranscriptionProtocol.STOP) {
            if (session == id) Process.killProcess(Process.myPid())
            return true
        }
        if (message.what != LocalTranscriptionProtocol.START) return true
        val reply = message.replyTo ?: return true
        try {
            require(UUID.fromString(id).toString() == id) { "Invalid local transcription session" }
            check(session == null) { "A local transcription is already running" }
            val uri = Uri.parse(requireNotNull(message.data.getString(LocalTranscriptionProtocol.URI)))
            require(uri.scheme == "content" || uri.scheme == "file") { "Unsupported recording URI" }
            val declaredSize = message.data.getLong(LocalTranscriptionProtocol.SIZE, -1)
            val maxBytes = message.data.getLong(LocalTranscriptionProtocol.MAX_BYTES)
            val started = message.data.getLong(LocalTranscriptionProtocol.STARTED)
            val timeout = message.data.getLong(LocalTranscriptionProtocol.TIMEOUT)
            val language = TranscriptionLanguage.fromId(
                requireNotNull(message.data.getString(LocalTranscriptionProtocol.LANGUAGE)),
            )
            require(maxBytes in 1..MAX_LOCAL_INPUT_BYTES && timeout in 1..LOCAL_JOB_TIMEOUT_MS)
            require(started in 0..SystemClock.elapsedRealtime())
            require(declaredSize >= -1)
            session = id
            reply.binder.linkToDeath({ Process.killProcess(Process.myPid()) }, 0)
            val watchdog = object : Runnable {
                override fun run() {
                    val remaining = timeout - (SystemClock.elapsedRealtime() - started)
                    if (remaining <= 0) Process.killProcess(Process.myPid())
                    else control.postDelayed(this, min(remaining, 1000))
                }
            }
            control.post(watchdog)
            Thread({
                try {
                    val remaining = timeout - (SystemClock.elapsedRealtime() - started)
                    if (remaining <= 0) throw LocalAudioLimitException("Local transcription timed out")
                    val budget = LocalAudioBudget(maxElapsedNanos = remaining * 1_000_000)
                    val directory = LocalTranscriptionProtocol.directory(this)
                    check(directory.mkdirs() || directory.isDirectory) { "Cannot create local audio staging" }
                    val audio = directory.resolve(LocalTranscriptionProtocol.AUDIO)
                    val result = directory.resolve(LocalTranscriptionProtocol.RESULT)
                    if (result.exists() && !result.delete()) throw IOException("Cannot clear the staged result")
                    if (declaredSize > maxBytes) throw LocalAudioLimitException("The recording exceeds the input byte limit")
                    contentResolver.openAssetFileDescriptor(uri, "r").use { descriptor ->
                        if (descriptor == null) throw IOException("The recording cannot be opened")
                        if (descriptor.length > maxBytes) throw LocalAudioLimitException("The recording exceeds the input byte limit")
                        descriptor.createInputStream().use { input ->
                            audio.outputStream().use { output -> copyBounded(input, output, maxBytes, budget::checkTime) }
                        }
                    }
                    budget.checkTime()
                    val transcript = transcribeSnapshot(audio, language, budget)
                    budget.checkTime()
                    result.writeBytes(transcriptBytes(transcript))
                    budget.checkTime()
                    send(reply, id, LocalTranscriptionProtocol.DONE)
                } catch (exception: Exception) {
                    send(reply, id, LocalTranscriptionProtocol.ERROR, exception.message ?: "Local transcription failed")
                }
            }, "local-transcription").start()
        } catch (exception: Exception) {
            send(reply, id, LocalTranscriptionProtocol.ERROR, exception.message ?: "Local transcription failed")
        }
        return true
    }

    internal open fun transcribeSnapshot(
        audio: File,
        language: TranscriptionLanguage,
        budget: LocalAudioBudget,
    ): String = AudioTranscriber(
        contentResolver, ModelStore(this).ensureInstalled(), language, checkCancelled = {},
    ).transcribe(Uri.fromFile(audio), budget)

    private fun send(reply: Messenger, id: String, what: Int, error: String? = null) {
        val message = Message.obtain(null, what).apply {
            data.putString(LocalTranscriptionProtocol.ID, id)
            if (error != null) data.putString("error", error.take(500))
        }
        try {
            reply.send(message)
        } catch (_: android.os.RemoteException) {
            Process.killProcess(Process.myPid())
        }
    }

    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }

    override fun onDestroy() {
        Process.killProcess(Process.myPid())
        super.onDestroy()
    }
}
