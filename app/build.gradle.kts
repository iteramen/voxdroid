import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

// Git failing or missing yields "unknown", never a build error: the catch lives inside the ValueSource because
// the configuration cache re-runs it outside any try block of this script.
abstract class GitCommit @Inject constructor(private val exec: ExecOperations) : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = runCatching {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            isIgnoreExitValue = true
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
        }
        out.toString().trim()
    }.getOrNull().orEmpty().ifEmpty { "unknown" }
}

plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.iteramen.voxdroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.iteramen.voxdroid"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        val commit = providers.of(GitCommit::class) {}.get()
        buildConfigField("String", "COMMIT", "\"$commit\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    val keystorePropertiesPresent = keystorePropertiesFile.exists()
    if (keystorePropertiesPresent) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    fun requiredKeystoreProperty(key: String): String =
        keystoreProperties.getProperty(key)?.takeIf { it.isNotBlank() }
            ?: error("keystore.properties: missing $key")

    signingConfigs {
        create("release") {
            if (keystorePropertiesPresent) {
                storeFile = rootProject.file(requiredKeystoreProperty("storeFile"))
                storePassword = requiredKeystoreProperty("storePassword")
                keyAlias = requiredKeystoreProperty("keyAlias")
                keyPassword = requiredKeystoreProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropertiesPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        disable += "UseKtx"
    }

    // The absent-file warning is an action on the release tasks: a configuration-cache hit skips this script.
    if (!keystorePropertiesPresent) {
        tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
            doFirst {
                logger.warn("release signing: keystore.properties absent, release artifacts will be unsigned")
            }
        }
    }
}

dependencies {
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.14.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub under unit tests, so the real classes ride on the test classpath.
    testImplementation("org.json:json:20260814")
}

// These files are test inputs, so an edit to one of them does not leave testDebugUnitTest UP-TO-DATE.
tasks.withType<Test>().configureEach {
    inputs.files(
        "src/main/AndroidManifest.xml",
        "src/main/res/raw/polish_prompt.txt",
        "src/main/res/xml/section_triggers.xml",
        "src/main/res/values/strings.xml",
        "src/main/res/xml/section_speech.xml",
    )
}

if (gradle.startParameter.isWriteDependencyLocks) {
    configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach { resolutionStrategy.activateDependencyLocking() }
}
