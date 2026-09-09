import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.usb4java)
    implementation(libs.javacv)
    implementation(libs.ffmpeg)
    implementation(variantOf(libs.ffmpeg) { classifier("windows-x86_64") })
    implementation(variantOf(libs.javacpp) { classifier("windows-x86_64") })
    implementation(libs.commons.imaging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

compose.desktop {
    application {
        mainClass = "com.technicallyvu.scope.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "USB Scope"
            packageVersion = "0.1.0"
        }
    }
}

tasks.register<JavaExec>("probe") {
    group = "tools"
    description = "List USB devices, stream from the endoscope, optionally record a .upkt fixture"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.technicallyvu.scope.desktop.ProbeKt")
}
