import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// 서명 정보: keystore.properties 파일(로컬) 또는 환경 변수(GitHub Actions)에서 읽음.
// 항상 같은 키로 서명해야 앱을 업데이트해도 저장된 그룹/플래그/평점이 유지됨.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey) ?: System.getenv(envKey)

val storeFilePath = signingValue("storeFile", "ZAMPL_KEYSTORE_PATH")
val hasSigningKey = storeFilePath != null && rootProject.file(storeFilePath).exists()

android {
    namespace = "com.zampl.player"
    compileSdk = 35

    defaultConfig {
        // applicationId 는 절대 바꾸지 말 것 (바꾸면 다른 앱으로 인식되어 데이터가 이어지지 않음)
        applicationId = "com.zampl.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.1.1"
    }

    signingConfigs {
        if (hasSigningKey) {
            create("zampl") {
                storeFile = rootProject.file(storeFilePath!!)
                storePassword = signingValue("storePassword", "ZAMPL_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "ZAMPL_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "ZAMPL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigningKey) signingConfig = signingConfigs.getByName("zampl")
        }
        debug {
            // 디버그 빌드도 같은 키로 서명해서, 어떤 빌드를 설치해도 데이터가 이어지게 함
            if (hasSigningKey) signingConfig = signingConfigs.getByName("zampl")
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
}

ksp {
    // DB 구조 기록. 나중에 DB 구조를 바꿀 때 마이그레이션 작성에 사용.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    val room = "2.7.0"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("io.coil-kt:coil-compose:2.7.0")
}
