package cz.majkey.prepis

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCapacityUiTest {
    @Test
    fun fullQueueExplainsLocalAndConfirmedCloudRejectionAndCanBeDismissed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = WorkManager.getInstance(context)
        val tag = "queue-capacity-ui-fixture"
        val requests = (0 until MAX_FOLDER_DOCUMENTS).map { index ->
            OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInitialDelay(30, TimeUnit.DAYS)
                .addTag(tag)
                .addTag(if (index % 2 == 0) TranscriptionWorker.GLOBAL_TAG else CloudTranscriptionWorker.GLOBAL_TAG)
                .build()
        }
        var activity: MainActivity? = null
        try {
            manager.enqueue(requests).result.get(60, TimeUnit.SECONDS)
            val launched = instrumentation.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            activity = launched
            lateinit var model: MainViewModel
            instrumentation.runOnMainSync { model = ViewModelProvider(launched)[MainViewModel::class.java] }
            val recording = Recording(Uri.parse("content://test/not-opened"), "fixture.m4a", 1, 1)
            val cloud = TranscriptionProfile(TranscriptionModel.OPENAI_GPT_4O, TranscriptionLanguage.CZECH)

            for (local in listOf(true, false)) {
                instrumentation.runOnMainSync {
                    if (local) model.enqueue(recording, TranscriptionProfile.LOCAL_CZECH)
                    else model.confirmCloudTranscription(recording, cloud)
                }
                awaitUi {
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.findText(context.getString(R.string.queue_full_title)) != null
                }
                assertTrue(model.queueFull.value)
                var button = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findText(context.getString(android.R.string.ok))
                while (button != null && !button.isClickable) button = button.parent
                assertTrue(button?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
                awaitUi { !model.queueFull.value }
            }
            assertTrue(manager.getWorkInfosByTag(tag).get().all { !it.state.isFinished })
            assertTrue(manager.getWorkInfosByTag(recording.admissionTag(cloud)).get().isEmpty())
            assertTrue(manager.getWorkInfosByTag(recording.admissionTag(TranscriptionProfile.LOCAL_CZECH)).get().isEmpty())
        } finally {
            activity?.let { instrumentation.runOnMainSync { it.finish() } }
            manager.cancelAllWorkByTag(tag).result.get(60, TimeUnit.SECONDS)
        }
    }

    private fun AccessibilityNodeInfo.findText(text: String): AccessibilityNodeInfo? {
        if (this.text?.toString() == text) return this
        for (index in 0 until childCount) {
            getChild(index)?.findText(text)?.let { return it }
        }
        return null
    }

    private fun awaitUi(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Expected queue-capacity UI state", condition())
    }
}
