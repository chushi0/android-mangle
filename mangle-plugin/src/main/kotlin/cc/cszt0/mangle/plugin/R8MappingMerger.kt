package cc.cszt0.mangle.plugin

import java.security.MessageDigest

/**
 * Appends our sections to an R8 mapping.
 *
 * Retrace lets the last section of an obfuscated class win, and R8 already wrote one for each
 * kept class, under the mangled names. So our section carries R8's line ranges over, with the
 * original method names, or stack traces would lose their line numbers. A renamed class has no
 * members in our section because its members keep their names, so R8's lines carry over as they are.
 */
internal object R8MappingMerger {
    private val SECTION = Regex("""^(\S+) -> (\S+):$""")
    private val MEMBER = Regex("""^(\s+(?:\d+:\d+:)?\S+ )(\w+)(\(.*)$""")
    private const val HASH_PREFIX = "# pg_map_hash: SHA-256 "

    fun merge(r8Mapping: String, fragment: String): String {
        val r8Sections = sections(r8Mapping)
        val appended = StringBuilder()
        sections(fragment).forEach { (header, members) ->
            val mangledClass = SECTION.matchEntire(header)!!.groupValues[2]
            val originalNames = members.associate { line ->
                line.substringAfterLast(" -> ") to MEMBER.matchEntire(line)!!.groupValues[2]
            }
            appended.append(header).append('\n')
            members.forEach { appended.append(it).append('\n') }
            val r8Members = r8Sections["$mangledClass -> $mangledClass:"].orEmpty()
            if (members.isEmpty()) r8Members.forEach { appended.append(it).append('\n') }
            r8Members.forEach { line ->
                val member = MEMBER.matchEntire(line)
                val original = member?.let { originalNames[it.groupValues[2]] }
                if (original != null) {
                    appended.append(member.groupValues[1]).append(original).append(member.groupValues[3]).append('\n')
                }
            }
        }
        val base = if (r8Mapping.isEmpty() || r8Mapping.endsWith('\n')) r8Mapping else "$r8Mapping\n"
        return updateHash(base + appended)
    }

    private fun sections(text: String): Map<String, List<String>> {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        text.lineSequence().forEach { line ->
            when {
                SECTION.matches(line) -> current = sections.getOrPut(line) { mutableListOf() }
                line.startsWith(" ") && !line.trimStart().startsWith("#") -> current?.add(line)
            }
        }
        return sections
    }

    /** R8 hashes everything after the hash line; tools verify it, so it has to cover what we added. */
    private fun updateHash(mapping: String): String {
        val start = mapping.indexOf(HASH_PREFIX)
        if (start < 0) return mapping
        val end = mapping.indexOf('\n', start)
        val rest = mapping.substring(end + 1)
        val hash = MessageDigest.getInstance("SHA-256").digest(rest.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return mapping.substring(0, start) + HASH_PREFIX + hash + "\n" + rest
    }
}
