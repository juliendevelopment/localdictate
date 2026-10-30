plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
val sherpaAar = layout.buildDirectory.file("dependencies/sherpa-onnx-static-link-onnxruntime-2.0.1.aar")
val fetchSherpa = tasks.register<Exec>("fetchSherpa") {
    inputs.file(rootProject.file("scripts/fetch_sherpa.py"))
    outputs.file(sherpaAar)
    commandLine("python3", rootProject.file("scripts/fetch_sherpa.py"), sherpaAar.get().asFile)
}
android {
    namespace = "be.arte.localdictate"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "be.arte.localdictate"
        minSdk = 29
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        jniLibs.excludes += setOf("**/x86/**", "**/x86_64/**", "**/armeabi-v7a/**")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.maxHeapSize = "768m"
            test.maxParallelForks = 1
            // Optional pre-cached SDK directory for read-only HOME environments; no global config.
            providers.gradleProperty("robolectricDependencyDir").orNull?.let {
                test.systemProperty("robolectric.offline", "true")
                test.systemProperty("robolectric.dependency.dir", it)
            }
            System.getenv("TMPDIR")?.let { test.systemProperty("java.io.tmpdir", it) }
        }
    }
}
dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(files(sherpaAar).builtBy(fetchSherpa))
    implementation(libs.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.lifecycle.viewmodel.compose)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
