@file:OptIn(ExperimentalLayoutApi::class)

package kr.contest.wakecar.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kr.contest.wakecar.NapController
import kr.contest.wakecar.NapPlan
import kr.contest.wakecar.R
import kr.contest.wakecar.UiState
import kr.contest.wakecar.core.EndReason
import kr.contest.wakecar.core.GrowStage
import kr.contest.wakecar.core.Growth
import kr.contest.wakecar.core.Mission
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.core.StartBlock
import kotlin.math.ceil

/** 한국어 낱말 안에서 줄이 바뀌지 않게 한다(CSS word-break: keep-all). 글자 사이에 줄바꿈 금지 문자(U+2060)를 넣는다. */
fun keepAll(s: String): String = buildString {
    s.forEachIndexed { i, c ->
        append(c)
        val next = s.getOrNull(i + 1)
        if (next != null && !c.isWhitespace() && !next.isWhitespace()) append('⁠')
    }
}

/** 낱말 안에서 줄이 바뀌지 않게 그리되, 화면 낭독기와 화면 자동화에는 원래 글자를 알려 준다. */
@Composable
fun KText(text: String, color: Color, style: TextStyle, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(keepAll(text), color = color, style = style, textAlign = textAlign, modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) })
}

/** 장면 안 캐릭터 자리: 가로 비율 x, 바닥선 비율 ground(스프라이트 칸에서 바닥은 위에서 84%). */
@Composable
fun BoxScope.CharacterSpot(ui: UiState, c: NapController, x: Float, ground: Float, size: Dp, pose: Pose, favoriteKey: Int = 0) {
    BoxWithConstraints(Modifier.matchParentSize()) {
        val g = Growth.char(ui.growth, ui.settings.character.asset)
        PlushCharacter(
            character = ui.settings.character,
            outfit = g.wearing,
            pose = pose,
            phase = ui.nap.phase,
            size = size,
            growScale = GrowStage.of(g.level).scale,
            onPet = c::pet,
            favoriteKey = favoriteKey,
            celebrateKey = ui.levelUp?.id ?: 0L,
            modifier = Modifier.offset(x = maxWidth * x - size / 2, y = maxHeight * ground - size * .84f),
        )
        // 쓰다듬기 보상 숫자는 캐릭터 머리 위에서 떠오른다
        Box(Modifier.offset(x = maxWidth * x - 160.dp, y = maxHeight * ground - size * .84f).width(320.dp)) { GainFloat(ui) }
    }
}

/** 낮잠 단계와 키우기 상태로 정하는 기본 표정. */
fun basePose(ui: UiState): Pose = when (ui.nap.phase) {
    Phase.SETUP -> if (Growth.sleepy(ui.growth, ui.wallClock) >= 70) Pose.DROWSY else Pose.AWAKE
    Phase.SLEEPING -> Pose.SLEEP
    Phase.WAKING -> if (ui.nap.stage >= 4) Pose.AWAKE else Pose.DROWSY
    Phase.DONE -> if (ui.nap.endReason == EndReason.COMPLETED) Pose.HAPPY else Pose.AWAKE
    else -> Pose.AWAKE
}

// ------------------------------------------------------------------ 시작 화면

@Composable
fun SetupScreen(ui: UiState, c: NapController, land: Boolean, sky: Sky, sunT: Float, favoriteKey: Int, onSettings: () -> Unit, onGrow: () -> Unit, onRequestSpeed: () -> Unit) {
    if (land) {
        Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            StageCard(ui, c, sky, sunT, favoriteKey, onGrow, onRequestSpeed, Modifier.weight(1.08f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight()) { SetupPanel(ui, c, onSettings, scroll = true) }
        }
    } else {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 240.dp), verticalArrangement = Arrangement.spacedBy(40.dp)) {
                StageCard(ui, c, sky, sunT, favoriteKey, onGrow, onRequestSpeed, Modifier.fillMaxWidth().height(440.dp))
                SetupPanel(ui, c, onSettings, scroll = false, docked = true)
            }
            // 세로 화면: 주 버튼을 아래에 붙인다
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Tone.Paper).padding(horizontal = 48.dp, vertical = 24.dp)) { StartArea(ui, c) }
        }
    }
}

