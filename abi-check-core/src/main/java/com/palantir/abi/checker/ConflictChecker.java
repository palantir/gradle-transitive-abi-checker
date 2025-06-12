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

import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.ArtifactName;
import com.palantir.abi.checker.datamodel.DeclaredClass;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.conflict.Conflict;
import com.palantir.abi.checker.datamodel.conflict.FieldDependency;
import com.palantir.abi.checker.datamodel.conflict.MethodDependency;
import com.palantir.abi.checker.datamodel.field.FieldReference;
import com.palantir.abi.checker.datamodel.graph.ClassGraph;
import com.palantir.abi.checker.datamodel.graph.ClassIndex;
import com.palantir.abi.checker.datamodel.method.CallSite;
import com.palantir.abi.checker.datamodel.method.DeclaredMethod;
import com.palantir.abi.checker.datamodel.method.MethodReference;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import com.palantir.abi.checker.util.ExceptionsChecker;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * This class is responsible for finding ABI conflicts for artifacts in the runtime classpath.
 *
 * It is expected to be provided with:
 *   - Its configuration {@link ConflictCheckerConfiguration}, indicating things that can be ignored.
 *   - The complete runtime classpath
 *     - The order of the artifacts is important, as it indicates which version of a class to use
 *       if a class is present in multiple artifacts (first occurrence wins).
 *   - The entry point classes for the project
 *
 * It will then proceed in the following way:
 *   - Determine the set of classes that are reachable from the entry point classes
 *     - Note: the logic here is somewhat too broad at the time being, as we will recursively include classes
 *         that may be reached through methods that themselves are not reachable. A better logic would be to
 *         follow along the method calls directly, starting from either the main methods (for applications) or
 *         any public method in public interfaces (for libraries)
 *   - For each reachable class, and for each method in that class:
 *     - For each method call or field access:
 *       - Try to load the target class
 *       - Try to find the target member (following the class hierarchy where relevant)
 *       - Report any conflict that is found during this
 */
public final class ConflictChecker {
    private final ConflictCheckerConfiguration configuration;
    private final ClassIndex index;
    private final ClassGraph classGraph;

    private ConflictChecker(
            ConflictCheckerConfiguration configuration,
            AbiCheckerClassLoader classLoader,
            ClassIndex index,
            Collection<ClassLocation> projectClasses) {
        this.configuration = configuration;
        this.index = index;

        if (configuration.getCheckCompletely()) {
            classGraph = ClassGraph.createAllReachable(classLoader, index);
        } else {
            classGraph = ClassGraph.createWithEntryPoint(classLoader, index, projectClasses);
        }
    }

    /**
     * Completely checks for ABI conflicts by following the outbound references/calls originating from
     *    {@code projectClasses} gathering all class usages in {@code artifactsToCheck} which then checks their ABI
     *    against the corpus of runtime classes supplied to the {@code ConflictChecker}.
     *
     * @param runtimeClasspathArtifacts all artifacts, including implicit artifacts (runtime provided artifacts)
     * @param projectClasses the classes of the project we're verifying (this is considered the
     *    entry point for reachability)
     * @return a list of conflicts
     */
    public static List<Conflict> checkWithEntryPoints(
            ConflictCheckerConfiguration configuration,
            AbiCheckerClassLoader classLoader,
            List<Artifact> runtimeClasspathArtifacts,
            Collection<ClassLocation> projectClasses) {
        ConflictChecker checker = new ConflictChecker(
                configuration, classLoader, ClassIndex.create(runtimeClasspathArtifacts), projectClasses);
        return checker.checkInternal();
    }

