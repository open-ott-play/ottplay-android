package play.ott.nativeapp.playback

import android.app.Activity
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import play.ott.nativeapp.MainActivity
import play.ott.nativeapp.OttplayApplication

/** Completes asynchronous Android teardown before another test reuses the media notification. */
@UnstableApi
internal object PlaybackTestLifecycle {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    fun awaitDestroyed(activity: Activity) {
        awaitState("Playback Activity was not destroyed") {
            var destroyed = false
            instrumentation.runOnMainSync { destroyed = activity.isDestroyed }
            destroyed to "finishing=${activity.isFinishing}, destroyed=$destroyed"
        }
    }

    @Suppress("DEPRECATION") // Since Android O this query still exposes the calling app's services.
    fun finishPreviousPlayback() {
        val activities = mutableSetOf<Activity>()
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            Stage.entries.filter { it != Stage.DESTROYED }.forEach { stage ->
                activities += monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>()
            }
            activities.forEach { it.finish() }
        }
        activities.forEach(::awaitDestroyed)

        val service = ComponentName(context, PlaybackService::class.java)
        context.stopService(Intent().setComponent(service))
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        awaitState("Previous playback service or media notification survived teardown") {
            val serviceRunning = activityManager.getRunningServices(Int.MAX_VALUE).any { it.service == service }
            val mediaNotifications = notificationManager.activeNotifications.filter {
                it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
            }
            (!serviceRunning && mediaNotifications.isEmpty()) to
                "serviceRunning=$serviceRunning, mediaNotificationIds=${mediaNotifications.map { it.id }}"
        }
        runBlocking {
            withTimeout(10_000) {
                (context.applicationContext as OttplayApplication).repository.preferences.resumeWriter.awaitIdle()
            }
        }
    }

    private fun awaitState(message: String, state: () -> Pair<Boolean, String>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var lastState = "not observed"
        while (System.nanoTime() < deadline) {
            val (ready, description) = state()
            lastState = description
            if (ready) return
            Thread.sleep(50)
        }
        throw AssertionError("$message; $lastState")
    }
}
