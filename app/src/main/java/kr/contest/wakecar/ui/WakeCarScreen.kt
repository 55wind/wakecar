package kr.contest.wakecar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kr.contest.wakecar.NapController
import kr.contest.wakecar.UiState
import kr.contest.wakecar.core.Phase
import kotlin.math.abs

/**
 * 화면 설계: design/SPEC.md, 시안 design/wakecar-demo.html.
 * - Pleos Connect 다크 테마, 장면(하늘, 해, 언덕) 위의 3D 인형 캐릭터, 깨우는 장면에만 새벽빛.
 * - 해는 동쪽(왼쪽)에서 떠서 서쪽(오른쪽)으로 지고 거꾸로 움직이지 않는다. 잠들면 지고, 깨우기 1단계부터 다시 뜬다.
 * - 차량 화면은 해상도에 비해 밀도 값이 낮게 잡혀 있어 글자가 작게 나온다. 디자인 기준 크기(가로 1700x1184, 세로 840x1184)에
 *   화면이 들어가도록 밀도를 다시 잡아 1dp = 시안 1px로 그린다.
 */
@Composable
fun WakeCarScreen(ui: UiState, controller: NapController, onRequestSpeed: () -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Tone.Paper).safeDrawingPadding()) {
        val system = LocalDensity.current
        val land = maxWidth > maxHeight
        val wPx = with(system) { maxWidth.toPx() }
        val hPx = with(system) { maxHeight.toPx() }
        val scale = minOf(wPx / (if (land) 1700f else 840f), hPx / 1184f)
        CompositionLocalProvider(LocalDensity provides Density(scale, 1f)) {
            Screen(ui, controller, land, onRequestSpeed)
        }
    }
}

@Composable
private fun Screen(ui: UiState, c: NapController, land: Boolean, onRequestSpeed: () -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    var showGrow by remember { mutableStateOf(false) }
    var sweeping by remember { mutableStateOf(false) }
    var favoriteKey by remember { mutableIntStateOf(0) }
    val sun = remember { Animatable(-0.12f) }
    val nap = ui.nap
    val hour = ui.clockMinuteOfDay / 60f

    // 장면의 하늘과 해 목표
    val (targetSky, targetSun) = when (nap.phase) {
        Phase.SETUP -> skyOfHour(hour) to sunOfHour(hour)
        Phase.SLEEPING -> if (nap.stage == 0) Sky.NIGHT to 1.14f else Sky.DAWN to 0.105f
        Phase.WAKING -> Sky.DAWN to listOf(.105f, .105f, .18f, .21f, .24f)[nap.stage.coerceIn(0, 4)]
        Phase.GUARD -> Sky.MORNING to .33f
        Phase.REST, Phase.DONE -> Sky.MORNING to .39f
    }
    LaunchedEffect(targetSun, sweeping) {
        if (sweeping) {
            sun.snapTo(-0.14f)
            sun.animateTo(1.16f, tween(14_000, easing = LinearEasing))
            sweeping = false
            return@LaunchedEffect
        }
        val cur = sun.value
        if (targetSun < cur - 0.01f && cur > 0f) {
            // 해는 거꾸로 가지 않는다: 서쪽으로 지고, 동쪽 지평선 아래로 옮겨 다시 뜬다
            if (cur < 1.14f) sun.animateTo(1.14f, tween(1400, easing = FastOutSlowInEasing))
            sun.snapTo(-0.14f)
            if (targetSun > -0.14f) sun.animateTo(targetSun, tween(1800, easing = FastOutSlowInEasing))
        } else if (targetSun < cur - 0.01f) {
            sun.snapTo(targetSun)
        } else {
            sun.animateTo(targetSun, tween((900 + 2600 * abs(targetSun - cur).coerceAtMost(1f)).toInt(), easing = FastOutSlowInEasing))
        }
    }
    val t = sun.value
    val sky = when {
        sweeping -> skyOfSun(t)
        nap.phase == Phase.SLEEPING && nap.stage == 0 && t > 0f && t < 1f -> Sky.EVENING
        else -> targetSky
    }

    Box(Modifier.fillMaxSize()) {
        when (nap.phase) {
            Phase.SETUP -> SetupScreen(ui, c, land, sky, t, favoriteKey, { showSettings = true }, { showGrow = true }, onRequestSpeed)
            Phase.SLEEPING -> SleepingScreen(ui, c, sky, t)
            Phase.WAKING -> WakingScreen(ui, c, land, sky, t)
            Phase.GUARD -> GuardScreen(ui, c, land, sky, t)
            Phase.REST -> RestScreen(ui, c, land, sky, t)
            Phase.DONE -> DoneScreen(ui, c, land, sky, t) { showGrow = true }
        }
        GainFloat(ui)
        LevelUpToast(ui)
    }
    if (showSettings) SettingsSheet(ui, c, onClose = { showSettings = false }, onSunSweep = { showSettings = false; sweeping = true }, onCharacterPicked = { favoriteKey++ })
    if (showGrow) GrowSheet(ui, c) { showGrow = false }
}
