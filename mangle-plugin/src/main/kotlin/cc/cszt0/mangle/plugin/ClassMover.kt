package cc.cszt0.mangle.plugin

import org.gradle.api.GradleException
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode

private const val MANGLE_DESC = "Lcc/cszt0/mangle/annotation/Mangle;"
private const val KEEP_DESC = "Landroidx/annotation/Keep;"
private const val OBJECT = "java/lang/Object"

internal class ClassInfo(
    val access: Int,
    val superName: String?,
    val interfaces: List<String>,
    /** `name+descriptor` of every field and method, to its access flags. */
    val members: Map<String, Int>,
) {
    companion object {
        fun of(node: ClassNode) = ClassInfo(
            node.access,
            node.superName,
            node.interfaces,
            node.fields.associate { it.name + it.desc to it.access } +
                node.methods.associate { it.name + it.desc to it.access },
        )
    }
}

/** Classes guaranteed to exist unrenamed at runtime, i.e. the Android boot classpath. */
internal fun interface PlatformClasses {
    fun find(internalName: String): ClassInfo?
}

internal class MoveResult(val classes: Map<String, ByteArray>, val mapping: String)

/**
 * Moves the selected methods out of their classes.
 *
 * For a host class `H` and a selected method, the body goes to a public static method of a new
 * class in the default package, so one string serves both `Class.forName` and JNI `FindClass`. The
 * original method keeps its name and flags and only forwards, which leaves overriding, interface
 * implementation and `synchronized` untouched.
 *
 * Moved code loses `H`'s access rights. Anything it cannot reach directly goes through a generated
 * static accessor in `H`: the same instruction compiled there was legal, so the accessor is too,
 * and that covers private members, `super` calls and protected framework members alike. Accessors
 * replace the instruction with an identical stack shape, so frames stay valid. Only `new` can't be
 * forwarded that way, hence constructors and classes of this project are widened to public.
 *
 * Types that are not on the boot classpath are erased to `Object` in the new signatures: R8 renames
 * them, so a signature a JNI caller spells out would otherwise break. Moved bodies cast them back.
 */
