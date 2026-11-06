package cc.cszt0.mangle.plugin

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

abstract class MangleExtension {
    /** HMAC-SHA256 key. Required: the same key always yields the same names. */
    abstract val key: Property<String>

    /** Directory that receives `java_mangle.h`. Defaults to `build/generated/mangle/include`. */
    abstract val headerOutputDir: DirectoryProperty
}