@Composable
private fun StageCard(ui: UiState, c: NapController, sky: Sky, sunT: Float, favoriteKey: Int, onGrow: () -> Unit, onRequestSpeed: () -> Unit, modifier: Modifier) {
    SkyScene(sky, sunT, modifier.clip(StageShape)) {
        BoxWithConstraints(Modifier.matchParentSize()) {
            val size = (maxWidth * .62f).coerceAtMost(maxHeight * .78f)
            CharacterSpot(ui, c, x = .5f, ground = .8f, size = size, pose = basePose(ui), favoriteKey = favoriteKey)
        }
        Column(Modifier.align(Alignment.TopStart).padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.clip(ControlShape).background(Tone.Glass).padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text(clock(ui.clockMinuteOfDay), color = Tone.Ink, style = Type.TitleS.copy(fontWeight = FontWeight.Normal))
            }
            KText(greeting(ui), color = Tone.Ink, style = Type.TitleL, modifier = Modifier.widthIn(max = 420.dp))
        }
        GrowHud(ui, onGrow, Modifier.align(Alignment.TopEnd).padding(28.dp))
        StatusChips(ui, onRequestSpeed, Modifier.align(Alignment.BottomStart).padding(24.dp))
    }
}

private fun greeting(ui: UiState): String {
    if (Growth.sleepy(ui.growth, ui.wallClock) >= 70) return "${callName(ui.settings.character.title)}도 졸려해요"
    return when (skyOfHour(ui.clockMinuteOfDay / 60f)) {
        Sky.DAWN -> "천천히, 아침을 맞아요"
        Sky.MORNING -> "잠깐 쉬어 가도 괜찮아요"
        Sky.EVENING -> "하루의 속도를 낮춰요"
        Sky.NIGHT -> "오늘은 잠시 쉬어 가요"
    }
}

/** 받침 있는 이름은 '이'를 붙여 부른다(콩콩이, 빵삐). */
fun callName(n: String): String = if ((n.last() - '가') % 28 != 0) n + "이" else n

@Composable
private fun StatusChips(ui: UiState, onRequestSpeed: () -> Unit, modifier: Modifier) {
    val v = ui.vehicle
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip(v.inPark?.let { if (it) "기어 P" else "기어 P로" } ?: "기어 확인 중", v.inPark)
        if (v.moving != null) Chip(if (v.moving) "움직이는 중" else "0km/h", !v.moving)
        else Chip("속도 확인하기", null, onClick = onRequestSpeed)
        Chip(v.anyDoorOpen?.let { if (it) "문 열림" else "문 닫힘" } ?: "문 확인 중", v.anyDoorOpen?.not())
        Chip(v.driverDoorLocked?.let { if (it) "잠김" else "잠그고 주무세요" } ?: "잠금 확인 중", v.driverDoorLocked)
        Chip("${if (v.runsOnFuel == true) "연료" else "배터리"} ${v.energyPercent?.let { "$it%" } ?: "확인 중"}", v.energyPercent?.let { it >= 15 })
        if (v.chargePortOpen == true) Chip("충전 중", true)
    }
}

