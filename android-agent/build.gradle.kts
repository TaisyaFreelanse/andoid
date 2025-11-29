// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.20" apply false
}

// Global project configuration
allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}

// Version catalog for dependency management
extra["kotlinVersion"] = "1.9.20"
extra["coroutinesVersion"] = "1.7.3"
extra["okhttpVersion"] = "4.12.0"
extra["retrofitVersion"] = "2.9.0"
extra["lifecycleVersion"] = "2.7.0"
