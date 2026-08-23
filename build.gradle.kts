import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.grammarkit")
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("generated/sources/grammarKit"))
    }
}

tasks.generateParser {
    sourceFile.set(file("src/main/grammars/Mojo.bnf"))
    targetRootOutputDir.set(layout.buildDirectory.dir("generated/sources/grammarKit"))
    pathToParser.set("com/github/petrmakhnev/intellijmojo/lang/parser/MojoParser.java")
    pathToPsiRoot.set("com/github/petrmakhnev/intellijmojo/lang/psi")
    purgeOldFiles.set(true)
}

tasks.compileKotlin {
    dependsOn(tasks.generateParser)
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea("2025.2.6.2")
        testFramework(TestFrameworkType.Platform)
    }
}