@Composable
private fun Chip(text: String, good: Boolean?, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(Tone.GlassStrong)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (good) {
            true -> Icon(Icons.Filled.Check, null, tint = Tone.Ok, modifier = Modifier.size(28.dp))
            false -> Icon(Icons.Filled.Close, null, tint = Tone.Danger, modifier = Modifier.size(28.dp))
            null -> Box(Modifier.size(12.dp).clip(CircleShape).background(Tone.Muted))
        }
        Text(text, color = Tone.Ink, style = Type.BodyS.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun SetupPanel(ui: UiState, c: NapController, onSettings: () -> Unit, scroll: Boolean, docked: Boolean = false) {
    val napMinutes = ui.plan.napMinutes(ui.clockMinuteOfDay)
    Column(
        Modifier.fillMaxWidth().then(if (scroll) Modifier.fillMaxHeight().verticalScroll(rememberScrollState()) else Modifier),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(painterResource(R.mipmap.ic_launcher), contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
            Text("일어나차", color = Tone.Ink, style = Type.TitleS, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(Tone.Paper3).clickable(role = Role.Button, onClick = onSettings)
                    .semantics { contentDescription = "설정" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Settings, contentDescription = null, tint = Tone.Ink, modifier = Modifier.size(36.dp)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                napMinutes == null -> {
                    Text("잘 시간이 부족해요", color = Tone.Muted, style = Type.BodyL)
                    Text("--:--", color = Tone.Ink, style = Type.Display)
                    KText("돌아갈 시각을 늦추거나 다음에 쉬어 가세요.", color = Tone.Ink2, style = Type.BodyL)
                }
                ui.demo -> {
                    Text("깨우기까지", color = Tone.Muted, style = Type.BodyL)
                    Text("${napMinutes * ui.msPerNapMinute / 1000}초", color = Tone.Ink, style = Type.Display)
                    Text("${napMinutes}분 낮잠 (시연)", color = Tone.Ink2, style = Type.BodyL)
                }
                else -> {
                    Text("깨울 시각", color = Tone.Muted, style = Type.BodyL)
                    Text(clock(ui.clockMinuteOfDay + napMinutes), color = Tone.Ink, style = Type.Display)
                    KText(
                        if (napMinutes > 20) "${napMinutes}분 자요. 20분보다 길게 자면 깬 뒤 더 멍할 수 있어요." else "${napMinutes}분 자요.",
                        color = Tone.Ink2, style = Type.BodyL,
                    )
                }
            }
        }
        // 얼마나 잘지(세그먼트)
        Row(Modifier.fillMaxWidth().clip(ControlShapeL).background(Tone.Paper2).padding(8.dp)) {
            listOf(10, 15, 20).forEach { m -> Segment("${m}분", !ui.plan.byReturnTime && ui.plan.minutes == m, { c.selectMinutes(m) }, Modifier.weight(1f)) }
            Segment("시각으로", ui.plan.byReturnTime, c::selectReturnTime, Modifier.weight(1.3f))
        }
        if (ui.plan.byReturnTime) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                WButton("− 5분", onClick = { c.shiftReturnTime(-5) }, modifier = Modifier.semantics { contentDescription = "돌아갈 시각 5분 앞당기기" })
                Text("${clock(ui.plan.returnByMinuteOfDay)}까지 자리로", color = Tone.Ink, style = Type.TitleM, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                WButton("+ 5분", onClick = { c.shiftReturnTime(5) }, modifier = Modifier.semantics { contentDescription = "돌아갈 시각 5분 늦추기" })
            }
            KText("걸어가는 ${NapPlan.WALK_MINUTES}분과 잠 깨는 ${NapPlan.WAKE_MINUTES}분을 빼고 깨워 드려요.", color = Tone.Muted, style = Type.Label)
        }
        Column(Modifier.fillMaxWidth().clip(PanelShape).background(Tone.Paper2)) {
            SettingRow(
                "등받이 일으키기",
                if (ui.support.seat == true) "반응이 없을 때 조금씩 세워요. 뒤로 젖히지는 않아요." else "이 차에서는 쓸 수 없어요.",
                onClick = { if (ui.support.seat == true) c.setSeatRaise(!ui.seatRaise) },
            ) { WSwitch(ui.seatRaise, { if (ui.support.seat == true) c.setSeatRaise(!ui.seatRaise) }, enabled = ui.support.seat == true) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.Rule))
            SettingRow("깨우는 소리", "${ui.settings.alarm.title}, ${if (ui.settings.ramp) "3분 전부터 점점 크게" else "처음부터 같은 크기"}") {
                WButton("바꾸기", onClick = onSettings)
            }
        }
        if (!docked) {
            Spacer(Modifier.weight(1f))
            StartArea(ui, c)
        }
    }
}

@Composable
private fun StartArea(ui: UiState, c: NapController) {
    val canStart = ui.startBlock == null && ui.plan.napMinutes(ui.clockMinuteOfDay) != null
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ui.startBlock?.let { Notice(blockMessage(it), Tone.Danger) }
        if (ui.startBlock == null && ui.vehicle.driverDoorLocked == false) Notice("문이 잠겨 있지 않아요. 문을 잠그고 쉬세요.", Tone.Wake)
        if (ui.vehicle.runsOnFuel == true) Notice("엔진차는 시동을 끄고 전원만 켠 채 쉬세요. 공회전 제한이 있어요.", Tone.Wake)
        WButton("낮잠 시작", onClick = c::start, kind = ButtonKind.FILL, large = true, enabled = canStart, modifier = Modifier.fillMaxWidth())
        KText("지하주차장처럼 막힌 곳에서는 시동을 켠 채 자지 마세요.", color = Tone.Muted, style = Type.Label)
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.heightIn(min = 84.dp).clip(ControlShape).background(if (selected) Tone.Fill else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (selected) Tone.OnFill else Tone.Ink2, style = Type.TitleS) }
}

