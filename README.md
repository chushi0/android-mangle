# Mangle

An Android Gradle plugin that gives selected Java code stable, keyed, unreadable names.

R8 renames code freely, which is a problem for anything that looks code up by name, such as JNI and
reflection: those names have to be kept, and kept names are readable. Mangle moves that code into
generated classes named by HMAC-SHA256, and hands the names to Java and C/C++ as constants. The
strings in your dex and `.so` are then hashes, and the same key always produces the same hashes.

Mangle does not encrypt string literals or hide logic. It only replaces names.

## How it works

Mangle does one of two things, depending on where `@Mangle` is. On a class, it renames that class
to a hash in the default package and rewrites every reference, which is what you need for a class
native code has to find, such as an exception it throws. On a method, it moves the compiled body
into a new class and leaves a wrapper:

```java
// You write
package com.example;
class Greeter {
    private String name;
    @Mangle String greet() { return "Hi " + name; }
}

// After the build, roughly
package com.example;
public class Greeter {                       // R8 is still free to rename or inline this
    private String name;
    String greet() { return m5e1c...(this); }
    public static String access$mangle$0(Greeter g) { return g.name; }
}

@Keep public final class m2f9a... {          // default package
    @Keep public static String m5e1c...(Object self) {
        return "Hi " + Greeter.access$mangle$0((Greeter) self);
    }
}
```

| Stage | Task | Output |
| --- | --- | --- |
| Before javac | `mangleScan<Variant>` parses `.java` sources | `MangleIdentifiers.java`, R8 keep rules |
| Before CMake | `mangleGenerateHeader` | `java_mangle.h` |
| After javac, before R8 | `mangleTransform<Variant>` renames classes and moves methods with ASM | Transformed classes |
| After R8 | Last action of `minify<Variant>WithR8` | Our mapping appended to `mapping.txt` |
| No R8 | `mangleMapping<Variant>` | `mapping.txt` holding only our mapping |

The scan runs on source because javac inlines `public static final String` constants into the code
that uses them, so the values have to exist before compilation. Names depend only on the class and
method name, which is why parsing is enough and no type resolution is needed.

## Compatibility

Tested end to end, including a release build running on a Pixel 9 (Android 17, API 37), with both
Groovy and Kotlin DSL fixtures:

| AGP | Gradle | JDK |
| --- | --- | --- |
| 9.5.0, 9.4.1, 9.2.1 | 9.6.0 | 25 |
| 9.0.1 | 9.1.0 | 21 |
| 8.13.2, 8.11.1, 8.9.1, 8.7.3, 8.5.0 | 8.13 | 21 |
| 8.5.0 | 8.7 | 21 |

AGP 8.5 is the minimum we test. AGP 8.1 cannot work: it lacks the `SourceDirectories.Flat.static`
API the plugin calls. Versions between the two are untested.

