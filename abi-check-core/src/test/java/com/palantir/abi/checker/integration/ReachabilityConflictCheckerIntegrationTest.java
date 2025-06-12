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

import com.palantir.abi.checker.datamodel.conflict.Conflict;
import com.palantir.abi.checker.datamodel.conflict.Conflict.ConflictCategory;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ReachabilityConflictCheckerIntegrationTest extends BaseConflictCheckerIntegrationTest {

    @Test
    public void unreachable_classes_dont_create_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        // This class is not reachable, so even though it has an ABI break, it should not create a conflict
        sources.unreachableDependency(
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
        assertNoConflicts(tempDir);
    }

    @Test
    public void classes_reachable_only_through_other_reachable_classes_can_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.Reachable",
                // language=java
                """
                package com;
                public class Reachable {
                    public BreakingClass field = new BreakingClass();
                }
                """);

        // This class is reachable through Reachable, itself reachable from root, so it should conflict
        sources.unreachableDependency(
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
    public void classes_are_reachable_through_method_call() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.Reachable",
                // language=java
                """
                package com;
                public class Reachable {
                    public Reachable () {
                        // This should be considered as reaching BreakingClass
                        (new BreakingClass()).method();
                    }
                }
                """);

        // This class is reachable through Reachable, itself reachable from root, so it should conflict
        sources.unreachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public void method() {
                        new Removed();
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

        // No classes after, so it should conflict

        generateClassFiles(tempDir, sources.build());
        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.Removed");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/Removed");
    }

    @Test
    public void classes_are_reachable_through_method_reference() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.Reachable",
                // language=java
                """
                package com;
                public class Reachable {
                    public Reachable () {
                        // This should be considered as reaching BreakingClass
                        Runnable r = BreakingClass::method;
                        r.run();
                    }
                }
                """);

        // This class is reachable through Reachable, itself reachable from root, so it should conflict
        sources.unreachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public static void method() {
                        new Removed();
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

        // No classes after, so it should conflict

        generateClassFiles(tempDir, sources.build());
        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.Removed");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/Removed");
    }

    @Test
    public void classes_are_reachable_through_inheritance() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.Reachable",
                // language=java
                """
                package com;
                public class Reachable extends BreakingClass {}
                """);

        // This class is reachable through Reachable, itself reachable from root, so it should conflict
        sources.unreachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public static Removed field = new Removed();
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Removed",
                // language=java
                """
                package com;
                public class Removed {}
                """);

        // No classes after, so it should conflict

        generateClassFiles(tempDir, sources.build());
        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.Removed");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/Removed");
    }

    @Test
    public void classes_are_reachable_through_interface_inheritance() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.Reachable",
                // language=java
                """
                package com;
                public class Reachable implements BreakingClass {
                    public Reachable() {
                        method();
                    }
                }
                """);

        // This class is reachable through Reachable, itself reachable from root, so it should conflict
        sources.unreachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public interface BreakingClass {
                    default void method() {
                        new Removed();
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

        // No classes after, so it should conflict

        generateClassFiles(tempDir, sources.build());
        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.Removed");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/Removed");
    }

    @Test
    public void classes_are_reachable_through_field() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.Reachable",
                // language=java
                """
                package com;
                public class Reachable {
                    public Reachable () {
                        // This should be considered as reaching BreakingClass
                        System.out.println(BreakingClass.field);
                    }
                }
                """);

        // This class is reachable through Reachable, itself reachable from root, so it should conflict
        sources.unreachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public static Removed field = new Removed();
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Removed",
                // language=java
                """
                package com;
                public class Removed {}
                """);

        // No classes after, so it should conflict

        generateClassFiles(tempDir, sources.build());
        List<Conflict> conflicts = checkConflicts(tempDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.CLASS_NOT_FOUND);
        assertThat(conflict.dependency().targetClass().className()).isEqualTo("com.Removed");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoClassDefFoundError.class)
                .withMessageContaining("com/Removed");
    }
}
