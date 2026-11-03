import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    signingProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "dev.shield"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.anonym239.shield"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("SHIELD_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("SHIELD_VERSION_NAME") ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "SHIELD_KEYSTORE_FILE")
            if (store != null) {
                storeFile = file(store)
                storePassword = signingValue("storePassword", "SHIELD_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SHIELD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SHIELD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val release = signingConfigs.getByName("release")
            signingConfig = if (release.storeFile != null) release else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
        lintConfig = file("lint.xml")
        // The Go core AAR is built in CI; lint should not fail on its absence of sources.
        checkDependencies = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Go network core (wireguard-go + gVisor netstack), built with gomobile: see scripts/build-gocore.sh
    implementation(files("libs/shieldcore.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
    implementation(libs.vico.compose.m3)
    implementation(libs.zxing.embedded)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json)
}
