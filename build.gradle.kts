plugins {
    java
}

group = "dev.groupsurvival"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.codemc.io/repository/maven-releases/")
    maven("https://repo.lunarclient.dev")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.262-stable")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")
    compileOnly("com.lunarclient:apollo-api:1.3.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveFileName.set("GroupSurvivalSystem-${project.version}.jar")
}

