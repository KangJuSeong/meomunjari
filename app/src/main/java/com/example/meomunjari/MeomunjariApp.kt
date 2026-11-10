package com.example.meomunjari

import android.app.Application
import com.example.meomunjari.di.AppContainer

class MeomunjariApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
