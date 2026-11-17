package kr.dhl.studyapp

import android.app.Application
import kr.dhl.studyapp.data.Prefs
import kr.dhl.studyapp.i18n.L
import kr.dhl.studyapp.notify.Alarms
import kr.dhl.studyapp.notify.Notify
import kr.dhl.studyapp.notify.SyncWorker

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        L.apply(Prefs(this).langMode)
        Notify.channels(this)
        SyncWorker.schedule(this)
        Alarms.scheduleBriefing(this)
    }
}
