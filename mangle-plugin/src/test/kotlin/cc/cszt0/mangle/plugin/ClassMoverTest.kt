package cc.cszt0.mangle.plugin

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.lang.reflect.Modifier
import javax.tools.ToolProvider

/** Moved classes run on the JVM here, whose verifier is stricter about access and frames than ART. */
class ClassMoverTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val key = "test-key"
    private val warnings = mutableListOf<String>()

    private val platform = PlatformClasses { name ->
        ClassLoader.getSystemResourceAsStream("$name.class")
            ?.takeIf { name.startsWith("java/") }
            ?.use { input ->
                val node = ClassNode()
                ClassReader(input).accept(node, ClassReader.SKIP_CODE)
                ClassInfo.of(node)
            }
    }

    private fun compile(vararg sources: Pair<String, String>, release: String = "11"): Map<String, ByteArray> {
        val root = temp.newFolder()
        val output = temp.newFolder()
        val files = sources.map { (path, text) ->
            File(root, path).apply {
                parentFile.mkdirs()
                writeText(text)
            }
        }
        val compiler = ToolProvider.getSystemJavaCompiler()
        val arguments = listOf(
            "--release", release, "-proc:none", "-nowarn",
            "-cp", System.getProperty("java.class.path"), "-d", output.path,
        ) + files.map { it.path }
        assertEquals(0, compiler.run(null, null, null, *arguments.toTypedArray()))
        return output.walkTopDown().filter { it.extension == "class" }
            .associate { it.relativeTo(output).path.removeSuffix(".class") to it.readBytes() }
    }

    private fun move(classes: Map<String, ByteArray>, selected: Map<String, Set<String>>) =
        ClassMover(classes, MangleManifest(selected), key, platform) { warnings += it }.run()

    private fun load(result: MoveResult) = object : ClassLoader(null) {
        override fun findClass(name: String): Class<*> {
            val bytes = result.classes[name.replace('.', '/')] ?: throw ClassNotFoundException(name)
            return defineClass(name, bytes, 0, bytes.size)
        }
    }

    private val sample = "p/Sample.java" to """
        package p;
        import cc.cszt0.mangle.annotation.Mangle;
        import java.util.function.*;

        class Parent {
            protected int inherited = 7;
            protected String hook() { return "parent"; }
        }
        interface Named { default String name() { return "named"; } }

        public class Sample extends Parent implements Named, Cloneable {
            private int count = 40;
            private static String secret = "s";
            private int bump(int by) { count += by; return count; }
            private static class Hidden {
                final String value;
                private Hidden(String value) { this.value = value; }
            }

            @Mangle private int privates() { secret += "!"; return bump(2); }
            @Mangle @Override protected String hook() { return super.hook() + inherited; }
            @Mangle @Override public String name() { return Named.super.name() + "!"; }
            @Mangle String lambdas(String prefix) {
                Supplier<Integer> capturing = () -> count + prefix.length();
                Function<String, Hidden> ctor = Hidden::new;
                return ctor.apply("v").value + capturing.get();
            }
            @Mangle Sample[] arrays(Sample one, long wide, Sample[] many) {
                return wide > 0 ? many : new Sample[] {one};
            }
            @Mangle synchronized boolean locked() { return Thread.holdsLock(this); }
            @Mangle int loop(int n) {
                int total = 0;
                for (int i = 0; i < n; i++) {
                    try {
                        if (i % 2 == 0) throw new IllegalStateException();
                        total += i;
                    } catch (IllegalStateException e) {
                        total -= 1;
                    }
                }
                return total;
            }
            @Mangle Object cloned() throws CloneNotSupportedException { return clone(); }
            @Mangle static String over(int v) { return "int" + v; }
            @Mangle static String over(String v) { return "str" + v; }
            void over(long notAnnotated) {}
            public native String nat(Sample other);

            public static String run() throws Exception {
                Sample s = new Sample();
                if (s.privates() != 42 || !secret.equals("s!")) return "privates";
                if (!s.hook().equals("parent7")) return "hook:" + s.hook();
                if (!s.name().equals("named!")) return "name";
                if (!s.lambdas("xy").equals("v44")) return "lambdas:" + s.lambdas("xy");
                Sample[] many = {s};
                if (s.arrays(s, 1, many) != many || s.arrays(s, 0, many)[0] != s) return "arrays";
                if (!s.locked()) return "locked";
                if (s.loop(4) != 2) return "loop:" + s.loop(4);
                if (!(s.cloned() instanceof Sample)) return "cloned";
                if (!over(1).equals("int1") || !over("a").equals("stra")) return "over";
                return "ok";
            }
        }
    """.trimIndent()

    private val selected = mapOf(
        "p.Sample" to setOf(
            "privates", "hook", "name", "lambdas", "arrays", "locked", "loop", "cloned", "over", "nat",
        ),
    )

    private fun runSample(release: String) {
        val loader = load(move(compile(sample, release = release), selected))
        assertEquals("ok", loader.loadClass("p.Sample").getMethod("run").invoke(null))
    }

    @Test
    fun `moved code behaves like the original`() = runSample("11")

    @Test
    fun `works with synthetic accessors of pre-nestmate class files`() = runSample("8")

    @Test
    fun `new class is public static, kept and has erased signatures`() {
        val result = move(compile(sample), selected)
        val mangledClass = Naming.mangleClass(key, "p.Sample")
        val node = ClassNode().also { ClassReader(result.classes.getValue(mangledClass)).accept(it, 0) }
        assertEquals(listOf("Landroidx/annotation/Keep;"), node.invisibleAnnotations.map { it.desc })
        assertEquals(null, node.sourceFile)
        assertEquals(10 + 1, node.methods.size)
        node.methods.forEach { method ->
            assertEquals(listOf("Landroidx/annotation/Keep;"), method.invisibleAnnotations.map { it.desc })
            assertTrue(method.access and Opcodes.ACC_PUBLIC != 0 && method.access and Opcodes.ACC_STATIC != 0)
            assertFalse(method.desc, method.desc.contains("Lp/"))
        }
        val native = node.methods.single { it.name == Naming.mangleMethod(key, "p.Sample", "nat") }
        assertTrue(native.access and Opcodes.ACC_NATIVE != 0)
        assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;", native.desc)
    }

    @Test
    fun `wrappers keep the original contract`() {
        val loader = load(move(compile(sample), selected))
        val sampleClass = loader.loadClass("p.Sample")
        assertTrue(Modifier.isSynchronized(sampleClass.getDeclaredMethod("locked").modifiers))
        assertFalse(Modifier.isNative(sampleClass.getDeclaredMethod("nat", sampleClass).modifiers))
        assertTrue(Modifier.isPrivate(sampleClass.getDeclaredMethod("privates").modifiers))
    }

    @Test
    fun `only the annotated overload moves`() {
        val result = move(compile(sample), selected)
        val mangledClass = Naming.mangleClass(key, "p.Sample")
        val node = ClassNode().also { ClassReader(result.classes.getValue(mangledClass)).accept(it, 0) }
        val overloads = node.methods.filter { it.name == Naming.mangleMethod(key, "p.Sample", "over") }
        assertEquals(setOf("(I)Ljava/lang/String;", "(Ljava/lang/String;)Ljava/lang/String;"), overloads.map { it.desc }.toSet())
    }

    @Test
    fun `mapping lists original signatures`() {
        val mapping = move(compile(sample), selected).mapping
        val mangledClass = Naming.mangleClass(key, "p.Sample")
        assertTrue(mapping, mapping.startsWith("p.Sample -> $mangledClass:\n"))
        assertTrue(mapping, mapping.contains("    java.lang.String over(int) -> "))
        assertTrue(mapping, mapping.contains("    p.Sample[] arrays(p.Sample,long,p.Sample[]) -> "))
    }

    @Test
    fun `overloads that collide after erasure fail the build`() {
        val classes = compile(
            "Clash.java" to """
                import cc.cszt0.mangle.annotation.Mangle;
                class A {}
                class B {}
                class Clash { @Mangle void x(A a) {} @Mangle void x(B b) {} }
            """.trimIndent(),
        )
        val message = runCatching { move(classes, mapOf("Clash" to setOf("x"))) }.exceptionOrNull()
        assertTrue(message.toString(), message is GradleException && "Clash.x" in message.message!!)
    }

    @Test
    fun `classes missing from source are left alone with a warning`() {
        val classes = compile(
            "Other.java" to "class Other { native void x(); }",
        )
        val result = move(classes, emptyMap())
        assertTrue(classes.getValue("Other").contentEquals(result.classes.getValue("Other")))
        assertEquals(1, warnings.size)
    }
}
