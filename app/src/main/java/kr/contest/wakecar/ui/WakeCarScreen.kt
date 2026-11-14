package kr.contest.wakecar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kr.contest.wakecar.NapController
import kr.contest.wakecar.NapPlan
import kr.contest.wakecar.UiState
import kr.contest.wakecar.core.EndReason
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.core.StartBlock

/**
 * 디자인 방향 (ui-ux-pro-max 스킬의 추천을 따름)
 * - 스타일: 절제된 스위스 스타일. 격자에 맞춘 넉넉한 여백, 큰 글자, 높은 대비, 꼭 필요한 것만.
 * - 색: 수면 앱 팔레트 "밤의 남색과 보라". 깨우는 장면에만 새벽빛(호박색)을 더해 자는 장면과 구분한다.
 * - 접근성: 본문 대비 7:1 안팎, 터치 영역 76dp 이상, 색만으로 상태를 전하지 않는다(점과 글자를 함께).
 * - 움직이는 그림은 쓰지 않는다(차량 화면 원칙).
 */
private object Tone {
    val Night = Color(0xFF0F172A)
    val NightDeep = Color(0xFF0A1020)
    val Card = Color(0xFF192134)
    val CardHigh = Color(0xFF26304A)
    val Border = Color(0x1FFFFFFF)
    val Text = Color(0xFFFFFFFF)
    val TextSoft = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF94A3B8)
    /** 자는 화면 글자. 눈이 부시지 않게 어둡게. */
    val Faint = Color(0xFF475569)
    val Indigo = Color(0xFF4338CA)
    val Violet = Color(0xFF7C3AED)
    val Periwinkle = Color(0xFF818CF8)
    /** 깨우는 장면의 새벽빛. */
    val Dawn = Color(0xFFFBBF24)
    val OnDawn = Color(0xFF1C1917)
    val Ok = Color(0xFF34D399)
    val Danger = Color(0xFFF87171)
}

private object Type {
    val Overline = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
    val Hero = TextStyle(fontSize = 120.sp, fontWeight = FontWeight.Bold, lineHeight = 124.sp, letterSpacing = (-2).sp)
    val Display = TextStyle(fontSize = 58.sp, fontWeight = FontWeight.Bold, lineHeight = 72.sp)
    val Title = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.SemiBold, lineHeight = 42.sp)
    val Body = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Normal, lineHeight = 34.sp)
    val Label = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
    val Caption = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Normal, lineHeight = 30.sp)
}

private val Pill = RoundedCornerShape(50)
private val CardShape = RoundedCornerShape(28.dp)

@Composable
fun WakeCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Tone.Violet,
            onPrimary = Tone.Text,
            secondary = Tone.Periwinkle,
            tertiary = Tone.Dawn,
            onTertiary = Tone.OnDawn,
            background = Tone.Night,
            surface = Tone.Card,
            onBackground = Tone.Text,
            onSurface = Tone.Text,
            error = Tone.Danger,
        ),
        content = content,
    )
}

@Composable
fun WakeCarScreen(ui: UiState, controller: NapController) {
    val phase = ui.nap.phase
    val asleep = phase == Phase.SLEEPING
    val background = when {
        asleep -> Brush.verticalGradient(listOf(Color.Black, Color.Black))
        // 깨우는 장면: 왼쪽 위에서 새벽빛이 번진다.
        phase == Phase.WAKING -> Brush.radialGradient(
            colors = listOf(Tone.Dawn.copy(alpha = 0.26f), Tone.Night, Tone.NightDeep),
            center = Offset(0f, 0f),
            radius = 2200f,
        )
        else -> Brush.verticalGradient(listOf(Tone.Night, Tone.NightDeep))
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
        // 차량 화면은 해상도에 비해 밀도 값이 낮게 잡혀 있어(예: 2560x1440, 160dpi) 글자가 작게 나온다.
        // 화면 폭을 기준 폭으로 나눈 값으로 전체 크기를 맞춘다. 시스템 밀도보다 작게는 줄이지 않는다.
        val system = LocalDensity.current
        val widthPx = with(system) { maxWidth.toPx() }
        val scale = maxOf(system.density, widthPx / DESIGN_WIDTH_DP)
        CompositionLocalProvider(LocalDensity provides Density(scale, system.fontScale)) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)) {
                when (phase) {
                    Phase.SETUP -> SetupScreen(ui, controller)
                    Phase.SLEEPING -> SleepingScreen(ui, controller)
                    Phase.WAKING -> WakingScreen(ui, controller)
                    Phase.GUARD -> GuardScreen(ui, controller)
                    Phase.DONE -> DoneScreen(ui, controller)
                }
            }
        }
    }
}

