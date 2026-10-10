plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tokenmon.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tokenmon.mobile"
        minSdk = 26
        targetSdk = 35
        // 修 bug 必须同时抬 versionCode：两个都叫同一个 versionName 的 APK 无法分辨，
        // 装机后也看不出到底装的是修复前还是修复后。
        //   1  1.0.0  首个可用版本
        //   2  1.0.1  修 SSE 帧解包（Dashboard 在有数据/无数据间闪）
        //   3  1.0.2  数据年龄不再显示"刚刚"；额度未变时不再每 2 秒重绘
        //   4  1.0.3  展示 Codex 重置卡数量与到期信息
        //   5  1.0.4  小组件可缩到 1×1，按尺寸切换单数字/列表两种布局
        //   6  1.0.5  小组件透明背景；选择器预览不再显示永久转圈
        //   7  1.0.6  小组件填满可用空间：百分比随格子放大，列宽改为弹性
        //   8  1.0.7  自适应额度圆环、App 信息层级与连接恢复提示
        //   9  1.0.8  1×1 小组件改为两行额度（5 小时 + 每周）与 24 小时内的重置卡到期提醒
        //   10 1.0.9  小组件按设计稿重做；拆出单账号与多账号两个组件
        //   11 1.0.10 修 1×1 丢信息：环心倒计时与底部 7 天行改为缩小而非丢弃；
        //             大版式门槛回到实测过的 240dp（260dp 会把 2×2 判成 1×1）
        //   12 1.0.11 重置卡角标门槛 150dp → 110dp，小格子上不再整个消失
        //   13 1.0.12 分档改为「短边≥150 + 长宽比」，2×2 缩放基准改为 380dp；
        //             修 2×2/2×1/1×2 全被判成 1×1、标题与到期日期画不出来的问题
        //   14 1.0.13 1×1 排版：角标贴顶、底行贴底、环心留白；角标任何尺寸都显示；
        //             透明外观拆成「透明·黑字」与「透明·白字」两档
        versionCode = 15
        versionName = "1.0.14"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.preferences)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
