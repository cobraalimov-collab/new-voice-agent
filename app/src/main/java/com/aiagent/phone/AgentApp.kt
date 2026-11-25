package com.aiagent.phone

import android.app.Application

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AgentLog.add("SYS", "App started (Android ${android.os.Build.VERSION.SDK_INT}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})")
        TtsManager.init(this)
    }
}
