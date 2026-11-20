package zsh.android.client

import android.app.Application
import zsh.android.client.env.EnvManager
import zsh.android.client.runtime.RuntimeManager
import zsh.android.client.store.Settings

class ZshaApp : Application() {

    lateinit var settings: Settings
        private set
    lateinit var envManager: EnvManager
        private set
    lateinit var runtimeManager: RuntimeManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        envManager = EnvManager(this, settings)
        runtimeManager = RuntimeManager(envManager.paths, settings)
    }
}
