plugins {
    id("com.android.application")
}

android {
    namespace = "dev.tqmane.befuck"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.tqmane.befuck"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    sourceSets.named("main") {
        resources.directories.add("build/generated/notices")
    }
}

val prepareNotices = tasks.register<Copy>("prepareNotices") {
    from(rootProject.files("LICENSE", "NOTICE"))
    into(layout.buildDirectory.dir("generated/notices/META-INF/befuck"))
}
tasks.named("preBuild") { dependsOn(prepareNotices) }

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("org.maplibre.gl:android-sdk:13.3.1")
}

// Reuse the app's compiled classes and dependencies for the JVM checks.
val compileChecks = tasks.register<JavaCompile>("compileChecks") {
    dependsOn("compileDebugKotlin", "compileDebugJavaWithJavac")
    source(rootProject.file("checks/MetadataCheck.java"))
    classpath = files(
        provider { tasks.named("compileDebugKotlin").get().outputs.files },
        provider { tasks.named<JavaCompile>("compileDebugJavaWithJavac").get().destinationDirectory },
        configurations.named("debugRuntimeClasspath"),
    )
    destinationDirectory.set(layout.buildDirectory.dir("checks"))
    options.release.set(17)
}

tasks.register<JavaExec>("checkModule") {
    group = "verification"
    description = "Check media metadata, repair inference, and selection snapshots."
    dependsOn(compileChecks)
    mainClass.set("MetadataCheck")
    classpath = files(compileChecks.flatMap { it.destinationDirectory }, compileChecks.map { it.classpath })
    enableAssertions = true
}
