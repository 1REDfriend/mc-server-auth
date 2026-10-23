plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.tongt"
version = "0.2.0-beta.1"

val paperApi = "io.papermc.paper:paper-api:26.3.build.200-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(paperApi)
    // Shaded into the plugin jar, so the plugin never depends on a runtime download to start.
    implementation("at.favre.lib:bcrypt:0.10.2")
    implementation("org.eclipse.angus:angus-mail:2.0.5")

    testImplementation(paperApi)
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

// The plain jar lacks the libraries; only the shaded jar may land in build/libs as mc-server-auth-*.jar.
tasks.jar {
    archiveClassifier = "plain"
    enabled = false
}

tasks.shadowJar {
    archiveClassifier = ""
    // bcrypt is relocated so another plugin's copy cannot clash. Jakarta Mail / Angus are NOT relocated: they find
    // their providers through META-INF resources keyed by class name, and Paper ships no jakarta.mail.
    relocate("at.favre.lib", "dev.tongt.mcserverauth.lib.favre")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class",
            "module-info.class")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
