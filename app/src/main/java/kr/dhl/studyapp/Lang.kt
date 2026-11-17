package kr.dhl.studyapp

import java.util.Locale

/** App language: "system" (phone language; Korean phones → 한국어, everything else → English), "ko" or "en". */
object L {
    @Volatile var en = false

    fun resolve(mode: String): Boolean = when (mode) {
        "en" -> true
        "ko" -> false
        else -> Locale.getDefault().language != "ko"
    }

    fun apply(mode: String) {
        en = resolve(mode)
    }
}

/** Pick the Korean or English text for the current app language. */
fun tr(ko: String, en: String): String = if (L.en) en else ko

/** Market names are stored in Korean in the snapshot; translate on display. */
fun marketName(n: String): String = when (n) {
    "나스닥" -> tr(n, "Nasdaq")
    "달러인덱스" -> tr(n, "Dollar Index")
    else -> n
}
