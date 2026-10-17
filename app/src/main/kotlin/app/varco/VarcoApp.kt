package app.varco

import android.app.Application
import android.content.Context
import app.varco.data.Store
import app.varco.data.Vault
import app.varco.importer.Importer
import app.varco.keys.ChipKeys
import app.varco.service.SessionService
import app.varco.ssh.Prompts
import app.varco.ssh.Sessions
import app.varco.ssh.SshEnv
import app.varco.ui.lock.AppLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** App-wide objects. Sessions live here, so that they outlive the activity. */
class VarcoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prompts = Prompts()
    val chip by lazy { ChipKeys(this) }
    lateinit var store: Store
        private set
    lateinit var sessions: Sessions
        private set
    lateinit var appLock: AppLock
        private set
    val importer by lazy { Importer(this, store, chip, prompts) }

    private val _ready = MutableStateFlow(false)

    /** True after the store is read; the UI waits for it behind the splash screen. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        store = Store(filesDir, Vault(), scope)
        sessions = Sessions(this, store, SshEnv(store, chip, prompts), scope)
        appLock = AppLock({ store.state.value.settings.appLock }, { store.state.value.settings.lockAfter })
        scope.launch(Dispatchers.IO) {
            store.load()
            appLock.onLoaded()
            _ready.value = true
        }
        scope.launch {
            sessions.shells.map { it.isNotEmpty() }.distinctUntilChanged().collect { open ->
                if (open) SessionService.start(this@VarcoApp)
            }
        }
    }
}

val Context.app: VarcoApp get() = applicationContext as VarcoApp
