package kr.contest.wakecar.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.contest.wakecar.NapController
import kr.contest.wakecar.UiState
import kr.contest.wakecar.core.GrowStage
import kr.contest.wakecar.core.Growth
import kr.contest.wakecar.core.Outfit
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.core.Quest
import kr.contest.wakecar.settings.AlarmSound
import kr.contest.wakecar.settings.Character
import kr.contest.wakecar.settings.Motion
import kr.contest.wakecar.settings.SleepTrack

/** 화면 위에 뜨는 창 틀(어두운 배경 + 가운데 판). */
@Composable
private fun Sheet(title: String, onClose: () -> Unit, closeLabel: String = "완료", header: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color(0xB3000000)).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.widthIn(max = 760.dp).fillMaxWidth(.92f).heightIn(max = 1100.dp).padding(vertical = 24.dp)
                    .clip(PanelShape).background(Tone.Paper)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                // 제목과 닫기 단추는 고정하고 내용만 스크롤한다
                Row(Modifier.padding(start = 40.dp, end = 40.dp, top = 32.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (header != null) Box(Modifier.weight(1f)) { header() } else Text(title, color = Tone.Ink, style = Type.TitleL, modifier = Modifier.weight(1f))
                    WButton(closeLabel, onClick = onClose)
                }
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 40.dp, end = 40.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.Rule))
        Text(title, color = Tone.Ink, style = Type.TitleS)
        if (hint != null) KText(hint, color = Tone.Muted, style = Type.Label)
        content()
    }
}

/** 고르는 카드(테두리로 선택 표시). */
@Composable
private fun Choice(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.clip(ControlShape).background(Tone.Paper3).border(2.dp, if (selected) Tone.Ok else Color.Transparent, ControlShape)
            .clickable(role = Role.RadioButton, onClick = onClick).padding(20.dp),
    ) { content() }
}

@Composable
private fun VolumeRow(label: String, value: Int, min: Int, onChange: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { Text(label, color = Tone.Ink, style = Type.BodyS, modifier = Modifier.weight(1f)); Text("$value%", color = Tone.Ink, style = Type.BodyS) }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) }, valueRange = min.toFloat()..100f,
            colors = SliderDefaults.colors(thumbColor = Tone.Ink, activeTrackColor = Tone.Ok, inactiveTrackColor = Tone.Paper4),
        )
    }
}

