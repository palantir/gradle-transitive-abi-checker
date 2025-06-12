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
import com.palantir.abi.checker.datamodel.conflict.FieldDependency;
import com.palantir.abi.checker.datamodel.method.MethodDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contains integration tests for the conflict checker related to class changes, such as renames, removals, etc.
 */
public class FieldConflictCheckerIntegrationTest extends BaseConflictCheckerIntegrationTest {

    @Test
    public void renaming_field_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        System.out.println(new ClassWithAbiBreak().field);
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public String field;
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public String renamedField;
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatFieldNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("<init>"),
                "com.ClassWithAbiBreak",
                "Ljava/lang/String;",
                "field");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchFieldError.class)
                .withMessageContaining("com.ClassWithAbiBreak")
                .withMessageContaining("java.lang.String field");
    }

    @Test
    public void removing_field_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        System.out.println(new ClassWithAbiBreak().field);
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public String field;
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {}
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatFieldNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("<init>"),
                "com.ClassWithAbiBreak",
                "Ljava/lang/String;",
                "field");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchFieldError.class)
                .withMessageContaining("com.ClassWithAbiBreak")
                .withMessageContaining("java.lang.String field");
    }

    @Test
    public void updating_field_type_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        System.out.println(new ClassWithAbiBreak().field);
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public Object field;
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    // Even if the type is source-compatible, this is still an ABI break
                    public String field;
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatFieldNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("<init>"),
                "com.ClassWithAbiBreak",
                "Ljava/lang/Object;",
                "field");

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchFieldError.class)
                .withMessageContaining("com.ClassWithAbiBreak")
                .withMessageContaining("java.lang.Object field");
    }

    @Test
    public void adding_new_field_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        System.out.println(new ClassWithAbiBreak().field);
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public Object field;
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public Object field;
                    public Object newField;
                }
                """);

        generateClassFiles(tempDir, sources.build());
        assertNoConflicts(tempDir);
    }

    @Test
    public void catching_no_such_field_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    String field;
                    public MyClass() {
                        try {
                            field = (new Target()).removed;
                        } catch (NoSuchFieldError e) {
                            field = null;
                        }
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Target",
                // language=java
                """
                package com;
                public class Target {
                    public String removed = "present";
                }
                """);

        sources.transitiveAfterDependency(
                "com.Target",
                // language=java
                """
                package com;
                public class Target {}
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void static_field_becoming_virtual_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        System.out.println(TargetClass.field);
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public static String field = "static";
                }
                """);

        sources.transitiveAfterDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public String field = "virtual";
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(IncompatibleClassChangeError.class)
                .withMessageContaining("Expected static field com.TargetClass.field");

        assertThatFieldNotFound(
                tempDir, "com.MyClass", voidMethod("<init>"), "com.TargetClass", "Ljava/lang/String;", "field");
    }

    @Test
    public void virtual_field_becoming_static_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        System.out.println((new TargetClass()).field);
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public String field = "virtual";
                }
                """);

        sources.transitiveAfterDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public static String field = "static";
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(IncompatibleClassChangeError.class)
                .withMessageContaining("Expected non-static field com.TargetClass.field");

        assertThatFieldNotFound(
                tempDir, "com.MyClass", voidMethod("<init>"), "com.TargetClass", "Ljava/lang/String;", "field");
    }

    private static void assertThatFieldNotFound(
            Path baseDir,
            String fromClass,
            MethodDescriptor fromMethod,
            String targetClass,
            String targetFieldType,
            String targetFieldName) {
        List<Conflict> conflicts = checkConflicts(baseDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.FIELD_NOT_FOUND);
        assertThat(conflict.dependency()).isInstanceOf(FieldDependency.class);

        FieldDependency fieldDependency = (FieldDependency) conflict.dependency();
        assertThat(fieldDependency.fromClass().className()).isEqualTo(fromClass);
        assertThat(fieldDependency.fromMethod().method()).isEqualTo(fromMethod);
        assertThat(fieldDependency.targetClass().className()).isEqualTo(targetClass);
        assertThat(fieldDependency.field().name()).isEqualTo(targetFieldName);
        assertThat(fieldDependency.field().type()).isEqualTo(TypeDescriptors.fromRaw(targetFieldType));
    }
}
