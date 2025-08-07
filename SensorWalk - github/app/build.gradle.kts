// 文件: app/build.gradle.kts
/**
 * 修改历史 (Modification History):
 * ------------------------------------------------------------------------------
 * YYYY-MM-DD - [你的名字/ID] - [简述上次修改，AI无需填写]
 * 2025-07-30 - Gemini-AI - [V1] 数据库版本升级至7，版本号同步修改，以触发数据库迁移。
 * 2025-07-30 - Gemini-AI - [V2] 保持依赖不变。本次重构利用现有库解决问题，无需新增或修改依赖。
 * 2025-07-30 - Gemini-AI - 同步数据库版本。为支持报告与数据文件关联，数据库版本已升级，应用版本代码`versionCode`必须同步提升至7，以确保在用户设备上正确触发数据库迁移逻辑。
 * 2025-07-30 - Gemini-AI - [V4] 新增图表库依赖 `com.patrykandpatrick.vico`，以支持步态角度范围的条形图展示，解决【需求 5.3.2】。
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.sensorwalk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.sensorwalk"
        minSdk = 28
        targetSdk = 34
        versionCode = 7 // ★★★ 数据库已升级，版本号同步为 7 ★★★
        versionName = "1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "NONE"
            }
        }
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
        }
    }

    androidResources {
        noCompress.addAll(listOf("json"))
        generateLocaleConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xjvm-default=all",
            "-Xallow-result-return-type",
            "-Xemit-jvm-type-annotations"
        )
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }

    packaging {
        resources {
            pickFirsts += "META-INF/io.netty.versions.properties"

            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "**/*.version"
            excludes += "**/DEPENDENCIES"
            excludes += "**/LICENSE*"
            excludes += "**/NOTICE*"
            excludes += "**/module-info.class"
            excludes += "META-INF/maven/**"
            excludes += "META-INF/proguard/**"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.google.material)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Serialization & Networking
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation("io.ktor:ktor-server-content-negotiation-jvm:${libs.versions.ktor.get()}")

    // Kotlin Reflection & Stdlib
    implementation("org.jetbrains.kotlin:kotlin-reflect:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:${libs.versions.kotlin.get()}")

    // Charting & Math
    implementation(libs.vico.compose.m3)
    implementation(libs.commons.math3)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

hilt {
    enableAggregatingTask = true
}
