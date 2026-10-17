plugins {
    java
}

group = "dev.tongt"
version = "0.1.0"

val paperApi = "io.papermc.paper:paper-api:26.3.build.200-stable"
val bcrypt = "at.favre.lib:bcrypt:0.10.2"
val angusMail = "org.eclipse.angus:angus-mail:2.0.5"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // bcrypt and angus-mail are downloaded by Paper at runtime (plugin.yml `libraries`).
    compileOnly(paperApi)
    compileOnly(bcrypt)
    compileOnly(angusMail)

    testImplementation(paperApi)
    testImplementation(bcrypt)
    testImplementation(angusMail)
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
