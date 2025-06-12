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
    public void try_catch_can_ignore_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public ClassWithAbiBreak field;
                    public BreakingClass() {
                        try {
                            field = new ClassWithAbiBreak();
                        } catch (NoClassDefFoundError e) {
                            // ignore
                        }
                    }
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
}
