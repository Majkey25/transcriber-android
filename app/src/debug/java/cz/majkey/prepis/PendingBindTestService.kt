package cz.majkey.prepis

import android.content.Intent
import android.os.IBinder
import android.os.SystemClock

class PendingBindTestService : LocalTranscriptionService() {
    override fun onBind(intent: Intent): IBinder {
        cacheDir.resolve("local-bind-fixture").writeText("binding")
        SystemClock.sleep(1500)
        return super.onBind(intent)
    }
}
