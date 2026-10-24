/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl
import org.apache.tools.ant.filters.StringInputStream
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import java.util.Properties
import kotlin.io.encoding.Base64

private val Project.android: CommonExtension
    get() = extensions.getByName("android") as CommonExtension

private val Project.androidApp: ApplicationExtension
    get() = extensions.getByType<ApplicationExtension>()

private val Project.androidComponents: ApplicationAndroidComponentsExtension
    get() = extensions.getByType<ApplicationAndroidComponentsExtension>()

private lateinit var metadata: Properties
private lateinit var localProperties: Properties

fun Project.requireMetadata(): Properties {
    if (!::metadata.isInitialized) {
        metadata = Properties().apply {
            load(rootProject.file("version.properties").inputStream())
        }
    }
    return metadata
}

fun Project.requireLocalProperties(): Properties {
    if (!::localProperties.isInitialized) {
        localProperties = Properties()

        val base64 = System.getenv("LOCAL_PROPERTIES")
        if (!base64.isNullOrBlank()) {
            localProperties.load(StringInputStream(String(Base64.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL).decode(base64))))
        } else if (project.rootProject.file("local.properties").exists()) {
            localProperties.load(rootProject.file("local.properties").inputStream())
        }
    }
    return localProperties
}

fun Project.setupCommon(projectName: String = "") {
    android.apply {
        buildToolsVersion = "37.0.0"
        compileSdk = 37
        defaultConfig.minSdk = if (projectName.lowercase() == "naive") 24 else 21
        compileOptions.sourceCompatibility = JavaVersion.VERSION_21
        compileOptions.targetCompatibility = JavaVersion.VERSION_21
        lint.showAll = true
        lint.checkAllWarnings = true
        lint.checkReleaseBuilds = false
        lint.warningsAsErrors = true
        lint.textOutput = project.file("build/lint.txt")
        lint.htmlOutput = project.file("build/lint.html")
        packaging.jniLibs.useLegacyPackaging = true
        // Do not strip symbols by AGP to improve reproducibility. Symbols are manually stripped in advanced.
        packaging.jniLibs.keepDebugSymbols.add("**/*.so")
        packaging.resources.excludes.addAll(
            listOf(
                "**/*.kotlin_*",
                "/META-INF/*.version",
                "/META-INF/androidx/**",
                "/META-INF/native/**",
                "/META-INF/native-image/**",
                "/META-INF/INDEX.LIST",
                "DebugProbesKt.bin",
                "com/**",
                "org/**",
                "**/*.java",
                "**/*.proto",
            )
        )
    }
}

fun Project.setupAppCommon(projectName: String = "") {
    setupCommon(projectName)

    val lp = requireLocalProperties()
    val keystorePwd = lp.getProperty("KEYSTORE_PASS") ?: System.getenv("KEYSTORE_PASS")
    val alias = lp.getProperty("ALIAS_NAME") ?: System.getenv("ALIAS_NAME")
    val pwd = lp.getProperty("ALIAS_PASS") ?: System.getenv("ALIAS_PASS")

    androidApp.apply {
        if (keystorePwd != null) {
            signingConfigs.create("release") {
                storeFile = rootProject.file("release.keystore")
                storePassword = keystorePwd
                keyAlias = alias
                keyPassword = pwd
                enableV3Signing = true
            }
        }

        defaultConfig.targetSdk = 36
        buildTypes.getByName("release") {
            vcsInfo.include = false
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            ndk.debugSymbolLevel = "NONE"
        }
        buildTypes.getByName("debug") {
            applicationIdSuffix = "debug"
            isDebuggable = true
            isJniDebuggable = true
        }
        dependenciesInfo.includeInApk = false
        dependenciesInfo.includeInBundle = false
        bundle.language.enableSplit = false
        if (gradle.startParameter.taskNames.isNotEmpty() && gradle.startParameter.taskNames.any { it.lowercase().contains("assemble") }) {
            splits.abi.apply {
                isEnable = true
                isUniversalApk = false
                reset()
                include("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
            }
        }
    }
    val cleanTask = tasks.register("cleanAboutLibrariesGenerated") {
        delete(layout.buildDirectory.dir("generated/aboutLibraries"))
    }

    tasks.configureEach {
        if (name.contains("preBuild")) {
            dependsOn(cleanTask)
        }
    }
    dependencies.add("implementation", project(":plugin:api"))
}

fun Project.setupPlugin(projectName: String) {
    setupAppCommon(projectName)

    val propPrefix = projectName.uppercase()
    val verName = requireMetadata().getProperty("${propPrefix}_VERSION_NAME").trim()
    val verCode = requireMetadata().getProperty("${propPrefix}_VERSION").trim().toInt()

    androidApp.apply {
        defaultConfig.versionName = verName
        defaultConfig.versionCode = verCode
        flavorDimensions.add("vendor")
        productFlavors.create("oss")
    }
    androidComponents.apply {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                (output as? VariantOutputImpl)?.let { variantOutputImpl ->
                    val versionName = variantOutputImpl.versionName.orNull.orEmpty()
                    variantOutputImpl.outputFileName.set(variantOutputImpl.outputFileName.get()
                        .replace(project.name, "${projectName}-plugin-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                    )
                }
            }
        }
    }
}

fun Project.setupApp() {
    val pkgName = requireMetadata().getProperty("PACKAGE_NAME").trim()
    val verName = requireMetadata().getProperty("VERSION_NAME").trim()
    val verCode = requireMetadata().getProperty("VERSION_CODE").trim().toInt() * 5
    setupAppCommon()
    androidApp.apply {
        defaultConfig.applicationId = pkgName
        defaultConfig.versionCode = verCode
        defaultConfig.versionName = verName
        buildTypes.getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
        }
        buildFeatures.aidl = true
        buildFeatures.buildConfig = true
        buildFeatures.viewBinding = true
        compileOptions.isCoreLibraryDesugaringEnabled = true
        flavorDimensions.add("vendor")
        productFlavors.create("oss")
        productFlavors.create("clone") {
            applicationIdSuffix = "test"
        }
        tasks.register("downloadAssets") {
            downloadAssets(update = false)
        }
        tasks.register("updateAssets") {
            downloadRootCAList()
            downloadAssets(update = true)
        }

    }
    androidComponents.apply {
        onVariants { variant ->
            variant.outputs.forEach { output ->
                when (output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier) {
                    "arm64-v8a" -> output.versionCode.set(verCode + 4)
                    "x86_64" -> output.versionCode.set(verCode + 3)
                    "armeabi-v7a" -> output.versionCode.set(verCode + 2)
                    "x86" -> output.versionCode.set(verCode + 1)
                }
                (output as? VariantOutputImpl)?.let { variantOutputImpl ->
                    val versionName = variantOutputImpl.versionName.orNull.orEmpty()
                    val appName = if (variant.flavorName == "clone") "TestExclaveD" else "ExclaveD"
                    variantOutputImpl.outputFileName.set(variantOutputImpl.outputFileName.get()
                        .replace(project.name, "$appName-$versionName")
                        .replace("-release", "")
                        .replace("-oss", "")
                        .replace("-clone", "")
                    )
                }
            }
        }
    }
}