private fun blockMessage(block: StartBlock): String = when (block) {
    StartBlock.NOT_CONNECTED -> "차량 정보를 읽고 있어요."
    StartBlock.NOT_IN_PARK -> "기어를 P에 놓으면 시작할 수 있어요."
    StartBlock.MOVING -> "차가 멈추면 시작할 수 있어요."
    StartBlock.DOOR_OPEN -> "문을 닫으면 시작할 수 있어요."
    StartBlock.LOW_ENERGY -> "배터리나 연료가 15% 아래라 쉬어 가기 어려워요."
}

@Composable
fun Notice(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
        KText(text, color = color, style = Type.BodyS)
    }
}

/** 키우기 상태 띠: 레벨, 단계와 이름, 경험치 막대, 별사탕. 누르면 키우기 창. */
@Composable
fun GrowHud(ui: UiState, onGrow: () -> Unit, modifier: Modifier) {
    val g = Growth.char(ui.growth, ui.settings.character.asset)
    val stage = GrowStage.of(g.level)
    Row(
        modifier.heightIn(min = 72.dp).clip(Pill).background(Tone.GlassStrong).clickable(role = Role.Button, onClick = onGrow)
            .semantics { contentDescription = "키우기: ${stage.label} ${ui.settings.character.title}, 레벨 ${g.level}, 별사탕 ${ui.growth.coins}개" }
            .padding(start = 10.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.heightIn(min = 52.dp).widthIn(min = 56.dp).clip(Pill).background(Tone.Ok).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text("Lv.${g.level}", color = Tone.OnFill, style = Type.LabelS.copy(fontWeight = FontWeight.ExtraBold))
        }
        Column(Modifier.width(140.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${stage.label} ${ui.settings.character.title}", color = Tone.Ink, style = Type.LabelS.copy(fontWeight = FontWeight.Bold), maxLines = 1)
            Meter(g.exp.toFloat() / Growth.expToNext(g.level), Tone.Ok, Modifier.fillMaxWidth())
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CoinIcon(24.dp); Text("${ui.growth.coins}", color = Tone.Ink, style = Type.LabelS.copy(fontWeight = FontWeight.Bold))
        }
    }
}

// ------------------------------------------------------------------ 자는 중

@Composable
fun SleepingScreen(ui: UiState, c: NapController, sky: Sky, sunT: Float) {
    SkyScene(sky, sunT, Modifier.fillMaxSize()) {
        CharacterSpot(ui, c, x = .5f, ground = .9f, size = 560.dp, pose = basePose(ui))
        Column(Modifier.align(Alignment.TopCenter).padding(top = 110.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.nap.stage >= 1) {
                Text("곧 깨워 드릴게요", color = Tone.Ink, style = Type.HeadL)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${ui.napMinutesLeft}", color = Tone.Ink, style = Type.Display)
                    Text("분 남음", color = Tone.Ink2, style = Type.TitleM, modifier = Modifier.padding(bottom = 18.dp))
                }
            }
            Text("${withRo(ui.settings.alarm.title)} 깨워 드릴게요", color = Tone.Ink2, style = Type.BodyL)
        }
        WButton("그만 자기", onClick = c::endNap, kind = ButtonKind.GLASS, modifier = Modifier.align(Alignment.TopEnd).padding(40.dp))
        KText(
            "문이 열리거나 기어를 바꾸면 바로 멈춥니다.", color = Tone.Muted, style = Type.Label,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        )
    }
}

/** 받침에 따라 '로'와 '으로'를 고른다(ㄹ받침은 '로'). */
fun withRo(w: String): String { val j = (w.last() - '가') % 28; return if (j == 0 || j == 8) w + "로" else w + "으로" }

// ------------------------------------------------------------------ 깨우기

