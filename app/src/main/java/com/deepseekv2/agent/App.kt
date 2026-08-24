package com.deepseekv2.agent

import android.app.Application
import com.deepseekv2.agent.di.AppGraph

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
