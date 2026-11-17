package kr.dhl.studyapp.data

import java.time.LocalDate
import java.time.LocalTime

/** Made-up data for the emulator smoke test (`am start … --ez demo true`). */
object Demo {
    fun snapshot(): Snapshot {
        val today = LocalDate.now()
        fun day(n: Long) = today.plusDays(n).toString()
        fun at(n: Long, h: Int, m: Int) = today.plusDays(n).atTime(LocalTime.of(h, m)).toString()
        val dueIn5 = today.plusDays(5).let { "Due ${it.monthValue}/${it.dayOfMonth} 23:59" }
        val ic = listOf("icampus")
        val tasks = listOf(
            TaskItem("demo1", "DEMO 끌어서 옮기기", dueIn5, labels = ic, priority = 3, dueDate = day(0), dueTime = "19:30"),
            TaskItem("demo2", "[선형대수학] WeBWorK hw11", "$dueIn5 예상", labels = ic, priority = 2, dueDate = day(2), dueTime = "17:00"),
            TaskItem("demo3", "Report 3", projectName = "경제학입문", labels = ic, priority = 4, dueDate = day(-2)),
            TaskItem("demo4", "12주차 · Chapter 23", "$dueIn5 · 동영상", projectName = "General Chemistry 2", labels = ic, priority = 3, dueDate = day(1), dueTime = "13:00"),
            TaskItem("demo5", "[과제2] M1의 소득유통속도와 이자율", projectName = "경제학입문", labels = ic, priority = 3, dueDate = day(3), dueTime = "15:00"),
            TaskItem("demo6", "장보기", projectName = "Inbox", priority = 1),
            TaskItem("demo7", "매주 방 청소", projectName = "Inbox", dueDate = day(4), recurring = true),
        )
        val events = listOf(
            CalEvent("e1", "[English Writing] 영어쓰기 수업", at(0, 9, 0), at(0, 10, 15)),
            CalEvent("e2", "[창의적글쓰기] 창의적글쓰기 수업", at(0, 12, 0), at(0, 13, 15)),
            CalEvent("e3", "[창의적융합디자인] 팀 활동", at(0, 16, 0), at(0, 17, 50)),
            CalEvent("e4", "[선형대수학] WeBWorK hw11 마감 예상 (~23:59)", at(5, 23, 59), at(5, 23, 59)),
            CalEvent("e5", "[일반화학2] 일반화학2 수업", at(1, 15, 0), at(1, 16, 15)),
            CalEvent("e6", "개교기념일", day(6) + "T00:00", day(7) + "T00:00", allDay = true),
        )
        val markets = listOf(
            MarketItem("나스닥", 24579.43, 1.09), MarketItem("S&P500", 6812.10, 0.64),
            MarketItem("QLD", 121.37, 2.15), MarketItem("달러인덱스", 99.41, -0.22),
        )
        val session = today.minusDays(1).let { "${it.monthValue}/${it.dayOfMonth}" }
        return Snapshot(System.currentTimeMillis(), tasks, events, markets, session)
    }
}
