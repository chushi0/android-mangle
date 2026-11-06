package cc.cszt0.mangle.integration

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

private val MANGLED = Regex("m[0-9a-f]{16}")

/** Builds each fixture with each AGP of `mangle.matrix`, inspects the APK and runs it on a device. */
@RunWith(Parameterized::class)
class HelloWorldTest(private val fixture: Fixture, agpGradleJdk: String) {
    class Fixture(
        val name: String,
        val applicationId: String,
        val header: String,
        val expectedLog: String,
        /** Original names that must not survive in the release dex. */
        val forbidden: List<String>,
    ) {
        override fun toString() = name
    }

    companion object {
        private val fixtures = listOf(
            Fixture(
                name = "hello-java-groovy",
                applicationId = "com.example.hello",
                header = "app/build/generated/mangle/include/java_mangle.h",
                expectedLog = "greeting=Hello world features=ok",
                forbidden = listOf(
                    "GreetingBridge", "GreetingSource", "readGreeting", "loadGreeting", "nativeGreeting",
                    "MangleIdentifiers", "Features", "selfTest", "access\$mangle",
                ),
            ),
            Fixture(
                name = "hello-kotlin-dsl",
                applicationId = "com.example.hellokts",
                header = "app/src/main/cpp/generated/java_mangle.h",
                expectedLog = "greeting=Hello world kotlinNative=kotlin",
                forbidden = listOf("NativeGreeting", "fetch", "MangleIdentifiers"),
            ),
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun parameters(): List<Array<Any>> =
            System.getProperty("mangle.matrix").split(',').flatMap { entry ->
                fixtures.map { arrayOf<Any>(it, entry) }
            }

        private val sdkDir: File by lazy {
            val path = System.getenv("ANDROID_HOME") ?: "${System.getProperty("user.home")}/Library/Android/sdk"
            File(path).also { assertTrue("Set ANDROID_HOME", it.isDirectory) }
        }

        private fun run(vararg command: String): String {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            return process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }
    }

    private val agpVersion = agpGradleJdk.split(':')[0]
    private val gradleVersion = agpGradleJdk.split(':')[1]
    private val javaHome = agpGradleJdk.split(':').getOrNull(2)
    private val projectDir = File(System.getProperty("mangle.workDir"), "${fixture.name}-$agpVersion")
    private val app = File(projectDir, "app/build")

    private fun prepare() {
        projectDir.deleteRecursively()
        File(System.getProperty("mangle.projects"), fixture.name).copyRecursively(projectDir)
        File(projectDir, "local.properties").writeText("sdk.dir=${sdkDir.path}\n")
        File(projectDir, "gradle.properties").appendText(
            """

            mangleRepo=${System.getProperty("mangle.repo")}
            mangleVersion=${System.getProperty("mangle.version")}
            agpVersion=$agpVersion
            """.trimIndent() + "\n" + (javaHome?.let { "org.gradle.java.home=$it\n" } ?: ""),
        )
    }

    private fun gradle(vararg arguments: String): BuildResult {
        val userHome = File(System.getProperty("mangle.gradleUserHome"))
        val runner = GradleRunner.create()
            .withProjectDir(projectDir)
            .withTestKitDir(userHome)
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
        val installed = File(userHome, "wrapper/dists/gradle-$gradleVersion-bin").walkTopDown().maxDepth(2)
            .firstOrNull { it.name == "gradle-$gradleVersion" }
        return (if (installed != null) runner.withGradleInstallation(installed) else runner.withGradleVersion(gradleVersion)).build()
    }

    private fun dexdump(): File =
        File(sdkDir, "build-tools").listFiles()!!.maxBy { it.name }.resolve("dexdump")

    private fun releaseDex(): Map<String, ByteArray> {
        val apk = File(app, "outputs/apk/release/app-release.apk")
        return ZipFile(apk).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".dex") }
                .associate { it.name to zip.getInputStream(it).readBytes() }
        }
    }

    private fun identifiers(): Map<String, String> =
        File(projectDir, fixture.header).readLines()
            .mapNotNull { Regex("""#define (\w+) "(m[0-9a-f]+)"""").find(it)?.destructured }
            .associate { (constant, mangled) -> constant to mangled }

    @Test
    fun `release is mangled and runs, debug gets its own mapping`() {
        prepare()
        val first = gradle("assembleRelease", "assembleDebug")

        assertHeaderPrecedesNativeBuild(first)
        assertDexIsMangled()
        assertMappings()
        assertSecondBuildChangesNothing()
        assertRunsOnDevice()
    }

    private fun assertHeaderPrecedesNativeBuild(result: BuildResult) {
        val tasks = result.tasks.map { it.path }
        val header = tasks.indexOf(":app:mangleGenerateHeader")
        assertTrue("header task ran", header >= 0)
        val native = tasks.indexOfFirst { it.contains("CMake") }
        assertTrue("$tasks", native > header)
        assertTrue(identifiers().isNotEmpty())
    }

    private fun assertDexIsMangled() {
        val dexFiles = releaseDex()
        val dumpDir = File(projectDir, "dex").apply { mkdirs() }
        val classes = dexFiles.flatMap { (name, bytes) ->
            val file = File(dumpDir, name).apply { writeBytes(bytes) }
            val text = String(bytes, Charsets.ISO_8859_1)
            fixture.forbidden.forEach { assertFalse("$it leaked into $name", text.contains(it)) }
            Regex("Class descriptor\\s*: '(.*)'").findAll(run(dexdump().path, file.path))
                .map { it.groupValues[1] }.toList()
        }
        val text = dexFiles.values.joinToString("") { String(it, Charsets.ISO_8859_1) }
        identifiers().values.forEach { assertTrue("$it is not in the dex", text.contains(it)) }
        val app = classes.filterNot { it.startsWith("Landroidx/") || it.startsWith("Lkotlin") || it.startsWith("L_COROUTINE") }
        val readable = app.filter { descriptor ->
            val simple = descriptor.removePrefix("L").removeSuffix(";")
            !MANGLED.matches(simple) && simple.length > 3 && !simple.endsWith("/MainActivity")
        }
        assertEquals("only the manifest entry point keeps a readable name", emptyList<String>(), readable)
    }

    private fun assertMappings() {
        val release = File(app, "outputs/mapping/release/mapping.txt").readText()
        val debug = File(app, "outputs/mapping/debug/mapping.txt").readText()
        assertTrue("R8 content is kept", release.startsWith("# compiler: R8"))
        assertFalse("debug has no R8 content", debug.contains("pg_map_hash"))
        debug.lines().filter { it.isNotEmpty() }.forEach { line ->
            assertTrue(line, release.contains(line + "\n"))
        }

        val hashLine = release.lines().first { it.startsWith("# pg_map_hash") }
        val rest = release.substringAfter(hashLine + "\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(rest.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("# pg_map_hash: SHA-256 $hash", hashLine)
    }

    private fun assertSecondBuildChangesNothing() {
        val mapping = File(app, "outputs/mapping/release/mapping.txt")
        val before = mapping.readText()
        val result = gradle("assembleRelease", "assembleDebug")
        assertEquals(TaskOutcome.UP_TO_DATE, result.task(":app:minifyReleaseWithR8")?.outcome)
        assertTrue(result.output.contains("Reusing configuration cache"))
        assertEquals(before, mapping.readText())
    }

    private fun assertRunsOnDevice() {
        val adb = File(sdkDir, "platform-tools/adb").path
        val online = run(adb, "devices").lines().drop(1).any { it.endsWith("\tdevice") }
        assumeTrue("no device attached; skipping the on-device check", online)
        val apk = File(app, "outputs/apk/release/app-release.apk").path
        assertTrue(run(adb, "install", "-r", apk), run(adb, "install", "-r", apk).contains("Success"))
        try {
            run(adb, "logcat", "-c")
            run(adb, "shell", "am", "start", "-W", "-n", "${fixture.applicationId}/.MainActivity")
            val deadline = System.currentTimeMillis() + 10_000
            var log = ""
            while (System.currentTimeMillis() < deadline && !log.contains("greeting=")) {
                Thread.sleep(300)
                log = run(adb, "logcat", "-d", "-s", "MangleTest:I", "AndroidRuntime:E")
            }
            assertTrue(log, log.contains(fixture.expectedLog))
        } finally {
            run(adb, "uninstall", fixture.applicationId)
        }
    }
}