/** 설정: 캐릭터, 깨울 때, 잠들 때, 기타. */
@Composable
fun SettingsSheet(ui: UiState, c: NapController, onClose: () -> Unit, onSunSweep: () -> Unit, onCharacterPicked: () -> Unit) {
    val s = ui.settings
    Sheet("설정", onClose = { c.stopPreview(); onClose() }) {
        Section("캐릭터", "카드를 누르면 좋아하는 모션을 보여 줘요. 화면 속 캐릭터는 옆으로 끌면 360도로 돌아가요.") {
            Character.entries.forEach { ch -> CharacterCard(ch, ch == s.character) { c.selectCharacter(ch); c.cue("pet${ch.ordinal + 1}"); onCharacterPicked() } }
        }
        Section("깨울 때") {
            AlarmSound.entries.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { a ->
                        Choice(a == s.alarm, { c.updateSettings { it.copy(alarm = a) } }, Modifier.weight(1f).heightIn(min = 120.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(a.title, color = Tone.Ink, style = Type.BodyL.copy(fontWeight = FontWeight.Bold))
                                KText(a.hint, color = Tone.Muted, style = Type.Label)
                            }
                        }
                    }
                }
            }
            Box(Modifier.clip(PanelShape).background(Tone.Paper2)) {
                SettingRow("점점 크게 깨우기", "깨우기 3분 전부터 아주 작게 시작해 단계마다 키워요.", onClick = { c.updateSettings { it.copy(ramp = !it.ramp) } }) {
                    WSwitch(s.ramp, { c.updateSettings { it.copy(ramp = !it.ramp) } })
                }
            }
            VolumeRow("알람 최대 음량", s.alarmVolume, 10) { v -> c.updateSettings { it.copy(alarmVolume = v) } }
            val p = ui.preview
            WButton(if (p?.kind == "alarm") "중지 (${p.stage}단계 재생 중)" else "알람 미리듣기 (16초)", onClick = c::previewAlarm, modifier = Modifier.fillMaxWidth())
        }
        Section("잠들 때") {
            Box(Modifier.clip(PanelShape).background(Tone.Paper2)) {
                SettingRow("수면 음악", onClick = { c.updateSettings { it.copy(music = !it.music) } }) { WSwitch(s.music, { c.updateSettings { it.copy(music = !it.music) } }) }
            }
            Column(Modifier.graphicsLayer { alpha = if (s.music) 1f else .4f }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SleepTrack.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { t ->
                            Choice(t == s.track, { if (s.music) c.updateSettings { it.copy(track = t) } }, Modifier.weight(1f)) {
                                Text(t.title, color = Tone.Ink, style = Type.BodyS.copy(fontWeight = FontWeight.Bold))
                            }
                        }
                        repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                KText(s.track.hint, color = Tone.Muted, style = Type.Label)
                Box(Modifier.clip(PanelShape).background(Tone.Paper2)) {
                    SettingRow("잠들면 서서히 줄이기", "낮잠 길이의 절반 동안 30%까지 줄여요.", onClick = { c.updateSettings { it.copy(fade = !it.fade) } }) {
                        WSwitch(s.fade, { c.updateSettings { it.copy(fade = !it.fade) } })
                    }
                }
                VolumeRow("수면 음악 음량", s.musicVolume, 0) { v -> c.updateSettings { it.copy(musicVolume = v) } }
                WButton(if (ui.preview?.kind == "music") "미리듣기 중지" else "수면 음악 미리듣기", onClick = { if (s.music) c.previewMusic() }, modifier = Modifier.fillMaxWidth(), enabled = s.music)
            }
        }
        Section("기타") {
            Column(Modifier.clip(PanelShape).background(Tone.Paper2)) {
                SettingRow("효과음", onClick = { c.updateSettings { it.copy(effects = !it.effects) } }) { WSwitch(s.effects, { c.updateSettings { it.copy(effects = !it.effects) } }) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Tone.Rule))
                val canDemo = ui.nap.phase == Phase.SETUP
                SettingRow("시연 모드", "낮잠 1분을 2초로 줄입니다.", onClick = { if (canDemo) c.setDemo(!ui.demo) }) { WSwitch(ui.demo, { if (canDemo) c.setDemo(!ui.demo) }, enabled = canDemo) }
            }
            WButton("해 뜨고 지기 보기 (14초)", onClick = { c.stopPreview(); onSunSweep() }, modifier = Modifier.fillMaxWidth())
            Text("차가 해 주는 일", color = Tone.Ink2, style = Type.BodyS.copy(fontWeight = FontWeight.Bold))
            KText(
                "조명 조절: ${support(ui.support.ambientLight)}, 바람 세기: ${support(ui.support.fan)}. 등받이는 직접 젖혀 주세요. 앱은 등받이를 뒤로 젖히지 않습니다.",
                color = Tone.Muted, style = Type.Label,
            )
        }
    }
}

private fun support(v: Boolean?) = when (v) { true -> "차가 합니다"; false -> "화면으로 안내"; null -> "확인 중" }

