import groovy.json.JsonSlurper
import java.util.Properties

// プッシュ通知（FCM）の設定。Firebase の画面からダウンロードした google-services.json を app/ に置くと有効になる。
// Google のプラグインは使わず、必要な4つの値だけを読んで BuildConfig に入れる（ファイルがなくてもビルドできる）
val fcm: Map<String, String> = run {
    val f = file("google-services.json")
    if (!f.exists()) return@run emptyMap()
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parse(f) as Map<String, Any?>
    val info = root["project_info"] as Map<String, Any?>
    val clients = root["client"] as List<Map<String, Any?>>
    val client = clients.firstOrNull { c ->
        ((c["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "jp.eldia.hotel"
    } ?: clients.first()
    val clientInfo = client["client_info"] as Map<String, Any?>
    val apiKey = (client["api_key"] as List<Map<String, Any?>>).first()["current_key"]
    mapOf(
        "FCM_PROJECT_ID" to info["project_id"].toString(),
        "FCM_SENDER_ID" to info["project_number"].toString(),
        "FCM_APP_ID" to clientInfo["mobilesdk_app_id"].toString(),
        "FCM_API_KEY" to apiKey.toString(),
    )
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "jp.eldia.hotel"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.eldia.hotel"
        // Pixel 9a 前後の端末を想定。Android 10 以上
        minSdk = 29
        // Google Play の条件（2026年8月以降の新規アプリは API 36 以上）
        targetSdk = 36
        // GitHub Actions では実行番号から自動で増やす（Play には毎回大きい番号が必要）
        // ビルド番号（GitHub Actions の実行番号）。手元のビルドは 2
        val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull()
        versionCode = buildNumber ?: 2
        // バージョン名もビルドごとに上がる（例: 0.3.62）。大きな変更のときは前の2つ（0.3）を手で上げる
        versionName = "0.3." + (buildNumber?.toString() ?: "0-dev")
        listOf("FCM_PROJECT_ID", "FCM_SENDER_ID", "FCM_APP_ID", "FCM_API_KEY").forEach { k ->
            buildConfigField("String", k, "\"${fcm[k].orEmpty()}\"")
        }
    }

    // 配布用の署名。鍵とパスワードは Git に入れない
    // - Mac：keystore.properties（storeFile / storePassword / keyAlias / keyPassword）
    // - GitHub Actions：環境変数 SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD
    val keystoreFile = rootProject.file("keystore.properties")
    val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }
    val envStore = System.getenv("SIGNING_STORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
    signingConfigs {
        if (envStore != null) {
            create("release") {
                storeFile = file(envStore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        } else if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 不要なコードと画像を取り除いてアプリを小さくする（R8）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
}

dependencies {
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
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.biometric)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // 冷蔵庫の期限読み取り（カメラ映像から文字を読む・端末内で処理）
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.japanese)
    // ホーム画面のウィジェット（今日やること）
    implementation(libs.glance.appwidget)
    // プッシュ通知（新しい不具合・発注の承認待ち）
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.play.app.update.ktx)

    debugImplementation(libs.androidx.ui.tooling)
}
