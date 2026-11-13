import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// release 서명 정보는 저장소 밖 파일(keystore.properties)에서 읽는다. 파일이 없으면 서명 없이 빌드된다.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    
    namespace = "kr.contest.wakecar"
    compileSdk = 35
    // Android Automotive 표준 차량 속성(GEAR_SELECTION)을 읽기 위한 android.car 라이브러리.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.contest.wakecar"
        // 공모전/Pleos 요구사항: minSdk 28 이상 (사이닝 키 로테이션·리니지 지원 때문).
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // 제출용 APK는 release 빌드(팀명_앱이름_release.apk)여야 한다. 서명 설정은 signingConfigs 로 추가.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Pleos Connect SDK — 승인 없이 바로 쓸 수 있는 두 모듈만 기본 포함.
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    // 아래는 Pleos Playground 에서 SDK 사용 신청이 승인된 뒤 주석 해제.
    // implementation(libs.pleos.tts)
    // implementation(libs.pleos.stt)
    // implementation(libs.pleos.fusedlocation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
