package com.lightterm

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.lightterm.core.device.DeviceProfileResolver
import com.lightterm.core.network.ServerConnectivityTester
import com.lightterm.core.session.SessionManager
import com.lightterm.core.session.SshTransportFactory
import com.lightterm.data.local.LightTermDatabase
import com.lightterm.data.repository.AppSettingsRepository
import com.lightterm.data.repository.CommandHistoryRepository
import com.lightterm.data.repository.CommandTemplateRepository
import com.lightterm.data.repository.RemoteFileHistoryRepository
import com.lightterm.data.repository.ServerRepository
import com.lightterm.data.repository.VirtualKeyRepository
import com.lightterm.data.security.SecureCredentialStore
import com.lightterm.data.security.SshKeyManager

class LightTermApp : Application(), DefaultLifecycleObserver {
    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        appContainer = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        appContainer.sessionManager.setAppForeground(true)
    }

    override fun onStop(owner: LifecycleOwner) {
        appContainer.sessionManager.setAppForeground(false)
    }
}

class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val deviceProfile = DeviceProfileResolver().resolve()
    val appSettingsRepository = AppSettingsRepository(
        context = appContext,
        defaultFontSizeSp = deviceProfile.terminalFontSizeSp,
    )
    val database: LightTermDatabase = Room.databaseBuilder(
        appContext,
        LightTermDatabase::class.java,
        "lightterm.db",
    ).addMigrations(
        LightTermDatabase.MIGRATION_2_3,
        LightTermDatabase.MIGRATION_3_4,
    )
        .fallbackToDestructiveMigration()
        .build()
    val secureCredentialStore = SecureCredentialStore(appContext)
    val sshKeyManager = SshKeyManager()
    val serverRepository = ServerRepository(
        dao = database.serverConfigDao(),
        credentialStore = secureCredentialStore,
        sshKeyManager = sshKeyManager,
    )
    val virtualKeyRepository = VirtualKeyRepository(appContext)
    val commandTemplateRepository = CommandTemplateRepository(appContext)
    val commandHistoryRepository = CommandHistoryRepository(appContext)
    val remoteFileHistoryRepository = RemoteFileHistoryRepository(appContext)
    val sessionManager = SessionManager(
        transportFactory = SshTransportFactory(
            secureCredentialStore,
            sshKeyManager,
            appContext.filesDir,
            messageResolver = { resId, args ->
                appContext.getString(resId, *args)
            },
        ),
        deviceProfile = deviceProfile,
        messageResolver = { resId, args ->
            appContext.getString(resId, *args)
        },
    )
    val serverConnectivityTester = ServerConnectivityTester(appContext)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as LightTermApp).appContainer
