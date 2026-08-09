import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = "1.0"

buildscript{
    repositories{
        mavenCentral()
    }
}

// Mindustry version to depend on.
// Valid values:
// - latest: depend on the latest release of mindustry
// - be: depend on the very latest commit of mindustry
// - v<number>: depend on a specific version
val mindustryVersion = "v159.7"
val kotlinVersion = "2.3.20"
val sdkRoot: String? = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val isWindows = System.getProperty("os.name").lowercase().contains("windows")
val modArtifactName = project.name

plugins{
    kotlin("jvm") version "2.3.20"
}

sourceSets.main{
    kotlin.srcDirs("src")
}

repositories{
    mavenCentral()

    // Downloads the dependencies JAR file from Mindustry releases; does not use any real repository. Surprisingly, this is the most reliable option.
    ivy{
        url = uri("https://github.com/")
        patternLayout{
            artifact(
                when(mindustryVersion){
                    "latest" -> "/[organisation]/[module]/releases/[revision]/download/dependencies.jar" // latest stable release
                    "be" -> "/[organisation]/[module]/releases/download/master/[revision].jar" // latest commit (BE)
                    else -> "/[organisation]/[module]/releases/download/[revision]/dependencies.jar" // specific release
                }
            )
        }
        metadataSources{ artifact() }

        content{
            if(mindustryVersion == "be"){
                // BE artifact version is always 'latest'
                includeVersion("Anuken", "MindustryBuilds", "latest")
            }else{
                includeVersion("Anuken", "Mindustry", mindustryVersion)
            }
        }
    }
}

dependencies{
    compileOnly(if(mindustryVersion == "be") "Anuken:MindustryBuilds:latest" else "Anuken:Mindustry:$mindustryVersion")
}

tasks.withType<KotlinCompile>().configureEach{
    compilerOptions{
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks.withType<JavaCompile>().configureEach{
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.release.set(8)
}

val jarAndroid = tasks.register("jarAndroid"){
    dependsOn("jar")

    doLast{
        if (sdkRoot.isNullOrEmpty() || !File(sdkRoot).exists()){
            throw GradleException("No valid Android SDK found. Ensure that ANDROID_HOME is set to your Android SDK directory.")
        }

        val platformRoot = File("$sdkRoot/platforms/").listFiles()
            ?.sorted()
            ?.reversed()
            ?.find{ f -> File(f, "android.jar").exists() }
            ?: throw GradleException("No android.jar found. Ensure that you have an Android platform installed.")

        // collect dependencies needed for desugaring
        val dependencies = (configurations.compileClasspath.get().toList() +
                configurations.runtimeClasspath.get().toList() +
                listOf(File(platformRoot, "android.jar")))
            .joinToString(" "){ "--classpath ${it.path}" }

        // dex and desugar files - this requires d8 in your PATH
        val d8 = if (isWindows) "d8.bat" else "d8"

        val process = ProcessBuilder(
            "$d8 $dependencies --min-api 21 --output ${modArtifactName}Android.jar ${modArtifactName}Desktop.jar".split(" "))
            .directory(File("${layout.buildDirectory.get().asFile}/libs"))
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        process.waitFor()
    }
}

tasks.jar{
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("${modArtifactName}Desktop.jar")

    from(configurations.runtimeClasspath.map{ config -> config.map{ if (it.isDirectory) it else zipTree(it) } })

    from(rootDir){
        include("mod.hjson")
    }

    from("assets/"){
        include("**")
    }
}

val deploy = tasks.register("deploy", Jar::class){
    dependsOn(jarAndroid)
    dependsOn(tasks.jar)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("$modArtifactName.jar")

    from({
        listOf(
            zipTree("${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Desktop.jar"),
            zipTree("${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Android.jar")
        )
    })

    doLast{
        delete(
            "${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Desktop.jar",
            "${layout.buildDirectory.get().asFile}/libs/${modArtifactName}Android.jar"
        )
    }
}
