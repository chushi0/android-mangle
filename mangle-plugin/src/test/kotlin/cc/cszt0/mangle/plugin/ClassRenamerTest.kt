package cc.cszt0.mangle.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import javax.tools.ToolProvider

/** Renamed classes run on the JVM here, which enforces packages, nests and overriding. */
class ClassRenamerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val key = "test-key"
    private val warnings = mutableListOf<String>()

    private fun compile(vararg sources: Pair<String, String>, release: String = "11"): Map<String, ByteArray> {
        val root = temp.newFolder()
        val output = temp.newFolder()
        val files = sources.map { (path, text) ->
            File(root, path).apply {
                parentFile.mkdirs()
                writeText(text)
            }
        }
        val arguments = listOf(
            "--release", release, "-proc:none", "-nowarn",
            "-cp", System.getProperty("java.class.path"), "-d", output.path,
        ) + files.map { it.path }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()))
        return output.walkTopDown().filter { it.extension == "class" }
            .associate { it.relativeTo(output).path.removeSuffix(".class") to it.readBytes() }
    }

    private fun rename(classes: Map<String, ByteArray>, vararg renamed: String) =
        ClassRenamer(classes, MangleManifest(emptyMap(), renamed.toSet()), key) { warnings += it }.run()

    private fun load(classes: Map<String, ByteArray>) = object : ClassLoader(null) {
        override fun findClass(name: String): Class<*> {
            val bytes = classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    private fun assertRuns(classes: Map<String, ByteArray>) =
        assertEquals("ok", load(classes).loadClass("p.Host").getMethod("run").invoke(null))

    private fun node(bytes: ByteArray) = ClassNode().also { ClassReader(bytes).accept(it, 0) }

    /** The shape of the real use case: a private nested exception that native code throws. */
    private val nested = "p/Host.java" to """
        package p;
        import cc.cszt0.mangle.annotation.Mangle;

        public class Host {
            private int secret = 41;
            private int reveal() { return secret + 1; }

            @Mangle
            private static class Failure extends Exception {
                private final int code;
                private Failure() { this(7); }
                private Failure(int code) { this.code = code; }
                private int code() { return code; }
                int read(Host host) { return host.reveal() + host.secret; }
            }

            private static class Sibling {
                private static int peek(Failure failure) { return failure.code; }
            }

            private static void fail() throws Failure { throw new Failure(); }

            public static String run() {
                try {
                    fail();
                    return "not thrown";
                } catch (Failure e) {
                    if (e.code() != 7) return "code";
                    if (Sibling.peek(e) != 7) return "sibling";
                    if (e.read(new Host()) != 83) return "read";
                    return "ok";
                }
            }
        }
    """.trimIndent()

    private val failure = "p.Host\$Failure"

    @Test
    fun `nested class keeps working under nestmates`() = assertRuns(rename(compile(nested), failure).classes)

    @Test
    fun `nested class keeps working with synthetic accessors`() =
        assertRuns(rename(compile(nested, release = "8"), failure).classes)

    @Test
    fun `class takes the mangled name in the default package and leaves no trace of the old one`() {
        val classes = rename(compile(nested), failure).classes
        val mangled = Naming.mangleClass(key, failure)
        assertFalse(classes.containsKey("p/Host\$Failure"))
        val loaded = load(classes).loadClass(mangled)
        assertEquals(mangled, loaded.name)
        assertEquals(Exception::class.java.name, loaded.superclass.name)
        assertTrue(java.lang.reflect.Modifier.isPublic(loaded.modifiers))
        classes.forEach { (name, bytes) ->
            assertFalse(name, String(bytes, Charsets.ISO_8859_1).contains("Failure"))
        }
        assertTrue(node(classes.getValue(mangled)).invisibleAnnotations.isNullOrEmpty())
    }

    @Test
    fun `native code can instantiate it through the default constructor`() {
        val loaded = load(rename(compile(nested), failure).classes).loadClass(Naming.mangleClass(key, failure))
        assertEquals(1, loaded.declaredConstructors.count { it.parameterCount == 0 })
    }

    @Test
    fun `methods of the renamed class stay in it`() {
        val classes = rename(compile(nested), failure).classes
        val methods = node(classes.getValue(Naming.mangleClass(key, failure))).methods.map { it.name }
        assertTrue(methods.toString(), "code" in methods && "read" in methods)
    }

    @Test
    fun `renaming the outer class dissolves the nest for the classes left behind`() {
        val classes = compile(
            "p/Host.java" to """
                package p;
                public class Host {
                    public static String run() { return Outer.value(); }
                }
            """.trimIndent(),
            "p/Outer.java" to """
                package p;
                import cc.cszt0.mangle.annotation.Mangle;
                @Mangle
                class Outer {
                    private static int base = 1;
                    static class A { private static int a() { return B.b() + base; } }
                    static class B { private static int b() { return 2; } }
                    static String value() { return A.a() == 3 ? "ok" : "bad"; }
                }
            """.trimIndent(),
        )
        assertRuns(rename(classes, "p.Outer").classes)
    }

    @Test
    fun `package-private members and types work across the new boundary in both directions`() {
        val classes = compile(
            "p/Host.java" to """
                package p;
                import cc.cszt0.mangle.annotation.Mangle;
                class Hidden { int value = 3; static int twice(int v) { return v * 2; } }
                class Base {
                    int inherited = 4;
                    Base() {}
                    int fromBase() { return 5; }
                }
                @Mangle
                class Renamed extends Base {
                    int own = 1;
                    Renamed() {}
                    int sum(Hidden hidden) { return Hidden.twice(hidden.value) + inherited + fromBase(); }
                }
                class Child extends Renamed {}
                public class Host {
                    public static String run() {
                        Renamed renamed = new Renamed();
                        if (renamed.sum(new Hidden()) != 15) return "sum";
                        Child child = new Child();
                        if (child.own != 1 || child.sum(new Hidden()) != 15) return "child";
                        java.util.function.Supplier<Renamed> ref = Renamed::new;
                        return ref.get().own == 1 ? "ok" : "ref";
                    }
                }
            """.trimIndent(),
        )
        assertRuns(rename(classes, "p.Renamed").classes)
    }

    @Test
    fun `package-private overrides still dispatch to the override`() {
        val classes = compile(
            "p/Host.java" to """
                package p;
                import cc.cszt0.mangle.annotation.Mangle;
                class Base { String name() { return "base"; } }
                @Mangle
                class Renamed extends Base { @Override String name() { return "renamed"; } }
                class Leaf extends Renamed { @Override String name() { return "leaf"; } }
                public class Host {
                    public static String run() {
                        Base renamed = new Renamed();
                        Base leaf = new Leaf();
                        if (!renamed.name().equals("renamed")) return "renamed:" + renamed.name();
                        return leaf.name().equals("leaf") ? "ok" : "leaf:" + leaf.name();
                    }
                }
            """.trimIndent(),
        )
        assertRuns(rename(classes, "p.Renamed").classes)
    }

    @Test
    fun `mapping has one empty section per renamed class`() {
        assertEquals(
            "$failure -> ${Naming.mangleClass(key, failure)}:\n",
            rename(compile(nested), failure).mapping,
        )
    }

    @Test
    fun `unrelated classes are left byte for byte`() {
        val classes = compile(nested, "q/Other.java" to "package q; public class Other { int x; }")
        val result = rename(classes, failure).classes
        assertTrue(classes.getValue("q/Other").contentEquals(result.getValue("q/Other")))
    }

    @Test
    fun `nothing to rename returns the input and missing classes warn`() {
        val classes = compile(nested)
        val result = rename(classes, "p.Gone")
        assertEquals(classes, result.classes)
        assertEquals("", result.mapping)
        assertEquals(1, warnings.size)
    }
}
