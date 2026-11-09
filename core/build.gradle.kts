plugins {
    java
    alias(libs.plugins.shadow)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    compileOnly(libs.paper.api)

    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)

    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.embedded.postgres)
    testImplementation(enforcedPlatform(libs.postgres.binaries.bom))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// Only the shaded jar is deployable; avoid producing a second, incomplete jar.
tasks.jar {
    enabled = false
}

tasks.shadowJar {
    archiveBaseName.set("ClubCore")
    archiveClassifier.set("")

    // Flyway discovers its core and database plugins through ServiceLoader files that several jars contribute.
    // Duplicates must reach the transformer to be merged rather than dropped.
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesMatching("META-INF/LICENSE*") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // Paper already provides SLF4J; bundling it would conflict.
    dependencies {
        exclude(dependency("org.slf4j:.*"))
    }

    val shaded = "com.inpuzah.club.core.libs"
    relocate("org.postgresql", "$shaded.postgresql")
    relocate("com.zaxxer.hikari", "$shaded.hikari")
    relocate("org.flywaydb", "$shaded.flywaydb")
    relocate("org.checkerframework", "$shaded.checkerframework")

    exclude(
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA",
        "module-info.class", "META-INF/versions/*/module-info.class",
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
