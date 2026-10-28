/*
 * HytaleHub Plugin - Server Integration for Hytale-Hub.de
 * Build configuration for Hytale server plugin (Java 25+)
 *
 * Reference: https://hytale-docs.com/docs/modding/plugins/overview
 */

plugins {
    java
}

group = "de.hytalehub"
version = "0.2.3"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    
    // Option 1: Offizielles Hytale Maven Repository (wenn verfügbar)
    // maven {
    //     name = "HytaleAPI"
    //     url = uri("https://maven.hytale.com/releases")
    // }
    
    // Option 2: Lokales libs/ Verzeichnis für Server JAR
    flatDir {
        dirs("libs")
    }
}

dependencies {
    // Hytale Server API - provided at runtime by server
    //
    // WICHTIG: Wähle EINE der folgenden Optionen:
    //
    // Option A: Wenn offizielles Maven Repo verfügbar:
    // compileOnly("com.hypixel.hytale:server-api:1.0.0")
    //
    // Option B: Lokale Server JAR in libs/ Ordner:
    // 1. Erstelle Ordner: libs/
    // 2. Kopiere HytaleServer.jar dort hinein
    // 3. Uncomment folgende Zeile:
    compileOnly(files("libs/HytaleServer.jar"))

    // JSR-305 Annotations (@Nonnull, @Nullable)
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    // Gson for JSON parsing (provided by server runtime)
    compileOnly("com.google.code.gson:gson:2.10.1")

    // Jakarta Servlet API (for HTTP mode - provided by WebServer at runtime)
    // Note: WebServerPlugin integration uses reflection to avoid compile-time dependency
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.1.0")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all",
        "-Xlint:-processing"
    ))
}

tasks.jar {
    // Handle duplicate files (manifest.json is already in resources)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    // WICHTIG: Explizit die Resources einbinden für Asset Pack!
    // Ref: https://www.troubledev.com/blog/getting-started-with-custom-uis-in-hytale
    from("src/main/resources")
    
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Main-Class" to "de.hytalehub.HytaleHubPlugin"
        )
    }
    
    archiveBaseName.set("HytaleHub")
}

// Task to copy built JAR to mods folder for testing
tasks.register<Copy>("deployToServer") {
    dependsOn(tasks.jar)
    from(tasks.jar.get().archiveFile)
    into(file(System.getProperty("hytale.server.mods", "../server/mods")))
}
