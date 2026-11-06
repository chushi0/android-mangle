package cc.cszt0.mangle.plugin

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JavaSourceScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun scan(source: String) =
        scanManifest(source).entries

    private fun scanManifest(source: String) =
        JavaSourceScanner.scan(listOf(temp.newFolder().resolve("Sample.java").apply { writeText(source) }))

    @Test
    fun `moves annotated and native methods, and renames annotated classes without moving their methods`() {
        val manifest = scanManifest(
            """
            package a.b;
            import cc.cszt0.mangle.annotation.Mangle;
            class Sample {
                @Mangle void annotated() {}
                void plain() {}
                native void nat();
                @Mangle static class Nested {
                    Nested() {}
                    void one() {}
                    static int two() { return 2; }
                    interface Skipped { void abstractMethod(); }
                }
                @Mangle abstract static class Abstract {
                    abstract void noBody();
                    void hasBody() {}
                }
            }
            """,
        )
        assertEquals(mapOf("a.b.Sample" to setOf("annotated", "nat")), manifest.entries)
        assertEquals(setOf("a.b.Sample\$Nested", "a.b.Sample\$Abstract"), manifest.renamed)
    }

    @Test
    fun `accepts qualified and wildcard forms and ignores an unrelated Mangle`() {
        assertEquals(
            mapOf("Sample" to setOf("x")),
            scan("class Sample { @cc.cszt0.mangle.annotation.Mangle void x() {} @Mangle void y() {} }"),
        )
        assertEquals(
            mapOf("Sample" to setOf("x")),
            scan("import cc.cszt0.mangle.annotation.*; class Sample { @Mangle void x() {} }"),
        )
        assertEquals(
            emptyMap<String, Set<String>>(),
            scan("import other.Mangle; class Sample { @Mangle void x() {} }"),
        )
    }

    @Test
    fun `tolerates syntax errors`() {
        scan("class Sample { void broken( { }")
    }
}
