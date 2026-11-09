plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
    id("org.jetbrains.kotlin.jvm") version "1.9.20" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

detekt {
    source.setFrom(fileTree(rootDir) {
        include("offline/src/**/*.kt", "core/src/**/*.kt", "ml/src/**/*.kt", "pipeline/src/**/*.kt")
    })
    config.setFrom("config/detekt/detekt.yml")
}