/** 화면 폭을 이 값(dp)으로 보고 크기를 잡는다. */
private const val DESIGN_WIDTH_DP = 1280f

// ------------------------------------------------------------------ 시작 화면

@Composable
private fun SetupScreen(ui: UiState, c: NapController) {
    val napMinutes = ui.plan.napMinutes(ui.clockMinuteOfDay)
    val canStart = ui.startBlock == null && napMinutes != null

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(modifier = Modifier.weight(1.45f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            Text("일어나차", color = Tone.Periwinkle, style = Type.Overline)
            Spacer(Modifier.height(20.dp))

            // 주인공은 깨울 시각이다. 가장 크게 보여 준다.
            when {
                napMinutes == null -> {
                    Text("잘 시간이 부족해요", color = Tone.Text, style = Type.Display)
                    Text("돌아갈 시각을 늦추거나 다음에 쉬어 가세요.", color = Tone.TextMuted, style = Type.Body)
                }
                ui.demo -> {
                    val seconds = napMinutes * ui.msPerNapMinute / 1000
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${seconds}초", color = Tone.Text, style = Type.Hero)
                        Text(
                            "뒤에 깨워 드릴게요",
                            color = Tone.TextSoft,
                            style = Type.Title,
                            modifier = Modifier.padding(start = 20.dp, bottom = 24.dp),
                        )
                    }
                    Text("${napMinutes}분 낮잠을 ${seconds}초로 줄여 보여 드려요.", color = Tone.TextMuted, style = Type.Body)
                }
                else -> {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(clock(ui.clockMinuteOfDay + napMinutes), color = Tone.Text, style = Type.Hero)
                        Text(
                            "에 깨워 드릴게요",
                            color = Tone.TextSoft,
                            style = Type.Title,
                            modifier = Modifier.padding(start = 20.dp, bottom = 24.dp),
                        )
                    }
                    Text(
                        if (napMinutes > 20) "${napMinutes}분 잡니다. 20분보다 길게 자면 깬 뒤 더 멍할 수 있어요." else "${napMinutes}분 잡니다.",
                        color = Tone.TextMuted,
                        style = Type.Body,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            // 얼마나 잘지 고르는 칸. 한 덩어리로 묶어 선택지임을 보여 준다.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Pill)
                    .background(Tone.Card)
                    .border(1.dp, Tone.Border, Pill)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(10, 15, 20).forEach { minutes ->
                    Segment(
                        text = "${minutes}분",
                        selected = !ui.plan.byReturnTime && ui.plan.minutes == minutes,
                        onClick = { c.selectMinutes(minutes) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Segment("시각으로", ui.plan.byReturnTime, c::selectReturnTime, Modifier.weight(1.25f))
            }

            if (ui.plan.byReturnTime) {
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GhostButton("5분 앞으로", onClick = { c.shiftReturnTime(-5) })
                    Text(
                        "${clock(ui.plan.returnByMinuteOfDay)}까지 자리로",
                        color = Tone.Text,
                        style = Type.Title,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    GhostButton("5분 뒤로", onClick = { c.shiftReturnTime(5) })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "걸어가는 ${NapPlan.WALK_MINUTES}분과 잠 깨는 ${NapPlan.WAKE_MINUTES}분을 빼고 깨워 드려요.",
                    color = Tone.TextMuted,
                    style = Type.Caption,
                )
            }

            Spacer(Modifier.height(28.dp))
            PrimaryButton("정차 중이에요. 낮잠 시작", enabled = canStart, onClick = c::start, modifier = Modifier.fillMaxWidth())
            ui.startBlock?.let {
                Spacer(Modifier.height(14.dp))
                Notice(blockMessage(it), Tone.Danger)
            }

            Spacer(Modifier.height(18.dp))
            if (ui.vehicle.runsOnFuel == true) {
                // 엔진차는 공조를 켠 채 쉬면 공회전이 된다(서울은 5~25도에서 2분 제한).
                Notice("엔진차는 시동을 끄고 전원만 켠 채 쉬세요. 공회전 제한이 있어요.", Tone.Dawn)
                Spacer(Modifier.height(8.dp))
            }
            Text("지하주차장처럼 막힌 곳에서는 시동을 켠 채 자지 마세요.", color = Tone.TextMuted, style = Type.Caption)
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Panel("시작 전 확인") {
                val v = ui.vehicle
                StatusRow("기어", v.inPark?.let { if (it) "P" else "P에 놓아 주세요" }, good = v.inPark)
                StatusRow("문", v.anyDoorOpen?.let { if (it) "열려 있어요" else "닫힘" }, good = v.anyDoorOpen?.not())
                StatusRow("문 잠금", v.driverDoorLocked?.let { if (it) "잠김" else "잠그고 주무세요" }, good = v.driverDoorLocked)
                StatusRow(
                    if (v.runsOnFuel == true) "연료" else "배터리",
                    v.energyPercent?.let { "$it%" },
                    good = v.energyPercent?.let { it >= 15 },
                )
                if (v.chargePortOpen == true) StatusRow("충전", "충전하는 동안 쉬어 가세요", good = true)
            }
            Panel("차가 해 주는 일") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SupportTile("조명 조절", ui.support.ambientLight, Modifier.weight(1f))
                    SupportTile("바람 세기", ui.support.fan, Modifier.weight(1f))
                }
                Text("등받이는 직접 젖혀 주세요. 앱은 등받이를 뒤로 젖히지 않습니다.", color = Tone.TextMuted, style = Type.Caption)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CardShape)
                    .clickable(role = Role.Switch) { c.setDemo(!ui.demo) }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("시연 모드", color = Tone.Text, style = Type.Label)
                    Text("낮잠 1분을 2초로 줄입니다.", color = Tone.TextMuted, style = Type.Caption)
                }
                Text(
                    if (ui.demo) "켜짐" else "꺼짐",
                    color = if (ui.demo) Tone.Periwinkle else Tone.TextMuted,
                    style = Type.Label,
                )
                Switch(
                    checked = ui.demo,
                    onCheckedChange = { c.setDemo(it) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Tone.Violet,
                        checkedThumbColor = Tone.Text,
                        uncheckedTrackColor = Tone.CardHigh,
                        uncheckedThumbColor = Tone.TextMuted,
                        uncheckedBorderColor = Tone.Border,
                    ),
                )
            }
        }
    }
}

