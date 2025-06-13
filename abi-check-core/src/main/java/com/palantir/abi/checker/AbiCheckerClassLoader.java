/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright (C) 2016 - 2025 Spotify AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.palantir.abi.checker;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.annotations.VisibleForTesting;
import com.palantir.abi.checker.datamodel.DeclaredClass;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.field.FieldDescriptor;
import com.palantir.abi.checker.datamodel.field.FieldReference;
import com.palantir.abi.checker.datamodel.method.CallSite;
import com.palantir.abi.checker.datamodel.method.DeclaredMethod;
import com.palantir.abi.checker.datamodel.method.MethodDescriptor;
import com.palantir.abi.checker.datamodel.method.MethodReference;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

/** Loads a single class from an input stream. */
public final class AbiCheckerClassLoader {

    // This is a set of classes that is using @HotSpotIntrinsicCandidate
    // and thus define native methods that don't actually exist in the class file
    // This could be removed if we stop loading the full JDK
    private static final Set<String> BLACKLIST =
            new HashSet<>(Arrays.asList("java/lang/invoke/MethodHandle", "java/lang/invoke/VarHandle"));

    // Note: URL#equals does DNS resolution, so we shouldn't use it here
    private final LoadingCache<ClassLocation, DeclaredClass> cache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .build(location -> {
                try (InputStream classInputStream = location.openStream()) {
                    return loadInternal(classInputStream);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse class: " + location, e);
                }
            });

    public DeclaredClass load(ClassLocation classLocation) {
        return cache.get(classLocation);
    }

    @VisibleForTesting
    static DeclaredClass loadInternal(InputStream in) throws IOException {
        ClassNode classNode = readClassNode(in);

        ClassTypeDescriptor className = TypeDescriptors.fromClassName(classNode.name);
        Set<ClassTypeDescriptor> parents = readParents(classNode);
        Map<FieldDescriptor, FieldReference> declaredFields = readDeclaredFields(className, classNode);

        Map<MethodDescriptor, DeclaredMethod> declaredMethods = new HashMap<>();
        Set<ClassTypeDescriptor> loadedClasses = new HashSet<>();

        for (MethodNode method : classNode.methods) {
            analyseMethod(className, method, declaredMethods, loadedClasses);
        }

        return DeclaredClass.builder()
                .className(className)
                .methods(declaredMethods)
                .parents(parents)
                .loadedClasses(loadedClasses)
                .fields(declaredFields)
                .build();
    }

    private static ClassNode readClassNode(InputStream in) throws IOException {
        final ClassNode classNode = new ClassNode();
        ClassReader reader = new ClassReader(in);
        reader.accept(classNode, 0);
        return classNode;
    }

    private static Set<ClassTypeDescriptor> readParents(ClassNode classNode) {
        final Set<ClassTypeDescriptor> parents = classNode.interfaces.stream()
                .map(TypeDescriptors::fromClassName)
                .collect(Collectors.toSet());
        // java/lang/Object has no superclass
        if (classNode.superName != null) {
            parents.add(TypeDescriptors.fromClassName(classNode.superName));
        }
        return parents;
    }

    private static Map<FieldDescriptor, FieldReference> readDeclaredFields(
            ClassTypeDescriptor className, ClassNode classNode) {
        Map<FieldDescriptor, FieldReference> fields = new HashMap<>();

        final Iterable<FieldNode> classFields = classNode.fields;
        for (FieldNode field : classFields) {
            FieldDescriptor fieldDescriptor = FieldDescriptor.of(TypeDescriptors.fromRaw(field.desc), field.name);
            boolean isStatic = (field.access & Opcodes.ACC_STATIC) != 0;
            FieldReference fieldReference = FieldReference.of(className, fieldDescriptor, isStatic);
            fields.put(fieldDescriptor, fieldReference);
        }
        return fields;
    }

