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

package com.palantir.abi.checker.datamodel.graph;

import com.palantir.abi.checker.AbiCheckerClassLoader;
import com.palantir.abi.checker.datamodel.DeclaredClass;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.field.FieldReference;
import com.palantir.abi.checker.datamodel.method.CallSite;
import com.palantir.abi.checker.datamodel.method.DeclaredMethod;
import com.palantir.abi.checker.datamodel.method.MethodReference;
import com.palantir.abi.checker.datamodel.method.Reference;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class is a representation of the class graph for the (reachable) classes of the runtime classpath.
 *
 * It can let us:
 *   - Resolve and load a class
 *   - Resolve a class's method or field to its actual owning class' reference
 */
public final class ClassGraph {
    private final AbiCheckerClassLoader classLoader;
    private final ClassIndex index;

    // Maps to the list of classes we went through to determine the reachability
    private final Map<ClassTypeDescriptor, List<ClassTypeDescriptor>> reachableClasses;

    private ClassGraph(
            AbiCheckerClassLoader classLoader,
            ClassIndex index,
            Map<ClassTypeDescriptor, List<ClassTypeDescriptor>> reachableClasses) {
        this.classLoader = classLoader;
        this.index = index;
        this.reachableClasses = reachableClasses;
    }

    /**
     * Creates the class graph assuming all classes in the runtime classpath are reachable.
     */
    public static ClassGraph createAllReachable(AbiCheckerClassLoader classLoader, ClassIndex index) {
        Map<ClassTypeDescriptor, List<ClassTypeDescriptor>> reachableClasses =
                index.knownClasses().keySet().stream().collect(Collectors.toMap(Function.identity(), List::of));
        return new ClassGraph(classLoader, index, reachableClasses);
    }

    /**
     * Creates the class graph starting from the provided entry point classes.
     */
    public static ClassGraph createWithEntryPoint(
            AbiCheckerClassLoader classLoader, ClassIndex index, Collection<ClassLocation> entryPoint) {
        return new ClassGraph(classLoader, index, reachableFrom(classLoader, index, entryPoint));
    }

    public Set<ClassTypeDescriptor> reachableClasses() {
        return reachableClasses.keySet();
    }

    public List<ClassTypeDescriptor> getReachabilityPath(ClassTypeDescriptor classTypeDescriptor) {
        return reachableClasses.getOrDefault(classTypeDescriptor, List.of());
    }

    public Optional<MethodReference> resolveMethodReference(DeclaredClass targetClass, MethodReference targetMethod) {
        return resolveMember(targetClass, targetMethod, (clazz, method) -> {
            DeclaredMethod declaredMethod = clazz.methods().get(method.method());
            return declaredMethod == null ? null : declaredMethod.reference();
        });
    }

    public Optional<FieldReference> resolveFieldReference(DeclaredClass targetClass, FieldReference targetField) {
        return resolveMember(targetClass, targetField, (clazz, field) -> {
            return clazz.fields().get(field.field());
        });
    }

    /**
     * Resolves a class's member (method or field) to its actual class' reference, by walking up the class hierarchy as
     *   needed.
     */
    private <T extends Reference> Optional<T> resolveMember(
            DeclaredClass targetClass, T targetMember, BiFunction<DeclaredClass, T, T> memberResolver) {
        // Note that the member here might actually have a different class than the original target from
        final T member = memberResolver.apply(targetClass, targetMember);

        if (member != null) {
            if (targetMember.isStatic() != member.isStatic()) {
                // We have found the field, but the staticness is different, so it can't be referenced as desired
                return Optional.empty();
            }

            return Optional.of(member);
        }

        // Might be defined in a super class
        // TODO(aldexis): Handle cyclic inheritance ABI breaks (e.g. when loading/resolving classes)
        for (ClassTypeDescriptor parentClass : targetClass.parents()) {
            final Optional<DeclaredClass> declaredClass = loadClass(parentClass);
            // ignore null parents - this means that the parent cannot be found, and this error gets
            // reported since the class's constructor tries to call its parent's constructor.
            if (declaredClass.isPresent()) {
                Optional<T> parentMember = resolveMember(declaredClass.get(), targetMember, memberResolver);
                if (parentMember.isPresent()) {
                    return parentMember;
                }
            }
        }

        return Optional.empty();
    }

    public Optional<DeclaredClass> loadClass(ClassTypeDescriptor classTypeDescriptor) {
        return Optional.ofNullable(index.knownClasses().get(classTypeDescriptor))
                .map(this::loadClass);
    }

    private DeclaredClass loadClass(ClassLocation classLocation) {
        return classLoader.load(classLocation);
    }

    private static Map<ClassTypeDescriptor, List<ClassTypeDescriptor>> reachableFrom(
            AbiCheckerClassLoader classLoader, ClassIndex index, Collection<ClassLocation> values) {
        // Contains the wip reachability paths that we still need to analyze
        // The class we want to analyze is the last one in the list, while the rest is the path to get there
        //    from the base classes
        // We seed this with each of the base classes we are looking to start from
        Queue<List<ClassLocation>> toCheck =
                new ArrayDeque<>(values.stream().map(List::of).toList());
        Map<ClassTypeDescriptor, List<ClassTypeDescriptor>> reachable = new HashMap<>();

        while (!toCheck.isEmpty()) {
            List<ClassLocation> currentPath = toCheck.remove();
            // This should be constant time because it will be either a singleton list (as initialized above)
            //   or an ArrayList, as created below
            ClassLocation current = currentPath.get(currentPath.size() - 1);

            if (reachable.containsKey(current.className())) {
                continue;
            }

            reachable.put(
                    current.className(),
                    currentPath.stream().map(ClassLocation::className).toList());

            Consumer<Stream<ClassTypeDescriptor>> enqueueKnownClasses =
                    stream -> stream.filter(typeDescriptor -> !reachable.containsKey(typeDescriptor))
                            .map(index.knownClasses()::get)
                            .filter(Objects::nonNull)
                            .map(classLocation -> {
                                // Create a new ArrayList for the new reachability path
                                // Needs to be an ArrayList to have constant-time access to the last element above
                                List<ClassLocation> newPath = new ArrayList<>(currentPath);
                                newPath.add(classLocation);
                                return newPath;
                            })
                            .forEach(toCheck::add);

            DeclaredClass declaredClass = classLoader.load(current);

            enqueueKnownClasses.accept(declaredClass.parents().stream());

            enqueueKnownClasses.accept(declaredClass.loadedClasses().stream());

            // TODO(aldexis): what about method return type / parameters? caught exceptions? declared fields?
            enqueueKnownClasses.accept(declaredClass.methods().values().stream()
                    .flatMap(declaredMethod -> declaredMethod.methodCalls().stream())
                    .map(CallSite::owner));

            enqueueKnownClasses.accept(declaredClass.methods().values().stream()
                    .flatMap(declaredMethod -> declaredMethod.fieldAccesses().stream())
                    .map(CallSite::owner));
        }

        return Collections.unmodifiableMap(reachable);
    }
}
