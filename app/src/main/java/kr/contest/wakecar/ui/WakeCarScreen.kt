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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
import kr.contest.wakecar.core.Interruption
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.core.StartBlock

/**
 * 화면 원칙: 큰 글자와 큰 버튼, 움직이는 그림 없음, 한 화면에 할 일 하나.
 * 정차(P) 중에만 쓰지만 잠에서 막 깬 사람이 읽는다는 점을 기준으로 잡았다.
 */
private object Palette {
    val Background = Color(0xFF0C1016)
    val Surface = Color(0xFF171D26)
    val SurfaceHigh = Color(0xFF232C38)
    val Text = Color(0xFFECF0F4)
    val Muted = Color(0xFF93A0AE)
    /** 자는 화면 글자. 눈이 부시지 않게 어둡게. */
    val Dim = Color(0xFF3B4552)
    /** 깨우기 강조색(아침 햇빛). */
    val Sunrise = Color(0xFFFFB347)
    val OnSunrise = Color(0xFF1B1204)
    val Ok = Color(0xFF6FD08C)
    val Alert = Color(0xFFFF6F61)
}

@Composable
fun WakeCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Sunrise,
            onPrimary = Palette.OnSunrise,
            background = Palette.Background,
            surface = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            error = Palette.Alert,
        ),
        content = content,
    )
}

@Composable
fun WakeCarScreen(ui: UiState, controller: NapController) {
    val asleep = ui.nap.phase == Phase.SLEEPING && ui.nap.interruption == null
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(if (asleep) Color.Black else Palette.Background)
            .safeDrawingPadding(),
    ) {
        // 차량 화면은 해상도에 비해 밀도 값이 낮게 잡혀 있어(예: 2560x1440, 160dpi) 글자가 작게 나온다.
        // 화면 폭을 기준 폭으로 나눈 값으로 전체 크기를 맞춘다. 시스템 밀도보다 작게는 줄이지 않는다.
        val system = LocalDensity.current
        val widthPx = with(system) { maxWidth.toPx() }
        val scale = maxOf(system.density, widthPx / DESIGN_WIDTH_DP)
        CompositionLocalProvider(LocalDensity provides Density(scale, system.fontScale)) {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 28.dp)) {
                when (ui.nap.phase) {
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

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
        Column(
            modifier = Modifier.weight(1.5f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("일어나차", color = Palette.Sunrise, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text("얼마나 잘까요?", color = Palette.Text, fontSize = 44.sp, fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(10, 15, 20).forEach { minutes ->
                    BigButton(
                        text = "${minutes}분",
                        selected = !ui.plan.byReturnTime && ui.plan.minutes == minutes,
                        onClick = { c.selectMinutes(minutes) },
                        modifier = Modifier.weight(1f),
                    )
                }
                BigButton(
                    text = "시각으로",
                    selected = ui.plan.byReturnTime,
                    onClick = c::selectReturnTime,
                    modifier = Modifier.weight(1.2f),
                )
            }

            if (ui.plan.byReturnTime) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BigButton("5분 앞으로", onClick = { c.shiftReturnTime(-5) })
                    Text(
                        text = "${clock(ui.plan.returnByMinuteOfDay)}까지 자리로",
                        color = Palette.Text,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    BigButton("5분 뒤로", onClick = { c.shiftReturnTime(5) })
                }
                Caption("걸어가는 ${NapPlan.WALK_MINUTES}분과 잠 깨는 ${NapPlan.WAKE_MINUTES}분을 빼고 깨워 드려요.")
            }

            Text(
                text = when {
                    napMinutes == null -> "돌아갈 시각까지 잘 시간이 부족해요."
                    ui.demo -> "${napMinutes}분 낮잠을 ${napMinutes * ui.msPerNapMinute / 1000}초로 줄여 보여 드려요."
                    else -> "${clock(ui.clockMinuteOfDay + napMinutes)}에 깨워 드릴게요. ${napMinutes}분 잡니다."
                },
                color = if (napMinutes == null) Palette.Alert else Palette.Text,
                fontSize = 28.sp,
            )

            BigButton(
                text = "낮잠 시작",
                primary = true,
                enabled = canStart,
                onClick = c::start,
                modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp),
            )
            ui.startBlock?.let { Text(blockMessage(it), color = Palette.Alert, fontSize = 24.sp) }

            if (ui.vehicle.runsOnFuel == true) {
                // 엔진차는 공조를 켠 채 쉬면 공회전이 된다(서울은 5~25도에서 2분 제한).
                Text(
                    "엔진차는 시동을 끄고 전원만 켠 채 쉬세요. 공회전 제한이 있어요.",
                    color = Palette.Sunrise,
                    fontSize = 22.sp,
                )
            }
            Caption("지하주차장처럼 막힌 곳에서는 시동을 켠 채 자지 마세요.")
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Panel(title = "시작 전 확인") {
                val v = ui.vehicle
                StatusRow("기어", v.inPark?.let { if (it) "P" else "P에 놓아 주세요" }, good = v.inPark)
                StatusRow("문", v.anyDoorOpen?.let { if (it) "열려 있어요" else "닫힘" }, good = v.anyDoorOpen?.not())
                StatusRow(
                    "문 잠금",
                    v.driverDoorLocked?.let { if (it) "잠김" else "잠그고 주무세요" },
                    good = v.driverDoorLocked,
                )
                StatusRow(
                    if (v.runsOnFuel == true) "연료" else "배터리",
                    v.energyPercent?.let { "$it%" },
                    good = v.energyPercent?.let { it >= 15 },
                )
                if (v.chargePortOpen == true) {
                    StatusRow("충전", "충전하는 동안 쉬어 가세요", good = true)
                }
            }
            Panel(title = "차가 해 주는 일") {
                SupportRow("등받이 젖히고 세우기", ui.support.seat)
                SupportRow("조명 낮추고 밝히기", ui.support.ambientLight)
                SupportRow("바깥 공기 들이기", ui.support.freshAir)
                SupportRow("바람 세기 올리기", ui.support.fan)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("시연 모드", color = Palette.Text, fontSize = 24.sp)
                    Caption("낮잠 1분을 2초로 줄입니다.")
                }
                BigButton(
                    text = if (ui.demo) "켜짐" else "꺼짐",
                    selected = ui.demo,
                    onClick = { c.setDemo(!ui.demo) },
                    modifier = Modifier.width(160.dp),
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
    val interruption = ui.nap.interruption
    if (interruption != null) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = when (interruption) {
                    Interruption.DOOR_OPENED -> "문이 열렸어요."
                    Interruption.BRAKE_PRESSED -> "브레이크를 밟으셨어요."
                },
                color = Palette.Muted,
                fontSize = 30.sp,
            )
            Text("낮잠을 끝낼까요?", color = Palette.Text, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                BigButton("계속 자기", onClick = c::continueSleep, modifier = Modifier.width(280.dp))
                BigButton("끝내기", primary = true, onClick = c::endNap, modifier = Modifier.width(280.dp))
            }
        }
        return
    }

    val preWake = ui.nap.stage >= 1
    val color = if (preWake) Palette.Muted else Palette.Dim
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (preWake) "곧 깨워 드릴게요" else "${ui.napMinutesLeft}분 뒤에 깨워 드려요",
                color = color,
                fontSize = 48.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text("문이 열리거나 기어를 바꾸면 바로 멈춥니다.", color = color, fontSize = 22.sp)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .heightIn(min = 76.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, color, RoundedCornerShape(20.dp))
                .clickable(role = Role.Button, onClick = c::endNap)
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("그만 자기", color = color, fontSize = 24.sp)
        }
    }
}