@Composable
fun WakingScreen(ui: UiState, c: NapController, land: Boolean, sky: Sky, sunT: Float) {
    val nap = ui.nap
    val mission = nap.mission ?: return
    SkyScene(sky, sunT, Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { c.stopSeatRaise() }) }) {
        CharacterSpot(ui, c, x = if (land) .76f else .82f, ground = if (land) .86f else .2f, size = if (land) 460.dp else 180.dp, pose = basePose(ui))
        val lead = when {
            nap.realarmCount > 0 -> "다시 잠드신 것 같아요"
            nap.wokenEarlyForEnergy -> "배터리가 부족해 일찍 깨웠어요"
            nap.wokenEarlyForPower -> "차량 전원이 곧 꺼져서 일찍 깨웠어요"
            else -> "일어날 시간이에요"
        }
        if (land) {
            Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                    KText(lead, color = Tone.Wake, style = Type.HeadS)
                    MissionCard(mission, nap.missionStep)
                    LockHint()
                }
                Column(Modifier.weight(.9f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                    Stages(ui)
                    HoldToStop(c::emergencyStop)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                KText(lead, color = Tone.Wake, style = Type.TitleL, modifier = Modifier.widthIn(max = 520.dp))
                MissionCard(mission, nap.missionStep)
                LockHint()
                Spacer(Modifier.weight(1f))
                Stages(ui)
                HoldToStop(c::emergencyStop)
            }
        }
    }
}

@Composable
private fun LockHint() {
    Row(
        Modifier.clip(ControlShape).background(Tone.GlassStrong).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null, tint = Tone.Ink2, modifier = Modifier.size(26.dp))
        KText("화면을 눌러서는 꺼지지 않아요. 차를 직접 움직여 주세요.", color = Tone.Ink2, style = Type.Label)
    }
}

/** 흰 미션 카드: 아이콘, 미션 문장(가장 긴 줄 너비로 상자를 맞춤), 단계 진행. */
@Composable
private fun MissionCard(mission: Mission, step: Int) {
    // '두 번', '한 번'은 숫자와 단위가 갈라지지 않게 붙인다
    val prompt = keepAll(mission.prompt.replace("두 번", "두\u00A0번").replace("한 번", "한\u00A0번"))
    val style = Type.HeadM.copy(color = Tone.OnFill, lineBreak = LineBreak.Heading)
    val measurer = rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    BoxWithConstraints {
        val textMaxPx = (constraints.maxWidth - with(density) { (80.dp + 128.dp + 40.dp).roundToPx() }).coerceAtLeast(200)
        val layout = remember(prompt, textMaxPx) { measurer.measure(prompt, style, constraints = Constraints(maxWidth = textMaxPx)) }
        val lineW = (0 until layout.lineCount).maxOf { layout.getLineRight(it) - layout.getLineLeft(it) }
        Row(
            Modifier.clip(StageShape).background(Tone.Card).padding(40.dp),
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Box(Modifier.size(128.dp).clip(PanelShape).background(Tone.Wake), contentAlignment = Alignment.Center) { MissionIcon(mission) }
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(prompt, style = style, modifier = Modifier.width(with(density) { ceil(lineW).toDp() + 2.dp }).clearAndSetSemantics { text = AnnotatedString(mission.prompt) })
                Track(mission, step)
            }
        }
    }
}

private fun missionSteps(m: Mission) = when (m) {
    Mission.BRAKE_TWICE -> listOf("한 번", "두 번")
    Mission.WIPER_ON -> listOf("켜기")
    Mission.WINDOW_DOWN_UP -> listOf("내리기", "올리기")
}

@Composable
private fun Track(mission: Mission, step: Int) {
    val names = missionSteps(mission)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        names.forEachIndexed { i, n ->
            val done = i < step
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(if (done) Tone.Wake else Tone.Card2), contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Filled.Check, null, tint = Tone.OnFill, modifier = Modifier.size(32.dp))
                    else Text("${i + 1}", color = Tone.OnFill, style = Type.BodyS.copy(fontWeight = FontWeight.Bold))
                }
                Text(n, color = Tone.OnFill.copy(alpha = if (done) 1f else .7f), style = Type.BodyS)
            }
        }
    }
}

