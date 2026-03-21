// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.3.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

layout.buildDirectory = file("C:/tmp/android-agent-build/root")

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
extra["kotlinVersion"] = "1.9.22"
extra["coroutinesVersion"] = "1.7.3"
extra["okhttpVersion"] = "4.12.0"
extra["retrofitVersion"] = "2.9.0"
extra["lifecycleVersion"] = "2.7.0"
