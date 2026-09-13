import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)   // AGP 9: Kotlin is built in; do not apply org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.technicallyvu.scope"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.technicallyvu.scope"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.1"
        buildConfigField("String", "GIT_SHA", "\"${gitShortSha()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// Built-in Kotlin (AGP 9): the Kotlin JVM target must match compileOptions.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Build id shown on the trust/about screen so a bug report can be tied to an exact build.
// --always falls back to the short commit hash when there is no tag to describe; --dirty appends
// "-dirty" so a build made from an uncommitted working tree can never be mistaken for that commit.
// Uses providers.exec (not Runtime.exec) so it participates correctly in configuration cache; falls
// back to "unknown" when git isn't available (e.g. a source archive build) or the command fails.
fun gitShortSha(): String = runCatching {
    providers.exec { commandLine("git", "describe", "--always", "--dirty") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

// Privacy gate: fail the build if any dependency drags in the INTERNET permission.
androidComponents {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val variantName = variant.name
        val check = tasks.register("checkNoInternetPermission$cap") {
            val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
            inputs.file(manifest)
            outputs.upToDateWhen { false }
            doLast {
                val text = manifest.get().asFile.readText()
                check(!text.contains("android.permission.INTERNET")) { "INTERNET permission found in the merged $variantName manifest" }
                println("OK: no INTERNET permission in the $variantName manifest")
            }
        }
        tasks.matching { it.name == "assemble$cap" || it.name == "bundle$cap" }.configureEach { dependsOn(check) }
        tasks.named("check") { dependsOn(check) }
    }
}
