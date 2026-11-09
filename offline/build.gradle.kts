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
    }
    sourceSets.getByName("japanese").assets.srcDir(rootProject.file(".local/bundles/japanese"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "onnx" }
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
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
