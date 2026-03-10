plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.12.0"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
}

val platformVersion: String by project
val pluginVersion: String by project
val javaVersion: String by project

group = "com.github.audioplayer"
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(platformVersion)
        pluginVerifier()
        zipSigner()
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.audioplayer"
        name = "Audio Player"
        version = pluginVersion
        description = "Play audio files directly in the editor with playback controls."
    }
    signing {
        certificateChainFile.set(file(System.getenv("CERTIFICATE_CHAIN") ?: "chain.crt"))
        privateKeyFile.set(file(System.getenv("PRIVATE_KEY") ?: "private.pem"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD") ?: "")
    }
    publishing {
        token.set(System.getenv("PUBLISH_TOKEN") ?: "")
    }
}

ktlint {
    version.set("1.5.0")
    outputToConsole.set(true)
    outputColorName.set("RED")
    filter {
        exclude("**/build/**")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
