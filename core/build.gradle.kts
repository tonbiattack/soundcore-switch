plugins {
    alias(libs.plugins.kotlin.jvm)
}

// ツールチェーンは指定しない。この環境には JDK 21 しか無く、
// toolchain を 17 に固定すると自動ダウンロードが必要になるため。
// 代わりにバイトコードのターゲットだけ 17 に合わせる（Android 側と揃える）。
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
    // ドメイン層と ViewModel は Android にも Compose にも依存しない。
    // そのため :core は純粋な Kotlin/JVM モジュールにできる。
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
