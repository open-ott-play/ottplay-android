package play.ott.nativeapp
import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.ImageLoader
import coil.ImageLoaderFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.data.EpgRefreshWorker
class OttplayApplication : Application(), ImageLoaderFactory {
    val repository: NativeRepository by lazy { NativeRepository(this) }
    private val startedPlaybackActivities = mutableSetOf<Activity>()
    private val playbackVisible = MutableStateFlow(false)
    internal val playbackActivityVisible = playbackVisible.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (activity is MainActivity) {
                    startedPlaybackActivities += activity
                    playbackVisible.value = true
                }
            }
            override fun onActivityStopped(activity: Activity) = removePlaybackActivity(activity)
            override fun onActivityDestroyed(activity: Activity) = removePlaybackActivity(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        EpgRefreshWorker.schedule(this)
    }

    private fun removePlaybackActivity(activity: Activity) {
        // Track instances: a stopped launcher must not pause another visible launcher.
        if (startedPlaybackActivities.remove(activity)) playbackVisible.value = startedPlaybackActivities.isNotEmpty()
    }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(AppTransportPolicy.current.secure(OkHttpClient()))
        .build()
}
