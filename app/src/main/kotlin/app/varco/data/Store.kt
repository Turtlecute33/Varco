package app.varco.data

import android.util.AtomicFile
import android.util.Log
import app.varco.term.Scheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * All app data in one file, `store.bin`: JSON encrypted by the [Vault] key (AES-256-GCM, Keystore).
 * Server names, host names and saved passwords are thus encrypted on top of Android's file-based encryption.
 * The data is small (servers, keys, snippets), so each change writes the whole file, off the main thread.
 */
class Store(dir: File, private val vault: Vault, private val scope: CoroutineScope) {
    private val file = AtomicFile(File(dir, "store.bin"))
    private val writeLock = Mutex()
    private val pending = AtomicBoolean(false)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** Reads the file. Call once, off the main thread, before the UI shows data. */
    fun load() {
        val blob = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return
        }
        // A damaged file or a lost Keystore key must not crash the app. The file moves aside (never
        // overwritten), so that a passing Keystore error loses no data; the user starts with an empty list.
        _state.value = runCatching { decode(JSONObject(vault.decryptString(blob))) }.getOrElse {
            Log.w(TAG, "Store unreadable, kept as $UNREADABLE")
            file.baseFile.renameTo(File(file.baseFile.parentFile, UNREADABLE))
            AppState()
        }
    }

    /**
     * Applies [change] now and writes the new state in the background. Changes within [COALESCE_MS] go into
     * one write (a slider or pinch zoom makes many), because each write is a Keystore operation.
     */
    fun update(change: (AppState) -> AppState) {
        _state.update(change)
        if (pending.compareAndSet(false, true)) {
            scope.launch(Dispatchers.IO) {
                delay(COALESCE_MS)
                pending.set(false)
                save()
            }
        }
    }

    private suspend fun save() = writeLock.withLock {
        val bytes = vault.encryptString(encode(_state.value).toString())
        val out = file.startWrite()
        try {
            out.write(bytes)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "Store write failed", e)
        }
    }

    internal companion object {
        const val TAG = "VarcoStore"
        const val VERSION = 1
        const val COALESCE_MS = 300L
        const val UNREADABLE = "store.bin.unreadable"

        fun encode(s: AppState): JSONObject = JSONObject()
            .put("version", VERSION)
            .put("servers", JSONArray(s.servers.map { it.json() }))
            .put("keys", JSONArray(s.keys.map { it.json() }))
            .put("snippets", JSONArray(s.snippets.map { JSONObject().put("id", it.id).put("name", it.name).put("text", it.text).put("run", it.run) }))
            .put("knownHosts", JSONArray(s.knownHosts.map { JSONObject().put("host", it.host).put("port", it.port).put("type", it.type).put("key", it.key) }))
            .put("importedKnownHosts", s.importedKnownHosts)
            .put("schemes", JSONArray(s.schemes.map { it.json() }))
            .put("settings", s.settings.json())

        fun decode(o: JSONObject) = AppState(
            servers = o.list("servers") { server() },
            keys = o.list("keys") { key() },
            snippets = o.list("snippets") { Snippet(getString("id"), getString("name"), getString("text"), optBoolean("run", true)) },
            knownHosts = o.list("knownHosts") { KnownHost(getString("host"), getInt("port"), getString("type"), getString("key")) },
            importedKnownHosts = o.optString("importedKnownHosts"),
            schemes = o.list("schemes") { scheme() },
            settings = o.optJSONObject("settings")?.settings() ?: Settings(),
        )

        private fun <T> JSONObject.list(name: String, item: JSONObject.() -> T): List<T> {
            val a = optJSONArray(name) ?: return emptyList()
            return List(a.length()) { a.getJSONObject(it).item() }
        }

        private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name)

        private fun Server.json() = JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
            .put("user", user).put("keyId", keyId).put("password", password).put("lastUsed", lastUsed)

        private fun JSONObject.server() = Server(
            id = getString("id"), name = getString("name"), host = getString("host"), port = optInt("port", 22),
            user = getString("user"), keyId = str("keyId"), password = str("password"), lastUsed = optLong("lastUsed"),
        )

        private fun SshKey.json() = JSONObject().put("id", id).put("name", name).put("kind", kind.name).put("type", type)
            .put("publicKey", publicKey).put("alias", alias).put("authPerUse", authPerUse).put("strongBox", strongBox)
            .put("pivSlot", pivSlot).put("privateKey", privateKey)

        private fun JSONObject.key() = SshKey(
            id = getString("id"), name = getString("name"), kind = SshKey.Kind.valueOf(getString("kind")),
            type = getString("type"), publicKey = getString("publicKey"), alias = str("alias"),
            authPerUse = optBoolean("authPerUse"), strongBox = optBoolean("strongBox"),
            pivSlot = if (isNull("pivSlot")) null else getInt("pivSlot"), privateKey = str("privateKey"),
        )

        private fun Scheme.json() = JSONObject().put("id", id).put("name", name).put("fg", foreground)
            .put("bg", background).put("cursor", cursor).put("ansi", JSONArray(ansi.toList()))

        private fun JSONObject.scheme(): Scheme {
            val a = getJSONArray("ansi")
            return Scheme(getString("id"), getString("name"), getInt("fg"), getInt("bg"), getInt("cursor"), IntArray(16) { a.getInt(it) })
        }

        private fun Settings.json() = JSONObject().put("schemeId", schemeId).put("fontSize", fontSize).put("font", font)
            .put("appLock", appLock).put("lockAfter", lockAfter).put("secureWindow", secureWindow)
            .put("keepScreenOn", keepScreenOn).put("vibrateOnBell", vibrateOnBell)
            .put("serverClipboard", serverClipboard).put("scrollback", scrollback)

        private fun JSONObject.settings(): Settings {
            val d = Settings()
            return Settings(
                schemeId = str("schemeId"),
                fontSize = optInt("fontSize", d.fontSize),
                font = optString("font", d.font),
                appLock = optBoolean("appLock", d.appLock),
                lockAfter = optInt("lockAfter", d.lockAfter),
                secureWindow = optBoolean("secureWindow", d.secureWindow),
                keepScreenOn = optBoolean("keepScreenOn", d.keepScreenOn),
                vibrateOnBell = optBoolean("vibrateOnBell", d.vibrateOnBell),
                serverClipboard = optBoolean("serverClipboard", d.serverClipboard),
                scrollback = optInt("scrollback", d.scrollback),
            )
        }
    }
}
