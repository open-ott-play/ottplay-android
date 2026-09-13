package play.ott.nativeapp.ui

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit

/** Compose idle does not include the OS handoff from an IME or a dismissed Dialog. */
internal fun awaitActivityWindowReady(activity: Activity, hideIme: Boolean = false) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    if (hideIme) instrumentation.runOnMainSync {
        WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
    }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    var diagnostics = ""
    while (System.nanoTime() < deadline) {
        var ready = false
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            val imeVisible = ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
            ready = decor.isAttachedToWindow && decor.hasWindowFocus() && !imeVisible
            diagnostics = "windowFocus=${decor.hasWindowFocus()}, attached=${decor.isAttachedToWindow}, " +
                "ime=$imeVisible, focus=${activity.currentFocus?.javaClass?.simpleName}:${activity.currentFocus?.id}"
        }
        if (ready) return
        Thread.sleep(50)
    }
    throw AssertionError("Activity window did not become ready for a hardware key: $diagnostics")
}