// ------------------------------------------------------------------ 깨우기

@Composable
private fun WakingScreen(ui: UiState, c: NapController) {
    val nap = ui.nap
    val mission = nap.mission ?: return
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
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
                color = Palette.Sunrise,
                fontSize = 36.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = mission.prompt,
                color = Palette.Text,
                fontSize = 60.sp,
                lineHeight = 72.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(mission.steps) { index ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (index < nap.missionStep) Palette.Sunrise else Palette.SurfaceHigh),
                    )
                }
                Text(
                    text = "${nap.missionStep} / ${mission.steps}",
                    color = Palette.Muted,
                    fontSize = 26.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Caption("화면을 눌러서는 꺼지지 않아요. 차를 직접 움직여 주세요.")
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        ) {
            Panel(title = "깨우기 ${nap.stage}단계") {
                StageRow(1, "조명 밝히기", nap.stage)
                StageRow(2, "등받이 세우고 알람", nap.stage)
                StageRow(3, "바깥 공기와 바람", nap.stage)
                StageRow(4, "바람과 알람 최대", nap.stage)
            }
            if (ui.support.seat == false) Caption("등받이는 직접 세워 주세요.")
            if (nap.stage >= 3 && ui.support.freshAir == false) Caption("창문을 열어 바깥 공기를 쐬세요.")
            EmergencyStop(onStop = c::emergencyStop)
        }
    }
}

// ------------------------------------------------------------------ 확인 질문

