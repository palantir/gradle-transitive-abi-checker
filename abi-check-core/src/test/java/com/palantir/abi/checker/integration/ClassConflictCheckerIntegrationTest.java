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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.palantir.abi.checker.ConflictCheckerConfiguration;
import com.palantir.abi.checker.datamodel.conflict.Conflict;
import com.palantir.abi.checker.datamodel.conflict.Conflict.ConflictCategory;
import com.palantir.abi.checker.datamodel.conflict.MethodDependency;
import java.lang.reflect.InvocationTargetException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Contains integration tests for the conflict checker related to class changes, such as renames, removals, etc.
 */
public class ClassConflictCheckerIntegrationTest extends BaseConflictCheckerIntegrationTest {

    @Test
    public void renaming_class_creates_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public ClassWithAbiBreak field = new ClassWithAbiBreak();
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {}
                """);

        sources.transitiveAfterDependency(
                "com.RenamedClassWithAbiBreak",
                // language=java
                """
                package com;
                public class RenamedClassWithAbiBreak {}
                """);

        generateClassFiles(tempDir, sources.build());
        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.ClassWithAbiBreak");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/ClassWithAbiBreak");
    }

    @Test
    public void renaming_super_class_creates_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass extends ClassWithAbiBreak {}
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {}
                """);

        sources.transitiveAfterDependency(
                "com.RenamedClassWithAbiBreak",
                // language=java
                """
                package com;
                public class RenamedClassWithAbiBreak {}
                """);

        generateClassFiles(tempDir, sources.build());
        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.ClassWithAbiBreak");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/ClassWithAbiBreak");
    }

    @Test
    public void catching_no_class_def_found_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    Removed field;
                    public MyClass() {
                        try {
                            field = new Removed();
                        } catch (NoClassDefFoundError e) {
                            field = null;
                        }
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Removed",
                // language=java
                """
                package com;
                public class Removed {}
                """);

        // No classes after

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void removed_caught_exception_creates_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    // Note: the method here is unused at runtime, but this still breaks upon class verification
                    public void method() {
                        try {
                            // This statement is purely here to avoid the entire block being removed by the compiler
                            System.out.println("test");
                        } catch (RemovedException e) {
                            // ignore
                        } catch (KeptException e) {
                            // ignore
                        } catch (RemovedException2 e) {
                            // ignore
                        }
                    }
                }
                """);

        sources.transitiveDependency(
                "com.KeptException",
                // language=java
                """
                package com;
                public class KeptException extends RuntimeException {}
                """);

        sources.transitiveBeforeDependency(
                "com.RemovedException",
                // language=java
                """
                package com;
                public class RemovedException extends RuntimeException {}
                """);

        sources.transitiveBeforeDependency(
                "com.RemovedException2",
                // language=java
                """
                package com;
                public class RemovedException2 extends RuntimeException {}
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/RemovedException");

        // Sort conflicts by target class name, to avoid flakes
        List<Conflict> conflicts = checkConflicts(tempDir).stream()
                .sorted(Comparator.comparing(c -> c.dependency().targetClass().className()))
                .collect(Collectors.toList());

        assertThat(conflicts).hasSize(2);

        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.RemovedException");
        // Verify the line number matches the one from the source code
        assertThat(conflict.dependency().fromLineNumber()).isEqualTo(7);

        Conflict conflict2 = conflicts.get(1);
        assertThat(conflict2.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict2.dependency().targetClass().className()).isEqualTo("com.RemovedException2");
        // Verify the line number matches the one from the source code
        assertThat(conflict2.dependency().fromLineNumber()).isEqualTo(11);
    }

    @Test
    public void removed_caught_exception_creates_no_conflict_if_ignored() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    // Note: the method here is unused at runtime, but this still breaks upon class verification
                    public void method() {
                        try {
                            // This statement is purely here to avoid the entire block being removed by the compiler
                            System.out.println("test");
                        } catch (RemovedException e) {
                            // ignore
                        }
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.RemovedException",
                // language=java
                """
                package com;
                public class RemovedException extends RuntimeException {}
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/RemovedException");

        ConflictCheckerConfiguration configuration =
                config().addIgnoredClassPrefixes("com.RemovedException").build();

        List<Conflict> conflicts = checkConflicts(tempDir, configuration);

        assertThat(conflicts).hasSize(0);
    }

    @Test
    public void moving_inner_class_to_parent_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        new ChildClass().method(new ChildClass.InnerClass());
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends ParentClass {
                    public ChildClass() {}

                    public static class InnerClass {}

                    public void method(ChildClass.InnerClass clazz) {}
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ParentClass",
                // language=java
                """
                package com;
                public class ParentClass {}
                """);

        sources.transitiveAfterDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends ParentClass {
                    public ChildClass() {}

                    public void method(ParentClass.InnerClass clazz) {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.ParentClass",
                // language=java
                """
                package com;
                public class ParentClass {
                    public static class InnerClass {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/ChildClass$InnerClass");

        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(2);

        assertThat(conflicts).anySatisfy(c -> {
            assertThat(c.category()).isEqualTo(ConflictCategory.METHOD_SIGNATURE_NOT_FOUND);
            assertThat(c.dependency()).isInstanceOf(MethodDependency.class);
            MethodDependency methodDependency = (MethodDependency) c.dependency();
            assertThat(methodDependency.fromClass().className()).isEqualTo("com.BreakingClass");
            assertThat(methodDependency.fromMethod().method()).isEqualTo(voidMethod("<init>"));
            assertThat(methodDependency.targetClass().className()).isEqualTo("com.ChildClass");
            assertThat(methodDependency.targetMethod().method())
                    .isEqualTo(voidMethod("method", "Lcom/ChildClass$InnerClass;"));
        });

        assertThat(conflicts).anySatisfy(c -> {
            assertThat(c.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
            assertThat(c.dependency().targetClass().className()).isEqualTo("com.ChildClass$InnerClass");
        });
    }
}
