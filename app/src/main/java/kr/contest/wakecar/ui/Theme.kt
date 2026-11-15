package kr.contest.wakecar.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect UI Design Guide 2.1.5 다크 테마 값을 역할 이름으로 옮겼다(design/proto/tokens.css와 같은 값).
 * 화면 크기는 디자인 기준 폭(가로 1700, 세로 840)에 맞춰 1dp = 시안 1px이 되도록 키운다(WakeCarScreen 참고).
 */
object Tone {
    val Paper = Color(0xFF1D1E21)
    val Paper2 = Color(0xFF131417)
    val Paper3 = Color(0xFF313236)
    val Paper4 = Color(0xFF44464E)
    val Ink = Color(0xFFFFFFFF)
    val Ink2 = Color(0xD6FFFFFF)
    val Muted = Color(0xA3FFFFFF)
    val Faint = Color(0x52FFFFFF)
    val Rule = Color(0x1AFFFFFF)
    val Fill = Color(0xFFE0E1E6)
    val OnFill = Color(0xFF131417)
    val Ok = Color(0xFF00E074)
    val Danger = Color(0xFFFF4C28)
    /** 깨우는 장면의 새벽 신호(깨우기에서만). */
    val Wake = Color(0xFFFFC224)
    val Card = Color(0xFFFFFFFF)
    val Card2 = Color(0xFFEDEEF2)
    val Glass = Color(0x8F131417)
    val GlassStrong = Color(0xBD131417)
    val KnobOff = Color(0xFFA4A8AE)
    val Coin = Color(0xFFFFD45C)
    val Mood = Color(0xFFFF8FA3)
    val Sleepy = Color(0xFF8EA2E8)
}

object Type {
    val Display = TextStyle(fontSize = 120.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 124.sp, letterSpacing = (-2).sp)
    val HeadL = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 72.sp)
    val HeadM = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 70.sp)
    val HeadS = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 60.sp)
    val TitleL = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 52.sp)
    val TitleM = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, lineHeight = 46.sp)
    val TitleS = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
    val BodyL = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Normal, lineHeight = 42.sp)
    val BodyS = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal, lineHeight = 36.sp)
    val Label = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 34.sp)
    val LabelS = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 28.sp)
}

val PanelShape = RoundedCornerShape(32.dp)
val ControlShape = RoundedCornerShape(24.dp)
val ControlShapeL = RoundedCornerShape(32.dp)
val StageShape = RoundedCornerShape(40.dp)
val Pill = RoundedCornerShape(50)

@Composable
fun WakeCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tone.Ok, onPrimary = Tone.OnFill, secondary = Tone.Fill, tertiary = Tone.Wake, onTertiary = Tone.OnFill,
            background = Tone.Paper, surface = Tone.Paper, onBackground = Tone.Ink, onSurface = Tone.Ink, error = Tone.Danger,
        ),
        content = content,
    )
}

/** 누르면 95%로 줄어드는 Pleos 버튼 반응. */
@Composable
private fun Modifier.press(enabled: Boolean, role: Role, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) 0.95f else 1f, tween(120), label = "press")
    return this.scale(s).clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

enum class ButtonKind { FILL, BASIC, GLASS }

/** Pleos 버튼: Large 100dp(반경 32), Medium 80dp(반경 24). */
@Composable
fun WButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.BASIC,
    large: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val bg = when (kind) {
        ButtonKind.FILL -> if (enabled) Tone.Fill else Tone.Paper3
        ButtonKind.BASIC -> Tone.Paper3
        ButtonKind.GLASS -> Tone.GlassStrong
    }
    val fg = when {
        !enabled -> Tone.Faint
        kind == ButtonKind.FILL -> Tone.OnFill
        else -> Tone.Ink
    }
    Box(
        modifier = modifier
            .heightIn(min = if (large) 100.dp else 80.dp)
            .press(enabled, Role.Button, onClick)
            .clip(if (large) ControlShapeL else ControlShape)
            .background(bg)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Text(text, color = fg, style = (if (large) Type.TitleS else Type.BodyS).copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        }
    }
}

/** Pleos 스위치 80x48(손잡이 32). */
@Composable
fun WSwitch(checked: Boolean, onToggle: () -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = { onToggle() },
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = Tone.Ok, checkedThumbColor = Tone.Ink, checkedBorderColor = Tone.Ok,
            uncheckedTrackColor = Tone.Paper4, uncheckedThumbColor = Tone.KnobOff, uncheckedBorderColor = Tone.Paper4,
        ),
        modifier = Modifier.scale(1.25f).padding(horizontal = 8.dp),
    )
}

/** 제목 + 설명 + 오른쪽 조작. 줄 전체를 눌러도 된다. */
@Composable
fun SettingRow(title: String, caption: String? = null, onClick: (() -> Unit)? = null, minHeight: Dp = 96.dp, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onClick != null) Modifier.clickable(role = Role.Switch, onClick = onClick) else Modifier)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KText(title, color = Tone.Ink, style = Type.BodyL.copy(fontWeight = FontWeight.SemiBold))
            if (caption != null) KText(caption, color = Tone.Muted, style = Type.Label)
        }
        trailing()
    }
}

/** 막대 그래프 한 줄(경험치, 기분, 졸음). */
@Composable
fun Meter(value: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    // 채움은 그리기 단계에서만 바꾼다(배치를 바꾸지 않아 접근성 이벤트가 나가지 않는다)
    val v = androidx.compose.animation.core.animateFloatAsState(value.coerceIn(0f, 1f), tween(400), label = "meter")
    Box(
        modifier.height(height).clip(Pill).background(Color(0x2EFFFFFF)).drawBehind {
            drawRoundRect(color, size = androidx.compose.ui.geometry.Size(size.width * v.value, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        },
    )
}

@Composable
fun CoinIcon(size: Dp = 24.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val path = androidx.compose.ui.graphics.Path().apply {
            val pts = (0 until 10).map { i ->
                val r = if (i % 2 == 0) w / 2 else w / 4.6f
                val a = Math.toRadians((-90 + i * 36).toDouble())
                androidx.compose.ui.geometry.Offset(w / 2 + (r * kotlin.math.cos(a)).toFloat(), w / 2 + (r * kotlin.math.sin(a)).toFloat())
            }
            moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close()
        }
        drawPath(path, Tone.Coin)
    }
}
