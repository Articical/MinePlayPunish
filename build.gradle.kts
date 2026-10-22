plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "cloud.mineplay"
version = "1.2.1"
description = "Standalone, locally authoritative Mineplay moderation for Paper"

val javaFormatter = configurations.create("javaFormatter")
val mockitoAgent = configurations.create("mockitoAgent")
val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

dependencies {
    add(javaFormatter.name, "com.google.googlejavaformat:google-java-format:1.28.0:all-deps")
    add(mockitoAgent.name, "org.mockito:mockito-core:5.18.0") { isTransitive = false }
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")
    compileOnly("net.luckperms:api:5.5")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.google.re2j:re2j:1.8")
    implementation("info.picocli:picocli:4.7.7")
    testImplementation("io.papermc.paper:paper-api:26.3.build.200-stable")
    testImplementation("net.luckperms:api:5.5")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("org.mockito:mockito-core:5.18.0")
}

tasks.register<JavaExec>("formatJava") {
    description = "Formats project Java sources using google-java-format."
    classpath = javaFormatter
    mainClass.set("com.google.googlejavaformat.java.Main")
    args("--replace")
    args(fileTree("src") { include("**/*.java") }.files.sorted().map { it.absolutePath })
    for (pkg in listOf("api", "file", "parser", "tree", "util", "code")) {
        jvmArgs("--add-exports=jdk.compiler/com.sun.tools.javac.$pkg=ALL-UNNAMED")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:removal", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    inputs.files(fileTree("docs/presets") { include("uk-adult-*.json", "chat-filter-uk-adult.yml") })
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    jvmArgs("-javaagent:${mockitoAgent.singleFile}", "-Xshare:off")
}
tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
}
tasks.jar { archiveClassifier.set("unbundled") }
tasks.shadowJar {
    archiveClassifier.set("")
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    relocate("com.google.gson", "cloud.mineplay.punishments.libs.gson")
    relocate("com.google.re2j", "cloud.mineplay.punishments.libs.re2j")
    relocate("picocli", "cloud.mineplay.punishments.libs.picocli")
    // SQLite native libraries resolve their own resource names. Keep org.sqlite intact.
    dependencies { exclude(dependency("org.slf4j:.*:.*")) }
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.assemble { dependsOn(tasks.shadowJar) }
tasks.runServer {
    minecraftVersion("26.3")
    runDirectory.set(layout.projectDirectory.dir("run"))
    jvmArgs("-Xms1G", "-Xmx2G", "--enable-native-access=ALL-UNNAMED")
}
