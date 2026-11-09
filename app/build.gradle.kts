import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Pleos 빌드는 Pleos 전용 저장소에서 Vehicle SDK 를 받아야 한다. 그 저장소에 닿지 않는 환경에서도
// 시연 빌드가 막히지 않도록, gradle.properties 에 yeopjari.pleos=true 가 있을 때만 pleos 빌드를 만든다.
val pleosEnabled = providers.gradleProperty("yeopjari.pleos").orNull == "true"

// 릴리스 서명: keystore.properties 가 있으면 사용하고, 없으면 디버그 키로 서명해 바로 설치할 수 있게 한다.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "ai.yeopjari.app"
    compileSdk = 35
    // Android Automotive 의 표준 차량 속성(기어)을 읽는다. 컴파일에만 쓰고, 차가 아닌 기기에는 없어도 된다(Pleos 빌드에서만 사용).
    useLibrary("android.car")

    defaultConfig {
        applicationId = "ai.yeopjari.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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

    flavorDimensions += "platform"
    productFlavors {
        // 일반 Android 에뮬레이터에서 가상 고속도로 주행으로 시연한다.
        create("sim") {
            dimension = "platform"
            applicationIdSuffix = ".sim"
            versionNameSuffix = "-sim"
        }
        // Pleos Connect 에뮬레이터·차량에서 Vehicle SDK 로 실제 차량 상태를 읽는다.
        if (pleosEnabled) {
            create("pleos") {
                dimension = "platform"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 이 테스트 중 내려받는 Android 런타임(android-all)의 저장소. 기본 주소가 막힌 환경을 위해 Maven Central 의 repo1 로 둔다.
        unitTests.all { it.systemProperty("robolectric.dependency.repo.url", "https://repo1.maven.org/maven2") }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))

    // 시연 빌드 스모크 테스트(Robolectric 으로 실제 화면을 띄운다)
    "testSimImplementation"(libs.junit)
    "testSimImplementation"(libs.robolectric)
    "testSimImplementation"(libs.androidx.test.core)
    "testSimImplementation"(platform(libs.androidx.compose.bom))
    "testSimImplementation"(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    if (pleosEnabled) {
        // pleos 맛(flavor)이 있을 때만 그 테스트 설정이 생긴다(기본 빌드에서 없는 설정을 쓰면 빌드가 실패한다)
        "testPleosImplementation"(libs.junit)
        "pleosImplementation"(libs.vehicle.sdk)
        "pleosImplementation"(libs.fused.location.sdk)
        "pleosImplementation"(libs.navi.helper.sdk)
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
