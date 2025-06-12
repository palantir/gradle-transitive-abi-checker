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

import static org.assertj.core.api.Assertions.assertThat;

import com.google.common.collect.Maps;
import com.palantir.abi.checker.datamodel.DeclaredClass;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.field.FieldDescriptor;
import com.palantir.abi.checker.datamodel.field.FieldReference;
import com.palantir.abi.checker.datamodel.method.CallSite;
import com.palantir.abi.checker.datamodel.method.DeclaredMethod;
import com.palantir.abi.checker.datamodel.method.MethodDescriptor;
import com.palantir.abi.checker.datamodel.method.MethodReference;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import com.palantir.abi.checker.nested.ClassWithNestedClass;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class ArtifactLoaderTest {
    private static final MethodDescriptor methodOneDescriptor = MethodDescriptor.builder()
            .returnType(TypeDescriptors.fromRaw("V"))
            .name("methodOne")
            .parameterTypes(Collections.singletonList(TypeDescriptors.fromRaw("Ljava/lang/String;")))
            .build();
    private static final MethodDescriptor printlnDescriptor = MethodDescriptor.builder()
            .returnType(TypeDescriptors.fromRaw("V"))
            .name("println")
            .parameterTypes(Collections.singletonList(TypeDescriptors.fromRaw("Ljava/lang/String;")))
            .build();
    private static final MethodDescriptor internalStaticFieldAccessDescriptor = MethodDescriptor.builder()
            .returnType(TypeDescriptors.fromRaw("V"))
            .name("internalStaticFieldAccess")
            .build();
    private static final MethodDescriptor internalFieldAccessDescriptor = MethodDescriptor.builder()
            .returnType(TypeDescriptors.fromRaw("V"))
            .name("internalFieldAccess")
            .build();

    private static AbiCheckerClassLoader classLoader;
    private static ArtifactLoader artifactLoader;

    private static Map<ClassTypeDescriptor, DeclaredClass> jarClasses;

    @BeforeAll
    public static void beforeAll() throws IOException {
        classLoader = new AbiCheckerClassLoader();
        artifactLoader = new ArtifactLoader();
        jarClasses = Maps.transformValues(
                loadJarClasses(FilePathHelper.getPath("src/test/resources/ArtifactLoaderTest.jar")), classLoader::load);
    }

    /**
     * verify that the DeclaredClass.parents() set is actually populated with ClassTypeDescriptor
     * instances - other types might leak through due to asm's use of raw lists.
     */
    @Test
    public void testTypeOfClassParentsWhenInterfaces() throws IOException {
        final Map<ClassTypeDescriptor, ClassLocation> classes = loadTestClasses();

        final DeclaredClass classThatImplementsInterfaces = getDeclaredClass(
                classes, new ClassTypeDescriptor(this.getClass().getName()) + "$ExampleClassWithInterfaces");

        Set<ClassTypeDescriptor> parents = classThatImplementsInterfaces.parents();
        for (Object key : parents) {
            assertThat(key).isInstanceOf(ClassTypeDescriptor.class);
        }
    }

    // This is used implicitly in a test
    @SuppressWarnings("unused")
    private static final class ExampleClassWithInterfaces implements Serializable, Cloneable {
        // no fields needed, used by testTypeOfClassParentsWhenInterfaces above
    }

    @Test
    public void testLoadClass() {
        assertThat(jarClasses.get(TypeDescriptors.fromClassName("A")))
                .isNotNull()
                .describedAs("Artifact must contain class 'A'");
    }

    @Test
    public void testLoadMethod() {
        assertThat(jarClasses.get(TypeDescriptors.fromClassName("A")).methods().containsKey(methodOneDescriptor))
                .isTrue()
                .describedAs("Class must contain method with hairy signature");
    }

    @Test
    public void testLoadCall() {
        final DeclaredClass declaredClass = jarClasses.get(TypeDescriptors.fromClassName("A"));
        DeclaredMethod method = declaredClass.methods().get(methodOneDescriptor);
        CallSite<MethodReference> call = CallSite.of(
                MethodReference.of(TypeDescriptors.fromClassName("java/io/PrintStream"), printlnDescriptor, false),
                15,
                Set.of());
        assertThat(method.methodCalls().contains(call))
                .isTrue()
                .describedAs("Method must contain call to other method with hairy signature");
    }

    @Test
    public void testLoadField() {
        DeclaredClass loadedClass = jarClasses.get(TypeDescriptors.fromClassName("A"));
        FieldDescriptor myField = FieldDescriptor.of(TypeDescriptors.fromRaw("Ljava/lang/Object;"), "publicFieldOne");
        assertThat(loadedClass.fields().containsKey(myField))
                .isTrue()
                .describedAs("Class must contain field with hairy signature");
    }

    @Test
    public void testLoadStaticFieldAccess() {
        DeclaredMethod method =
                jarClasses.get(TypeDescriptors.fromClassName("A")).methods().get(internalStaticFieldAccessDescriptor);
        CallSite<FieldReference> access = accessedField("Ljava/lang/Object;", "staticFieldOne", "A", 11, true);
        assertThat(method.fieldAccesses().contains(access))
                .isTrue()
                .describedAs("Method must contain access to staticFieldOne: "
                        + method.fieldAccesses()
                        + " does not contain "
                        + access);
    }

    @Test
    public void testLoadFieldAccess() {
        DeclaredMethod method =
                jarClasses.get(TypeDescriptors.fromClassName("A")).methods().get(internalFieldAccessDescriptor);
        CallSite<FieldReference> access = accessedField("Ljava/lang/Object;", "publicFieldOne", "A", 12, false);
        assertThat(method.fieldAccesses().contains(access))
                .isTrue()
                .describedAs("Method must contain access to staticFieldOne: "
                        + method.fieldAccesses()
                        + " does not contain "
                        + access);
    }

    @Test
    public void testLoadParent() {
        assertThat(jarClasses.get(TypeDescriptors.fromClassName("A")).parents())
                .containsExactlyInAnyOrderElementsOf(
                        Collections.singleton(TypeDescriptors.fromClassName("java/lang/Object")));
    }

    /**
     * Verify that the asm jar can be loaded without exceptions by ArtifactLoader.
     *
     * <p>This test caught bugs where ArtifactLoader treated MethodSignature as the thing that was
     * unique within a classfile, whereas the actually unique thing is the MethodDescriptor (combining
     * the ReturnDescriptor and ParameterDescriptor list).
     */
    @Test
    public void testLoadAsmJar() throws Exception {
        final Map<ClassTypeDescriptor, ClassLocation> loadedClasses =
                loadJarClasses(FilePathHelper.getPath("src/test/resources/asm-5.0.4.jar"));

        // asm.jar is known to have 25 classes in it
        assertThat(loadedClasses).hasSize(25);
    }

    @Test
    public void testLoadBouncyCastleJar() throws Exception {
        final Map<ClassTypeDescriptor, ClassLocation> loadedClasses =
                loadJarClasses(FilePathHelper.getPath("src/test/resources/bcprov-jdk15on-1.68.jar"));

        String currentJavaVersion = System.getProperty("java.version");
        if (currentJavaVersion.startsWith("1.8.")) {
            assertThat(loadedClasses).hasSize(3604);
        } else if (currentJavaVersion.startsWith("11.")) {
            assertThat(loadedClasses).hasSize(3607);
        } else {
            assertThat(loadedClasses).hasSizeGreaterThan(0);
        }
    }

    @Test
    public void testLoadFromDirectory() throws Exception {
        final Map<ClassTypeDescriptor, ClassLocation> classes = loadTestClasses();
        assertThat(classes)
                .overridingErrorMessage("Loading classes from a directory should be supported")
                .isNotEmpty()
                // test that a class known to be in this directory exists in the map
                .containsKey(TypeDescriptors.fromClassName(ClassWithNestedClass.class.getName()));
    }

    @Test
    public void testNestedClassesNamedConsistenly() throws Exception {
        final Map<ClassTypeDescriptor, ClassLocation> classes = loadTestClasses();

        final DeclaredClass theClass =
                getDeclaredClass(classes, new ClassTypeDescriptor(ClassWithNestedClass.class.getName()).toString());

        final MethodDescriptor fooMethodDescriptor = theClass.methods().keySet().stream()
                .filter(descriptor -> descriptor.name().equals("foo"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("foo method missing?"));

        final DeclaredMethod fooMethod = theClass.methods().get(fooMethodDescriptor);

        String nestedClassName = fooMethod.methodCalls().stream()
                .map(CallSite::owner)
                .map(TypeDescriptor::toString)
                .filter(name -> name.contains("NestedClass"))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("call to NestedClass.bar missing?"));

        // make sure that the classMap contains an entry for the nestedClassName
        assertThat(classes).containsKey(TypeDescriptors.fromClassName(nestedClassName));
    }

    private DeclaredClass getDeclaredClass(Map<ClassTypeDescriptor, ClassLocation> classes, String className) {
        final ClassTypeDescriptor key = TypeDescriptors.fromClassName(className);
        assertThat(classes).containsKey(key);

        return classLoader.load(classes.get(key));
    }

    private static Map<ClassTypeDescriptor, ClassLocation> loadJarClasses(Path jarPath) throws IOException {
        return artifactLoader.loadClassesFromJar(jarPath.toFile());
    }

    private static Map<ClassTypeDescriptor, ClassLocation> loadTestClasses() throws IOException {
        return artifactLoader.loadClassesFromDirectory(FilePathHelper.getPath("build/classes/java/test"));
    }

    private static CallSite<FieldReference> accessedField(
            String desc, String name, String owner, int lineNumber, boolean isStatic) {
        return CallSite.of(
                FieldReference.of(TypeDescriptors.fromClassName(owner), TypeDescriptors.fromRaw(desc), name, isStatic),
                lineNumber,
                Set.of());
    }
}
