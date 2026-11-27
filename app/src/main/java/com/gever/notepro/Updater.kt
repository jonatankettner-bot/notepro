package com.gever.notepro

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases.
 * Every push to main builds a signed APK in GitHub Actions and publishes it as release "v<run number>".
 * The app compares that number with its own versionCode and, when newer, downloads the APK and hands it
 * to Android's PackageInstaller. Android asks the user to confirm the update.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/jonatankettner-bot/notepro/releases/latest"
    private val main = Handler(Looper.getMainLooper())

    data class Release(val versionCode: Int, val apkUrl: String, val notes: String)

    /** Calls back on the main thread with the newer release, or null if up to date / unreachable. */
    fun check(onResult: (Release?) -> Unit) {
        Thread {
            val r = try {
                val c = (URL(LATEST).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                val json = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val o = JSONObject(json)
                val code = o.getString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                val assets = o.getJSONArray("assets")
                var url: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
                }
                if (code > BuildConfig.VERSION_CODE && url != null) Release(code, url, o.optString("body")) else null
            } catch (t: Throwable) { null }
            main.post { onResult(r) }
        }.start()
    }

    /** Downloads the APK and starts the system install (update) flow. */
    fun install(activity: Activity, rel: Release, onStatus: (String) -> Unit) {
        val pm = activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            onStatus("צריך לאשר ל-NotePro להתקין עדכונים, ואז ללחוץ שוב")
            activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName)))
            return
        }
        onStatus("מוריד עדכון…")
        Thread {
            try {
                val installer = pm.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    var c = URL(rel.apkUrl).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = true
                    // GitHub release downloads redirect to another host; follow manually if needed.
                    var hops = 0
                    while (c.responseCode in 300..399 && hops++ < 5) {
                        val next = c.getHeaderField("Location"); c.disconnect()
                        c = URL(next).openConnection() as HttpURLConnection
                    }
                    session.openWrite("notepro.apk", 0, -1).use { out ->
                        c.inputStream.use { it.copyTo(out) }
                        session.fsync(out)
                    }
                    c.disconnect()
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(activity, id, Intent(activity, InstallReceiver::class.java), flags)
                    session.commit(pi.intentSender)
                }
                main.post { onStatus("מתקין…") }
            } catch (t: Throwable) {
                main.post { onStatus("העדכון נכשל: " + (t.message ?: t.javaClass.simpleName)) }
            }
        }.start()
    }
}

/** Receives PackageInstaller results; shows the system confirmation screen when Android asks for it. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "שגיאה לא ידועה"
                Toast.makeText(context, "העדכון לא הותקן: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
