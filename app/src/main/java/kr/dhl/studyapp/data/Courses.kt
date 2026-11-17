package kr.dhl.studyapp.data

/** Course names and colors. A "[course] " title prefix counts only for the names listed here. */
object Courses {
    private val PREFIX = Regex("^\\[([^\\]]+)]\\s*")

    private val COLORS = mapOf(
        "경제학입문" to 0xFF2F6FDF, "창의적글쓰기" to 0xFFC96A14,
        "일반화학2" to 0xFF23874D, "General Chemistry 2" to 0xFF23874D,
        "창의적융합디자인" to 0xFF7A45C4, "선형대수학" to 0xFFB4420F,
        "미적분2" to 0xFF0B6C84, "Calculus 2" to 0xFF0B6C84,
        "영어쓰기" to 0xFFB0175A, "영어작문" to 0xFFB0175A, "English Writing" to 0xFFB0175A,
    )
    private const val DEFAULT_COLOR = 0xFF5A6270

    /** "[선형대수학] …" → 선형대수학; "[과제2] …" → null (not a course). */
    fun prefix(s: String): String? = PREFIX.find(s)?.groupValues?.get(1)?.takeIf { it in COLORS }

    fun strip(s: String): String = if (prefix(s) != null) s.replaceFirst(PREFIX, "").ifBlank { s } else s

    fun fromProject(project: String): String = if (project.isBlank() || project == "Inbox") "" else project

    fun color(course: String): Long = COLORS[course] ?: DEFAULT_COLOR
}
