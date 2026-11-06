package cc.cszt0.mangle.plugin

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManifestTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `mangled names depend on the key and are valid single tokens`() {
        val name = Naming.mangle("key", "com.example.Foo")
        assertTrue(name, Regex("m[0-9a-f]{16}").matches(name))
        assertEquals(name, Naming.mangle("key", "com.example.Foo"))
        assertNotEquals(name, Naming.mangle("other", "com.example.Foo"))
    }

    @Test
    fun `constants cover classes and methods`() {
        val identifiers = MangleManifest(mapOf("com.example.Outer\$Inner" to setOf("run"))).identifiers("k")
        assertEquals(listOf("COM_EXAMPLE_OUTER_INNER", "COM_EXAMPLE_OUTER_INNER_RUN"), identifiers.map { it.constant })
    }

    @Test(expected = GradleException::class)
    fun `constant collisions fail the build`() {
        MangleManifest(mapOf("a.Foo" to setOf("getUrl", "getURL"))).identifiers("k")
    }

    @Test
    fun `manifest survives a round trip`() {
        val file = temp.newFile()
        MangleManifest(mapOf("a.Foo" to setOf("x", "y"), "a.Empty" to emptySet()), setOf("a.Foo\$Renamed")).write(file)
        val manifest = MangleManifest.read(file)
        assertEquals(mapOf("a.Empty" to emptySet(), "a.Foo" to setOf("x", "y")), manifest.entries)
        assertEquals(setOf("a.Foo\$Renamed"), manifest.renamed)
    }

    @Test
    fun `renamed classes get a constant and keep only their constructors`() {
        val manifest = MangleManifest(emptyMap(), setOf("a.Foo\$Failure"))
        assertEquals(listOf("A_FOO_FAILURE"), manifest.identifiers("k").map { it.constant })
        assertEquals(
            "-keep class ${Naming.mangleClass("k", "a.Foo\$Failure")} { <init>(...); }\n",
            Renderer.keepRules(manifest, "k"),
        )
    }

    @Test
    fun `a renamed class cannot also host moved methods`() {
        val failure = runCatching {
            MangleManifest(mapOf("a.Foo" to setOf("nat")), setOf("a.Foo")).identifiers("k")
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is GradleException && "a.Foo" in failure.message!!)
    }
}
