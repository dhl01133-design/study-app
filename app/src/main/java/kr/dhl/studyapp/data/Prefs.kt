package kr.dhl.studyapp.data

import android.content.Context
import android.content.SharedPreferences
import java.time.LocalTime
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var todoistToken by string("todoist", "", clean = { cleanToken(it) })
    var githubToken by string("github", "", clean = { cleanToken(it) })       // read-only, for app updates
    var icsText by string("ics", "", clean = { it.trim() })              // one iCal URL per line
    var marketUrl by string("market", DEFAULT_MARKET, clean = { it.trim() })
    var briefingText by string("brief", "08:17", clean = { it.trim() })
    var offsetsText by string("offsets", "6,1", clean = { it.trim() })
    var themeMode by string("theme", "system")                 // system | light | dark
    var langMode by string("lang", "en")                       // en (default) | ko | system
    var onlyIcampus by boolean("only_icampus", false)

    var alarmCodes: Set<String>
        get() = p.getStringSet("alarm_codes", emptySet())?.toSet() ?: emptySet()
        set(v) = p.edit().putStringSet("alarm_codes", v).apply()

    val icsUrls: List<String>
        get() = icsText.lines().map { it.trim() }.filter { it.startsWith("http") }

    val briefingTime: LocalTime
        get() = runCatching { LocalTime.parse(briefingText.padStart(5, '0')) }.getOrDefault(LocalTime.of(8, 17))

    /** Hours before a deadline at which alarms ring. */
    val alarmOffsets: List<Int>
        get() = offsetsText.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0..72 }.distinct().ifEmpty { listOf(6, 1) }

    private fun string(key: String, default: String, clean: (String) -> String = { it }) = pref(
        { getString(key, default) ?: default },
        { putString(key, clean(it)) },
    )

    private fun boolean(key: String, default: Boolean) = pref({ getBoolean(key, default) }, { putBoolean(key, it) })

    private fun <T> pref(read: SharedPreferences.() -> T, write: SharedPreferences.Editor.(T) -> Unit) =
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = p.read()
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = p.edit().apply { write(value) }.apply()
        }

    companion object {
        /** Drops spaces, line breaks and quotes picked up when copying a token. */
        fun cleanToken(s: String) = s.filterNot { it.isWhitespace() || it == '"' || it == '\'' }

        const val DEFAULT_MARKET = "https://raw.githubusercontent.com/dhl01133-design/market-closes/main/markets.json"
    }
}
