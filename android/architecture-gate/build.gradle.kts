plugins {
    java
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("com.opentypeless.architecture.CompiledArchitectureGate")
}

dependencies {
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-analysis:9.7")
    implementation("org.ow2.asm:asm-tree:9.7")
    implementation("org.ow2.asm:asm-util:9.7")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    useJUnit()
}

val compiledArchitectureVariants = listOf("debug", "release")
val compiledArchitectureDirectories = compiledArchitectureVariants.associateWith { variant ->
    rootProject.layout.projectDirectory.dir("app/build/editor-architecture/$variant")
}

val verifyCompiledArchitecture by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Verifies compiled debug and release architecture boundaries."
    dependsOn(":app:exportCompiledArchitectureInputs", tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set(application.mainClass)
    inputs.files(compiledArchitectureDirectories.values.flatMap { directory ->
        listOf(directory.file("project.paths").asFile, directory.file("all.paths").asFile)
    })

    compiledArchitectureVariants.forEach { variant ->
        val directory = compiledArchitectureDirectories.getValue(variant)
        args("--variant", variant)
        args("--project-manifest", directory.file("project.paths").asFile.absolutePath)
        args("--all-manifest", directory.file("all.paths").asFile.absolutePath)
    }
}

tasks.named("check") {
    dependsOn(tasks.named("test"), verifyCompiledArchitecture)
}