private fun blockMessage(block: StartBlock): String = when (block) {
    StartBlock.NOT_CONNECTED -> "차량 정보를 읽고 있어요."
    StartBlock.NOT_IN_PARK -> "기어를 P에 놓으면 시작할 수 있어요."
    StartBlock.DOOR_OPEN -> "문을 닫으면 시작할 수 있어요."
    StartBlock.LOW_ENERGY -> "배터리나 연료가 15% 아래라 쉬어 가기 어려워요."
}

// ------------------------------------------------------------------ 자는 중

@Composable
private fun SleepingScreen(ui: UiState, c: NapController) {
    val preWake = ui.nap.stage >= 1
    val color = if (preWake) Tone.TextMuted else Tone.Faint
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            if (preWake) {
                Text("곧 깨워 드릴게요", color = color, style = Type.Display)
            } else {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${ui.napMinutesLeft}",
                        color = color,
                        style = Type.Hero.copy(fontSize = 168.sp, lineHeight = 172.sp, fontWeight = FontWeight.Light),
                    )
                    Text("분", color = color, style = Type.Title, modifier = Modifier.padding(start = 12.dp, bottom = 36.dp))
                }
                Text("뒤에 깨워 드려요", color = color, style = Type.Title)
            }
            Spacer(Modifier.height(20.dp))
            Text("문이 열리거나 기어를 바꾸면 바로 멈춥니다.", color = color, style = Type.Caption)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .heightIn(min = 76.dp)
                .clip(Pill)
                .border(1.5.dp, color, Pill)
                .clickable(role = Role.Button, onClick = c::endNap)
                .padding(horizontal = 36.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("그만 자기", color = color, style = Type.Label)
        }
    }
}

// ------------------------------------------------------------------ 깨우기

