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
        MangleManifest(mapOf("a.Foo" to setOf("x", "y"), "a.Empty" to emptySet())).write(file)
        assertEquals(
            mapOf("a.Empty" to emptySet(), "a.Foo" to setOf("x", "y")),
            MangleManifest.read(file).entries,
        )
    }
}
