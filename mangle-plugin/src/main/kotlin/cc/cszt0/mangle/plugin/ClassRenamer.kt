package cc.cszt0.mangle.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode

internal class RenameResult(val classes: Map<String, ByteArray>, val mapping: String)

/**
 * Gives classes annotated with `@Mangle` their mangled name, in the default package like the classes
 * [ClassMover] generates, so that one string serves `FindClass` and `Class.forName`.
 *
 * Leaving the package changes what the JVM allows, and a class file does not say so by itself:
 * - Package-private access across the new boundary fails, so whatever crosses it is widened.
 * - Nests require a single package, so a nest holding a renamed class is dissolved, and private
 *   access between its former members is widened too.
 * - A package-private method silently stops overriding across packages, which would dispatch to the
 *   wrong method without any error, so both sides of such an override are widened.
 *
 * Only what is actually used across the boundary is widened, to leave R8 as much freedom as possible.
 */
internal class ClassRenamer(
    private val classes: Map<String, ByteArray>,
    private val manifest: MangleManifest,
    private val key: String,
    private val warn: (String) -> Unit,
) {
    private val names = LinkedHashMap<String, String>()
    private val nodes = HashMap<String, ClassNode>()
    private val dirty = HashSet<String>()
    private val dissolved = HashSet<String>()
    private val mangled get() = names.values

    fun run(): RenameResult {
        val mapping = StringBuilder()
        manifest.renamed.forEach { binaryName ->
            val internalName = binaryName.replace('.', '/')
            if (internalName in classes) {
                val target = Naming.mangleClass(key, binaryName)
                names[internalName] = target
                mapping.append("$binaryName -> $target:\n")
            } else {
                warn("$binaryName was found in source but not in the compiled classes; skipped.")
            }
        }
        if (names.isEmpty()) return RenameResult(classes, "")

        val remapper = SimpleRemapper(ASM9, names)
        classes.forEach { (name, bytes) ->
            val node = ClassNode()
            ClassReader(bytes).accept(ClassRemapper(node, remapper), 0)
            nodes[node.name] = node
            if (mentionsRenamedClass(bytes)) dirty += node.name
        }

        dissolveNests()
        nodes.values.forEach { it.innerClasses.removeIf { inner -> inner.name in mangled } }
        mangled.forEach { detach(nodes.getValue(it)) }
        nodes.values.toList().forEach(::widenReferences)
        nodes.values.toList().forEach(::preserveOverrides)

        val output = HashMap(classes)
        names.keys.forEach(output::remove)
        dirty.forEach { output[it] = serialize(nodes.getValue(it)) }
        return RenameResult(output, mapping.toString())
    }

    private fun mentionsRenamedClass(bytes: ByteArray): Boolean {
        val text = String(bytes, Charsets.ISO_8859_1)
        return names.keys.any { text.contains(String(it.toByteArray(), Charsets.ISO_8859_1)) }
    }

    private fun dissolveNests() {
        val hosts = mangled.mapTo(HashSet()) { nodes.getValue(it).nestHostClass ?: it }
        hosts.mapNotNull(nodes::get).forEach { host ->
            val members = host.nestMembers.orEmpty().toList()
            if (members.isEmpty()) return@forEach
            host.nestMembers = null
            dirty += host.name
            dissolved += host.name
            members.mapNotNull(nodes::get).forEach { member ->
                member.nestHostClass = null
                dirty += member.name
                dissolved += member.name
            }
        }
    }

    /** Its nesting would otherwise keep the readable simple name in the dex. */
    private fun detach(node: ClassNode) {
        node.access = node.access.toPublic()
        node.outerClass = null
        node.outerMethod = null
        node.outerMethodDesc = null
        node.sourceFile = null
        node.sourceDebug = null
        node.invisibleAnnotations?.removeIf { it.desc == MANGLE_DESC }
    }

    private fun widenReferences(node: ClassNode) {
        node.superName?.let { exposeClass(node, it) }
        node.interfaces.forEach { exposeClass(node, it) }
        node.methods.forEach { method ->
            method.tryCatchBlocks.forEach { block -> block.type?.let { exposeClass(node, it) } }
            method.instructions.forEach { insn ->
                when (insn) {
                    is FieldInsnNode -> exposeMember(node, insn.owner, insn.name + insn.desc)
                    is MethodInsnNode -> exposeMember(node, insn.owner, insn.name + insn.desc)
                    is TypeInsnNode -> exposeType(node, Type.getObjectType(insn.desc))
                    is MultiANewArrayInsnNode -> exposeType(node, Type.getType(insn.desc))
                    is LdcInsnNode -> exposeConstant(node, insn.cst)
                    is InvokeDynamicInsnNode -> insn.bsmArgs.forEach { exposeConstant(node, it) }
                }
            }
        }
    }

    private fun exposeConstant(from: ClassNode, constant: Any?) {
        when (constant) {
            is Type -> exposeType(from, constant)
            is Handle -> exposeMember(from, constant.owner, constant.name + constant.desc)
        }
    }

    private fun exposeType(from: ClassNode, type: Type) {
        when (type.sort) {
            Type.OBJECT -> exposeClass(from, type.internalName)
            Type.ARRAY -> exposeType(from, type.elementType)
            Type.METHOD -> {
                type.argumentTypes.forEach { exposeType(from, it) }
                exposeType(from, type.returnType)
            }
        }
    }

    private fun exposeClass(from: ClassNode, name: String) {
        val target = nodes[name] ?: return
        if (target.access and ACC_PUBLIC != 0 || !crossesBoundary(from.name, name)) return
        target.access = target.access.toPublic()
        target.innerClasses.filter { it.name == name }.forEach { it.access = it.access.toPublic() }
        dirty += name
    }

    private fun exposeMember(from: ClassNode, owner: String, member: String) {
        if (owner.startsWith("[")) return
        exposeClass(from, owner)
        val declaring = declaringClass(owner, member, HashSet()) ?: return
        if (!crossesBoundary(from.name, declaring.name)) return
        exposeClass(from, declaring.name)
        declaring.fields.filter { it.name + it.desc == member }.forEach { field ->
            if (field.access and ACC_PUBLIC == 0) {
                field.access = field.access.toPublic()
                dirty += declaring.name
            }
        }
        declaring.methods.filter { it.name + it.desc == member }.forEach { widen(declaring, it) }
    }

    private fun declaringClass(owner: String, member: String, visited: MutableSet<String>): ClassNode? {
        if (!visited.add(owner)) return null
        val node = nodes[owner] ?: return null
        if (node.fields.any { it.name + it.desc == member } || node.methods.any { it.name + it.desc == member }) {
            return node
        }
        // Constructors are not inherited.
        if (member.startsWith("<")) return null
        return (listOfNotNull(node.superName) + node.interfaces)
            .firstNotNullOfOrNull { declaringClass(it, member, visited) }
    }

    private fun crossesBoundary(from: String, to: String): Boolean {
        if (from == to) return false
        val leftPackage = packageOf(from) != packageOf(to) && (from in mangled || to in mangled)
        return leftPackage || (from in dissolved && to in dissolved)
    }

    private fun preserveOverrides(node: ClassNode) {
        node.methods.filter(::canOverride).forEach { method ->
            var ancestor = node.superName?.let(nodes::get)
            while (ancestor != null) {
                val overridden = ancestor.methods.firstOrNull {
                    it.name == method.name && it.desc == method.desc && canOverride(it)
                }
                if (overridden != null && overridden.access and (ACC_PUBLIC or ACC_PROTECTED) == 0 &&
                    originalPackage(node.name) == originalPackage(ancestor.name) &&
                    packageOf(node.name) != packageOf(ancestor.name)
                ) {
                    widen(ancestor, overridden)
                    widen(node, method)
                }
                ancestor = ancestor.superName?.let(nodes::get)
            }
        }
    }

    private fun canOverride(method: MethodNode) =
        method.access and (ACC_PRIVATE or ACC_STATIC) == 0 && !method.name.startsWith("<")

    private fun widen(owner: ClassNode, method: MethodNode) {
        if (method.access and ACC_PUBLIC != 0) return
        method.access = method.access.toPublic()
        dirty += owner.name
    }

    private fun packageOf(name: String) = name.substringBeforeLast('/', "")

    private fun originalPackage(name: String) =
        packageOf(names.entries.firstOrNull { it.value == name }?.key ?: name)

    private fun Int.toPublic() = this and (ACC_PRIVATE or ACC_PROTECTED).inv() or ACC_PUBLIC

    private fun serialize(node: ClassNode): ByteArray =
        ClassWriter(0).also(node::accept).toByteArray()
}