@Composable
private fun WakingScreen(ui: UiState, c: NapController) {
    val nap = ui.nap
    val mission = nap.mission ?: return
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        Column(
            modifier = Modifier.weight(1.5f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = when {
                    nap.realarmCount > 0 -> "다시 잠드신 것 같아요"
                    nap.wokenEarlyForEnergy -> "배터리가 부족해 일찍 깨웠어요"
                    else -> "일어날 시간이에요"
                },
                color = Tone.Dawn,
                style = Type.Title,
            )
            Text(mission.prompt, color = Tone.Text, style = Type.Display.copy(fontSize = 64.sp, lineHeight = 80.sp))

            // 미션 진행: 칸이 하나씩 채워진다. 숫자도 함께 적어 색에만 기대지 않는다.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(mission.steps) { index ->
                    Box(
                        modifier = Modifier
                            .width(120.dp)
                            .height(16.dp)
                            .clip(Pill)
                            .background(if (index < nap.missionStep) Tone.Dawn else Tone.CardHigh),
                    )
                }
                Text(
                    "${nap.missionStep} / ${mission.steps}",
                    color = Tone.TextSoft,
                    style = Type.Label,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = Tone.TextMuted, modifier = Modifier.size(26.dp))
                Text("화면을 눌러서는 꺼지지 않아요. 차를 직접 움직여 주세요.", color = Tone.TextMuted, style = Type.Caption)
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
        ) {
            Panel("깨우기 ${nap.stage}단계") {
                StageRow(1, "조명 밝히기", nap.stage)
                StageRow(2, "알람과 깨어남 미션", nap.stage)
                StageRow(3, "바람 세게, 알람 빠르게", nap.stage)
                StageRow(4, "알람 최대", nap.stage)
            }
            EmergencyStop(onStop = c::emergencyStop)
        }
    }
}

// ------------------------------------------------------------------ 확인 질문

@Composable
private fun GuardScreen(ui: UiState, c: NapController) {
    val nap = ui.nap
    val question = nap.question ?: return
    val total = if (ui.demo) 20 else 180
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("알람을 멈췄어요. 확인 ${nap.questionNumber} / ${nap.questionCount}", color = Tone.Periwinkle, style = Type.Title)
        Text(question.text, color = Tone.Text, style = Type.Display, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            question.choices.forEach { choice ->
                Box(
                    modifier = Modifier
                        .widthIn(min = 290.dp)
                        .heightIn(min = 132.dp)
                        .clip(CardShape)
                        .background(Tone.Card)
                        .border(1.dp, Tone.Border, CardShape)
                        .clickable(role = Role.Button) { c.answer() }
                        .padding(horizontal = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(choice, color = Tone.Text, style = Type.Title, textAlign = TextAlign.Center)
                }
            }
        }
        // 알람이 멈춘 뒤 한 번만 5분 미룰 수 있다.
        if (!nap.snoozeUsed) GhostButton("5분만 더", onClick = c::snooze)

        // 남은 시간 막대. 답이 없으면 다시 깨운다는 것을 눈으로 알 수 있게 한다.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.width(520.dp).height(8.dp).clip(Pill).background(Tone.CardHigh)) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((ui.guardSecondsLeft.toFloat() / total).coerceIn(0f, 1f))
                        .clip(Pill)
                        .background(Tone.Periwinkle),
                )
            }
            Text("${ui.guardSecondsLeft}초 동안 아무 조작이 없으면 다시 깨워 드려요.", color = Tone.TextMuted, style = Type.Caption)
        }
        EmergencyStop(onStop = c::emergencyStop)
    }
}

// ------------------------------------------------------------------ 끝

@Composable
private fun DoneScreen(ui: UiState, c: NapController) {
    val nap = ui.nap
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Tone.Ok.copy(alpha = 0.16f))
                .border(2.dp, Tone.Ok, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Tone.Ok, modifier = Modifier.size(48.dp))
        }
        Text(
            text = when (nap.endReason) {
                EndReason.COMPLETED -> "10분만 바람 쐬고 움직이세요"
                EndReason.DOOR_OPENED -> "문이 열려 낮잠을 끝냈어요"
                EndReason.BRAKE_PRESSED -> "브레이크를 밟아 낮잠을 끝냈어요"
                EndReason.LEFT_CAR -> "잘 다녀오세요"
                EndReason.GEAR_LEFT_PARK -> "기어가 P에서 벗어나 낮잠을 끝냈어요"
                EndReason.EMERGENCY_STOP -> "알람을 멈췄어요"
                EndReason.USER_ENDED, null -> "낮잠을 끝냈어요"
            },
            color = Tone.Text,
            style = Type.Display,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("잔 시간", "${nap.sleptMs / ui.msPerNapMinute}분")
            nap.wakeResponseMs?.let { Stat("깨는 데 걸린 시간", "${(it + 999) / 1000}초") }
            if (nap.realarmCount > 0) Stat("다시 깨운 횟수", "${nap.realarmCount}번")
        }
        if (nap.chargeReminder) Notice("충전이 끝났는지 확인하고 충전 케이블을 분리해 주세요.", Tone.Dawn)
        Text("조명과 공조를 낮잠 전 상태로 되돌렸어요.", color = Tone.TextMuted, style = Type.Caption)
        PrimaryButton("처음으로", onClick = c::reset, modifier = Modifier.width(340.dp))
    }
}

