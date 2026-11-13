package kr.contest.wakecar

import android.app.Application

class WakeCarApp : Application() {
    /** 화면이 다시 만들어져도 낮잠이 이어지도록 앱 단위로 하나만 둔다. */
    val controller: NapController by lazy { NapController(this) }
}
