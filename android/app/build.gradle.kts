import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult
import org.gradle.kotlin.dsl.KotlinClosure2

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.app.videoeditor"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.app.videoeditor"
        minSdk = 29
        targetSdk = 36
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.3.0"
    }
    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("KEYSTORE_PATH") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// On GitHub Actions, report every failed unit test (name + assertion message) as an annotation.
tasks.withType<Test>().configureEach {
    afterTest(KotlinClosure2<TestDescriptor, TestResult, Unit>({ d, r ->
        if (r.resultType == TestResult.ResultType.FAILURE) {
            val frame = r.exception?.stackTrace?.firstOrNull { it.className.startsWith("com.app.videoeditor") }
            val msg = (r.exception?.message ?: r.exception.toString()).replace("\n", " ").take(300)
            val at = if (frame != null) " at ${frame.fileName}:${frame.lineNumber}" else ""
            println("::error title=Unit test failed::${d.className}.${d.name} -> $msg$at")
        }
    }))
}
