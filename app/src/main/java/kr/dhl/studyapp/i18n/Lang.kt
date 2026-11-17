package kr.dhl.studyapp.i18n

import java.util.Locale

/** App language: "system" (Korean phones → 한국어, anything else → English), "ko" or "en". */
object L {
    @Volatile var en = false
        private set

    fun apply(mode: String) {
        en = when (mode) {
            "en" -> true
            "ko" -> false
            else -> Locale.getDefault().language != "ko"
        }
    }
}

/** The Korean or English text for the current app language. */
fun tr(ko: String, en: String): String = if (L.en) en else ko

/** Market names are stored in Korean; translated for display. */
fun marketName(name: String): String = when (name) {
    "나스닥" -> tr(name, "Nasdaq")
    "달러인덱스" -> tr(name, "Dollar Index")
    else -> name
}
