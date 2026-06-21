import xyz.jpenilla.resourcefactory.bukkit.BukkitPluginYaml

plugins {
    kotlin("jvm") version "2.3.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("xyz.jpenilla.resource-factory-bukkit-convention") version "1.3.1"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "seasons"
version = "1.1.1"
description = "Feel the rhythm of nature through dynamic seasonal cycles."

bukkitPluginYaml {
    name = "vxseasons"
    main = "vx.seasons.SeasonsPlugin"
    load = BukkitPluginYaml.PluginLoadOrder.STARTUP
    depend = listOf("packetevents")
    authors.add("vxquid")
    apiVersion = "26.1"
}

repositories {
    mavenCentral()
    maven("https://repo.aikar.co/content/groups/aikar/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.codemc.io/repository/maven-snapshots/")
    maven("https://mvn.lumine.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/main/")
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.+")
    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.cryptomorin:XSeries:13.6.0")
    implementation("co.aikar:acf-paper:0.5.1-SNAPSHOT") // Annotations-based commands.
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.2")
}

tasks {

    compileJava {
        // Set the release flag. This configures what version bytecode the compiler will emit, as well as what JDK APIs are usable.
        // See https://openjdk.java.net/jeps/247 for more information.
        options.release = 25
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name() // We want UTF-8 for everything
    }

    shadowJar {
        archiveFileName = "vxseasons-${version}.jar"
        minimize()
        relocate("co.aikar.commands", "vx.seasons.command")
        relocate("co.aikar.locales", "vx.seasons.command.locales")
        relocate("kotlin", "vx.seasons.kotlin")
        relocate("com.cryptomorin.xseries", "vx.seasons.utils")
    }

}

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

kotlin {
    jvmToolchain(25)
}