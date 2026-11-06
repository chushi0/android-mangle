package cc.cszt0.mangle.plugin

import org.gradle.api.GradleException
import java.io.File
import java.util.TreeMap
import java.util.TreeSet
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val IDENTIFIERS_PACKAGE = "cc.cszt0.mangle"
internal const val IDENTIFIERS_CLASS = "MangleIdentifiers"

internal object Naming {
    /**
     * `m` + 64 bits of HMAC in lowercase hex. A single lowercase alphanumeric token is valid as a
     * Java identifier and a JNI symbol, and cannot collide on a case-insensitive file system.
     */
    fun mangle(key: String, original: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val hash = mac.doFinal(original.toByteArray(Charsets.UTF_8))
        return "m" + hash.take(8).joinToString("") { "%02x".format(it) }
    }

    fun mangleClass(key: String, binaryName: String) = mangle(key, binaryName)

    fun mangleMethod(key: String, binaryName: String, method: String) = mangle(key, "$binaryName.$method")

    fun constant(original: String) = original.replace(Regex("[^A-Za-z0-9]"), "_").uppercase()
}

internal data class Identifier(val constant: String, val original: String, val mangled: String)

/** Classes and method names selected from source: binary class name to method names. */
internal class MangleManifest(entries: Map<String, Set<String>>) {
    val entries: Map<String, Set<String>> =
        TreeMap(entries.mapValues { TreeSet(it.value) as Set<String> })

    fun identifiers(key: String): List<Identifier> {
        val identifiers = entries.flatMap { (className, methods) ->
            listOf(Identifier(Naming.constant(className), className, Naming.mangleClass(key, className))) +
                methods.map {
                    val original = "$className.$it"
                    Identifier(Naming.constant(original), original, Naming.mangleMethod(key, className, it))
                }
        }
        failOnDuplicates(identifiers, Identifier::constant, "constant name")
        failOnDuplicates(identifiers, Identifier::mangled, "mangled name")
        return identifiers
    }

    private fun failOnDuplicates(
        identifiers: List<Identifier>,
        selector: (Identifier) -> String,
        what: String,
    ) {
        identifiers.groupBy(selector).values.firstOrNull { it.size > 1 }?.let { clash ->
            throw GradleException(
                "Mangle: ${clash.joinToString { it.original }} share the $what ${selector(clash[0])}; " +
                    "rename one of them.",
            )
        }
    }

    fun write(file: File) {
        file.parentFile.mkdirs()
        file.writeText(entries.entries.joinToString("") { (c, m) -> "$c\t${m.joinToString(",")}\n" })
    }

    fun merge(other: MangleManifest): MangleManifest {
        val merged = entries.mapValuesTo(TreeMap()) { it.value.toMutableSet() }
        other.entries.forEach { (c, m) -> merged.getOrPut(c) { mutableSetOf() } += m }
        return MangleManifest(merged)
    }

    companion object {
        fun read(file: File) = MangleManifest(
            file.readLines().filter { it.isNotEmpty() }.associate { line ->
                val (className, methods) = line.split('\t', limit = 2)
                className to methods.split(',').filter { it.isNotEmpty() }.toSet()
            },
        )
    }
}

internal object Renderer {
    fun identifiersJava(identifiers: List<Identifier>) = buildString {
        append("package $IDENTIFIERS_PACKAGE;\n\n")
        append("public final class $IDENTIFIERS_CLASS {\n")
        identifiers.forEach {
            append("    public static final String ${it.constant} = \"${it.mangled}\";\n")
        }
        append("\n    private $IDENTIFIERS_CLASS() {\n    }\n}\n")
    }

    fun header(identifiers: List<Identifier>) = buildString {
        append("#ifndef JAVA_MANGLE_H\n#define JAVA_MANGLE_H\n\n")
        identifiers.forEach { append("#define ${it.constant} \"${it.mangled}\"\n") }
        append("\n#endif // JAVA_MANGLE_H\n")
    }

    fun keepRules(manifest: MangleManifest, key: String) = buildString {
        manifest.entries.keys.forEach {
            append("-keep class ${Naming.mangleClass(key, it)} { *; }\n")
        }
    }
}
