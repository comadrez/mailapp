package com.comadrez.mailapp

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray

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
            Acct(o.getString("id"), o.getString("address"), o.getString("password"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun notify(ctx: Context): Boolean = prefs(ctx).getBoolean("notify", false)

    fun setNotify(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("notify", on).apply()
    }

    /** Message ids already handled for an address, or null if the watcher never looked at it before. */
    fun notified(ctx: Context, accountId: String): Set<String>? =
        prefs(ctx).getStringSet("notified:$accountId", null)?.toSet()

    fun setNotified(ctx: Context, accountId: String, ids: Set<String>) {
        prefs(ctx).edit().putStringSet("notified:$accountId", HashSet(ids)).apply()
    }
}
