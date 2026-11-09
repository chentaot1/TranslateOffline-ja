plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ilovephos.translate"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ilovephos.translate"
        minSdk = 31
        targetSdk = 36
        versionCode = 21
        versionName = "0.5.2"
        val abis = (findProperty("yomu.abis") as String?)?.split(",") ?: listOf("arm64-v8a")
        ndk { abiFilters += abis }
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "sourceLanguage"
    productFlavors {
        create("japanese") {
            dimension = "sourceLanguage"
            buildConfigField("String", "SOURCE_LANGUAGE", "\"JAPANESE\"")
            resValue("string", "app_name", "Translate Japanese")
        }
        create("chinese") {
            dimension = "sourceLanguage"
            applicationIdSuffix = ".chinese"
            buildConfigField("String", "SOURCE_LANGUAGE", "\"CHINESE\"")
            resValue("string", "app_name", "Translate Chinese")
        }
    }
    sourceSets.getByName("japanese").assets.srcDir(rootProject.file(".local/bundles/japanese"))
    sourceSets.getByName("chinese").assets.srcDir(rootProject.file(".local/bundles/chinese"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += listOf("gguf", "onnx") }
    packaging { jniLibs { useLegacyPackaging = true } }
    signingConfigs.getByName("debug") { storeFile = rootProject.file(".local/debug.keystore") }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":pipeline"))
    implementation(project(":ml"))
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}
