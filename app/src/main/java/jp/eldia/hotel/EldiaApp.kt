package jp.eldia.hotel

import android.app.Application
import jp.eldia.hotel.core.AppPrefs
import jp.eldia.hotel.core.CrashReporter
import jp.eldia.hotel.core.Push

class EldiaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPrefs.init(this)
        CrashReporter.init(this)
        Push.init(this)
    }
}
