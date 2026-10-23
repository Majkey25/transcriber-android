package cz.majkey.prepis

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LocalOwnerTestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid == Process.myUid()) {
            if (message.what == LocalTranscriptionProtocol.STOP) Process.killProcess(Process.myPid())
            if (message.what == LocalTranscriptionProtocol.START) {
                val uri = Uri.parse(requireNotNull(message.data.getString(LocalTranscriptionProtocol.URI)))
                scope.launch {
                    transcribeLocal(
                        this@LocalOwnerTestService, uri, -1, TranscriptionLanguage.AUTO,
                        CodecTestService::class.java, timeoutMs = 30_000,
                    )
                }
            }
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }
}