    private List<Conflict> checkInternal() {
        final List<Conflict> conflicts = new ArrayList<>();

        // Then go through everything in the selected portions of the classpath to make sure
        // all the method calls / field references are satisfied.
        for (ClassTypeDescriptor reachableClass : classGraph.reachableClasses()) {
            if (configuration.shouldIgnoreClass(reachableClass)) {
                continue;
            }

            if (!index.knownClasses().containsKey(reachableClass)
                    || !index.sourceMappings().containsKey(reachableClass)) {
                // This shouldn't happen since we claim the class is reachable
                throw new IllegalStateException("Class not found in index: " + reachableClass);
            }

            ArtifactName owningArtifact = index.sourceMappings().get(reachableClass);
            if (configuration.shouldIgnoreArtifact(owningArtifact)) {
                continue;
            }

            List<ClassTypeDescriptor> reachabilityPath = classGraph.getReachabilityPath(reachableClass);

            DeclaredClass clazz = classGraph
                    .loadClass(reachableClass)
                    .orElseThrow(() -> new IllegalStateException("Class not found: " + reachableClass));

            for (DeclaredMethod method : clazz.methods().values()) {
                conflicts.addAll(checkForBrokenMethodCalls(owningArtifact, method, reachabilityPath));
                conflicts.addAll(checkForBrokenFieldAccess(owningArtifact, method, reachabilityPath));
            }
        }
        return conflicts;
    }

    private List<Conflict> checkForBrokenMethodCalls(
            ArtifactName artifactName, DeclaredMethod method, List<ClassTypeDescriptor> reachabilityPath) {
        List<Conflict> conflicts = new ArrayList<>();

        for (CallSite<MethodReference> calledMethod : method.methodCalls()) {
            final ClassTypeDescriptor owningClass = calledMethod.owner();

            if (configuration.shouldIgnoreClass(owningClass)) {
                // Don't register a conflict if the target class is ignored
                continue;
            }

            final Optional<DeclaredClass> calledClass = classGraph.loadClass(owningClass);

            if (calledClass.isEmpty()) {
                final boolean catchesNoClassDef = calledMethod.caughtExceptions().stream()
                        .anyMatch(ExceptionsChecker::isClassLoadingExceptionType);
                if (!catchesNoClassDef) {
                    conflicts.add(Conflict.classNotFound(
                            MethodDependency.of(method, calledMethod, reachabilityPath),
                            artifactName,
                            index.sourceMappings().get(owningClass)));
                }
            } else if (missingMethod(calledMethod.reference(), calledClass.get())) {
                final boolean catchesNoSuchMethod = calledMethod.caughtExceptions().stream()
                        .anyMatch(ExceptionsChecker::isMethodNotFoundExceptionType);
                if (!catchesNoSuchMethod) {
                    conflicts.add(Conflict.methodNotFound(
                            MethodDependency.of(method, calledMethod, reachabilityPath),
                            artifactName,
                            index.sourceMappings().get(owningClass)));
                }
            }
        }

        return conflicts;
    }

    private List<Conflict> checkForBrokenFieldAccess(
            ArtifactName artifactName, DeclaredMethod method, List<ClassTypeDescriptor> reachabilityPath) {

        List<Conflict> conflicts = new ArrayList<>();

        for (CallSite<FieldReference> field : method.fieldAccesses()) {
            final ClassTypeDescriptor owningClass = field.owner();

            if (configuration.shouldIgnoreClass(owningClass)) {
                // Don't register a conflict if the target class is ignored
                continue;
            }

            final Optional<DeclaredClass> calledClass = classGraph.loadClass(owningClass);

            if (calledClass.isEmpty()) {
                final boolean catchesNoClassDef =
                        field.caughtExceptions().stream().anyMatch(ExceptionsChecker::isClassLoadingExceptionType);
                if (!catchesNoClassDef) {
                    conflicts.add(Conflict.classNotFound(
                            FieldDependency.of(method, field, reachabilityPath),
                            artifactName,
                            index.sourceMappings().get(owningClass)));
                }
            } else if (missingField(field.reference(), calledClass.get())) {
                final boolean catchesNoField =
                        field.caughtExceptions().stream().anyMatch(ExceptionsChecker::isFieldNotFoundExceptionType);
                if (!catchesNoField) {
                    conflicts.add(Conflict.fieldNotFound(
                            FieldDependency.of(method, field, reachabilityPath),
                            artifactName,
                            index.sourceMappings().get(owningClass)));
                }
            } else {
                // Everything is ok!
            }
        }

        return conflicts;
    }

    private boolean missingMethod(MethodReference calledMethod, DeclaredClass calledClass) {
        return classGraph.resolveMethodReference(calledClass, calledMethod).isEmpty();
    }

    private boolean missingField(FieldReference field, DeclaredClass calledClass) {
        return classGraph.resolveFieldReference(calledClass, field).isEmpty();
    }
}
