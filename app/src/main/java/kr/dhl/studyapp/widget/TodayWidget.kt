package kr.dhl.studyapp.widget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider as SolidColor
import kr.dhl.studyapp.MainActivity
import kr.dhl.studyapp.brief.Brief
import kr.dhl.studyapp.brief.DaySlice
import kr.dhl.studyapp.brief.Fmt
import kr.dhl.studyapp.brief.Line
import kr.dhl.studyapp.data.Courses
import kr.dhl.studyapp.data.Repo
import kr.dhl.studyapp.i18n.L
import kr.dhl.studyapp.i18n.tr
import java.time.LocalDate

/** Home-screen widget: today's classes, events and tasks. Tap to open the app. */
class TodayWidget : GlanceAppWidget() {

    // Exact size, so the time bar can be drawn at the widget's real width.
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = Repo(context)
        L.apply(repo.prefs.langMode)
        val s = repo.load()
        val today = LocalDate.now()
        val lines = Brief.todayLines(s, repo.prefs, today)
        val overdue = Brief.overdue(s, repo.prefs, today).size
        val slice = DaySlice.of(s, today)
        val density = context.resources.displayMetrics.density
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        provideContent {
            // 28 dp of horizontal padding inside the widget
            val bar = SliceBitmap.render(slice, LocalSize.current.width.value - 28f, density, dark)
            Content(today, lines, overdue, bar)
        }
    }

    @Composable
    private fun Content(today: LocalDate, lines: List<Line>, overdue: Int, bar: Bitmap) {
        Column(
            GlanceModifier.fillMaxSize().background(BG).cornerRadius(16.dp).padding(14.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.day(today), style = TextStyle(color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.Bold))
                if (overdue > 0) {
                    Spacer(GlanceModifier.defaultWeight())
                    Text(tr("기한 지남 $overdue", "$overdue overdue"), style = TextStyle(color = DANGER, fontSize = 12.sp))
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            Image(ImageProvider(bar), contentDescription = null, modifier = GlanceModifier.fillMaxWidth().height(36.dp))
            Spacer(GlanceModifier.height(6.dp))
            if (lines.isEmpty()) {
                Text(tr("없음", "Nothing"), style = TextStyle(color = SUB, fontSize = 13.sp))
            } else {
                LazyColumn {
                    items(lines) { l -> LineItem(l) }
                }
            }
        }
    }

    @Composable
    private fun LineItem(l: Line) {
        Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(l.time, style = TextStyle(color = SUB, fontSize = 12.sp), modifier = GlanceModifier.width(84.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(l.title, style = TextStyle(color = TEXT, fontSize = 13.sp), maxLines = 1)
                if (l.course.isNotEmpty()) {
                    val base = Color(Courses.color(l.course))
                    Text(
                        l.course,
                        style = TextStyle(color = ColorProvider(day = base, night = lerp(base, Color.White, 0.4f)), fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }

    companion object {
        private val BG = ColorProvider(day = Color.White, night = Color(0xFF1A1A1D))
        private val TEXT = ColorProvider(day = Color(0xFF111111), night = Color(0xFFF2F2F2))
        private val SUB = ColorProvider(day = Color(0xFF6E6E73), night = Color(0xFF9A9AA0))
        private val DANGER = SolidColor(Color(0xFFE5484D))

        /** Re-renders every placed widget from the current snapshot. */
        suspend fun refresh(ctx: Context) {
            runCatching { TodayWidget().updateAll(ctx) }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
