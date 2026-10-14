plugins {
    id("com.android.application")
}

android {
    namespace = "kr.c40.floor"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.c40.floor"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/legal/**", "META-INF/LICENSE*", "META-INF/NOTICE*")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.eclipse.californium:californium-core:3.14.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
}
