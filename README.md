# 일어나차 (WakeCar)

현대자동차그룹 차량용 앱 개발 공모전(Code your App into Pleos App Market) 출품작 저장소.

## 앱 이름 및 한 줄 소개

- 앱 이름: 일어나차 (WakeCar)
- 한 줄 소개: 다시 잠들지 않는 차 안 낮잠. 차가 기상 시각을 정하고, 몸을 깨우고, 정말 일어났는지 확인합니다.

정차(P) 중에만 동작합니다.

| 단계 | 차가 하는 일 |
|---|---|
| 쉬기 전 | 기어 P, 문, 배터리·연료를 확인하고 잘 시간(10·15·20분) 또는 돌아갈 시각으로 기상 시각을 정합니다 |
| 자는 동안 | 등받이를 젖히고 조명과 화면을 어둡게 합니다. 15분마다 1분씩 바깥 공기를 들입니다 |
| 깨울 때 | 조명 → 등받이와 알람 → 바깥 공기와 바람 → 최대 세기 순으로 올립니다 |
| 깬 뒤 | 알람은 화면을 눌러서는 꺼지지 않습니다. 브레이크 두 번, 와이퍼 켜기, 운전석 창문 내렸다 올리기 가운데 하나를 해야 멈춥니다 |
| 다시 잠들기 방지 | 확인 질문에 답이 없으면 다시 잠든 것으로 보고 다른 미션으로 한 번 더 깨웁니다 |

기어가 P에서 벗어나면 어느 단계에서든 즉시 끝내고 등받이·조명·공조를 낮잠 전 상태로 되돌립니다. 주행 제어(시동·잠금·제동·조향)는 쓰지 않습니다.

## 실행 방법

1. Android Studio에서 이 폴더를 엽니다 (JDK 17, Gradle 8.10.2 wrapper, AGP 8.8.2).
2. Pleos Connect Emulator(Pleos_Connect_v2.1, API 34, Automotive, Cold boot, RAM 4GB)를 만들고 실행합니다.
3. `app` 구성을 에뮬레이터에 Run 합니다.
4. Virtual Console을 엽니다: `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`.
5. Virtual Console에서 기어를 P로 두고 문을 닫으면 앱의 "낮잠 시작"이 켜집니다. 시연 모드를 켜면 낮잠 1분이 2초로 줄어듭니다.
6. 알람이 울리면 화면에 나온 미션을 Virtual Console로 수행합니다(브레이크 두 번, 와이퍼 Low, 운전석 창문 내렸다 올리기).

명령줄:

```bash
./gradlew assembleDebug        # 디버그 APK
./gradlew installDebug         # 실행 중인 에뮬레이터에 설치
./gradlew testDebugUnitTest    # 낮잠 흐름 단위 테스트
```

## 테스트 환경 및 필요한 설정

- Pleos Connect Emulator 2.1.0 (시스템 이미지: API 34 "Pleos Connect v2.1.0 APIs")
- Vehicle SDK 2.3.2 (Gear, Brake, Door, Wiper, Window, EvBattery, Fuel, Seat, Light, HVAC)
- 차량 상태 변경은 Pleos Connect Virtual Console 사용
- 등받이·무드등·외기·풍량 제어는 시작할 때 지원 여부를 확인하고, 지원하지 않는 환경에서는 화면 안내로 대신합니다
- 음성 안내는 Android 기본 TTS 엔진을 씁니다. 엔진이 없으면 알람과 화면 문구만 나옵니다
- Pleos Connect Emulator 2.1.0에서 전 장면을 확인했습니다(2026-11-14). 에뮬레이터는 등받이가 끝까지 젖혀진 값에서 시작하므로 시연 전에 Virtual Console에서 등받이를 80쯤으로 올려 둡니다
- `python tools/vc_test.py all`로 Virtual Console에 값을 넣어 가며 전체 흐름을 자동으로 돌려 볼 수 있습니다

## APK 파일 위치

- `apk/난나고서울은너야_일어나차_release.apk` (release 서명본, Pleos Connect Emulator 2.1.0에서 설치와 실행 확인)

## 저장소 구성

- `app/` 앱 모듈 (`kr.contest.wakecar`)
  - `core/` 낮잠 흐름 상태 기계 (Android·차량 API와 분리, 단위 테스트 포함)
  - `vehicle/` Pleos Vehicle SDK 연결
  - `audio/` 음성 안내, 알람
  - `ui/` 화면
- `docs/figures/` 서비스 흐름과 시스템 구성 그림, `docs/screens/` 에뮬레이터 화면 캡처
- `tools/` 에뮬레이터 자동 실행 스크립트
