plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:5.3.2")
    api("org.eclipse.californium:californium-core:3.14.0")
    // Android 에는 org.json 이 내장돼 있다. JVM(테스트, mock)에서만 실제 라이브러리를 쓴다.
    compileOnly("org.json:json:20250517")

    testImplementation("org.json:json:20250517")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.17")
}
