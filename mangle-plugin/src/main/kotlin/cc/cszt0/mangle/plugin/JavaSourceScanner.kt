package cc.cszt0.mangle.plugin

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.ModifiersTree
import com.sun.source.util.JavacTask
import org.gradle.api.GradleException
import java.io.File
import javax.lang.model.element.Modifier
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/**
 * Finds what will be mangled by parsing source, because `MangleIdentifiers` constants have to exist
 * before javac runs: javac inlines them into the code that references them. Parsing needs no
 * classpath, and mangled names depend only on class and method names, so no type resolution is
 * required. Syntax errors are ignored here; the real compile reports them.
 */
internal object JavaSourceScanner {
    private const val ANNOTATION_PACKAGE = "cc.cszt0.mangle.annotation"
    private const val ANNOTATION = "$ANNOTATION_PACKAGE.Mangle"

    fun scan(files: Collection<File>): MangleManifest {
        if (files.isEmpty()) return MangleManifest(emptyMap())
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: throw GradleException("Mangle needs Gradle to run on a JDK, not a JRE.")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val found = mutableMapOf<String, MutableSet<String>>()
        val renamed = mutableSetOf<String>()
        compiler.getStandardFileManager(diagnostics, null, Charsets.UTF_8).use { fileManager ->
            val task = compiler.getTask(
                null,
                fileManager,
                diagnostics,
                listOf("-proc:none"),
                null,
                fileManager.getJavaFileObjectsFromFiles(files),
            ) as JavacTask
            task.parse().forEach { unit ->
                val packagePrefix = unit.packageName?.let { "$it." } ?: ""
                val simpleNameIsMangle = importsMangle(unit)
                unit.typeDecls.filterIsInstance<ClassTree>().forEach {
                    scanClass(it, packagePrefix + it.simpleName, simpleNameIsMangle, found, renamed)
                }
            }
        }
        return MangleManifest(found, renamed)
    }

    private fun importsMangle(unit: CompilationUnitTree): Boolean =
        unit.packageName?.toString() == ANNOTATION_PACKAGE ||
            unit.imports.any {
                !it.isStatic && it.qualifiedIdentifier.toString().let { name ->
                    name == ANNOTATION || name == "$ANNOTATION_PACKAGE.*"
                }
            }

    private fun scanClass(
        tree: ClassTree,
        binaryName: String,
        simpleNameIsMangle: Boolean,
        found: MutableMap<String, MutableSet<String>>,
        renamed: MutableSet<String>,
    ) {
        if (tree.modifiers.hasMangle(simpleNameIsMangle)) renamed += binaryName
        tree.members.forEach { member ->
            when (member) {
                is ClassTree -> scanClass(member, "$binaryName\$${member.simpleName}", simpleNameIsMangle, found, renamed)
                is MethodTree -> {
                    val name = member.name.toString()
                    val native = Modifier.NATIVE in member.modifiers.flags
                    val hasBody = member.body != null
                    val selected = native || (hasBody && member.modifiers.hasMangle(simpleNameIsMangle))
                    if (selected && name != "<init>") found.getOrPut(binaryName) { mutableSetOf() } += name
                }
            }
        }
    }

    private fun ModifiersTree.hasMangle(simpleNameIsMangle: Boolean) = annotations.any {
        val name = it.annotationType.toString()
        name == ANNOTATION || (simpleNameIsMangle && name == "Mangle")
    }
}
