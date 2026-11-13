package kr.contest.wakecar

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.contest.wakecar.core.NapState
import kr.contest.wakecar.core.Phase
import kr.contest.wakecar.ui.WakeCarScreen
import kr.contest.wakecar.ui.WakeCarTheme

/**
 * 일어나차 화면. 정차(P) 중에만 쓰는 앱이라 주행 중 화면은 없다.
 * 기어가 P에서 벗어나면 세션이 스스로 끝난다(NapSession 참고).
 */
class MainActivity : ComponentActivity() {

    private val controller get() = (application as WakeCarApp).controller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller.onAppOpened()

        setContent {
            val ui by controller.ui.collectAsStateWithLifecycle()

            // 자는 동안 화면을 가장 어둡게, 깨우는 동안에는 켜진 채로 둔다.
            val brightness = screenBrightness(ui.nap)
            val keepOn = ui.nap.phase != Phase.SETUP && ui.nap.phase != Phase.DONE
            LaunchedEffect(brightness, keepOn) {
                window.attributes = window.attributes.apply { screenBrightness = brightness }
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            WakeCarTheme { WakeCarScreen(ui, controller) }
        }
    }

    override fun onDestroy() {
        if (isFinishing) controller.onAppClosed()
        super.onDestroy()
    }

    private fun screenBrightness(nap: NapState): Float = when {
        nap.phase != Phase.SLEEPING || nap.interruption != null ->
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        nap.stage == 0 -> 0.02f
        else -> 0.3f
    }
}
