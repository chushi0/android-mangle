package cc.cszt0.mangle.plugin

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JavaSourceScannerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun scan(source: String) =
        JavaSourceScanner.scan(listOf(temp.newFolder().resolve("Sample.java").apply { writeText(source) })).entries

    @Test
    fun `selects annotated methods, annotated classes and native methods`() {
        val entries = scan(
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
        assertEquals(
            mapOf(
                "a.b.Sample" to setOf("annotated", "nat"),
                "a.b.Sample\$Nested" to setOf("one", "two"),
                "a.b.Sample\$Abstract" to setOf("hasBody"),
            ),
            entries,
        )
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
