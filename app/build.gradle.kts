plugins {
    id("com.android.application")
}

android {
    namespace = "kr.c40.floor"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.c40.floor.onetouch"
        minSdk = 29
        targetSdk = 29
        versionCode = 3
        versionName = "0.3.0"
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
    // OkHttp, Californium 은 core 가 api 로 노출한다.
    implementation(project(":core"))
    implementation("org.slf4j:slf4j-nop:2.0.17")
}
