package kr.dhl.studyapp

import java.time.LocalDate
import java.time.LocalTime

/**
 * Made-up data for the emulator smoke test (`am start … --ez demo true`).
 * In demo mode nothing is synced, saved or sent to Todoist.
 */
object Demo {
    fun snapshot(): Snapshot {
        val t = LocalDate.now()
        fun d(n: Long) = t.plusDays(n).toString()
        fun at(n: Long, h: Int, m: Int) = t.plusDays(n).atTime(LocalTime.of(h, m)).toString()
        val ic = listOf("icampus")
        val tasks = listOf(
            TaskItem("demo1", "DEMO 끌어서 옮기기", "Due ${t.plusDays(5).monthValue}/${t.plusDays(5).dayOfMonth} 23:59", labels = ic, priority = 3, dueDate = d(0), dueTime = "19:30"),
            TaskItem("demo2", "[선형대수학] WeBWorK hw11", "Due ${t.plusDays(5).monthValue}/${t.plusDays(5).dayOfMonth} 23:59 예상", labels = ic, priority = 2, dueDate = d(2), dueTime = "17:00"),
            TaskItem("demo3", "Report 3", projectName = "경제학입문", labels = ic, priority = 4, dueDate = d(-2)),
            TaskItem("demo4", "12주차 · Chapter 23", "Due ${t.plusDays(5).monthValue}/${t.plusDays(5).dayOfMonth} 23:59 · 동영상", projectName = "General Chemistry 2", labels = ic, priority = 3, dueDate = d(1), dueTime = "13:00"),
            TaskItem("demo5", "[과제2] M1의 소득유통속도와 이자율", projectName = "경제학입문", labels = ic, priority = 3, dueDate = d(3), dueTime = "15:00"),
            TaskItem("demo6", "장보기", projectName = "Inbox", priority = 1),
            TaskItem("demo7", "매주 방 청소", projectName = "Inbox", dueDate = d(4), recurring = true),
        )
        val events = listOf(
            CalEvent("e1", "[English Writing] 영어쓰기 수업", at(0, 9, 0), at(0, 10, 15)),
            CalEvent("e2", "[창의적글쓰기] 창의적글쓰기 수업", at(0, 12, 0), at(0, 13, 15)),
            CalEvent("e3", "[창의적융합디자인] 팀 활동", at(0, 16, 0), at(0, 17, 50)),
            CalEvent("e4", "[선형대수학] WeBWorK hw11 마감 예상 (~23:59)", at(5, 23, 59), at(5, 23, 59)),
            CalEvent("e5", "[일반화학2] 일반화학2 수업", at(1, 15, 0), at(1, 16, 15)),
            CalEvent("e6", "개교기념일", d(6) + "T00:00", d(7) + "T00:00", allDay = true),
        )
        val markets = listOf(
            MarketItem("나스닥", 24579.43, 1.09), MarketItem("S&P500", 6812.10, 0.64),
            MarketItem("QLD", 121.37, 2.15), MarketItem("달러인덱스", 99.41, -0.22),
        )
        return Snapshot(System.currentTimeMillis(), tasks, events, markets, "${t.monthValue}/${t.minusDays(1).dayOfMonth}")
    }
}
