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

package com.palantir.abi.checker.integration;

import com.google.common.collect.ImmutableSet;
import com.google.testing.compile.JavaFileObjects;
import java.util.Set;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import org.immutables.value.Value;

/**
 * This class is used for integration tests to represent the various source files we need to generate to test for
 *   ABI breaks.
 * It contains:
 *   - Sources for the root project
 *   - Sources for the direct dependency, whether reachable by the root project or not
 *   - Sources for the transitive dependency, both to compile the direct dependency against, as well as a different
 *      set of sources to run the classes against.
 */
// Suppressing since we don't care about source/non-source retention for test classes
@SuppressWarnings("ImmutablesStyle")
@Value.Immutable
@Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE)
interface JavaFiles {

    /**
     * Sources for the root project. By default, it will contain a single file, which will call into each of
     *   the reachable classes.
     */
    @Value.Default
    default Set<JavaFileObject> rootSources() {
        // This is for example com/palantir/Test.java and we want simply Test
        Set<String> reachableClassNames = reachableDependencySources().stream()
                .map(JavaFileObject::getName)
                .map(name -> name.substring(name.lastIndexOf('/') + 1, name.length() - Kind.SOURCE.extension.length()))
                .collect(ImmutableSet.toImmutableSet());

        StringBuilder sb = new StringBuilder();
        for (String className : reachableClassNames) {
            sb.append("\n                    public %s field = new %s();".formatted(className, className));
        }
        if (!sb.isEmpty()) {
            sb.append("\n");
        }
        JavaFileObject rootSource = JavaFileObjects.forSourceString(
                "com.Root",
                """
                package com;
                public class Root {%s}
                """
                        .formatted(sb.toString()));

        return Set.of(rootSource);
    }

    /**
     * Sources for the direct dependency that are reachable from the main project.
     */
    Set<JavaFileObject> reachableDependencySources();

    /**
     * Sources for the direct dependency that are not reachable from the main project.
     */
    Set<JavaFileObject> unreachableDependencySources();

    /**
     * Sources for the transitive dependency, which the direct dependency and root project are going to be compiled
     *   against.
     */
    Set<JavaFileObject> transitiveBeforeSources();

    /**
     * Sources for the transitive dependency, which are going to be compiled separately and replaced at runtime,
     *   simulating a transitive version mismatch, and potential runtime ABI break.
     */
    Set<JavaFileObject> transitiveAfterSources();

    default Set<JavaFileObject> allBeforeSources() {
        ImmutableSet.Builder<JavaFileObject> sourceFiles = ImmutableSet.builder();
        sourceFiles.addAll(rootSources());
        sourceFiles.addAll(reachableDependencySources());
        sourceFiles.addAll(unreachableDependencySources());
        sourceFiles.addAll(transitiveBeforeSources());
        return sourceFiles.build();
    }

    static Builder builder() {
        return new Builder();
    }

    final class Builder extends ImmutableJavaFiles.Builder {
        Builder reachableDependency(String className, String source) {
            return addAllReachableDependencies(file(className, source));
        }

        Builder addAllReachableDependencies(JavaFileObject... reachableDependencySources) {
            return addAllReachableDependencySources(Set.of(reachableDependencySources));
        }

        Builder unreachableDependency(String className, String source) {
            return addAllUnreachableDependencies(file(className, source));
        }

        Builder addAllUnreachableDependencies(JavaFileObject... reachableDependencySources) {
            return addAllUnreachableDependencySources(Set.of(reachableDependencySources));
        }

        Builder transitiveBeforeDependency(String className, String source) {
            return addAllTransitiveBeforeDependencies(file(className, source));
        }

        Builder addAllTransitiveBeforeDependencies(JavaFileObject... reachableDependencySources) {
            return addAllTransitiveBeforeSources(Set.of(reachableDependencySources));
        }

        Builder transitiveAfterDependency(String className, String source) {
            return addAllTransitiveAfterDependencies(file(className, source));
        }

        Builder addAllTransitiveAfterDependencies(JavaFileObject... reachableDependencySources) {
            return addAllTransitiveAfterSources(Set.of(reachableDependencySources));
        }

        private static JavaFileObject file(String className, String source) {
            return JavaFileObjects.forSourceString(className, source);
        }
    }
}