Your source language must be Java for code to be moved; see [Known issues](#known-issues).
`build.gradle` and `build.gradle.kts` are both supported, as are pure Java projects.

## Usage

### 1. Apply the plugin

Mangle is published on [JitPack](https://jitpack.io/#chushi0/android-mangle). JitPack moves every
artifact to the group `com.github.chushi0.android-mangle`, including the plugin marker, so Gradle
cannot find the plugin by its id alone and needs a `resolutionStrategy`. JitPack must also be in
the dependency repositories, because the plugin adds
`com.github.chushi0.android-mangle:mangle-annotation` to `compileOnly` for you.

```groovy
// settings.gradle
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri('https://jitpack.io') }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == 'cc.cszt0.mangle') {
                useModule("com.github.chushi0.android-mangle:mangle-plugin:${target.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri('https://jitpack.io') }
    }
}
```

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "cc.cszt0.mangle") {
                useModule("com.github.chushi0.android-mangle:mangle-plugin:${target.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

Then apply it. Only `com.android.application` modules are supported.

```groovy
// build.gradle
plugins {
    id 'com.android.application'
    id 'cc.cszt0.mangle' version '1.0.1'
}

mangle {
    key = 'your-secret-key'                  // required
    headerOutputDir = file('src/main/cpp')   // optional
}
```

```kotlin
// build.gradle.kts
plugins {
    id("com.android.application")
    id("cc.cszt0.mangle") version "1.0.1"
}

mangle {
    key = "your-secret-key"
    headerOutputDir = layout.projectDirectory.dir("src/main/cpp")
}
```

`headerOutputDir` defaults to `build/generated/mangle/include`. The plugin adds it to the native
compiler's include path, so `#include "java_mangle.h"` works without changing `CMakeLists.txt`.

Keep the key out of version control if the names matter to you, for example with
`key = providers.gradleProperty('mangleKey').get()`.

### 2. Select code

```java
import cc.cszt0.mangle.annotation.Mangle;

class Bridge {
    @Mangle static String read() { ... }   // this method
}

class Jni {
    native String call();                  // always moved, no annotation needed
}

@Mangle
class Failure extends Exception { ... }    // the class itself is renamed; its methods are not moved
```

Constructors, static initializers, fields, abstract methods and compiler-generated methods stay
where they are when methods move.

`@Mangle` on a class renames that class only, not its nested classes. Its constructors are kept, so
native code can create or throw it. Its other members get no constant and R8 renames them as usual,
so native code cannot look them up by name. Leaving the package would cut off package-private and
private access, so classes and members used across that boundary become public.

A class annotated with `@Mangle` cannot also have `native` or `@Mangle` methods, because methods move
to a class with the very name the renamed class takes. The build fails and names the class; move
those methods elsewhere, or annotate the methods instead of the class.

### 3. Use the names

Every renamed class, moved class and method gets a constant, with the same name in both languages:
the full original name, upper-cased, with every non-alphanumeric character replaced by `_`. Overloads
share one constant.

```java
// cc.cszt0.mangle.MangleIdentifiers (generated; not kept, R8 removes it)
public static final String COM_EXAMPLE_SOURCE = "m51977d5129d0d0da";
public static final String COM_EXAMPLE_SOURCE_LOAD = "m48abfb896a91253e";
```

```c
// java_mangle.h
#define COM_EXAMPLE_SOURCE "m51977d5129d0d0da"
#define COM_EXAMPLE_SOURCE_LOAD "m48abfb896a91253e"
```

The class constant works unchanged for both `Class.forName` and `FindClass`, because new and renamed
classes are in the default package. Always use the new class, not the original one:

```java
Class<?> source = Class.forName(MangleIdentifiers.COM_EXAMPLE_SOURCE);
String value = (String) source.getMethod(MangleIdentifiers.COM_EXAMPLE_SOURCE_LOAD).invoke(null);
```

### 4. Adapt JNI code

**Native methods move, so bind them with `RegisterNatives`** on the new class. A
`Java_com_example_...` symbol no longer matches.

New methods differ from the originals in two ways, and JNI signatures must follow them:

- **All new methods are static.** An instance method takes its receiver as an extra first parameter.
- **Types outside the Android boot classpath become `Object`**, arrays included (`Foo[]` becomes
  `Object[]`). R8 renames those types, so a signature naming them would break. `java.*` and
  `android.*` types stay as they are; `androidx.*` types are erased.

```java
package com.example;
public class MainActivity extends Activity {
    private native String greeting(Item item);
}
```

```cpp
#include <jni.h>
#include "java_mangle.h"

// jclass: the method is static now. self: the MainActivity, moved here from the jclass position.
static jstring greeting(JNIEnv *env, jclass, jobject self, jobject item) { ... }

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    const JNINativeMethod methods[] = {
        {COM_EXAMPLE_MAINACTIVITY_GREETING,
         "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/String;",
         reinterpret_cast<void *>(greeting)},
    };
    env->RegisterNatives(env->FindClass(COM_EXAMPLE_MAINACTIVITY), methods, 1);
    return JNI_VERSION_1_6;
}
```

Reflection follows the same rules: `getMethod(name, Object.class, ...)`, then
`invoke(null, receiver, ...)` for what was an instance method.

**A renamed class is used like any other class,** only under its constant. Java keeps using it by its
original name, and a type that is renamed is still erased to `Object` in new method signatures.

```java
package com.example;
public class Checker {
    @Mangle private static class Failure extends Exception {}
    private static native int next() throws Failure;
}
```

```cpp
env->ThrowNew(env->FindClass(COM_EXAMPLE_CHECKER_FAILURE), nullptr);
```

## Mapping

`build/outputs/mapping/<variant>/mapping.txt` gains one section per renamed or moved class, in R8's format:

```
com.example.Source -> m51977d5129d0d0da:
    java.lang.String load() -> m48abfb896a91253e
    7:26:java.lang.String load():11:11 -> m48abfb896a91253e
```

R8's own content is left in place. Two things are added so that tools keep working: R8's line
ranges for the new classes are repeated under the original names (for a renamed class, all of R8's
member lines are repeated, since members keep their names), and `pg_map_hash` is recomputed
to cover the appended text. `retrace` restores original names and line numbers, and
`retrace --verify-mapping-file-hash` passes. `pg_map_id` is unchanged.

## Known issues

- **Java only.** Kotlin sources are not scanned. Kotlin classes, including `external fun`, are left
  unchanged and the build prints a warning. Kotlin code can still read `MangleIdentifiers`.
- **Application modules only.** Applying the plugin to `com.android.library` fails the build, since
  `MangleIdentifiers` has a fixed package and copies from several modules would clash. Code from
  dependencies is not processed either.
- **Local and anonymous classes are not processed.** `@Mangle` methods in them are left unchanged,
  with a warning.
- **Renamed classes must not be referenced by name outside bytecode.** Only class files are
  rewritten, so an `Activity` in `AndroidManifest.xml`, a `View` in a layout or a `Class.forName`
  string still using the original name breaks at runtime.
- **Widening a private method to public can make it overridable.** If a subclass declares a method
  with the same signature as a private method that had to be widened, calls may reach the subclass.
- **Lambda bodies stay in the original class.** They compile to synthetic methods that are not
  moved, so they are obfuscated by R8 as usual but do not end up in the new class.
- **The constant name scheme can collide.** Names are upper-cased and `$`, `.` map to `_`, so
  `getUrl`/`getURL`, or a nested class `Outer.Builder` next to a method `Outer.builder()`, clash if
  both are selected. The build fails and names the two; rename one.
- **Overloads can collide after erasure.** `x(Foo)` and `x(Bar)` both become `x(Object)` in the new
  class. The build fails and names the method; rename one.
- **Calling a new method directly skips the wrapper.** A `synchronized` method holds its lock in the
  wrapper, so JNI or reflection calling the new method runs without that lock.
- **Caller-sensitive code sees the new class.** `MethodHandles.lookup()` and stack inspection in a
  moved body observe the new class rather than the original one.
- **The header is the union of all variants.** Native tasks are shared between variants, so
  `java_mangle.h` contains names from every variant, including ones absent from the variant being
  built.
- **CMake only is tested.** The header is also generated and wired up for `ndk-build`, but no test
  covers that path.
- **The IDE flags `java_mangle.h` and `MangleIdentifiers` as missing until the first build.**
- **Debugger breakpoints in moved methods do not bind,** since the code no longer lives in the class
  the IDE expects. Moved code has no source file or local variable information.

## Development

```
mangle-annotation/     @Mangle, pure Java
mangle-plugin/         the Gradle plugin and its unit tests
integration-test/      TestKit suite
  projects/
    hello-java-groovy/ pure Java, build.gradle
    hello-kotlin-dsl/  Java + Kotlin, build.gradle.kts, custom header path
```

There is no `app` module, as is usual for a Gradle plugin repository: nothing consumes the plugin
from inside the build. The fixtures play that role, and they resolve the plugin from a local Maven
repository the way a real project would.

```bash
./gradlew :mangle-plugin:test       # unit tests; moved classes run under the JVM verifier
./gradlew :integration-test:test    # builds fixtures, checks APK and mapping
```

The fixture app runs a native → JNI → reflection chain that returns `Hello world`, and the test
asserts that no original name survives in the release dex. With a device attached, the test also
installs and runs it, then uninstalls it; otherwise that step is skipped.

To test other versions, pass `agp:gradle[:javaHome]` entries:

```bash
./gradlew :integration-test:test -Pmangle.matrix=9.5.0:9.6.0,8.5.0:8.13:/path/to/jdk21
```
