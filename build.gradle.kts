plugins {
    java
    checkstyle
}

repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    maven {
        url = uri("https://nexus.telesphoreo.me/repository/plex/")
    }
    mavenCentral()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")
    compileOnly("dev.plex:api:2.0-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
    testImplementation("dev.plex:api:2.0-SNAPSHOT")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("org.jdbi:jdbi3-core:3.54.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

group = "dev.plex"
version = "2.0-SNAPSHOT"
description = "Two-factor authentication module for Plex"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

checkstyle {
    toolVersion = "14.1.0"
    configFile = rootProject.file("config/checkstyle/checkstyle.xml")
}

tasks.jar {
    archiveBaseName.set("Module-2FA")
    archiveVersion.set("")
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
    }
    processResources {
        filteringCharset = Charsets.UTF_8.name()
    }
    test {
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
}
