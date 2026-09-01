rootProject.name = "Korabooks"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    /**
     * A newer R8 than the AGP ships with, because this project is ahead of it
     * on Kotlin.
     *
     * R8 is bundled inside the AGP, and 8.13.2 bundles R8 8.13.x. That R8 was
     * built before Kotlin 2.4 and cannot read its `@Metadata`, so a release
     * build printed **7 262** copies of "An error occurred when parsing kotlin
     * metadata" — measured, one per class it gave up on. Google's table
     * (developer.android.com/studio/build/kotlin-d8-r8-versions) puts Kotlin 2.4
     * at R8 9.1.29 with AGP 8.5.2 or newer: the AGP here is new enough, only its
     * bundled R8 is not.
     *
     * The consequence was not only noise. Metadata R8 cannot parse is metadata
     * it cannot rewrite, so anything reading it at runtime through reflection
     * sees names that no longer match the shrunk classes.
     *
     * Keep this in step with `kotlin` in gradle/libs.versions.toml: a Kotlin
     * bump needs the matching R8 from that table, or the warnings come back.
     */
    buildscript {
        repositories {
            mavenCentral()
            maven { url = uri("https://storage.googleapis.com/r8-releases/raw") }
        }
        dependencies {
            classpath("com.android.tools:r8:9.1.29")
        }
    }

    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

include(":epub-reader")
include(":komelia-app")
include(":komelia-domain:core")
include(":komelia-domain:offline")
include(":komelia-domain:komga-api")
include(":komelia-ui")

include(":komelia-infra:database:transaction")
include(":komelia-infra:database:shared")
include(":komelia-infra:database:sqlite")
include(":komelia-infra:database:wasm")
include(":komelia-infra:image-decoder:shared")
include(":komelia-infra:image-decoder:vips")
include(":komelia-infra:image-decoder:wasm-image-worker")
include(":komelia-infra:jni")
include(":komelia-infra:webview")


include(":third_party:ChipTextField:chiptextfield-core")
include(":third_party:ChipTextField:chiptextfield-m3")
include(":third_party:compose-sonner:sonner")
include(":third_party:indexeddb:core")
include(":third_party:indexeddb:external")

includeBuild("third_party/secret-service") {
    dependencySubstitution { substitute(module("de.swiesend:secret-service")) }
}
