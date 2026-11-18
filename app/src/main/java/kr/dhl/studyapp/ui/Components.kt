package kr.dhl.studyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.dhl.studyapp.brief.Line
import kr.dhl.studyapp.i18n.tr

/** Page title with a plain-text sync action. */
@Composable
fun Header(title: String, syncing: Boolean, onRefresh: () -> Unit) {
    val p = pal
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = p.text, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f))
        Text(
            if (syncing) tr("동기화 중", "Syncing") else tr("동기화", "Sync"),
            fontSize = 13.sp, color = p.sub,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = !syncing, onClick = onRefresh)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Section with a small gray title and no background. */
@Composable
fun SoftCard(
    title: String? = null,
    titleColor: Color = pal.sub,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = pal
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = titleColor, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, fontSize = 13.sp, color = p.faint)
            }
        }
        content()
    }
}

/** Thin full-width rule. */
@Composable
fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))

/** Time · title · course row used in the briefing lists. */
@Composable
fun LineRow(l: Line) {
    val p = pal
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(l.time, fontSize = 14.sp, color = p.sub, modifier = Modifier.width(92.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, fontSize = 15.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CourseChip(l.course)
        }
    }
}

/** Course name in small gray text. */
@Composable
fun CourseChip(course: String) {
    if (course.isEmpty()) return
    Text(course, color = pal.sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Outlined option button; filled when [selected]. */
@Composable
fun Pill(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val p = pal
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.clip(shape)
            .then(if (selected) Modifier.background(p.accent) else Modifier.border(1.dp, p.line, shape))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (selected) p.onAccent else p.text)
    }
}
