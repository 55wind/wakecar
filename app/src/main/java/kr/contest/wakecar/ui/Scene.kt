package kr.contest.wakecar.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.sin

/** 하늘 네 가지. 실제 시각(시작 화면)이나 낮잠 단계에 따라 바뀐다. */
enum class Sky { NIGHT, DAWN, MORNING, EVENING }

private class SkyLook(val top: Color, val mid: Color, val low: Color, val bottom: Color, val hillBack: Color, val hillFront: Color, val stars: Float, val clouds: Float)

private val LOOKS = mapOf(
    Sky.NIGHT to SkyLook(Color(0xFF060A18), Color(0xFF0E1530), Color(0xFF151D3C), Color(0xFF1B2546), Color(0xFF121A31), Color(0xFF0A0F20), .85f, .12f),
    Sky.DAWN to SkyLook(Color(0xFF121A33), Color(0xFF68413B), Color(0xFFE0745A), Color(0xFFFFC27A), Color(0xFF4A2C30), Color(0xFF2A1A1F), .25f, .22f),
    Sky.MORNING to SkyLook(Color(0xFF1F5FA8), Color(0xFF4E90D0), Color(0xFF7DB3E2), Color(0xFFA6D2F2), Color(0xFF4C8C70), Color(0xFF33694F), 0f, .85f),
    Sky.EVENING to SkyLook(Color(0xFF473331), Color(0xFF8A5238), Color(0xFFB4673E), Color(0xFFF0B36A), Color(0xFF533D35), Color(0xFF342A27), .1f, .3f),
)

/** 해 위치: t = 0 동쪽(왼쪽) 지평선, t = 1 서쪽(오른쪽) 지평선. 0보다 작거나 1보다 크면 언덕 뒤로 숨는다. */
fun sunOfHour(hour: Float): Float = when {
    hour < 6f -> -0.14f
    hour > 18.5f -> 1.14f
    else -> (hour - 6f) / 12.5f
}

fun skyOfHour(hour: Float): Sky = when {
    hour >= 5 && hour < 8 -> Sky.DAWN
    hour >= 8 && hour < 17 -> Sky.MORNING
    hour >= 17 && hour < 20 -> Sky.EVENING
    else -> Sky.NIGHT
}

fun skyOfSun(t: Float): Sky = when {
    t < 0f || t > 1.02f -> Sky.NIGHT
    t < 0.14f -> Sky.DAWN
    t < 0.84f -> Sky.MORNING
    else -> Sky.EVENING
}

/** 하늘 그림(별, 달, 해, 구름, 언덕). content는 그 위(캐릭터, 글자). */
@Composable
fun SkyScene(sky: Sky, sunT: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    val look = LOOKS.getValue(sky)
    val spec = tween<Color>(1400)
    val top by animateColorAsState(look.top, spec, label = "top")
    val mid by animateColorAsState(look.mid, spec, label = "mid")
    val low by animateColorAsState(look.low, spec, label = "low")
    val bottom by animateColorAsState(look.bottom, spec, label = "bottom")
    val hb by animateColorAsState(look.hillBack, spec, label = "hb")
    val hf by animateColorAsState(look.hillFront, spec, label = "hf")
    val stars by animateFloatAsState(look.stars, tween(1400), label = "stars")
    val clouds by animateFloatAsState(look.clouds, tween(1400), label = "clouds")
    val moon by animateFloatAsState(if (sky == Sky.NIGHT) 1f else 0f, tween(1400), label = "moon")
    val sunAlpha by animateFloatAsState(if (sky == Sky.NIGHT) 0f else 1f, tween(1400), label = "sun")
    val loop = rememberInfiniteTransition(label = "sky")
    // 끝없이 도는 값은 그리기 단계에서만 읽는다(다시 구성하지 않아야 접근성 도구와 화면 자동화가 멈춘 상태를 알아챈다)
    val twinkle = loop.animateFloat(0.4f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "tw")
    val drift = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(90_000, easing = LinearEasing)), label = "drift")

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(0f to top, 0.45f to mid, 0.74f to low, 1f to bottom))
            if (stars > 0.01f) drawStars(stars, twinkle.value)
            if (moon > 0.01f) drawMoon(moon)
            if (sunAlpha > 0.01f) drawSun(sunT, sunAlpha)
            if (clouds > 0.01f) drawClouds(clouds, drift.value)
            drawHills(hb, hf)
        }
        content()
    }
}

