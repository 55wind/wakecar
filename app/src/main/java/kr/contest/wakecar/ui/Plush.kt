package kr.contest.wakecar.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.contest.wakecar.core.Outfit
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.settings.Character
import kr.contest.wakecar.settings.Motion
import kotlin.math.roundToInt
import kotlin.random.Random

/** 표정 7종. 스프라이트 48~54번 칸(깬 표정은 회전 0번과 같은 각도). */
enum class Pose(val frame: Int) { AWAKE(48), DROWSY(49), SLEEP(50), HAPPY(51), SURPRISE(52), LOVE(53), WINK(54) }

/**
 * 3D 인형 렌더 스프라이트(design/character-v2/3d). assets/chars/<캐릭터>/<번호>.webp,
 * 옷장 소품 겹침 층은 assets/chars/<캐릭터>/<소품>/<번호>.webp. 칸 0~47은 20도부터 7.5도씩 돈 모습이다.
 */
object Sprites {
    const val TURN = 48
    private const val STEP = 360f / TURN
    private val cache = object : LruCache<String, ImageBitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun path(c: Character, outfit: Outfit?, frame: Int) =
        "chars/${c.asset}/${outfit?.let { it.name.lowercase() + "/" } ?: ""}%02d.webp".format(frame)

    fun cached(path: String): ImageBitmap? = cache.get(path)

    fun load(context: Context, path: String): ImageBitmap? = cache.get(path) ?: runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }?.asImageBitmap()?.also { cache.put(path, it) }
    }.getOrNull()

    suspend fun preload(context: Context, c: Character, outfit: Outfit?) = withContext(Dispatchers.IO) {
        for (i in 0 until 55) { load(context, path(c, null, i)); if (outfit != null) load(context, path(c, outfit, i)) }
    }

    fun turnFrame(angle: Float): Int = (((angle / STEP).roundToInt() % TURN) + TURN) % TURN
}

/** 한 칸 그리기(몸 + 소품 겹침). 캐시에 없으면 읽어 온다. */
@Composable
fun SpriteFrame(character: Character, outfit: Outfit?, frame: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val body by produceState(Sprites.cached(Sprites.path(character, null, frame)), character, frame) {
        if (value == null) value = withContext(Dispatchers.IO) { Sprites.load(context, Sprites.path(character, null, frame)) }
    }
    val acc by produceState(outfit?.let { Sprites.cached(Sprites.path(character, it, frame)) }, character, outfit, frame) {
        if (outfit != null && value == null) value = withContext(Dispatchers.IO) { Sprites.load(context, Sprites.path(character, outfit, frame)) }
    }
    Box(modifier) {
        body?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
        if (outfit != null) acc?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize()) }
    }
}

/** 몸짓 하나 = 시간(0~1)별 (세로 이동, 가로 늘임, 세로 늘임, 기울기, 가로 이동). 이동은 캐릭터 크기 비율. */
private class Key(val t: Float, val ty: Float = 0f, val sx: Float = 1f, val sy: Float = 1f, val rot: Float = 0f, val tx: Float = 0f)

private val MOTIONS: Map<Motion, Pair<Int, List<Key>>> = mapOf(
    Motion.HOP to (1000 to listOf(Key(0f), Key(.1f, sx = 1.1f, sy = .88f), Key(.25f, -.22f, .94f, 1.06f), Key(.5f), Key(.6f, sx = 1.1f, sy = .88f), Key(.75f, -.22f, .94f, 1.06f), Key(1f))),
    Motion.SQUISH to (1100 to listOf(Key(0f), Key(.2f, sx = 1.16f, sy = .8f), Key(.45f, sx = .9f, sy = 1.12f), Key(.65f, sx = 1.06f, sy = .95f), Key(1f))),
    Motion.FLOAT to (1600 to listOf(Key(0f), Key(.25f, -.12f, rot = -7f), Key(.6f, -.06f, rot = 6f), Key(1f))),
    Motion.ROLL to (1000 to listOf(Key(0f), Key(.25f, rot = -7f), Key(.5f, -.06f), Key(.75f, rot = 7f), Key(1f))),
    Motion.LEAN to (900 to listOf(Key(0f), Key(.35f, -.03f, rot = -9f), Key(.65f, rot = 4f), Key(1f))),
    Motion.PEEK to (1200 to listOf(Key(0f), Key(.3f, .02f, 1.04f, .96f), Key(.7f, .02f, 1.04f, .96f), Key(1f))),
    Motion.TOSS to (2400 to listOf(Key(0f), Key(.3f, sx = 1.03f, sy = .97f, rot = 10f), Key(.6f, rot = -4f), Key(1f))),
    Motion.SHIVER to (1000 to (0..8).map { Key(it / 8f, rot = if (it % 2 == 0) 0f else if (it % 4 == 1) -2f else 2f, tx = if (it % 2 == 0) 0f else if (it % 4 == 1) -.015f else .015f) }),
    Motion.BOUNCE to (700 to listOf(Key(0f), Key(.2f, sx = 1.08f, sy = .9f), Key(.45f, -.1f, .96f, 1.05f), Key(.7f, sx = 1.03f, sy = .97f), Key(1f))),
)

