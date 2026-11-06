package cc.cszt0.mangle.plugin

import com.android.build.api.artifact.ScopedArtifact
import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.ScopedArtifacts
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

class ManglePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("mangle", MangleExtension::class.java)
        extension.headerOutputDir.convention(project.layout.buildDirectory.dir("generated/mangle/include"))

        project.plugins.withId("com.android.application") { configure(project, extension) }
        project.plugins.withId("com.android.library") {
            throw GradleException(
                "Mangle only supports com.android.application: MangleIdentifiers has a fixed package, " +
                    "so every module applying the plugin would ship a clashing copy.",
            )
        }
    }

    private fun configure(project: Project, extension: MangleExtension) {
        val version = javaClass.getResource("version.txt")!!.readText().trim()
        project.dependencies.add("compileOnly", "cc.cszt0.mangle:mangle-annotation:$version")

        val headerTask = project.tasks.register("mangleGenerateHeader", MangleHeaderTask::class.java) {
            secretKey.set(extension.key)
            headerFile.set(extension.headerOutputDir.file("java_mangle.h"))
        }
        // A variant does not map to one native task: AGP names them after the CMake build type
        // and shares them between variants. That is why there is one header for all variants.
        project.tasks.configureEach {
            if (NATIVE_BUILD_TASK.matches(name)) dependsOn(headerTask)
        }

        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        androidComponents.onVariants { variant ->
            if (extension.key.orNull.isNullOrEmpty()) {
                throw GradleException("Mangle: set the HMAC key with mangle { key = \"...\" }.")
            }
            configureVariant(project, extension, androidComponents, variant, headerTask)
        }
    }

    private fun configureVariant(
        project: Project,
        extension: MangleExtension,
        androidComponents: ApplicationAndroidComponentsExtension,
        variant: ApplicationVariant,
        headerTask: TaskProvider<MangleHeaderTask>,
    ) {
        val suffix = variant.name.replaceFirstChar { it.uppercase() }
        val workDir = project.layout.buildDirectory.dir("intermediates/mangle/${variant.name}")
        val javaSources = variant.sources.java ?: return

        val scanTask = project.tasks.register("mangleScan$suffix", MangleScanTask::class.java) {
            // Only static directories: `all` would contain this task's own output.
            sourceDirs.from(javaSources.static)
            secretKey.set(extension.key)
            identifiersDir.set(project.layout.buildDirectory.dir("generated/mangle/${variant.name}/java"))
            manifestFile.set(workDir.map { it.file("manifest.txt") })
            keepRulesFile.set(workDir.map { it.file("keep-rules.pro") })
        }
        javaSources.addGeneratedSourceDirectory(scanTask, MangleScanTask::identifiersDir)
        variant.proguardFiles.add(scanTask.flatMap { it.keepRulesFile })
        headerTask.configure { manifests.from(scanTask.flatMap { it.manifestFile }) }

        variant.externalNativeBuild?.let { nativeBuild ->
            // CMake joins these into a command line, so a path with spaces needs its own quotes.
            val includeFlag = extension.headerOutputDir.map { "-I\"${it.asFile.absolutePath}\"" }
            nativeBuild.cFlags.add(includeFlag)
            nativeBuild.cppFlags.add(includeFlag)
        }

        val transformTask = project.tasks.register("mangleTransform$suffix", MangleTransformTask::class.java) {
            manifestFile.set(scanTask.flatMap { it.manifestFile })
            secretKey.set(extension.key)
            bootClasspath.set(androidComponents.sdkComponents.bootClasspath)
            mappingFragment.set(workDir.map { it.file("mapping-fragment.txt") })
        }
        variant.artifacts.forScope(ScopedArtifacts.Scope.PROJECT)
            .use(transformTask)
            .toTransform(
                ScopedArtifact.CLASSES,
                MangleTransformTask::allJars,
                MangleTransformTask::allDirectories,
                MangleTransformTask::outputJar,
            )

        val fragment = transformTask.flatMap { it.mappingFragment }
        if (variant.isMinifyEnabled) {
            val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
            project.tasks.configureEach {
                if (name == "minify${suffix}WithR8") {
                    inputs.file(fragment)
                    doLast(AppendToR8Mapping(fragment, mapping))
                }
            }
        } else {
            val mappingTask = project.tasks.register("mangleMapping$suffix", MangleMappingTask::class.java) {
                mappingFragment.set(fragment)
                mappingFile.set(project.layout.buildDirectory.file("outputs/mapping/${variant.name}/mapping.txt"))
            }
            project.tasks.configureEach {
                if (name == "assemble$suffix" || name == "bundle$suffix") dependsOn(mappingTask)
            }
        }
    }

    private companion object {
        val NATIVE_BUILD_TASK =
            Regex("preBuild|(configure|build)(CMake|NdkBuild).*|generateJsonModel.*|externalNativeBuild.*")
    }
}
