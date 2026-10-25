/*
 * Modified fork of ShizuCallRecorder; upstream notices remain in reused sources.
 * Licensed under GPL-3.0-or-later. See LICENSE.
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
}

val libphonenumberMetadataDir = layout.buildDirectory.dir("generated/libphonenumber/assets")
val libphonenumberArtifact = configurations.create("libphonenumberArtifact") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

abstract class ExtractMetadataTask : Sync() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val extractLibphonenumberMetadata = tasks.register<ExtractMetadataTask>("extractLibphonenumberMetadata") {
    from(libphonenumberArtifact.elements.map { files -> files.map { zipTree(it) } }) {
        include("com/google/i18n/phonenumbers/data/**")
        eachFile { relativePath = RelativePath(true, "phonenumber_data", name) }
        includeEmptyDirs = false
    }
    outputDir.set(libphonenumberMetadataDir)
    into(outputDir)
}

android {
    namespace = "org.jcastillo.grabaciones"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.jcastillo.grabaciones"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
            }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
        prefab = true
    }

    packaging {
        resources.excludes.add("com/google/i18n/phonenumbers/data/**")
    }

    androidResources { generateLocaleConfig = true }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(extractLibphonenumberMetadata, ExtractMetadataTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.libphonenumber)
    libphonenumberArtifact(libs.libphonenumber)
    implementation(libs.lame)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    compileOnly("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    compileOnly(project(":android-framework-hidden-stubs"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
