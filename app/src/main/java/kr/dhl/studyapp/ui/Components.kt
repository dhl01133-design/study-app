package kr.dhl.studyapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.dhl.studyapp.brief.Line
import kr.dhl.studyapp.data.Courses
import kr.dhl.studyapp.i18n.tr

/** Page title with a sync button (a spinner while syncing). */
@Composable
fun Header(title: String, syncing: Boolean, onRefresh: () -> Unit) {
    val p = pal
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = p.text, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(44.dp).clip(CircleShape).background(p.card), contentAlignment = Alignment.Center) {
            if (syncing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = p.accent)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = tr("동기화", "Sync"), tint = p.sub) }
            }
        }
    }
}

/** Borderless rounded card with an optional title and a right-aligned note. */
@Composable
fun SoftCard(
    title: String? = null,
    titleColor: Color = pal.text,
    tint: Color = pal.card,
    trailing: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = pal
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(tint).padding(18.dp)) {
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = titleColor, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, fontSize = 13.sp, color = p.sub)
            }
        }
        content()
    }
}

/** Time · title · course row used in the briefing lists. */
@Composable
fun LineRow(l: Line) {
    val p = pal
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(l.time, fontSize = 13.sp, color = p.sub, fontWeight = FontWeight.Medium, modifier = Modifier.width(84.dp).padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            Text(l.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = p.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (l.course.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                CourseChip(l.course)
            }
        }
    }
}

/** Course tag: the course color on a light tint of itself. */
@Composable
fun CourseChip(course: String) {
    if (course.isEmpty()) return
    val p = pal
    val base = Color(Courses.color(course))
    Text(
        course,
        color = if (p.dark) lerp(base, Color.White, 0.45f) else base,
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .background(base.copy(alpha = if (p.dark) 0.24f else 0.10f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

/** Rounded button; filled with the accent color when [selected]. */
@Composable
fun Pill(label: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val p = pal
    Box(
        modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) p.accent else if (p.dark) p.tile else p.card)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else p.text)
    }
}