@Composable
private fun MissionIcon(m: Mission) {
    Canvas(Modifier.size(84.dp)) {
        val s = size.width / 84f
        val st = Stroke(width = 2.4f * s * 2.2f, cap = StrokeCap.Round)
        val col = Tone.OnFill
        when (m) {
            Mission.BRAKE_TWICE -> {
                drawRoundRect(col, Offset(24 * s, 12 * s), androidx.compose.ui.geometry.Size(36 * s, 44 * s), androidx.compose.ui.geometry.CornerRadius(10 * s), style = st)
                listOf(24f, 34f, 44f).forEach { y -> drawLine(col, Offset(33 * s, y * s), Offset(51 * s, y * s), st.width, StrokeCap.Round) }
                drawLine(col, Offset(42 * s, 56 * s), Offset(42 * s, 74 * s), st.width, StrokeCap.Round)
                drawLine(col, Offset(30 * s, 74 * s), Offset(54 * s, 74 * s), st.width, StrokeCap.Round)
            }
            Mission.WIPER_ON -> {
                drawArc(col, 180f, 180f, false, Offset(8 * s, 18 * s), androidx.compose.ui.geometry.Size(68 * s, 88 * s), style = st)
                drawLine(col, Offset(42 * s, 62 * s), Offset(22 * s, 26 * s), st.width, StrokeCap.Round)
                drawCircle(col, 4 * s, Offset(42 * s, 62 * s))
            }
            Mission.WINDOW_DOWN_UP -> {
                val p = androidx.compose.ui.graphics.Path().apply { moveTo(12 * s, 66 * s); lineTo(12 * s, 34 * s); lineTo(30 * s, 16 * s); lineTo(72 * s, 16 * s); lineTo(72 * s, 66 * s); close() }
                drawPath(p, col, style = st)
                drawLine(col, Offset(46 * s, 28 * s), Offset(46 * s, 54 * s), st.width, StrokeCap.Round)
                drawLine(col, Offset(38 * s, 46 * s), Offset(46 * s, 54 * s), st.width, StrokeCap.Round)
                drawLine(col, Offset(54 * s, 46 * s), Offset(46 * s, 54 * s), st.width, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Stages(ui: UiState) {
    val labels = listOf(
        if (ui.settings.ramp) "작은 알람" else "조명", "알람과 미션", if (ui.seatRaise) "등받이 세우기" else "바람 세게", "알람 최대",
    )
    val stage = ui.nap.stage
    Row(Modifier.fillMaxWidth().clip(PanelShape).background(Tone.GlassStrong).semantics { contentDescription = "깨우기 ${stage}단계" }.padding(8.dp)) {
        labels.forEachIndexed { i, label ->
            val n = i + 1
            val on = n == stage; val past = n < stage
            Column(
                Modifier.weight(1f).clip(ControlShape).background(if (on) Tone.Wake.copy(alpha = .22f) else Color.Transparent).padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (on) Tone.Wake else Color.Transparent)
                        .border(2.dp, if (on) Tone.Wake else Tone.Muted, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (past) Icon(Icons.Filled.Check, null, tint = Tone.Ink, modifier = Modifier.size(24.dp))
                    else Text("$n", color = if (on) Tone.OnFill else Tone.Ink2, style = Type.LabelS.copy(fontWeight = FontWeight.Bold))
                }
                Text(label, color = if (on) Tone.Ink else Tone.Ink2, style = Type.LabelS.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal), maxLines = 1)
            }
        }
    }
}

/** 비상 종료: 3초 길게 눌러야 멈춘다. 누르는 동안 버튼이 왼쪽부터 차오른다. */
@Composable
fun HoldToStop(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 100.dp).clip(ControlShapeL).background(Tone.GlassStrong)
                .semantics { contentDescription = "비상 종료, 3초 길게 누르기" }
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        val job = scope.launch {
                            progress.animateTo(1f, tween((3000 * (1 - progress.value)).toInt(), easing = LinearEasing))
                            onStop()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) { job.cancel(); scope.launch { progress.animateTo(0f, tween(260)) } }
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.matchParentSize()) { Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(Tone.Fill)) }
            Text("비상 종료", color = if (progress.value > .5f) Tone.OnFill else Tone.Ink, style = Type.TitleS)
        }
        Text("3초 길게 누르기", color = Tone.Ink2, style = Type.Label)
    }
}

// ------------------------------------------------------------------ 확인 질문

