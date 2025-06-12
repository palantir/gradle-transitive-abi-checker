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
import com.palantir.abi.checker.datamodel.conflict.MethodDependency;
import com.palantir.abi.checker.datamodel.method.MethodDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Contains integration tests for the conflict checker related to class changes, such as renames, removals, etc.
 */
public class MethodConflictCheckerIntegrationTest extends BaseConflictCheckerIntegrationTest {

    @Test
    public void renaming_method_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        // Calling to create an actual ABI break at runtime for the java test
                        brokenMethod();
                    }
                    public void brokenMethod() {
                        new ClassWithAbiBreak().method();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void method() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void renamedMethod() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatMethodNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("brokenMethod"),
                "com.ClassWithAbiBreak",
                voidMethod("method"));

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.ClassWithAbiBreak.method()");
    }

    @Test
    public void removing_method_reference_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                import java.lang.Runnable;
                public class BreakingClass {
                    public BreakingClass() {
                        brokenMethod((new ClassWithAbiBreak())::method);
                    }
                    public void brokenMethod(Runnable r) {
                        r.run();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void method() {}
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

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.ClassWithAbiBreak.method()");

        assertThatMethodNotFound(
                tempDir, "com.BreakingClass", voidMethod("<init>"), "com.ClassWithAbiBreak", voidMethod("method"));
    }

    @Test
    public void adding_parameter_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        // Calling to create an actual ABI break at runtime for the java test
                        brokenMethod();
                    }
                    public void brokenMethod() {
                        new ClassWithAbiBreak().method();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void method() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void method(Object arg) {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatMethodNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("brokenMethod"),
                "com.ClassWithAbiBreak",
                voidMethod("method"));

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.ClassWithAbiBreak.method()");
    }

    @Test
    public void removing_parameter_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        // Calling to create an actual ABI break at runtime for the java test
                        brokenMethod();
                    }
                    public void brokenMethod() {
                        new ClassWithAbiBreak().method("test");
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void method(Object arg) {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void method() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatMethodNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("brokenMethod"),
                "com.ClassWithAbiBreak",
                voidMethod("method", "Ljava/lang/Object;"));

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.ClassWithAbiBreak.method(java.lang.Object)");
    }

    @Test
    public void updating_parameter_type_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        // Calling to create an actual ABI break at runtime for the java test
                        brokenMethod();
                    }
                    public void brokenMethod() {
                        new ClassWithAbiBreak().method("test");
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void method(String arg) {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    // Updating a parameter type, even in a source-compatible way, is a binary break
                    // See also: https://codefhtagn.blogspot.com/2010/11/java-binary-compatibility-more-than.html
                    public void method(Object arg) {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatMethodNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("brokenMethod"),
                "com.ClassWithAbiBreak",
                voidMethod("method", "Ljava/lang/String;"));

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.ClassWithAbiBreak.method(java.lang.String)");
    }

    @Test
    public void updating_return_type_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass {
                    public BreakingClass() {
                        // Calling to create an actual ABI break at runtime for the java test
                        brokenMethod();
                    }
                    public void brokenMethod() {
                        new ClassWithAbiBreak().method();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public Object method() {
                        return "test";
                    }
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    // Updating the return type, even in a source-compatible way, is a binary break
                    public String method() {
                        return "test";
                    }
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatMethodNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("brokenMethod"),
                "com.ClassWithAbiBreak",
                method("Ljava/lang/Object;", "method"));

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("java.lang.Object com.ClassWithAbiBreak.method()");
    }

    @Test
    public void adding_new_method_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.Dependency",
                // language=java
                """
                package com;
                public class Dependency {
                    public Dependency() {
                        new Transitive().method();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Transitive",
                // language=java
                """
                package com;
                public class Transitive {
                    public void method() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.Transitive",
                // language=java
                """
                package com;
                public class Transitive {
                    public void method() {}
                    public void method(String argument) {}
                }
                """);

        generateClassFiles(tempDir, sources.build());
        assertNoConflicts(tempDir);
    }

    @Test
    public void removing_method_on_parent_class_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass extends ClassWithAbiBreak {
                    public BreakingClass() {
                        // Calling to create an actual ABI break at runtime for the java test
                        brokenMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public class ClassWithAbiBreak {
                    public void brokenMethod() {}
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

        assertThatMethodNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("<init>"),
                // Because the method does not exist in the parent class anymore, we actually indicate
                //   the same class as the from class, which can be surprising, but correct
                "com.BreakingClass",
                voidMethod("brokenMethod"));

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.BreakingClass.brokenMethod()");
    }

    @Test
    public void removing_method_on_parent_interface_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.BreakingClass",
                // language=java
                """
                package com;
                public class BreakingClass implements ClassWithAbiBreak {
                    public BreakingClass() {
                        // Calling to create an actual ABI break at runtime for the java test
                        brokenMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public interface ClassWithAbiBreak {
                    default void brokenMethod() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.ClassWithAbiBreak",
                // language=java
                """
                package com;
                public interface ClassWithAbiBreak {}
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatMethodNotFound(
                tempDir,
                "com.BreakingClass",
                voidMethod("<init>"),
                // Because the method does not exist in the parent class anymore, we actually indicate
                //   the same class as the from class, which can be surprising, but correct
                "com.BreakingClass",
                voidMethod("brokenMethod"));

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.BreakingClass.brokenMethod()");
    }

    @Test
    public void using_method_from_parent_class_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends ParentClass {
                    public ChildClass() {
                        superMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ParentClass",
                // language=java
                """
                package com;
                public class ParentClass {
                    public void superMethod() {}
                }
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.ParentClass",
                // language=java
                """
                package com;
                public class ParentClass {
                    public void superMethod() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void using_method_from_super_parent_class_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass extends Parent {
                    public MyClass() {
                        method();
                    }
                }
                """);

        sources.unreachableDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent extends SuperParent {}
                """);

        sources.unreachableDependency(
                "com.SuperParent",
                // language=java
                """
                package com;
                public class SuperParent {
                    public void method() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void using_method_from_parent_interface_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass implements ParentInterface {
                    public ChildClass() {
                        superMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ParentInterface",
                // language=java
                """
                package com;
                public interface ParentInterface {
                    default void superMethod() {}
                }
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.ParentInterface",
                // language=java
                """
                package com;
                public interface ParentInterface {
                    default void superMethod() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void removing_method_from_parent_does_not_conflict_if_overridden() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        ChildClass field = new ChildClass();
                        field.overriddenMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {
                    public void overriddenMethod() {}
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {
                    public void overriddenMethod() {}
                }
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {
                    // Not actually an override anymore
                    public void overriddenMethod() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {}
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void removing_method_override_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        ChildClass field = new ChildClass();
                        field.overriddenMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {
                    public void overriddenMethod() {}
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {
                    public void overriddenMethod() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {}
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {
                    // Not actually overridden anymore
                    public void overriddenMethod() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void changing_method_override_covariant_return_type_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        ChildClass field = new ChildClass();
                        System.out.println(field.overriddenMethod());
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {
                    public CharSequence overriddenMethod() {
                        return "child";
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {
                    public CharSequence overriddenMethod() {
                        return new StringBuilder().append("super");
                    }
                }
                """);

        // This is a bit of a special case, since changing a method return type is typically an ABI break
        // However, in this specific case, because the method with the SAME return type still exists on a parent class,
        //   the generated bytecode will generate TWO overriddenMethod methods in ChildClass,
        //   one returning String and one returning CharSequence, which will call directly into the first one
        // The second one is a bridge method: https://docs.oracle.com/javase/tutorial/java/generics/bridgeMethods.html
        sources.transitiveAfterDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {
                    public String overriddenMethod() {
                        return "child";
                    }
                }
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {
                    public CharSequence overriddenMethod() {
                        return new StringBuilder().append("super");
                    }
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void static_method_call_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        TargetClass.staticMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public static void staticMethod() {}
                }
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public static void staticMethod() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void inherited_static_method_call_does_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        ChildClass.staticMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {
                    public static void staticMethod() {
                        System.out.println("child");
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {
                    public static void staticMethod() {
                        System.out.println("parent");
                    }
                }
                """);

        sources.transitiveAfterDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass extends Parent {}
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.Parent",
                // language=java
                """
                package com;
                public class Parent {
                    public static void staticMethod() {
                        System.out.println("parent");
                    }
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    // TODO(aldexis): Do not inherit static methods from interfaces
    @Disabled("This isn't actually working yet")
    @Test
    public void inherited_static_method_call_from_interface_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        ChildClass.staticMethod();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass implements ParentInterface {
                    public static void staticMethod() {
                        System.out.println("child");
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.ParentInterface",
                // language=java
                """
                package com;
                public interface ParentInterface {
                    public static void staticMethod() {
                        System.out.println("parent");
                    }
                }
                """);

        sources.transitiveAfterDependency(
                "com.ChildClass",
                // language=java
                """
                package com;
                public class ChildClass implements ParentInterface {}
                """);

        // Same as before
        sources.transitiveAfterDependency(
                "com.ParentInterface",
                // language=java
                """
                package com;
                public interface ParentInterface {
                    public static void staticMethod() {
                        System.out.println("parent");
                    }
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(NoSuchMethodError.class)
                .withMessageContaining("void com.ChildClass.staticMethod()");

        assertThatMethodNotFound(
                tempDir, "com.BreakingClass", voidMethod("<init>"), "com.ChildClass", voidMethod("staticMethod"));
    }

    @Test
    public void static_method_becoming_virtual_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        TargetClass.method();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public static void method() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public void method() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(IncompatibleClassChangeError.class)
                .withMessageContaining("Expected static method 'void com.TargetClass.method()'");

        assertThatMethodNotFound(tempDir, "com.MyClass", voidMethod("<init>"), "com.TargetClass", voidMethod("method"));
    }

    @Test
    public void virtual_method_becoming_static_conflicts() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        (new TargetClass()).method();
                    }
                }
                """);

        sources.transitiveBeforeDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public void method() {}
                }
                """);

        sources.transitiveAfterDependency(
                "com.TargetClass",
                // language=java
                """
                package com;
                public class TargetClass {
                    public static void method() {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertThatExceptionOfType(InvocationTargetException.class)
                .isThrownBy(() -> runClassFiles(tempDir))
                .havingCause()
                .isInstanceOf(IncompatibleClassChangeError.class)
                .withMessageContaining("Expecting non-static method 'void com.TargetClass.method()'");

        assertThatMethodNotFound(tempDir, "com.MyClass", voidMethod("<init>"), "com.TargetClass", voidMethod("method"));
    }

    @Test
    public void varargs_do_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;
                public class MyClass {
                    public MyClass() {
                        Target target = new Target();
                        // Shouldn't conflict no matter the number of arguments
                        target.invoke();
                        target.invoke("one");
                        target.invoke("one", 2);
                    }
                }
                """);

        sources.unreachableDependency(
                "com.Target",
                // language=java
                """
                package com;
                public class Target {
                    public final void invoke(Object... args) {}
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void method_handle_invoke_do_not_conflict() {
        JavaFiles.Builder sources = JavaFiles.builder();
        sources.reachableDependency(
                "com.MyClass",
                // language=java
                """
                package com;

                import java.lang.invoke.MethodHandle;
                import java.lang.invoke.MethodHandles;
                import java.lang.invoke.MethodType;

                public class MyClass {
                    public MyClass() {
                        try {
                            MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
                            MethodType mt = MethodType.methodType(String.class, String.class);
                            MethodHandle concatMH = publicLookup.findVirtual(String.class, "concat", mt);
                            (new Target()).func(concatMH);
                        } catch (Throwable t) {
                            throw new RuntimeException(t);
                        }
                    }
                }
                """);

        sources.unreachableDependency(
                "com.Target",
                // language=java
                """
                package com;

                import java.lang.invoke.MethodHandle;

                public class Target {
                    public final void func(MethodHandle handle) throws Throwable {
                       System.out.println((String) handle.invokeExact("x", "y"));
                    }
                }
                """);

        generateClassFiles(tempDir, sources.build());

        assertNoConflicts(tempDir);
    }

    @Test
    public void catching_no_such_method_does_not_conflict() {
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
                            field = (new Target()).removed();
                        } catch (NoSuchMethodError e) {
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
                    public String removed() {
                        return "present";
                    }
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

    private static void assertThatMethodNotFound(
            Path baseDir,
            String fromClass,
            MethodDescriptor fromMethod,
            String targetClass,
            MethodDescriptor targetMethod) {
        List<Conflict> conflicts = checkConflicts(baseDir);

        assertThat(conflicts).hasSize(1);
        Conflict conflict = conflicts.get(0);
        assertThat(conflict.category()).isEqualTo(ConflictCategory.METHOD_SIGNATURE_NOT_FOUND);
        assertThat(conflict.dependency()).isInstanceOf(MethodDependency.class);
        MethodDependency methodDependency = (MethodDependency) conflict.dependency();
        assertThat(methodDependency.fromClass().className()).isEqualTo(fromClass);
        assertThat(methodDependency.fromMethod().method()).isEqualTo(fromMethod);
        assertThat(methodDependency.targetClass().className()).isEqualTo(targetClass);
        assertThat(methodDependency.targetMethod().method()).isEqualTo(targetMethod);
    }
}
