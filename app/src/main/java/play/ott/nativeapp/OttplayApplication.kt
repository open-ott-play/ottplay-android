package play.ott.nativeapp
import android.app.Application
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.data.EpgRefreshWorker
class OttplayApplication : Application() {
    val repository: NativeRepository by lazy { NativeRepository(this) }
    override fun onCreate() { super.onCreate(); EpgRefreshWorker.schedule(this) }
}