    private static void analyseMethod(
            ClassTypeDescriptor className,
            MethodNode method,
            Map<MethodDescriptor, DeclaredMethod> declaredMethods,
            Set<ClassTypeDescriptor> loadedClasses) {
        final Set<CallSite<MethodReference>> methodCalls = new HashSet<>();
        final Set<CallSite<FieldReference>> fieldAccesses = new HashSet<>();

        int lineNumber = 0;
        for (final AbstractInsnNode insn : method.instructions) {
            try {
                if (insn instanceof LineNumberNode lineNumberNode) {
                    lineNumber = lineNumberNode.line;
                }
                if (insn instanceof MethodInsnNode methodInsn) {
                    handleMethodCall(
                            methodCalls,
                            lineNumber,
                            methodInsn,
                            () -> getCaughtExceptions(method.instructions, methodInsn, method));
                }
                if (insn instanceof FieldInsnNode fieldInsn) {
                    handleFieldAccess(
                            fieldAccesses,
                            lineNumber,
                            fieldInsn,
                            () -> getCaughtExceptions(method.instructions, fieldInsn, method));
                }
                if (insn instanceof InvokeDynamicInsnNode dynamicInsn) {
                    handleInvokeDynamic(
                            methodCalls,
                            fieldAccesses,
                            lineNumber,
                            dynamicInsn,
                            () -> getCaughtExceptions(method.instructions, dynamicInsn, method));
                }
                if (insn instanceof LdcInsnNode ldcInsnNode) {
                    handleLdc(loadedClasses, ldcInsnNode);
                }
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Error analysing " + className + "." + method.name + ", line: " + lineNumber, e);
            }
        }

        boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        MethodDescriptor methodDescriptor = MethodDescriptor.ofDescriptor(method.desc, method.name);
        final DeclaredMethod declaredMethod = DeclaredMethod.builder()
                .reference(MethodReference.of(className, methodDescriptor, isStatic))
                .methodCalls(methodCalls)
                .fieldAccesses(fieldAccesses)
                .build();

        if (declaredMethods.put(declaredMethod.reference().method(), declaredMethod) != null) {
            throw new RuntimeException(
                    "Multiple definitions of " + declaredMethod.reference().method() + " in class " + className);
        }
    }

    private static Set<ClassTypeDescriptor> getCaughtExceptions(
            final InsnList instructions, final AbstractInsnNode insn, final MethodNode method) {

        final Set<ClassTypeDescriptor> caughtExceptions = new HashSet<>();
        final int instructionIndex = instructions.indexOf(insn);
        for (final TryCatchBlockNode tryCatchBlockNode : method.tryCatchBlocks) {
            if (tryCatchBlockNode.type == null) {
                continue;
            }
            final int catchStartIndex = instructions.indexOf(tryCatchBlockNode.start);
            final int catchEndIndex = instructions.indexOf(tryCatchBlockNode.end);
            if (instructionIndex > catchStartIndex && instructionIndex < catchEndIndex) {
                caughtExceptions.add(TypeDescriptors.fromClassName(tryCatchBlockNode.type));
            }
        }
        return caughtExceptions;
    }

    /**
     * See:
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.invokeinterface
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.invokespecial
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.invokestatic
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.invokevirtual
     * for more details on the expected instructions.
     */
    private static void handleMethodCall(
            final Set<CallSite<MethodReference>> methodCalls,
            final int lineNumber,
            final MethodInsnNode insn,
            final Supplier<Set<ClassTypeDescriptor>> caughtExceptions) {
        addMethodCall(
                methodCalls, lineNumber, insn.owner, insn.name, insn.desc, isStaticMethodCall(insn), caughtExceptions);
    }

    private static boolean isStaticMethodCall(MethodInsnNode insn) {
        return switch (insn.getOpcode()) {
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> false;
            case Opcodes.INVOKESTATIC -> true;
            default -> throw new RuntimeException("Unexpected method call opcode: " + insn.getOpcode());
        };
    }

    private static void addMethodCall(
            Set<CallSite<MethodReference>> methodCalls,
            int lineNumber,
            String owner,
            String name,
            String descriptor,
            boolean isStatic,
            Supplier<Set<ClassTypeDescriptor>> caughtExceptions) {
        if (isNotArray(owner) && !BLACKLIST.contains(owner)) {
            ClassTypeDescriptor className = TypeDescriptors.fromClassName(owner);
            methodCalls.add(CallSite.of(
                    MethodReference.of(className, MethodDescriptor.ofDescriptor(descriptor, name), isStatic),
                    lineNumber,
                    caughtExceptions.get()));
        }
    }

    /**
     * See:
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.getfield
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.getstatic
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.putfield
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.putstatic
     * for more details on the expected instructions.
     */
    private static void handleFieldAccess(
            Set<CallSite<FieldReference>> fieldAccesses,
            int lineNumber,
            FieldInsnNode insn,
            final Supplier<Set<ClassTypeDescriptor>> caughtExceptions) {
        addFieldAccess(
                fieldAccesses,
                lineNumber,
                insn.owner,
                insn.name,
                insn.desc,
                isStaticFieldAccess(insn),
                caughtExceptions);
    }

