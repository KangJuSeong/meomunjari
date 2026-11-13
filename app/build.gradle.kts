plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.meomunjari"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.meomunjari"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // mock  : 일반 에뮬레이터/폰에서 동작. 차량·위치·내비를 앱 안 시뮬레이터 + 가상 차량 콘솔로 대체.
    // pleos : Pleos Connect 에뮬레이터/차량용. Pleos SDK(Vehicle, FusedLocation, NaviHelper) 사용.
    flavorDimensions += "vehicle"
    productFlavors {
        create("mock") {
            dimension = "vehicle"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
        }
        create("pleos") {
            dimension = "vehicle"
        }
    }

    buildTypes {
        release {
            // pleos 플레이버는 Vehicle SDK를 리플렉션으로 호출하므로 R8 최적화를 끈다.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Pleos SDK는 pleos 플레이버에서만 사용한다. mock 플레이버는 SDK 없이 빌드된다.
    "pleosImplementation"(libs.pleos.vehicle)
    "pleosImplementation"(libs.pleos.fusedlocation)
    "pleosImplementation"(libs.pleos.navihelper)
    "pleosImplementation"(libs.kotlin.reflect)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    "testPleosImplementation"("org.mockito:mockito-core:5.20.0")
    "testPleosImplementation"("org.mockito.kotlin:mockito-kotlin:5.4.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
