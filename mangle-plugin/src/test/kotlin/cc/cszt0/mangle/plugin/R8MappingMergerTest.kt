package cc.cszt0.mangle.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class R8MappingMergerTest {
    private val fragment = """
        a.Foo -> m1:
            java.lang.String bar(int) -> m2

    """.trimIndent()

    @Test
    fun `keeps R8 content and carries line ranges over`() {
        val r8 = """
            a.Foo -> b:
            m1 -> m1:
            # {"id":"sourceFile","fileName":"Foo.java"}
                1:3:java.lang.String m2(int):10:12 -> m2
                4:4:void a.Other.inlined():5:5 -> m2

        """.trimIndent()
        val merged = R8MappingMerger.merge(r8, fragment)
        assertTrue(merged.startsWith(r8))
        assertEquals(
            """
            a.Foo -> m1:
                java.lang.String bar(int) -> m2
                1:3:java.lang.String bar(int):10:12 -> m2

            """.trimIndent(),
            merged.removePrefix(r8),
        )
    }

    @Test
    fun `refreshes the hash over everything after it`() {
        val merged = R8MappingMerger.merge("# pg_map_hash: SHA-256 00\nm1 -> m1:\n", fragment)
        val (header, rest) = merged.split("\n", limit = 2)
        val expected = MessageDigest.getInstance("SHA-256").digest(rest.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("# pg_map_hash: SHA-256 $expected", header)
    }

    @Test
    fun `works without an R8 mapping`() {
        assertEquals(fragment, R8MappingMerger.merge("", fragment))
    }
}
