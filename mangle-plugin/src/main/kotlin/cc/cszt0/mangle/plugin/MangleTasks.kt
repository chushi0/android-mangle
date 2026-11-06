package cc.cszt0.mangle.plugin

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.Closeable
import java.io.File
import java.util.TreeMap
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Parses a variant's Java sources and generates everything that must exist before javac runs. */
abstract class MangleScanTask : DefaultTask() {
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirs: ConfigurableFileCollection

    @get:Input
    abstract val secretKey: Property<String>

    @get:OutputDirectory
    abstract val identifiersDir: DirectoryProperty

    @get:OutputFile
    abstract val manifestFile: RegularFileProperty

    @get:OutputFile
    abstract val keepRulesFile: RegularFileProperty

    @TaskAction
    fun run() {
        val sources = sourceDirs.asFileTree.matching { include("**/*.java") }.files
        val manifest = JavaSourceScanner.scan(sources)
        val key = secretKey.get()
        val identifiers = manifest.identifiers(key)

        manifest.write(manifestFile.get().asFile)
        keepRulesFile.get().asFile.writeText(Renderer.keepRules(manifest, key))
        val outputDir = identifiersDir.get().asFile
        outputDir.deleteRecursively()
        File(outputDir, "${IDENTIFIERS_PACKAGE.replace('.', '/')}/$IDENTIFIERS_CLASS.java").run {
            parentFile.mkdirs()
            writeText(Renderer.identifiersJava(identifiers))
        }
    }
}

/** Writes `java_mangle.h` once for all variants, so native builds have a single header to include. */
abstract class MangleHeaderTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifests: ConfigurableFileCollection

    @get:Input
    abstract val secretKey: Property<String>

    @get:OutputFile
    abstract val headerFile: RegularFileProperty

    @TaskAction
    fun run() {
        val manifest = manifests.files.map(MangleManifest::read)
            .fold(MangleManifest(emptyMap()), MangleManifest::merge)
        headerFile.get().asFile.writeText(Renderer.header(manifest.identifiers(secretKey.get())))
    }
}

abstract class MangleTransformTask : DefaultTask() {
    @get:Classpath
    abstract val allJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val allDirectories: ListProperty<Directory>

    @get:Classpath
    abstract val bootClasspath: ListProperty<RegularFile>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:Input
    abstract val secretKey: Property<String>

    @get:OutputFile
    abstract val outputJar: RegularFileProperty

    @get:OutputFile
    abstract val mappingFragment: RegularFileProperty

    @TaskAction
    fun run() {
        val entries = readEntries()
        val classes = entries.filterKeys(::isClass).mapKeys { it.key.removeSuffix(".class") }
        val result = JarPlatformClasses(bootClasspath.get().map { it.asFile }).use { platform ->
            ClassMover(
                classes,
                MangleManifest.read(manifestFile.get().asFile),
                secretKey.get(),
                platform,
            ) { logger.warn("Mangle: $it") }.run()
        }
        val output = TreeMap(entries.filterKeys { !isClass(it) })
        result.classes.forEach { (name, bytes) -> output["$name.class"] = bytes }
        writeJar(output)
        mappingFragment.get().asFile.writeText(result.mapping)
    }

    private fun isClass(path: String) =
        path.endsWith(".class") && !path.startsWith("META-INF/") && !path.endsWith("module-info.class")

    private fun readEntries(): Map<String, ByteArray> {
        val entries = HashMap<String, ByteArray>()
        allDirectories.get().forEach { directory ->
            val root = directory.asFile
            root.walkTopDown().filter { it.isFile }.forEach {
                entries.putIfAbsent(it.relativeTo(root).invariantSeparatorsPath, it.readBytes())
            }
        }
        allJars.get().forEach { jar ->
            ZipFile(jar.asFile).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory }.forEach {
                    entries.putIfAbsent(it.name, zip.getInputStream(it).use { input -> input.readBytes() })
                }
            }
        }
        return entries
    }

    private fun writeJar(entries: Map<String, ByteArray>) {
        ZipOutputStream(outputJar.get().asFile.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = 0 })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}

private class JarPlatformClasses(files: List<File>) : PlatformClasses, Closeable {
    private val jars = files.filter { it.isFile }.map(::JarFile)
    private val cache = HashMap<String, ClassInfo?>()

    override fun find(internalName: String): ClassInfo? = cache.getOrPut(internalName) {
        jars.firstNotNullOfOrNull { jar ->
            jar.getEntry("$internalName.class")?.let { entry ->
                val node = ClassNode()
                jar.getInputStream(entry).use { ClassReader(it).accept(node, ClassReader.SKIP_CODE) }
                ClassInfo.of(node)
            }
        }
    }

    override fun close() = jars.forEach(JarFile::close)
}

/** Writes the mapping for variants that do not run R8, at the path R8 would have used. */
abstract class MangleMappingTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFragment: RegularFileProperty

    @get:OutputFile
    abstract val mappingFile: RegularFileProperty

    @TaskAction
    fun run() {
        mappingFragment.get().asFile.copyTo(mappingFile.get().asFile, overwrite = true)
    }
}

/**
 * Runs as the last action of the R8 task rather than as a task of its own: R8 owns the file, and an
 * up-to-date or cached R8 run must neither lose the appended lines nor get them twice.
 */
internal class AppendToR8Mapping(
    private val fragment: Provider<RegularFile>,
    private val mapping: Provider<RegularFile>,
) : Action<Task> {
    override fun execute(task: Task) {
        val target = mapping.get().asFile
        target.parentFile.mkdirs()
        val r8Mapping = if (target.exists()) target.readText() else ""
        target.writeText(R8MappingMerger.merge(r8Mapping, fragment.get().asFile.readText()))
    }
}