    private static boolean isStaticFieldAccess(FieldInsnNode insn) {
        return switch (insn.getOpcode()) {
            case Opcodes.GETFIELD, Opcodes.PUTFIELD -> false;
            case Opcodes.GETSTATIC, Opcodes.PUTSTATIC -> true;
            default -> throw new RuntimeException("Unexpected field access opcode: " + insn.getOpcode());
        };
    }

    private static void addFieldAccess(
            Set<CallSite<FieldReference>> fieldAccesses,
            int lineNumber,
            String owner,
            String name,
            String descriptor,
            boolean isStatic,
            Supplier<Set<ClassTypeDescriptor>> caughtExceptions) {
        if (isNotArray(owner) && !BLACKLIST.contains(owner)) {
            FieldReference field = FieldReference.of(
                    TypeDescriptors.fromClassName(owner), TypeDescriptors.fromRaw(descriptor), name, isStatic);
            fieldAccesses.add(CallSite.of(field, lineNumber, caughtExceptions.get()));
        }
    }

    /**
     * invokeDynamic instructions are used for lambdas, method and field references.
     *
     * They contain a list of handles which refer to the methods or fields to call.
     *
     * See https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.invokedynamic
     *   and https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-4.10.1.9.invokedynamic.
     *
     * Note: field references seem to only be used for records' internal methods at this point?
     */
    private static void handleInvokeDynamic(
            Set<CallSite<MethodReference>> methodCalls,
            Set<CallSite<FieldReference>> fieldAccesses,
            int lineNumber,
            InvokeDynamicInsnNode insn,
            Supplier<Set<ClassTypeDescriptor>> caughtExceptions) {
        for (final Object arg : insn.bsmArgs) {
            if (arg instanceof Handle handle) {
                if (!isFieldHandle(handle)) {
                    addMethodCall(
                            methodCalls,
                            lineNumber,
                            handle.getOwner(),
                            handle.getName(),
                            handle.getDesc(),
                            isStaticHandle(handle),
                            caughtExceptions);
                } else {
                    addFieldAccess(
                            fieldAccesses,
                            lineNumber,
                            handle.getOwner(),
                            handle.getName(),
                            handle.getDesc(),
                            isStaticHandle(handle),
                            caughtExceptions);
                }
            }
        }
    }

    private static boolean isStaticHandle(Handle handle) {
        return switch (handle.getTag()) {
            case Opcodes.H_INVOKEVIRTUAL,
                    Opcodes.H_INVOKEINTERFACE,
                    Opcodes.H_INVOKESPECIAL,
                    Opcodes.H_NEWINVOKESPECIAL -> false;
            case Opcodes.H_INVOKESTATIC -> true;
            // Note: H_GETFIELD seems used within record's internal methods like toString
            //   I couldn't find usages of the other field related opcodes anywhere in the jdk
            case Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD -> false;
            case Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC -> true;
            default -> throw new RuntimeException("Unexpected handle opcode: " + handle.getTag());
        };
    }

    private static boolean isFieldHandle(Handle handle) {
        return switch (handle.getTag()) {
            case Opcodes.H_INVOKEVIRTUAL,
                    Opcodes.H_INVOKEINTERFACE,
                    Opcodes.H_INVOKESPECIAL,
                    Opcodes.H_NEWINVOKESPECIAL -> false;
            case Opcodes.H_INVOKESTATIC -> false;
            // Note: H_GETFIELD seems used within record's internal methods like toString
            //   I couldn't find usages of the other field related opcodes anywhere in the jdk
            case Opcodes.H_GETFIELD, Opcodes.H_PUTFIELD -> true;
            case Opcodes.H_GETSTATIC, Opcodes.H_PUTSTATIC -> true;
            default -> throw new RuntimeException("Unexpected handle opcode: " + handle.getTag());
        };
    }

    private static boolean isNotArray(String owner) {
        return owner.charAt(0) != '[';
    }

    /**
     * See
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.ldc
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.ldc_w
     *   - https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-6.html#jvms-6.5.ldc2_w
     *
     * If an LDC (LoaDConstant) instruction is emitted with a symbolic reference to a class, that class is
     *   loaded. This means we need to at least check for presence of that class, and also validate its
     *   static initialisation code, if any.
     *
     * It would probably be safe for some future to ignore other methods defined by the class.
     */
    private static void handleLdc(Set<ClassTypeDescriptor> loadedClasses, LdcInsnNode insn) {
        if (insn.cst instanceof Type type) {
            Type loadedType = type;

            if (type.getSort() == Type.ARRAY) {
                loadedType = type.getElementType();
            }

            if (loadedType.getSort() == Type.OBJECT) {
                loadedClasses.add(TypeDescriptors.fromClassName(loadedType.getInternalName()));
            }
        }
    }
}
