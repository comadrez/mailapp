package com.comadrez.mailapp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

const val DAY_MS = 86_400_000L

/** True while the app screen is visible, so the background watcher does not notify for mail you can already see. */
object Foreground {
    @Volatile var visible = false
}

/** One shared encrypted store, used by both the app screens and the background watcher. */
object Store {
    @Volatile private var cached: SharedPreferences? = null

    fun prefs(ctx: Context): SharedPreferences = cached ?: synchronized(this) {
        cached ?: run {
            val app = ctx.applicationContext
            EncryptedSharedPreferences.create(
                app, "driftbox_secure",
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.also { cached = it }
    }

    fun accounts(ctx: Context): List<Acct> = try {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Acct(o.getString("id"), o.getString("address"), o.getString("password"), o.optLong("created", 0L))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun notify(ctx: Context): Boolean = prefs(ctx).getBoolean("notify", true)

    fun setNotify(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("notify", on).apply()
    }

    fun askedNotif(ctx: Context): Boolean = prefs(ctx).getBoolean("asked_notif", false)
    fun setAskedNotif(ctx: Context) { prefs(ctx).edit().putBoolean("asked_notif", true).apply() }

    /** Days after which an address gets an age reminder (0 = off). */
    fun remindDays(ctx: Context): Int = prefs(ctx).getInt("remind_days", 0)
    fun setRemindDays(ctx: Context, d: Int) { prefs(ctx).edit().putInt("remind_days", d).apply() }

    /** Days after which an address is deleted automatically (0 = off). */
    fun autoDeleteDays(ctx: Context): Int = prefs(ctx).getInt("autodelete_days", 0)
    fun setAutoDeleteDays(ctx: Context, d: Int) { prefs(ctx).edit().putInt("autodelete_days", d).apply() }

    /** Which reminder threshold (in days) was already shown as a banner / sent as a notification for an address. */
    fun bannerSeen(ctx: Context, id: String): Int = prefs(ctx).getInt("banner:$id", 0)
    fun setBannerSeen(ctx: Context, id: String, d: Int) { prefs(ctx).edit().putInt("banner:$id", d).apply() }
    fun reminded(ctx: Context, id: String): Int = prefs(ctx).getInt("remnotif:$id", 0)
    fun setReminded(ctx: Context, id: String, d: Int) { prefs(ctx).edit().putInt("remnotif:$id", d).apply() }

    /** Message ids already handled for an address, or null if the watcher never looked at it before. */
    fun notified(ctx: Context, accountId: String): Set<String>? =
        prefs(ctx).getStringSet("notified:$accountId", null)?.toSet()

    fun setNotified(ctx: Context, accountId: String, ids: Set<String>) {
        prefs(ctx).edit().putStringSet("notified:$accountId", HashSet(ids)).apply()
    }
}
