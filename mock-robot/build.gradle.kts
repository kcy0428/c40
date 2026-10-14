plugins {
    id("org.jetbrains.kotlin.jvm")
    application
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

application {
    mainClass.set("kr.c40.floor.mock.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation("org.json:json:20250517")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation("junit:junit:4.13.2")
}
