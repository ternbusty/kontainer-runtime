plugins {
    kotlin("multiplatform") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    id("io.kotest") version "6.0.4"
    id("com.google.devtools.ksp") version "2.2.20-2.0.4"
}

group = "me.user"
version = "1.0-SNAPSHOT"
val kotestVersion = "6.0.4"

repositories {
    mavenCentral()
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val nativeTarget = when {
        hostOs == "Linux" && !isArm64 -> linuxX64()
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"
                baseName = "kontainer-runtime"
            }
        }

        compilations.getByName("main").cinterops {
            val libseccomp by creating {}
            val socket by creating {}
            val wait by creating {}
            val sched by creating {}
            val closerange by creating {}
            val syscall by creating {}
            val prctl by creating {}
        }
    }

    sourceSets {
        nativeMain.dependencies {
            implementation(libs.kotlinxSerializationJson)
        }
        nativeTest.dependencies {
            implementation("io.kotest:kotest-assertions-core:${kotestVersion}")
            implementation("io.kotest:kotest-framework-engine:${kotestVersion}")
        }
    }
}