// ------------------------------------------------------------------ 공통 부품

/** 화면에서 가장 중요한 행동 하나. 보라에서 남색으로 이어지는 바탕. */
@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val brush = if (enabled) {
        Brush.horizontalGradient(listOf(Tone.Violet, Tone.Indigo))
    } else {
        Brush.horizontalGradient(listOf(Tone.Card, Tone.Card))
    }
    Box(
        modifier = modifier
            .heightIn(min = 96.dp)
            .clip(Pill)
            .background(brush)
            .then(if (enabled) Modifier else Modifier.border(1.dp, Tone.Border, Pill))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) Tone.Text else Tone.Faint, style = Type.Title)
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 80.dp)
            .clip(Pill)
            .background(Tone.Card)
            .border(1.dp, Tone.Border, Pill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Tone.Text, style = Type.Label)
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .heightIn(min = 84.dp)
            .clip(Pill)
            .background(if (selected) Tone.Violet else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Tone.Text else Tone.TextSoft, style = Type.Title.copy(fontSize = 28.sp))
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Tone.Card)
            .border(1.dp, Tone.Border, CardShape)
            .padding(horizontal = 28.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(title, color = Tone.TextMuted, style = Type.Caption.copy(fontWeight = FontWeight.SemiBold))
        content()
    }
}

/** good: true = 괜찮음, false = 손봐야 함, null = 아직 모름. 점과 글자를 함께 써서 색에만 기대지 않는다. */
@Composable
private fun StatusRow(label: String, value: String?, good: Boolean?) {
    val color = when (good) {
        true -> Tone.Ok
        false -> Tone.Danger
        null -> Tone.TextMuted
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Tone.TextSoft, style = Type.Body, modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(
            value ?: "읽을 수 없음",
            color = if (good == null) Tone.TextMuted else Tone.Text,
            style = Type.Label,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun SupportTile(label: String, supported: Boolean?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Tone.CardHigh)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Tone.Text, style = Type.Caption.copy(fontWeight = FontWeight.SemiBold))
        Text(
            text = when (supported) {
                true -> "차가 합니다"
                false -> "화면으로 안내"
                null -> "확인 중"
            },
            color = if (supported == true) Tone.Ok else Tone.TextMuted,
            style = Type.Caption,
        )
    }
}

@Composable
private fun StageRow(stage: Int, label: String, current: Int) {
    val active = stage == current
    val passed = stage < current
    val textColor = when {
        active -> Tone.Text
        passed -> Tone.TextSoft
        else -> Tone.Faint
    }
    val ring = when {
        active -> Tone.Dawn
        passed -> Tone.TextMuted
        else -> Tone.Faint
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (active) Tone.Dawn else Color.Transparent)
                .border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (passed) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Tone.TextMuted, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    "$stage",
                    color = if (active) Tone.OnDawn else Tone.Faint,
                    style = Type.Caption.copy(fontWeight = FontWeight.Bold),
                )
            }
        }
        Text(label, color = textColor, style = if (active) Type.Label else Type.Body)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(CardShape)
            .background(Tone.Card)
            .border(1.dp, Tone.Border, CardShape)
            .padding(horizontal = 40.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, color = Tone.TextMuted, style = Type.Caption)
        Text(value, color = Tone.Text, style = Type.Display.copy(fontSize = 44.sp, lineHeight = 52.sp))
    }
}

/** 한 줄 알림. 색에만 기대지 않도록 경고 표시와 글자를 함께 쓴다. */
@Composable
private fun Notice(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Text(text, color = color, style = Type.Body)
    }
}

/**
 * 차량 신호를 읽지 못할 때를 위한 뒷문. 한 번 누르는 것으로는 꺼지지 않고,
 * 3초 동안 누르고 있어야 한다.
 */
@Composable
private fun EmergencyStop(onStop: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 76.dp)
            .clip(Pill)
            .border(1.dp, Tone.Border, Pill)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val released = withTimeoutOrNull(EMERGENCY_HOLD_MS) { tryAwaitRelease() }
                        if (released == null) onStop()
                    },
                )
            }
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("비상 종료는 3초간 길게 누르세요", color = Tone.TextMuted, style = Type.Caption)
    }
}

private const val EMERGENCY_HOLD_MS = 3_000L

private fun clock(minuteOfDay: Int): String {
    val m = minuteOfDay.mod(NapPlan.MINUTES_PER_DAY)
    return "%d:%02d".format(m / 60, m % 60)
}