private fun sample(keys: List<Key>, t: Float): Key {
    val i = keys.indexOfLast { it.t <= t }.coerceIn(0, keys.size - 2)
    val a = keys[i]; val b = keys[i + 1]
    val k = ((t - a.t) / (b.t - a.t)).coerceIn(0f, 1f).let { FastOutSlowInEasing.transform(it) }
    fun mix(x: Float, y: Float) = x + (y - x) * k
    return Key(t, mix(a.ty, b.ty), mix(a.sx, b.sx), mix(a.sy, b.sy), mix(a.rot, b.rot), mix(a.tx, b.tx))
}

/**
 * 장면 속 캐릭터. 스스로 움직이고(깜빡임, 두리번, 통통, 꾸벅, 좋아하는 모션, 자는 중 뒤척임, 깨우는 중 부르르),
 * 누를 때마다 표정과 몸짓이 바뀌며, 옆으로 끌면 360도로 돈다. 그림자는 바닥에 따로 그려 뛰어도 남는다.
 */
@Composable
fun PlushCharacter(
    character: Character,
    outfit: Outfit?,
    pose: Pose,
    phase: Phase,
    size: Dp,
    modifier: Modifier = Modifier,
    growScale: Float = 1f,
    onPet: (Int) -> Unit = {},
    favoriteKey: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var flash by remember { mutableStateOf<Pose?>(null) }
    var flashJob by remember { mutableStateOf<Job?>(null) }
    var look by remember { mutableStateOf<Float?>(null) }
    val turn = remember { Animatable(0f) }
    var motion by remember { mutableStateOf<Motion?>(null) }
    val motionT = remember { Animatable(0f) }
    var petCount by remember { mutableIntStateOf(0) }
    var lastTouch by remember { mutableLongStateOf(0L) }

    LaunchedEffect(character, outfit) { Sprites.preload(context, character, outfit) }

    fun flashPose(p: Pose, ms: Long) {
        flashJob?.cancel(); flash = p
        flashJob = scope.launch { delay(ms); flash = null }
    }
    fun act(m: Motion) {
        scope.launch {
            motion = m; motionT.snapTo(0f)
            motionT.animateTo(1f, tween(MOTIONS.getValue(m).first, easing = LinearEasing))
            motion = null
        }
    }
    fun turnTo(target: Float, ms: Int, done: suspend () -> Unit = {}) {
        scope.launch {
            if (look == null) turn.snapTo(0f)
            look = turn.value
            turn.animateTo(target, tween(ms, easing = FastOutSlowInEasing)) { look = value }
            done()
        }
    }
    fun turnHome() = turnTo(Math.round(turn.value / 360f) * 360f, 520) { look = null; turn.snapTo(0f) }
    fun spinOnce() = turnTo(360f, 950) { look = null; turn.snapTo(0f) }
    fun glance() {
        val side = if (Random.nextBoolean()) 30f else -30f
        turnTo(side, 520) { delay(420); turnTo(if (Random.nextBoolean()) -side else side * .5f, 640) { delay(380); turnHome() } }
    }

    // 설정에서 캐릭터를 고르면 좋아하는 모션
    LaunchedEffect(favoriteKey) {
        if (favoriteKey == 0) return@LaunchedEffect
        flashPose(Pose.HAPPY, 1600)
        if (character.motion == Motion.ROLL) spinOnce()
        act(character.motion)
    }

    // 스스로 움직이기
    LaunchedEffect(phase, character) {
        while (true) {
            delay(2600L + Random.nextLong(2600))
            if (flash != null || look != null || System.currentTimeMillis() - lastTouch < 2500) continue
            val r = Random.nextFloat()
            when (phase) {
                Phase.SLEEPING -> if (r < .35f) act(Motion.TOSS) else if (r < .5f) flashPose(Pose.DROWSY, 700)
                Phase.WAKING -> { act(Motion.SHIVER); if (r < .3f) flashPose(Pose.SURPRISE, 500) }
                else -> when {
                    r < .3f -> flashPose(Pose.DROWSY, 180)          // 깜빡
                    r < .52f -> glance()                             // 두리번
                    r < .66f -> act(Motion.BOUNCE)                   // 통통
                    r < .78f -> act(Motion.PEEK)                     // 꾸벅
                    r < .9f -> { flashPose(Pose.HAPPY, 900); act(Motion.LEAN) }
                    else -> act(character.motion)                    // 좋아하는 모션
                }
            }
        }
    }

    fun react() {
        val n = petCount++
        lastTouch = System.currentTimeMillis()
        when (phase) {
            Phase.SLEEPING -> { act(Motion.TOSS); flashPose(Pose.DROWSY, 900) }
            Phase.WAKING -> { act(Motion.SHIVER); flashPose(Pose.SURPRISE, 500) }
            else -> {
                flashPose(listOf(Pose.SURPRISE, Pose.HAPPY, Pose.WINK, Pose.LOVE, Pose.HAPPY)[n % 5], 1300)
                when (n % 5) { 0 -> act(Motion.LEAN); 1 -> act(Motion.SQUISH); 2 -> act(Motion.HOP); 3 -> act(Motion.FLOAT); else -> { spinOnce(); act(Motion.BOUNCE) } }
            }
        }
        onPet(n)
    }

    // 끝없이 도는 값(숨쉬기, 둥실, zz)과 몸짓 값은 그리기 단계에서만 읽는다(매 장면 다시 구성하지 않게)
    val loop = rememberInfiniteTransition(label = "idle")
    val breathe = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "breathe")
    val bob = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "bob")
    val zz = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "zz")

    val frame = look?.let { Sprites.turnFrame(it) } ?: (flash ?: pose).frame
    val sleeping = phase == Phase.SLEEPING
    val floaty = character == Character.MONGSIL
    fun current(): Key = motion?.let { sample(MOTIONS.getValue(it).second, motionT.value) } ?: Key(0f)
    fun bobTy() = if (floaty) -0.04f * bob.value else 0f

    BoxWithConstraints(
        modifier
            .size(size)
            .semantics { contentDescription = "${character.title}. 누르면 반응하고, 옆으로 끌면 돌아가요." }
            .pointerInput(character, phase) { detectTapGestures(onTap = { react() }) }
            .pointerInput(character) {
                detectHorizontalDragGestures(
                    onDragStart = { lastTouch = System.currentTimeMillis(); scope.launch { turn.stop(); if (look == null) { turn.snapTo(0f); look = 0f } } },
                    onDragEnd = { turnHome() },
                    onDragCancel = { turnHome() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val next = (look ?: 0f) + dx / density * .9f   // 오른쪽으로 끌면 얼굴도 오른쪽으로 돈다
                        look = next; scope.launch { turn.snapTo(next) }
                    },
                )
            },
    ) {
        // 바닥 그림자(스프라이트 칸에서 바닥은 위에서 84% 지점)
        Canvas(Modifier.fillMaxSize()) {
            val lift = -(current().ty + bobTy())
            val s = ((1f - lift * 2.4f).coerceIn(.55f, 1f)) * (if (floaty) .74f else 1f)
            val w = this.size.width * .46f * s; val h = this.size.height * .07f * s
            val c = Offset(this.size.width / 2, this.size.height * .845f)
            drawOval(
                Brush.radialGradient(listOf(Color(0x6B0A0C1E), Color(0x000A0C1E)), center = c, radius = w / 2),
                topLeft = Offset(c.x - w / 2, c.y - h / 2), size = androidx.compose.ui.geometry.Size(w, h),
                alpha = (1f - lift * 3f).coerceIn(.35f, 1f),
            )
        }
        Box(
            // 몸짓은 그리기 단계의 변환으로만 준다. graphicsLayer로 옮기면 배치 좌표가 바뀌어
            // 접근성 이벤트가 끊임없이 나가고 화면 자동화(uiautomator)가 멈춘 상태를 얻지 못한다.
            Modifier.fillMaxSize().drawWithContent {
                val m = current()
                val bsx = if (sleeping) 1f + .025f * breathe.value else 1f
                val bsy = if (sleeping) 1f - .03f * breathe.value else 1f
                val pivot = Offset(this.size.width * .5f, this.size.height * .84f)
                withTransform({
                    translate(m.tx * this@drawWithContent.size.width, (m.ty + bobTy()) * this@drawWithContent.size.height)
                    rotate(m.rot, pivot)
                    scale(m.sx * bsx * growScale, m.sy * bsy * growScale, pivot)
                }) { this@drawWithContent.drawContent() }
            },
        ) {
            Crossfade(targetState = frame, animationSpec = if (look != null) snap() else tween(170), label = "face") { f ->
                SpriteFrame(character, outfit, f, Modifier.fillMaxSize())
            }
        }
        if (sleeping) {
            // z 세 개가 차례로 떠오른다. 글자 노드 대신 그림으로 그려 접근성 트리를 건드리지 않는다.
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            Canvas(Modifier.fillMaxSize()) {
                listOf(0f, .33f, .66f).forEachIndexed { i, delayFrac ->
                    val k = (zz.value + 1f - delayFrac) % 1f
                    val layout = measurer.measure("z", Type.TitleS.copy(color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (34 - i * 6).sp))
                    drawText(layout, alpha = .9f * (1f - k), topLeft = Offset(this.size.width * (.8f - i * .05f), this.size.height * (.24f - i * .07f) - k * 30f * density))
                }
            }
        }
    }
}
