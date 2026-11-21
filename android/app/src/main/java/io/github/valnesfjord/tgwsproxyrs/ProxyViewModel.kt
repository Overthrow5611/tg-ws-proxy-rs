package io.github.valnesfjord.tgwsproxyrs

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.valnesfjord.tgwsproxyrs.ui.Accent
import io.github.valnesfjord.tgwsproxyrs.ui.Appearance
import io.github.valnesfjord.tgwsproxyrs.ui.ThemeMode
import io.github.valnesfjord.tgwsproxyrs.ui.dynamicColorAvailable
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.system.ErrnoException
import android.system.OsConstants
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class ProxyViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences(ProxyService.PREFS, 0)

    private val _args = MutableStateFlow(
        prefs.getString(ProxyService.PREF_ARGS, null) ?: ProxyService.DEFAULT_ARGS,
    )
    val args: StateFlow<String> = _args.asStateFlow()

    private val _appearance = MutableStateFlow(loadAppearance())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    val running = ProxyBridge.running
    val restarting = ProxyBridge.restarting
    val tgLink = ProxyBridge.tgLink
    val error = ProxyBridge.error

    /**
     * Bounded log buffer the UI observes directly.  A `StateFlow<List<String>>`
     * copied the whole list to append and copied it again to trim, i.e. up to
     * a thousand element copies per line on a chatty proxy; a snapshot list
     * shares its backing structure between versions and lets Compose observe
     * the single append, so only the rows on screen recompose.
     */
    private val _logs = mutableStateListOf<LogLine>()
    val logs: List<LogLine> = _logs

    /** Never reused, never reordered — see [LogLine]. */
    private var nextLogId = 0L

    private var autoOpenPending = false

    init {
        ProxyBridge.syncFromNative()
        viewModelScope.launch {
            ProxyBridge.logs.collect { line ->
                _logs.add(LogLine(nextLogId++, line))
                if (_logs.size > MAX_LOG_LINES) {
                    _logs.removeAt(0)
                }
            }
        }
        viewModelScope.launch {
            ProxyBridge.tgLink.collect { link ->
                if (link != null && autoOpenPending) {
                    autoOpenPending = false
                    openLink()
                }
            }
        }
    }

    fun updateArgs(value: String) {
        _args.value = value
        prefs.edit { putString(ProxyService.PREF_ARGS, value) }
    }

    fun setThemeMode(mode: ThemeMode) = saveAppearance(_appearance.value.copy(mode = mode))

    fun setAccent(accent: Accent) = saveAppearance(_appearance.value.copy(accent = accent))

    fun setAmoled(amoled: Boolean) = saveAppearance(_appearance.value.copy(amoled = amoled))

    private fun loadAppearance(): Appearance {
        val defaultAccent = if (dynamicColorAvailable) Accent.DYNAMIC else Accent.BLUE
        return Appearance(
            mode = prefs.getString(PREF_THEME, null)
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accent = prefs.getString(PREF_ACCENT, null)
                ?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: defaultAccent,
            amoled = prefs.getBoolean(PREF_AMOLED, false),
        )
    }

    private fun saveAppearance(value: Appearance) {
        _appearance.value = value
        prefs.edit {
            putString(PREF_THEME, value.mode.name)
            putString(PREF_ACCENT, value.accent.name)
            putBoolean(PREF_AMOLED, value.amoled)
        }
    }

    // ── Network check ────────────────────────────────────────────────────────

    /**
     * Why a probe failed.  The kind is what tells the causes apart: a carrier
     * whitelist or an IP block drops packets ([TIMEOUT]), a per-app data
     * restriction (MagicOS, MIUI, Data Saver) is refused locally at once
     * ([BLOCKED]), DPI on the SNI lets TCP through and kills the handshake ([TLS]).
     */
    enum class Failure { TIMEOUT, BLOCKED, NO_ROUTE, REFUSED, DNS, TLS, OTHER }

    /** One path's result: handshakes that completed out of [tries], their mean time, and the first failure. */
    data class PathResult(val ok: Int, val tries: Int, val avgMs: Long?, val failure: Failure?)

    /**
     * [russian] is a whitelisted Russian host: reachable while Telegram and
     * Cloudflare are not means whitelist mode.  [systemBlocked] is Android's
     * own word that this app's network is blocked: a per-app block rarely
     * shows up as EPERM on the socket — the system resolver refuses the
     * app's lookups instead, so from the probes alone it looks like DNS
     * failures and timeouts.  A block on this app cannot let any probe
     * through, so one success anywhere rules it out.
     */
    data class NetCheck(
        val direct: PathResult,
        val cloudflare: PathResult,
        val russian: PathResult,
        val systemBlocked: Boolean,
    ) {
        val appBlocked: Boolean
            get() = listOf(direct, cloudflare, russian).all { it.ok == 0 } && (
                systemBlocked ||
                listOf(direct, cloudflare, russian).all { it.failure == Failure.BLOCKED } ||
                (
                    cloudflare.failure == Failure.DNS && russian.failure == Failure.DNS &&
                        direct.failure in setOf(Failure.TIMEOUT, Failure.BLOCKED, Failure.NO_ROUTE)
                    )
                )
    }

    private val _netCheck = MutableStateFlow<NetCheck?>(null)
    val netCheck: StateFlow<NetCheck?> = _netCheck.asStateFlow()

    private val _netChecking = MutableStateFlow(false)
    val netChecking: StateFlow<Boolean> = _netChecking.asStateFlow()

    /**
     * TLS handshakes to the two places the presets send traffic: the direct
     * WebSocket endpoint (149.154.167.220 with Telegram's SNI, what
     * `--dc-ip 2/4` dials) and a Cloudflare edge (what the community domains
     * sit behind).  Only on request, never in the background.
     */
    fun runNetCheck() {
        if (_netChecking.value) return
        _netChecking.value = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                // In parallel: on a dead network each path waits out its timeouts.
                val direct = async { probe(DIRECT_IP, DIRECT_SNI) }
                val cloudflare = async { probe(CLOUDFLARE_HOST, CLOUDFLARE_HOST) }
                val russian = async { probe(RUSSIAN_HOST, RUSSIAN_HOST) }
                NetCheck(direct.await(), cloudflare.await(), russian.await(), systemBlocksData())
            }
            _netCheck.value = result
            _netChecking.value = false
        }
    }

    private fun probe(host: String, sni: String): PathResult {
        val times = mutableListOf<Long>()
        var failure: Failure? = null
        repeat(PROBE_TRIES) {
            val started = SystemClock.elapsedRealtime()
            var connected = false
            try {
                Socket().use { raw ->
                    raw.connect(InetSocketAddress(host, 443), PROBE_TIMEOUT_MS)
                    connected = true
                    raw.soTimeout = PROBE_TIMEOUT_MS
                    val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    (factory.createSocket(raw, sni, 443, true) as SSLSocket).use { tls ->
                        tls.sslParameters = tls.sslParameters.apply {
                            serverNames = listOf(SNIHostName(sni))
                        }
                        tls.startHandshake()
                    }
                }
                times += SystemClock.elapsedRealtime() - started
            } catch (e: Exception) {
                if (failure == null) failure = if (connected) Failure.TLS else classify(e)
            }
        }
        return PathResult(
            times.size,
            PROBE_TRIES,
            times.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            failure.takeIf { times.isEmpty() },
        )
    }

    /**
     * Not `restrictBackgroundStatus`: Data Saver only cuts background apps, and
     * both this check (screen on) and the proxy (a foreground service) are
     * exempt, so it would blame a setting that cannot be the cause.
     */
    private fun systemBlocksData(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.detailedState == NetworkInfo.DetailedState.BLOCKED
        }.getOrDefault(false)
    }

    private fun classify(e: Throwable): Failure {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is ErrnoException) {
                return when (cause.errno) {
                    OsConstants.EPERM, OsConstants.EACCES -> Failure.BLOCKED
                    OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH -> Failure.NO_ROUTE
                    OsConstants.ECONNREFUSED, OsConstants.ECONNRESET -> Failure.REFUSED
                    OsConstants.ETIMEDOUT -> Failure.TIMEOUT
                    else -> Failure.OTHER
                }
            }
            cause = cause.cause
        }
        return when (e) {
            is UnknownHostException -> Failure.DNS
            is SocketTimeoutException -> Failure.TIMEOUT
            is SSLException -> Failure.TLS
            else -> Failure.OTHER
        }
    }

    // ── Update check ─────────────────────────────────────────────────────────

    sealed interface UpdateState {
        data object Checking : UpdateState
        data class Available(val version: String) : UpdateState
        data object UpToDate : UpdateState
        data object Failed : UpdateState
    }

    private val _update = MutableStateFlow<UpdateState?>(null)
    val update: StateFlow<UpdateState?> = _update.asStateFlow()

    val currentVersion: String = runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull() ?: "?"

    /** Reads the fork's Cargo.toml on GitHub; one request, only when asked. */
    fun checkForUpdate() {
        if (_update.value == UpdateState.Checking) return
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URL(FORK_CARGO_TOML).openConnection() as HttpURLConnection
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 8_000
                    try {
                        connection.inputStream.bufferedReader().useLines { lines ->
                            lines.firstNotNullOfOrNull { VERSION_LINE.matchEntire(it.trim())?.groupValues?.get(1) }
                        }
                    } finally {
                        connection.disconnect()
                    }
                }.getOrNull()
            }
            _update.value = when {
                latest == null -> UpdateState.Failed
                isNewer(latest, currentVersion) -> UpdateState.Available(latest)
                else -> UpdateState.UpToDate
            }
        }
    }

    fun openBuilds() = openUrl(FORK_ACTIONS)

    // ── Settings backup ──────────────────────────────────────────────────────

    fun exportSettings(uri: Uri) {
        val app = getApplication<Application>()
        val json = JSONObject().apply {
            put("format", BACKUP_FORMAT)
            put("args", _args.value)
            prefs.getString(ProxyService.PREF_SECRET, null)?.let { put("secret", it) }
            put("theme", _appearance.value.mode.name)
            put("accent", _appearance.value.accent.name)
            put("amoled", _appearance.value.amoled)
        }.toString(2)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // CreateDocument always makes a new file, so plain "w" is
                    // enough and works with providers that reject "wt".
                    app.contentResolver.openOutputStream(uri, "w")!!.use { it.write(json.toByteArray()) }
                }.isSuccess
            }
            toast(if (ok) R.string.backup_saved else R.string.backup_failed)
        }
    }

    /**
     * Refused while a run is up or restarting: the service would keep the
     * running arguments but pick up the imported secret on its next restart,
     * silently breaking the proxy saved in Telegram.  Everything is parsed and
     * validated before anything is written.
     */
    fun importSettings(uri: Uri) {
        if (running.value || restarting.value) {
            toast(R.string.backup_stop_first)
            return
        }
        val app = getApplication<Application>()
        viewModelScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching {
                    val text = app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    JSONObject(text).also { require(it.optString("format") == BACKUP_FORMAT) }
                }.getOrNull()
            }
            if (parsed == null || running.value || restarting.value) {
                toast(if (parsed == null) R.string.backup_failed else R.string.backup_stop_first)
                return@launch
            }
            fun text(key: String) = if (parsed.has(key) && !parsed.isNull(key)) parsed.optString(key) else null
            val args = text("args")?.takeIf { it.isNotBlank() }
            val secret = text("secret")?.takeIf { SECRET_HEX.matches(it) }
            val current = _appearance.value
            val appearance = Appearance(
                mode = text("theme")?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: current.mode,
                accent = (text("accent")?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: current.accent)
                    .let { if (it == Accent.DYNAMIC && !dynamicColorAvailable) Accent.BLUE else it },
                amoled = parsed.optBoolean("amoled", current.amoled),
            )
            args?.let(::updateArgs)
            secret?.let { prefs.edit { putString(ProxyService.PREF_SECRET, it) } }
            saveAppearance(appearance)
            toast(R.string.backup_restored)
        }
    }

    private fun toast(@StringRes message: Int) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    /** Active clients, bytes up, bytes down; null if the shim had nothing to say. */
    fun readStats(): Triple<Long, Long, Long>? {
        val parts = NativeProxy.nativeStats()?.split(' ')?.mapNotNull { it.toLongOrNull() }
        return if (parts != null && parts.size == 3) Triple(parts[0], parts[1], parts[2]) else null
    }

    /** Swap in a preset, keeping the current choice of detailed logging. */
    fun applyPreset(preset: Preset) {
        updateArgs(if (isVerbose(_args.value)) withoutQuiet(preset.args) else preset.args)
    }

    fun setVerbose(verbose: Boolean) {
        val current = _args.value
        if (verbose == isVerbose(current)) return
        updateArgs(if (verbose) withoutQuiet(current) else "--quiet $current")
    }

    fun copyLog() {
        val app = getApplication<Application>()
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(app.getString(R.string.log_label), logText()))
        // Android 13+ confirms clipboard writes itself.
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(app, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareLog() {
        val app = getApplication<Application>()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, logText())
        }
        val chooser = Intent.createChooser(send, app.getString(R.string.share_log_title)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            app.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
        }
    }

    private fun logText() = _logs.joinToString("\n") { it.text }

    fun start() {
        ProxyBridge.clearError()
        autoOpenPending = true
        ProxyService.start(getApplication(), _args.value)
    }

    fun stop() {
        ProxyService.stop(getApplication())
    }

    fun openLink() {
        val link = tgLink.value ?: return
        val intent = Intent(Intent.ACTION_VIEW, link.toUri()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ProxyBridge.reportMessage(getApplication<Application>().getString(R.string.telegram_missing))
        }
    }

    fun openRepo() = openUrl(FORK_REPO)

    private fun openUrl(url: String) {
        val uri = url.toUri()
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            getApplication<Application>().startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            ProxyBridge.reportMessage(getApplication<Application>().getString(R.string.browser_missing))
        }
    }

    data class Preset(@StringRes val label: Int, val args: String)

    companion object {
        private const val MAX_LOG_LINES = 500
        private const val PREF_THEME = "theme"
        private const val PREF_ACCENT = "accent"
        private const val PREF_AMOLED = "amoled"

        private const val DIRECT_IP = "149.154.167.220"
        private const val DIRECT_SNI = "kws2.web.telegram.org"
        private const val CLOUDFLARE_HOST = "cloudflare.com"
        private const val RUSSIAN_HOST = "ya.ru"
        private const val PROBE_TRIES = 3
        private const val PROBE_TIMEOUT_MS = 4_000

        private const val FORK_REPO = "https://github.com/Overthrow5611/tg-ws-proxy-rs"
        private const val FORK_ACTIONS = "$FORK_REPO/actions/workflows/ci.yml?query=branch%3Amain"
        private const val FORK_CARGO_TOML =
            "https://raw.githubusercontent.com/Overthrow5611/tg-ws-proxy-rs/main/Cargo.toml"
        private val VERSION_LINE = Regex("""version\s*=\s*"(\d+\.\d+\.\d+)"""")

        private const val BACKUP_FORMAT = "tg-ws-proxy-settings-1"
        private val SECRET_HEX = Regex("[0-9a-f]{32}")

        /** Whether dotted version [a] is greater than [b]; non-numeric parts count as 0. */
        fun isNewer(a: String, b: String): Boolean {
            val x = a.split('.').map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        val PRESETS = listOf(
            Preset(R.string.preset_direct, ProxyService.DEFAULT_ARGS),
            Preset(R.string.preset_direct_cf, ProxyService.DIRECT_CF_ARGS),
        )

        private val QUIET = Regex("""(^|\s)(--quiet|-q)(?=\s|$)""")
        private val BLANKS = Regex("""[ \t]{2,}""")
        private val SPACES = Regex("""\s+""")

        fun isVerbose(args: String) = !QUIET.containsMatchIn(args)

        /** Drops `--quiet`/`-q`, leaving the user's line breaks alone. */
        fun withoutQuiet(args: String) = QUIET.replace(args, "$1").replace(BLANKS, " ").trim()

        /** Whether [args] is [preset] up to `--quiet` and spacing. */
        fun matches(args: String, preset: Preset) =
            withoutQuiet(args).replace(SPACES, " ") == withoutQuiet(preset.args).replace(SPACES, " ")
    }
}
