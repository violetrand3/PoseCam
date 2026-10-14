import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

// Release signing. keystore.properties and the keystore itself are outside version control;
// without them `assembleRelease` still builds, but unsigned and uninstallable.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Cloud upload: the backend URL lives in build config, never in source. Resolution order:
// -Pposecam.cloud.baseUrl.<buildType>, local.properties (git-ignored), then the environment
// (POSECAM_CLOUD_BASEURL_<BUILDTYPE>). The value is the DataCollect marketplace API ROOT, with no path prefix
// (e.g. https://api.example.com): the app adds /v1/... itself. Release builds must use https. A blank URL leaves cloud
// upload completely off and the app behaves exactly as it did before the feature existed. There is deliberately no
// default URL.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun buildSetting(key: String): String {
    val value = (findProperty(key) as String?)
        ?: localProperties.getProperty(key)
        ?: System.getenv(key.uppercase().replace('.', '_'))
        ?: ""
    return value.trim()
}

fun cloudBaseUrl(buildType: String): String {
    val url = buildSetting("posecam.cloud.baseUrl.$buildType")
    // Tokens and recordings travel to this URL: a release build never talks cleartext (CloudConfig enforces it at run time too).
    if (buildType == "release" && url.isNotEmpty() && !url.startsWith("https://")) {
        throw GradleException("posecam.cloud.baseUrl.release must be an https:// URL (the API root, no path prefix), not: $url")
    }
    return url
}

// The website (posecam.web.appUrl.<buildType>): only the fallback for the links GET /v1/meta normally supplies (sign-up
// terms, password reset, "see progress on the web"), used until the app has fetched meta once.
fun webAppUrl(buildType: String): String = buildSetting("posecam.web.appUrl.$buildType")

android {
    namespace = "com.posecam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.posecam"
        // ARCore's own minimum; any phone that can run ARCore can run PoseCam.
        minSdk = 24
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.0"
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true    // Android 7-8 sideloads verify the v1 signature
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildFeatures {
        buildConfig = true   // CLOUD_BASE_URL
    }

    buildTypes {
        debug {
            buildConfigField("String", "CLOUD_BASE_URL", "\"${cloudBaseUrl("debug")}\"")
            buildConfigField("String", "WEB_APP_URL", "\"${webAppUrl("debug")}\"")
        }
        release {
            buildConfigField("String", "CLOUD_BASE_URL", "\"${cloudBaseUrl("release")}\"")
            buildConfigField("String", "WEB_APP_URL", "\"${webAppUrl("release")}\"")
            // R8 is left off deliberately: ARCore and the Camera2 metadata paths use
            // reflection, and a field tool is not worth a silent stripping bug.
            isMinifyEnabled = false
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            // Plain JVM tests run against the unmocked android.jar: without this, any android.util.Log
            // call (the cloud layer logs) throws instead of doing nothing.
            isReturnDefaultValues = true
            isIncludeAndroidResources = true   // Robolectric (real Room database in JVM tests)
        }
    }
}

dependencies {
    implementation(libs.arcore)
    implementation(libs.androidx.core)

    // Cloud upload
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // android.jar's org.json is stubbed in plain JVM unit tests; the cloud client and manifest reader use it.
    testImplementation(libs.org.json)
    // Runs the real Room database (unique index, joins, aggregates) in JVM tests.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}

// Room exports its schema so future migrations can be written and tested against it.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
