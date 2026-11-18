plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

val zekoKeystorePath = System.getenv("ZEKO_KEYSTORE_PATH")
    ?.takeIf { it.isNotBlank() && file(it).exists() }
val zekoKeystorePassword = System.getenv("ZEKO_KEYSTORE_PASSWORD")
    ?.takeIf { it.isNotBlank() }

android {
    namespace = "com.zeko.player"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.zeko.player"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "2.0.4"
    }
    signingConfigs {
        create("zekoStable") {
            if (zekoKeystorePath != null && zekoKeystorePassword != null) {
                storeFile = file(zekoKeystorePath)
                storeType = "PKCS12"
                storePassword = zekoKeystorePassword
                keyAlias = "zeko-player"
                keyPassword = zekoKeystorePassword
            }
        }
    }
    buildTypes {
        getByName("debug") {
            if (zekoKeystorePath != null && zekoKeystorePassword != null) {
                signingConfig = signingConfigs.getByName("zekoStable")
            }
        }
    }
    sourceSets.getByName("main") {
        manifest.srcFile("AndroidManifest.xml")
        java.srcDirs("Src/src")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
}