private val STARS = listOf(
    .08f to .12f, .19f to .23f, .26f to .08f, .36f to .2f, .44f to .1f, .52f to .27f, .61f to .07f, .7f to .18f,
    .77f to .09f, .86f to .25f, .93f to .13f, .13f to .37f, .33f to .38f, .66f to .35f, .9f to .4f, .56f to .43f,
)

private fun DrawScope.drawStars(alpha: Float, twinkle: Float) {
    STARS.forEachIndexed { i, (x, y) ->
        val a = alpha * if (i % 3 == 0) twinkle else 1f
        drawCircle(Color.White.copy(alpha = a), radius = if (i % 4 == 0) 2.6f * density else 1.6f * density, center = Offset(size.width * x, size.height * y))
    }
}

private fun DrawScope.drawMoon(alpha: Float) {
    // 초승달: 둥근 달에서 조금 비켜 놓은 원을 빼서 만든다(하늘색 원을 덧칠하지 않아 테두리나 얼룩이 생기지 않는다).
    val r = 44f * density
    val c = Offset(size.width * 0.86f - r, size.height * 0.1f + r)
    val moon = Color(0xFFF4EFD8)
    // 부드럽게 퍼지는 달빛(가장자리로 갈수록 투명)
    drawCircle(Brush.radialGradient(listOf(moon.copy(alpha = .28f * alpha), moon.copy(alpha = .08f * alpha), Color.Transparent), center = c, radius = r * 2.4f), radius = r * 2.4f, center = c)
    val disc = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.48f, -r * 0.22f), r * 0.86f)) }
    val crescent = Path().apply { op(disc, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
    drawPath(crescent, moon, alpha = alpha)
}

private fun DrawScope.drawSun(t: Float, alpha: Float) {
    val up = sin(PI * t.coerceIn(0f, 1f)).toFloat()
    val below = if (t < 0f) -t else if (t > 1f) t - 1f else 0f
    val x = size.width * (0.08f + 0.84f * t.coerceIn(-0.1f, 1.1f))
    val y = size.height * (0.76f - 0.6f * up + below * 1.2f)
    val r = 75f * density
    val sun = Color(0xFFFFD27A)
    drawCircle(Brush.radialGradient(listOf(sun.copy(alpha = .55f * alpha), Color.Transparent), center = Offset(x, y), radius = r * 2.6f), radius = r * 2.6f, center = Offset(x, y))
    drawCircle(sun.copy(alpha = alpha), radius = r, center = Offset(x, y))
}

private fun DrawScope.drawCloud(cx: Float, cy: Float, w: Float, alpha: Float) {
    // 원 여러 개를 한 도형으로 합쳐 한 번에 칠한다(겹친 곳이 진해지지 않게)
    val p = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(Offset(cx - w * .22f, cy), w * .2f))
        addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy - w * .08f), w * .28f))
        addOval(androidx.compose.ui.geometry.Rect(Offset(cx + w * .24f, cy + w * .01f), w * .22f))
        addRoundRect(androidx.compose.ui.geometry.RoundRect(cx - w * .42f, cy, cx + w * .46f, cy + w * .2f, w * .1f, w * .1f))
    }
    drawPath(p, Color.White, alpha = alpha)
}

private fun DrawScope.drawClouds(alpha: Float, drift: Float) {
    val w = size.width
    fun x(base: Float, speed: Float) = ((base + drift * speed) % 1.3f - .15f) * w
    drawCloud(x(.15f, .5f), size.height * .44f, 150f * density, alpha * .9f)
    drawCloud(x(.78f, .35f), size.height * .3f, 190f * density, alpha)
    drawCloud(x(.5f, .6f), size.height * .52f, 96f * density, alpha * .7f)
}

private fun DrawScope.drawHills(back: Color, front: Color) {
    val w = size.width; val h = size.height; val top = h * 0.7f; val hh = h - top
    val b = Path().apply {
        moveTo(0f, top + hh * .55f)
        cubicTo(w * .16f, top + hh * .27f, w * .32f, top + hh * .32f, w * .47f, top + hh * .5f)
        cubicTo(w * .62f, top + hh * .68f, w * .78f, top + hh * .27f, w, top + hh * .41f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(b, back)
    val f = Path().apply {
        moveTo(0f, top + hh * .77f)
        cubicTo(w * .2f, top + hh * .59f, w * .38f, top + hh * .64f, w * .54f, top + hh * .73f)
        cubicTo(w * .72f, top + hh * .83f, w * .86f, top + hh * .64f, w, top + hh * .68f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(f, front)
}