@Composable
private fun GuardScreen(ui: UiState, c: NapController) {
    val nap = ui.nap
    val question = nap.question ?: return
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "알람을 멈췄어요. 확인 ${nap.questionNumber} / ${nap.questionCount}",
            color = Palette.Muted,
            fontSize = 26.sp,
        )
        Text(question.text, color = Palette.Text, fontSize = 52.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            question.choices.forEachIndexed { index, choice ->
                BigButton(
                    text = choice,
                    onClick = { c.answer(index) },
                    modifier = Modifier.widthIn(min = 260.dp).heightIn(min = 120.dp),
                )
            }
        }
        if (nap.wrongAnswer) Text("다시 골라 주세요.", color = Palette.Alert, fontSize = 26.sp)
        Caption("${ui.guardSecondsLeft}초 안에 답이 없으면 다시 깨워 드려요.")
        EmergencyStop(onStop = c::emergencyStop)
    }
}

// ------------------------------------------------------------------ 끝

@Composable
private fun DoneScreen(ui: UiState, c: NapController) {
    val nap = ui.nap
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (nap.endReason) {
                EndReason.COMPLETED ->
                    if (nap.needsStretch) "2분만 몸을 풀고 움직이세요" else "좋아요, 늦지 않게 다녀오세요"
                EndReason.LEFT_CAR -> "잘 다녀오세요"
                EndReason.GEAR_LEFT_PARK -> "기어가 P에서 벗어나 낮잠을 끝냈어요"
                EndReason.EMERGENCY_STOP -> "알람을 멈췄어요"
                EndReason.USER_ENDED, null -> "낮잠을 끝냈어요"
            },
            color = Palette.Text,
            fontSize = 52.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("잔 시간", "${nap.sleptMs / ui.msPerNapMinute}분")
            nap.wakeResponseMs?.let { Stat("깨는 데 걸린 시간", "${(it + 999) / 1000}초") }
            if (nap.realarmCount > 0) Stat("다시 깨운 횟수", "${nap.realarmCount}번")
        }
        if (nap.chargeReminder) {
            Text(
                "충전이 끝났는지 확인하고 충전 케이블을 분리해 주세요.",
                color = Palette.Sunrise,
                fontSize = 26.sp,
            )
        }
        if (ui.support.seatLeftReclined) {
            // 주행 기어에서는 차가 등받이 움직임을 막는다.
            Text("등받이는 직접 세워 주세요.", color = Palette.Sunrise, fontSize = 26.sp)
            Caption("조명과 공조는 낮잠 전 상태로 되돌렸어요.")
        } else {
            Caption("등받이, 조명, 공조를 낮잠 전 상태로 되돌렸어요.")
        }
        BigButton("처음으로", primary = true, onClick = c::reset, modifier = Modifier.width(320.dp))
    }
}

// ------------------------------------------------------------------ 공통 부품

@Composable
private fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    selected: Boolean = false,
) {
    val highlighted = primary || selected
    val background = when {
        !enabled -> Palette.Surface
        highlighted -> Palette.Sunrise
        else -> Palette.SurfaceHigh
    }
    val foreground = when {
        !enabled -> Palette.Dim
        highlighted -> Palette.OnSunrise
        else -> Palette.Text
    }
    Box(
        modifier = modifier
            .heightIn(min = 88.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = foreground, fontSize = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Surface)
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, color = Palette.Muted, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

/** good: true = 괜찮음, false = 손봐야 함, null = 아직 모름. */
@Composable
private fun StatusRow(label: String, value: String?, good: Boolean?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontSize = 24.sp, modifier = Modifier.weight(1f))
        Text(
            text = value ?: "읽을 수 없음",
            color = when (good) {
                true -> Palette.Ok
                false -> Palette.Alert
                null -> Palette.Muted
            },
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SupportRow(label: String, supported: Boolean?) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.Text, fontSize = 24.sp, modifier = Modifier.weight(1f))
        Text(
            text = when (supported) {
                true -> "차가 합니다"
                false -> "화면으로 안내"
                null -> "확인 중"
            },
            color = if (supported == true) Palette.Ok else Palette.Muted,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StageRow(stage: Int, label: String, current: Int) {
    val color = when {
        stage == current -> Palette.Sunrise
        stage < current -> Palette.Text
        else -> Palette.Dim
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("$stage", color = color, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(label, color = color, fontSize = 24.sp)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Surface)
            .padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = Palette.Muted, fontSize = 22.sp)
        Text(value, color = Palette.Text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = Palette.Muted, fontSize = 22.sp)
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
            .clip(RoundedCornerShape(20.dp))
            .border(2.dp, Palette.SurfaceHigh, RoundedCornerShape(20.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        val released = withTimeoutOrNull(EMERGENCY_HOLD_MS) { tryAwaitRelease() }
                        if (released == null) onStop()
                    },
                )
            }
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("비상 종료는 3초간 길게 누르세요", color = Palette.Muted, fontSize = 22.sp)
    }
}

private const val EMERGENCY_HOLD_MS = 3_000L

private fun clock(minuteOfDay: Int): String {
    val m = minuteOfDay.mod(NapPlan.MINUTES_PER_DAY)
    return "%d:%02d".format(m / 60, m % 60)
}
