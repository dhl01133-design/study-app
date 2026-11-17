package kr.dhl.studyapp.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kr.dhl.studyapp.data.Http
import kr.dhl.studyapp.data.str
import kr.dhl.studyapp.i18n.tr
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object UpToDate : UpdateState
    data class Available(val build: Int) : UpdateState
    data object Downloading : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates the app from the private repo's "latest" release (build number = GitHub run number = versionCode).
 * Uses a read-only GitHub token. Installs through PackageInstaller: the first update asks for confirmation;
 * on Android 12+ later ones can go through without asking once the app has installed itself.
 */
object Updater {
    private const val API = "https://api.github.com/repos/dhl01133-design/study-app"
    private val BUILD = Regex("build (\\d+)")

    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    private data class Release(val build: Int, val assetId: String)

    @Volatile private var release: Release? = null
    @Volatile private var lastCheck = 0L

    fun currentBuild(ctx: Context): Int {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }

    /** Checks at most every 30 minutes unless [force]. */
    suspend fun check(ctx: Context, token: String, force: Boolean = false) {
        if (token.isBlank()) return
        if (!force && System.currentTimeMillis() - lastCheck < 30 * 60_000) return
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Installing) return
        lastCheck = System.currentTimeMillis()
        state.value = withContext(Dispatchers.IO) {
            runCatching { latest(token) }.fold(
                { r ->
                    release = r
                    if (r.build > currentBuild(ctx)) UpdateState.Available(r.build) else UpdateState.UpToDate
                },
                { UpdateState.Failed(describe(it)) },
            )
        }
    }

    /** Whether Android still needs "install unknown apps" allowed for this app. */
    fun needsInstallPermission(ctx: Context) = !ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Downloads the latest APK and hands it to PackageInstaller. The app restarts when it's done. */
    suspend fun install(ctx: Context, token: String) = withContext(Dispatchers.IO) {
        state.value = UpdateState.Downloading
        try {
            val r = release ?: latest(token).also { release = it }
            val apk = File(ctx.cacheDir, "update.apk")
            download(token, r.assetId, apk)
            commit(ctx, apk)
            state.value = UpdateState.Installing
        } catch (e: Exception) {
            state.value = UpdateState.Failed(describe(e))
        }
    }

    private fun headers(token: String, accept: String = "application/vnd.github+json") = mapOf(
        "Authorization" to "Bearer $token",
        "Accept" to accept,
        "X-GitHub-Api-Version" to "2022-11-28",
    )

    private fun latest(token: String): Release {
        val json = Json.parseToJsonElement(Http.request("$API/releases/tags/latest", headers = headers(token))).jsonObject
        val build = BUILD.find(json.str("name"))?.groupValues?.get(1)?.toInt() ?: throw IOException("no build number")
        val asset = json["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it.str("name").endsWith(".apk") }
            ?: throw IOException("no APK in release")
        return Release(build, asset.str("id"))
    }

    /** The asset URL redirects to a signed download URL, which must be fetched without the GitHub token. */
    private fun download(token: String, assetId: String, dest: File) {
        val api = URL("$API/releases/assets/$assetId").openConnection() as HttpURLConnection
        api.instanceFollowRedirects = false
        for ((k, v) in headers(token, "application/octet-stream")) api.setRequestProperty(k, v)
        val conn = when (val code = api.responseCode) {
            200 -> api
            in 300..399 -> {
                val location = api.getHeaderField("Location") ?: throw IOException("redirect without location")
                api.disconnect()
                URL(location).openConnection() as HttpURLConnection
            }
            else -> throw IOException("HTTP $code")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun commit(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // The installer fills in the result extras, so the PendingIntent must be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    private fun describe(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            m.startsWith("HTTP 401") || m.startsWith("HTTP 403") || m.startsWith("HTTP 404") ->
                tr("GitHub 토큰이 올바르지 않아요", "GitHub token is invalid")
            else -> tr("업데이트 실패: ", "Update failed: ") + m
        }
    }
}
