import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Android 版と同じドメイン層・ViewModel・表示文言を使う。
    implementation(project(":core"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)

    // Windows の Bluetooth 状態を取得するために Win32 API を直接叩く。
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.tonbiattack.soundcoreswitch.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "SoundcoreSwitch"
            packageVersion = "1.0.0"
            // MSI のメタデータは ASCII に限定する。
            // jpackage / WiX はロケールによって非 ASCII を扱えず、
            // 日本語ロケール以外の CI ランナーで失敗するため。
            description = "Soundcore multipoint connection switcher"
            vendor = "tonbiattack"

            windows {
                menuGroup = "Soundcore Switch"
                // デスクトップにショートカットを作る（仕様書 F-08 の考え方を踏襲）。
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }
        }
    }
}