internal class ClassMover(
    private val classes: Map<String, ByteArray>,
    private val manifest: MangleManifest,
    private val key: String,
    private val platform: PlatformClasses,
    private val warn: (String) -> Unit,
) {
    private val nodes = HashMap<String, ClassNode>()
    private val dirty = LinkedHashSet<String>()
    private val generated = LinkedHashMap<String, ClassNode>()
    private val mapping = StringBuilder()

    fun run(): MoveResult {
        warnAboutUnselected()
        manifest.entries.forEach { (binaryName, methods) ->
            val internalName = binaryName.replace('.', '/')
            if (internalName in classes) {
                moveClass(internalName, binaryName, methods)
            } else {
                warn("$binaryName was found in source but not in the compiled classes; skipped.")
            }
        }
        val output = HashMap(classes)
        dirty.forEach { output[it] = serialize(nodes.getValue(it)) }
        generated.forEach { (name, node) -> output[name] = serialize(node) }
        return MoveResult(output, mapping.toString())
    }

    private fun warnAboutUnselected() {
        val listed = manifest.entries.keys.mapTo(HashSet()) { it.replace('.', '/') }
        classes.forEach { (name, bytes) ->
            if (name in listed) return@forEach
            val node = ClassNode()
            ClassReader(bytes).accept(node, ClassReader.SKIP_CODE)
            val relevant = node.invisibleAnnotations.hasMangle() ||
                node.methods.any { it.invisibleAnnotations.hasMangle() || it.isNative() }
            if (relevant) {
                warn("${name.replace('/', '.')} has @Mangle or native methods but was not found in Java source (Kotlin?); left unchanged.")
            }
        }
    }

    private fun moveClass(internalName: String, binaryName: String, selected: Set<String>) {
        val host = node(internalName)
        val classMangled = host.invisibleAnnotations.hasMangle()
        val target = ClassNode().apply {
            version = host.version
            access = ACC_PUBLIC or ACC_FINAL or ACC_SUPER
            name = Naming.mangleClass(key, binaryName)
            superName = OBJECT
            invisibleAnnotations = mutableListOf(AnnotationNode(KEEP_DESC))
        }
        check(generated.put(target.name, target) == null)
        mapping.append("$binaryName -> ${target.name}:\n")

        val rewriter = BodyRewriter(host)
        val moved = HashSet<String>()
        host.methods.toList().forEach { method ->
            val eligible = method.name in selected && !method.name.startsWith("<") &&
                method.access and (ACC_SYNTHETIC or ACC_BRIDGE or ACC_ABSTRACT) == 0 &&
                (classMangled || method.isNative() || method.invisibleAnnotations.hasMangle())
            if (eligible) {
                moveMethod(host, binaryName, method, target, rewriter)
                moved += method.name
            }
        }
        (selected - moved).forEach { warn("$binaryName.$it was found in source but could not be moved.") }

        host.invisibleAnnotations?.removeIf { it.desc == MANGLE_DESC }
        widenClass(internalName)
        dirty += internalName
    }

    private fun moveMethod(
        host: ClassNode,
        binaryName: String,
        method: MethodNode,
        target: ClassNode,
        rewriter: BodyRewriter,
    ) {
        val isStatic = method.access and ACC_STATIC != 0
        val returnType = Type.getReturnType(method.desc)
        val arguments = Type.getArgumentTypes(method.desc)
        val parameters = if (isStatic) arguments.toList() else listOf(Type.getObjectType(host.name)) + arguments
        val erasedParameters = parameters.map(::erase)
        val erasedReturn = erase(returnType)

        val moved = MethodNode(
            ACC_PUBLIC or ACC_STATIC or (method.access and ACC_NATIVE),
            Naming.mangleMethod(key, binaryName, method.name),
            Type.getMethodDescriptor(erasedReturn, *erasedParameters.toTypedArray()),
            null,
            null,
        )
        moved.invisibleAnnotations = mutableListOf(AnnotationNode(KEEP_DESC))
        if (target.methods.any { it.name == moved.name && it.desc == moved.desc }) {
            throw GradleException(
                "Mangle: overloads of $binaryName.${method.name} become identical once non-platform " +
                    "types are erased: ${moved.desc}. Rename one of them.",
            )
        }

        if (!method.isNative()) {
            moved.instructions = method.instructions
            moved.tryCatchBlocks = method.tryCatchBlocks
            rewriter.rewrite(moved)
            moved.instructions.insert(castErasedParameters(parameters, erasedParameters))
        }
        target.methods.add(moved)

        method.instructions = InsnList().apply {
            var slot = 0
            parameters.forEach {
                add(VarInsnNode(it.getOpcode(ILOAD), slot))
                slot += it.size
            }
            add(MethodInsnNode(INVOKESTATIC, target.name, moved.name, moved.desc, false))
            if (erasedReturn != returnType) add(TypeInsnNode(CHECKCAST, returnType.internalName))
            add(InsnNode(returnType.getOpcode(IRETURN)))
        }
        method.tryCatchBlocks = mutableListOf()
        method.localVariables = null
        method.visibleLocalVariableAnnotations = null
        method.invisibleLocalVariableAnnotations = null
        method.access = method.access and ACC_NATIVE.inv()
        method.invisibleAnnotations?.removeIf { it.desc == MANGLE_DESC }

        val javaArguments = arguments.joinToString(",") { it.className }
        mapping.append("    ${returnType.className} ${method.name}($javaArguments) -> ${moved.name}\n")
    }

    private fun castErasedParameters(parameters: List<Type>, erased: List<Type>) = InsnList().apply {
        var slot = 0
        parameters.forEachIndexed { index, type ->
            if (type != erased[index]) {
                add(VarInsnNode(ALOAD, slot))
                add(TypeInsnNode(CHECKCAST, type.internalName))
                add(VarInsnNode(ASTORE, slot))
            }
            slot += type.size
        }
    }

    private fun erase(type: Type): Type = when (type.sort) {
        Type.OBJECT -> if (platform.find(type.internalName) != null) type else Type.getObjectType(OBJECT)
        Type.ARRAY -> {
            val element = erase(type.elementType)
            Type.getType("[".repeat(type.dimensions) + element.descriptor)
        }
        else -> type
    }

    private inner class BodyRewriter(private val host: ClassNode) {
        private val hostIsInterface = host.access and ACC_INTERFACE != 0
        private val accessors = HashMap<List<Any>, MethodNode>()

        fun rewrite(method: MethodNode) {
            method.tryCatchBlocks.forEach { block -> block.type?.let { widenType(Type.getObjectType(it)) } }
            val instructions = method.instructions
            instructions.toArray().forEach { insn ->
                when (insn) {
                    is FieldInsnNode -> {
                        if (isReachable(insn.owner, insn.name + insn.desc)) {
                            widenType(Type.getObjectType(insn.owner))
                        } else {
                            instructions.set(insn, callTo(fieldAccessor(insn)))
                        }
                    }
                    is MethodInsnNode -> when {
                        insn.name == "<init>" -> widenConstructor(insn.owner, insn.desc)
                        insn.opcode != INVOKESPECIAL && isReachable(insn.owner, insn.name + insn.desc) ->
                            widenType(Type.getObjectType(insn.owner))
                        else -> instructions.set(
                            insn,
                            callTo(methodAccessor(insn.opcode, insn.owner, insn.name, insn.desc, insn.itf)),
                        )
                    }
                    is InvokeDynamicInsnNode -> {
                        widenType(Type.getMethodType(insn.desc))
                        insn.bsmArgs = Array(insn.bsmArgs.size) { i ->
                            when (val argument = insn.bsmArgs[i]) {
                                is Handle -> rewriteHandle(argument)
                                is Type -> argument.also(::widenType)
                                else -> argument
                            }
                        }
                    }
                    is TypeInsnNode -> widenType(Type.getObjectType(insn.desc))
                    is MultiANewArrayInsnNode -> widenType(Type.getType(insn.desc))
                    is LdcInsnNode -> (insn.cst as? Type)?.let(::widenType)
                }
            }
        }

        private fun callTo(accessor: MethodNode) =
            MethodInsnNode(INVOKESTATIC, host.name, accessor.name, accessor.desc, hostIsInterface)

        private fun rewriteHandle(handle: Handle): Handle {
            val opcode = when (handle.tag) {
                H_INVOKESTATIC -> INVOKESTATIC
                H_INVOKEVIRTUAL -> INVOKEVIRTUAL
                H_INVOKEINTERFACE -> INVOKEINTERFACE
                H_INVOKESPECIAL -> INVOKESPECIAL
                H_NEWINVOKESPECIAL -> {
                    widenConstructor(handle.owner, handle.desc)
                    return handle
                }
                else -> return handle
            }
            if (opcode != INVOKESPECIAL && isReachable(handle.owner, handle.name + handle.desc)) {
                widenType(Type.getObjectType(handle.owner))
                return handle
            }
            val accessor = methodAccessor(opcode, handle.owner, handle.name, handle.desc, handle.isInterface)
            return Handle(H_INVOKESTATIC, host.name, accessor.name, accessor.desc, hostIsInterface)
        }

        private fun fieldAccessor(insn: FieldInsnNode): MethodNode =
            accessors.getOrPut(listOf(insn.opcode, insn.owner, insn.name, insn.desc)) {
                val owner = Type.getObjectType(insn.owner)
                val value = Type.getType(insn.desc)
                val type = when (insn.opcode) {
                    GETSTATIC -> Type.getMethodType(value)
                    PUTSTATIC -> Type.getMethodType(Type.VOID_TYPE, value)
                    GETFIELD -> Type.getMethodType(value, owner)
                    else -> Type.getMethodType(Type.VOID_TYPE, owner, value)
                }
                newAccessor(type) { add(FieldInsnNode(insn.opcode, insn.owner, insn.name, insn.desc)) }
            }

        private fun methodAccessor(opcode: Int, owner: String, name: String, desc: String, itf: Boolean): MethodNode =
            accessors.getOrPut(listOf(opcode, owner, name, desc)) {
                val arguments = Type.getArgumentTypes(desc)
                val receiver = when (opcode) {
                    INVOKESTATIC -> emptyList()
                    // Both require a receiver of the calling class. javac still names Object as the
                    // owner of an inherited clone(), so the owner can't serve as the receiver type.
                    INVOKESPECIAL -> listOf(Type.getObjectType(host.name))
                    else -> listOf(
                        Type.getObjectType(if (isForeignProtected(owner, name + desc)) host.name else owner),
                    )
                }
                val type = Type.getMethodType(Type.getReturnType(desc), *(receiver + arguments).toTypedArray())
                newAccessor(type) { add(MethodInsnNode(opcode, owner, name, desc, itf)) }
            }

        private fun isForeignProtected(owner: String, member: String): Boolean {
            val access = resolve(owner, member, HashSet()) ?: return false
            return access and ACC_PROTECTED != 0 && owner.substringBeforeLast('/', "") != host.name.substringBeforeLast('/', "")
        }

        private fun newAccessor(type: Type, operation: InsnList.() -> Unit): MethodNode {
            val accessor = MethodNode(
                ACC_PUBLIC or ACC_STATIC or ACC_SYNTHETIC,
                "access\$mangle\$${accessors.size}",
                type.descriptor,
                null,
                null,
            )
            accessor.instructions.apply {
                var slot = 0
                type.argumentTypes.forEach {
                    add(VarInsnNode(it.getOpcode(ILOAD), slot))
                    slot += it.size
                }
                operation()
                add(InsnNode(type.returnType.getOpcode(IRETURN)))
            }
            host.methods.add(accessor)
            return accessor
        }
    }

    /** Whether a public class in another package may use the member without going through an accessor. */
    private fun isReachable(owner: String, member: String): Boolean {
        if (owner.startsWith("[")) return true
        val info = classInfo(owner) ?: return false
        if (owner !in classes && info.access and ACC_PUBLIC == 0) return false
        val access = resolve(owner, member, HashSet()) ?: return false
        return access and ACC_PUBLIC != 0
    }

    private fun resolve(owner: String, member: String, visited: MutableSet<String>): Int? {
        if (!visited.add(owner)) return null
        val info = classInfo(owner) ?: return null
        info.members[member]?.let { return it }
        info.superName?.let { parent -> resolve(parent, member, visited)?.let { return it } }
        info.interfaces.forEach { parent -> resolve(parent, member, visited)?.let { return it } }
        return null
    }

    private fun classInfo(name: String): ClassInfo? =
        if (name in classes) ClassInfo.of(node(name)) else platform.find(name)

    private fun widenType(type: Type) {
        when (type.sort) {
            Type.OBJECT -> widenClass(type.internalName)
            Type.ARRAY -> widenType(type.elementType)
            Type.METHOD -> {
                type.argumentTypes.forEach(::widenType)
                widenType(type.returnType)
            }
        }
    }

    private fun widenClass(name: String) {
        if (name !in classes) return
        val node = node(name)
        if (node.access and ACC_PUBLIC != 0) return
        node.access = node.access.toPublic()
        node.innerClasses.filter { it.name == name }.forEach { it.access = it.access.toPublic() }
        dirty += name
    }

    private fun widenConstructor(owner: String, desc: String) {
        widenClass(owner)
        if (owner !in classes) return
        val constructor = node(owner).methods.first { it.name == "<init>" && it.desc == desc }
        if (constructor.access and ACC_PUBLIC == 0) {
            constructor.access = constructor.access.toPublic()
            dirty += owner
        }
    }

    private fun Int.toPublic() = this and (ACC_PRIVATE or ACC_PROTECTED).inv() or ACC_PUBLIC

    private fun node(name: String): ClassNode = nodes.getOrPut(name) {
        ClassNode().also { ClassReader(classes.getValue(name)).accept(it, ClassReader.EXPAND_FRAMES) }
    }

    private fun serialize(node: ClassNode): ByteArray =
        ClassWriter(ClassWriter.COMPUTE_MAXS).also(node::accept).toByteArray()

    private fun List<AnnotationNode>?.hasMangle() = this?.any { it.desc == MANGLE_DESC } == true

    private fun MethodNode.isNative() = access and ACC_NATIVE != 0
}
