package j3.voicekey

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Native.init(filesDir.absolutePath)
        Watchdog.schedule(this)
    }

    companion object {
        lateinit var app: App
            private set
        val prefs by lazy { Prefs(app) }
    }
}