/** 캐릭터 카드: 그림, 이름, 설명, 좋아하는 것. 누르면 좋아하는 모션. */
@Composable
private fun CharacterCard(ch: Character, selected: Boolean, onPick: () -> Unit) {
    val scope = rememberCoroutineScope()
    var frame by remember { mutableIntStateOf(Pose.AWAKE.frame) }
    val y = remember { Animatable(0f) }
    val sx = remember { Animatable(1f) }
    val rot = remember { Animatable(0f) }
    fun play() {
        scope.launch {
            when (ch.motion) {
                Motion.ROLL -> { for (i in 0..Sprites.TURN) { frame = i % Sprites.TURN; delay(20) }; frame = Pose.HAPPY.frame }
                else -> frame = Pose.HAPPY.frame
            }
        }
        scope.launch {
            when (ch.motion) {
                Motion.HOP -> repeat(2) { y.animateTo(-18f, tween(160)); y.animateTo(0f, tween(200)) }
                Motion.SQUISH -> { sx.animateTo(1.18f, tween(180)); sx.animateTo(.9f, tween(220)); sx.animateTo(1f, tween(260)) }
                Motion.FLOAT -> { y.animateTo(-14f, tween(400)); rot.animateTo(-7f, tween(300)); rot.animateTo(6f, tween(400)); rot.animateTo(0f, tween(300)); y.animateTo(0f, tween(400)) }
                else -> { rot.animateTo(-7f, tween(250)); rot.animateTo(7f, tween(400)); rot.animateTo(0f, tween(250)) }
            }
            delay(600); frame = Pose.AWAKE.frame
        }
    }
    Choice(selected, { onPick(); play() }, Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(112.dp).clip(ControlShape).background(Tone.Paper2)) {
                SpriteFrame(ch, null, frame, Modifier.fillMaxSize().graphicsLayer {
                    translationY = y.value * density; scaleX = sx.value; scaleY = 2f - sx.value; rotationZ = rot.value
                })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(ch.title, color = Tone.Ink, style = Type.BodyL.copy(fontWeight = FontWeight.Bold))
                KText(ch.desc, color = Tone.Muted, style = Type.Label)
                Text("좋아하는 것: ${ch.favorite}", color = Tone.Ok, style = Type.Label.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/** 키우기 창: 상태, 오늘의 할 일, 옷장. */
@Composable
fun GrowSheet(ui: UiState, c: NapController, onClose: () -> Unit) {
    val ch = ui.settings.character
    val g = Growth.char(ui.growth, ch.asset)
    val stage = GrowStage.of(g.level)
    val name = callName(ch.title)
    Sheet("키우기", onClose = onClose, closeLabel = "닫기", header = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SpriteFrame(ch, g.wearing, Pose.HAPPY.frame, Modifier.size(120.dp))
            Column {
                Text("Lv.${g.level}", color = Tone.Ok, style = Type.Label.copy(fontWeight = FontWeight.Bold))
                Text("${stage.label} ${ch.title}", color = Tone.Ink, style = Type.TitleL, maxLines = 1)
            }
        }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Meter(g.exp.toFloat() / Growth.expToNext(g.level), Tone.Ok, Modifier.weight(1f), height = 14.dp)
            Text("${g.exp} / ${Growth.expToNext(g.level)}", color = Tone.Muted, style = Type.Label)
        }
        val next = GrowStage.entries.firstOrNull { it.minLevel > g.level }
        Text(next?.let { "Lv.${it.minLevel}이 되면 ${withRo(it.label)} 자라요." } ?: "다 자랐어요. 옷장을 꾸며 보세요.", color = Tone.Muted, style = Type.Label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CoinIcon(32.dp); Text("${ui.growth.coins} 별사탕", color = Tone.Ink, style = Type.BodyL.copy(fontWeight = FontWeight.Bold))
        }
        Section("상태") {
            val mood = Growth.mood(g, ui.wallClock); val sleepy = Growth.sleepy(ui.growth, ui.wallClock)
            StatBar("기분", mood, Tone.Mood); StatBar("졸음", sleepy, Tone.Sleepy)
            KText(
                when { sleepy >= 70 -> "${name}도 많이 졸려해요. 같이 낮잠을 자면 졸음이 0이 돼요."; mood < 40 -> "${name}가 심심해해요. 쓰다듬어 주세요."; else -> "낮잠을 잘 자고 잘 일어나면 경험치와 별사탕을 받아요." },
                color = Tone.Muted, style = Type.Label,
            )
        }
        Section("오늘의 할 일") {
            // 날짜가 바뀌었으면 어제 기록 대신 새로 시작한 오늘의 할 일을 보여 준다
            val daily = Growth.rollDay(ui.growth, ui.today).daily
            Quest.entries.forEach { q ->
                val n = minOf(daily.progress(q), q.need); val got = q in daily.claimed
                Row(Modifier.fillMaxWidth().heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        KText(q.text, color = Tone.Ink, style = Type.BodyS)
                        Text("$n / ${q.need}", color = Tone.Muted, style = Type.Label)
                    }
                    WButton(if (got) "받음" else "${q.reward} 받기", onClick = { c.claim(q) }, enabled = !got && n >= q.need, leading = if (got) null else ({ CoinIcon(24.dp) }))
                }
            }
        }
        Section("옷장", "별사탕으로 소품을 사서 입혀요. 다시 누르면 벗어요.") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Outfit.entries.forEach { o ->
                    val own = o in g.owned; val on = g.wearing == o; val locked = g.level < o.minLevel
                    Column(
                        Modifier.weight(1f).clip(ControlShape).background(Tone.Paper3).border(2.dp, if (on) Tone.Ok else Color.Transparent, ControlShape).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 머리 위 소품이 잘 보이게 위쪽을 확대해 보여 준다
                        Box(Modifier.size(130.dp).clip(ControlShape)) {
                            SpriteFrame(ch, o, Pose.AWAKE.frame, Modifier.size(130.dp).graphicsLayer { scaleX = 1.7f; scaleY = 1.7f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(.5f, .12f) })
                        }
                        Text(o.label, color = Tone.Ink, style = Type.BodyS.copy(fontWeight = FontWeight.Bold))
                        WButton(
                            when { on -> "벗기"; own -> "입기"; locked -> "Lv.${o.minLevel}부터"; else -> "${o.price}" },
                            onClick = { c.outfit(o) }, enabled = own || (!locked && ui.growth.coins >= o.price),
                            leading = if (!own && !locked) ({ CoinIcon(22.dp) }) else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBar(label: String, v: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, color = Tone.Ink2, style = Type.BodyS, modifier = Modifier.width(72.dp))
        Meter(v / 100f, color, Modifier.weight(1f), height = 10.dp)
        Text("$v", color = Tone.Ink, style = Type.BodyS.copy(fontWeight = FontWeight.Bold), modifier = Modifier.width(52.dp))
    }
}

/** 레벨 업 축하(장면 위, 2.6초). */
@Composable
fun BoxScope.LevelUpToast(ui: UiState) {
    val ev = ui.levelUp ?: return
    var shown by remember { mutableStateOf(-1L) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(ev.id) { if (shown != ev.id) { shown = ev.id; visible = true; delay(2600); visible = false } }
    AnimatedVisibility(visible, Modifier.align(Alignment.TopCenter).padding(top = 160.dp), enter = scaleIn(initialScale = .6f) + fadeIn(), exit = fadeOut()) {
        Column(
            Modifier.clip(PanelShape).background(Color(0xFFFFF7E0)).padding(horizontal = 40.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Lv.${ev.value.level}", color = Color(0xFFE89A12), style = Type.HeadS)
            val name = callName(ui.settings.character.title)
            Text(if (ev.value.grew) "${name}가 ${withRo(ev.value.stage.label)} 자랐어요!" else "${name}의 레벨이 올랐어요", color = Color(0xFF3A2A10), style = Type.BodyL.copy(fontWeight = FontWeight.Bold))
        }
    }
}

/** 쓰다듬기 보상 숫자(캐릭터 위로 떠오름). */
@Composable
fun BoxScope.GainFloat(ui: UiState) {
    val ev = ui.gain ?: return
    val y = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(-1L) }
    LaunchedEffect(ev.id) {
        if (last == ev.id) return@LaunchedEffect
        last = ev.id; visible = true; y.snapTo(0f); y.animateTo(-60f, tween(1400)); visible = false
    }
    if (visible) {
        Row(
            Modifier.align(Alignment.TopCenter).offset(y = y.value.dp).clip(Pill).background(Color(0x73000000)).padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("+${ev.value.exp} 경험치", color = Tone.Ink, style = Type.LabelS.copy(fontWeight = FontWeight.Bold))
            CoinIcon(20.dp)
            Text("+${ev.value.coins}", color = Tone.Ink, style = Type.LabelS.copy(fontWeight = FontWeight.Bold))
        }
    }
}
