import com.android.ide.common.vectordrawable.Svg2Vector
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.conw.unit"
    compileSdk = 36
    defaultConfig {
        applicationId = "net.conw.unit"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    buildFeatures { buildConfig = true }
}

kotlin { jvmToolchain(17) }

abstract class GenerateLogo : DefaultTask() {
    @get:InputFile abstract val svgFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val output = outputDirectory.file("drawable/ic_logo_foreground.xml").get().asFile
        output.parentFile.mkdirs()
        output.outputStream().use { stream ->
            val errors = Svg2Vector.parseSvgToXml(svgFile.get().asFile.toPath(), stream)
            check(errors.isBlank()) { errors }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val generateLogo = tasks.register<GenerateLogo>(
            "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}Logo"
        ) {
            svgFile.set(layout.projectDirectory.file("src/main/assets/home/logo.svg"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(generateLogo, GenerateLogo::outputDirectory)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.google.zxing:core:3.5.4")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