@Composable
fun GuardScreen(ui: UiState, c: NapController, land: Boolean, sky: Sky, sunT: Float) {
    val nap = ui.nap
    val q = nap.question ?: return
    val total = if (ui.demo) 20 else 180
    SkyScene(sky, sunT, Modifier.fillMaxSize()) {
        CharacterSpot(ui, c, x = if (land) .21f else .5f, ground = .93f, size = if (land) 420.dp else 300.dp, pose = basePose(ui))
        Row(Modifier.fillMaxSize().padding(48.dp)) {
            if (land) Spacer(Modifier.weight(.8f))
            Column(
                Modifier.weight(1.2f).align(if (land) Alignment.CenterVertically else Alignment.Top)
                    .clip(StageShape).background(Tone.GlassStrong).padding(40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Text("알람을 멈췄어요. 확인 ${nap.questionNumber} / ${nap.questionCount}", color = Tone.Ink2, style = Type.TitleS)
                KText(q.text, color = Tone.Ink, style = Type.HeadM.copy(lineBreak = LineBreak.Heading))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    q.choices.forEach { choice ->
                        Box(
                            Modifier.weight(1f).heightIn(min = 128.dp).clip(PanelShape).background(Tone.Card).clickable(role = Role.Button) { c.answer() }.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { KText(choice, color = Tone.OnFill, style = Type.TitleS, textAlign = TextAlign.Center) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    if (!nap.snoozeUsed) WButton("5분만 더", onClick = c::snooze, kind = ButtonKind.GLASS)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Meter(ui.guardSecondsLeft.toFloat() / total, Tone.Ink, Modifier.fillMaxWidth(), height = 10.dp)
                        KText("${ui.guardSecondsLeft}초 동안 아무 조작이 없으면 다시 깨워 드려요.", color = Tone.Ink2, style = Type.Label)
                    }
                }
                HoldToStop(c::emergencyStop, Modifier.widthIn(max = 520.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 깬 뒤 쉬는 시간

@Composable
fun RestScreen(ui: UiState, c: NapController, land: Boolean, sky: Sky, sunT: Float) {
    val left = ui.restSecondsLeft
    val total = if (ui.demo) 20 else 600
    SkyScene(sky, sunT, Modifier.fillMaxSize()) {
        CharacterSpot(ui, c, x = if (land) .25f else .5f, ground = .94f, size = if (land) 480.dp else 300.dp, pose = basePose(ui))
        Row(Modifier.fillMaxSize().padding(48.dp)) {
            if (land) Spacer(Modifier.weight(.95f))
            Column(
                Modifier.weight(1.05f).align(if (land) Alignment.CenterVertically else Alignment.Top).clip(StageShape).background(Tone.GlassStrong).padding(48.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                KText("깬 직후에는 반응이 느릴 수 있어요", color = Tone.Ink2, style = Type.TitleS)
                Text("잠깐 쉬어 가세요", color = Tone.Ink, style = Type.HeadL)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Canvas(Modifier.size(120.dp)) {
                        val st = Stroke(10.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(Tone.Rule, 0f, 360f, false, style = st)
                        drawArc(Tone.Ok, -90f, 360f * (left.toFloat() / total).coerceIn(0f, 1f), false, style = st)
                    }
                    Text(
                        when { left <= 0 -> "이제 출발해도 좋아요."; left >= 60 -> "${(left + 59) / 60}분 남았어요."; else -> "${left}초 남았어요." },
                        color = if (left <= 0) Tone.Ok else Tone.Ink, style = Type.TitleL,
                    )
                }
                KText("창문을 열어 바깥 바람을 쐬거나 차 밖에서 잠깐 걸어 보세요.", color = Tone.Ink2, style = Type.BodyS)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    WButton("운전하지 않아요. 끝내기", onClick = c::endRest, large = true, modifier = Modifier.weight(1f))
                    WButton("출발할게", onClick = c::depart, kind = ButtonKind.FILL, large = true, enabled = left <= 0, modifier = Modifier.weight(1f))
                }
                KText("졸리면 운전하지 마세요. 출발 여부는 언제나 운전자가 정합니다.", color = Tone.Muted, style = Type.Label)
            }
        }
    }
}

// ------------------------------------------------------------------ 끝

@Composable
fun DoneScreen(ui: UiState, c: NapController, land: Boolean, sky: Sky, sunT: Float, onGrow: () -> Unit) {
    val nap = ui.nap
    SkyScene(sky, sunT, Modifier.fillMaxSize()) {
        CharacterSpot(ui, c, x = .5f, ground = .95f, size = if (land) 400.dp else 320.dp, pose = basePose(ui))
        GrowHud(ui, onGrow, Modifier.align(Alignment.TopEnd).padding(28.dp))
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = if (land) 40.dp else 120.dp, start = 48.dp, end = 48.dp).widthIn(max = 1040.dp)
                .clip(StageShape).background(Tone.Paper2.copy(alpha = .9f)).padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(Tone.Ok), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Tone.OnFill, modifier = Modifier.size(48.dp))
                }
                KText(doneTitle(nap.endReason), color = Tone.Ink, style = Type.HeadS)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Stat("잔 시간", "${nap.sleptMs / ui.msPerNapMinute}분")
                nap.wakeResponseMs?.let { Stat("깨는 데 걸린 시간", "${(it + 999) / 1000}초") }
                if (nap.realarmCount > 0) Stat("다시 깨운 횟수", "${nap.realarmCount}번")
            }
            ui.reward?.let { r ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${callName(ui.settings.character.title)}가 받은 보상", color = Tone.Muted, style = Type.Label)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text("+${r.exp} 경험치", color = Tone.Ink, style = Type.TitleS)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { CoinIcon(28.dp); Text("+${r.coins} 별사탕", color = Tone.Ink, style = Type.TitleS) }
                    }
                    if (r.notes.isNotEmpty()) KText(r.notes.joinToString(", "), color = Tone.Muted, style = Type.Label)
                    r.levelUps.lastOrNull()?.let { Text("레벨 업! Lv.${it.level}", color = Tone.Coin, style = Type.TitleS.copy(fontWeight = FontWeight.ExtraBold)) }
                }
            }
            if (nap.chargeReminder) Notice("충전이 끝났는지 확인하고 충전 케이블을 분리해 주세요.", Tone.Wake)
            KText("조명과 공조를 낮잠 전 상태로 되돌렸어요.", color = Tone.Muted, style = Type.Label)
            StatsLine(ui)
            WButton("처음으로", onClick = c::reset, kind = ButtonKind.FILL, large = true, modifier = Modifier.widthIn(min = 340.dp))
        }
    }
}

private fun doneTitle(r: EndReason?) = when (r) {
    EndReason.COMPLETED -> "조심히 다녀오세요"
    EndReason.MOVED -> "차가 움직여 낮잠을 끝냈어요"
    EndReason.DOOR_OPENED -> "문이 열려 낮잠을 끝냈어요"
    EndReason.BRAKE_PRESSED -> "브레이크를 밟아 낮잠을 끝냈어요"
    EndReason.LEFT_CAR -> "잘 다녀오세요"
    EndReason.GEAR_LEFT_PARK -> "기어가 P에서 벗어나 낮잠을 끝냈어요"
    EndReason.EMERGENCY_STOP -> "알람을 멈췄어요"
    EndReason.USER_ENDED, null -> "낮잠을 끝냈어요"
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Tone.Muted, style = Type.Label)
        Text(value, color = Tone.Ink, style = Type.HeadS.copy(fontWeight = FontWeight.Bold))
    }
}

/** 이 차에만 저장한 익명 기록. 효과를 재는 데 쓴다. */
@Composable
private fun StatsLine(ui: UiState) {
    val st = ui.stats
    if (st.woken == 0) return
    val parts = buildList {
        add("지금까지 ${st.woken}번 깨웠어요")
        st.realarmPercent?.let { add("다시 잠들어 한 번 더 깨운 비율 $it%") }
        st.restFilledPercent?.let { add("권장 휴식을 채우고 출발한 비율 $it%") }
    }
    KText("내 휴식 기록(이 차에만 저장): " + parts.joinToString(", "), color = Tone.Muted, style = Type.Label, textAlign = TextAlign.Center)
}

fun clock(minuteOfDay: Int): String {
    val m = minuteOfDay.mod(NapPlan.MINUTES_PER_DAY)
    return "%02d:%02d".format(m / 60, m % 60)
}
