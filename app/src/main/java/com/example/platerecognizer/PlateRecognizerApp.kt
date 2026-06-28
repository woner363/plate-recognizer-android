package com.example.platerecognizer

import android.app.Application

/** Application 入口：装配 [AppContainer]，并把它暴露给 ViewModel Factory。 */
class PlateRecognizerApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
