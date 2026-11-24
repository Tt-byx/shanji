plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 服务器地址：优先读本地 server-url.properties（已 gitignore，不上传 GitHub），
// 缺省回落到占位地址——仓库里永远不出现真实服务器 IP
val serverUrl: String = rootProject.file("server-url.properties").takeIf { it.exists() }
    ?.readLines()?.firstOrNull { it.startsWith("serverUrl=") }?.substringAfter("=")?.trim()
    ?: "http://192.168.1.100:8080/"

android {
    namespace = "com.shanji.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.shanji.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.16"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    signingConfigs {
        // 复用构建镜像里的固定签名（/root/.android/debug.keystore）：
        // release 与历史 debug 包同签名，可直接覆盖安装
        create("release") {
            storeFile = file("/root/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 性能：R8 代码优化 + 资源收缩（真机掉帧优化的最大杠杆）
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Room 数据库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // MainActivity 基类 FragmentActivity 需要
    implementation("androidx.fragment:fragment-ktx:1.8.5")

    // 网络：Retrofit + OkHttp + kotlinx-serialization（与项目 JSON 栈一致）
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
