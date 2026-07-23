import java.util.Base64

plugins {
    id("kurtos-native")
}

val generateAssets by tasks.registering {
    val assetsDir = rootProject.layout.projectDirectory.dir("assets")
    val outDir = layout.buildDirectory.dir("generated/assets")

    inputs.dir(assetsDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(outDir)

    doLast {
        val root = assetsDir.asFile
        val target = outDir.get().file("kapi/res/EmbeddedAssets.kt").asFile
        target.parentFile.mkdirs()

        val sb = StringBuilder()
        sb.append("package kapi.res\n\n")
        sb.append("object EmbeddedAssets {\n")
        sb.append("    val data: Map<String, String> = mapOf(\n")

        root.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".png", ignoreCase = true) }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { file ->
            val key = file.relativeTo(root).invariantSeparatorsPath
            val encoded = Base64.getEncoder().encodeToString(file.readBytes())
            sb.append("        \"").append(key).append("\" to \"").append(encoded).append("\",\n")
        }

        sb.append("    )\n}\n")
        target.writeText(sb.toString())
    }
}

kotlin.sourceSets.matching { it.name == "nativeMain" }.configureEach {
    kotlin.srcDir(generateAssets)
}
