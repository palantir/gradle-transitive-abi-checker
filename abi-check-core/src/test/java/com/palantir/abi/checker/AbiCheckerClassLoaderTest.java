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

import static com.palantir.abi.checker.ClassLoadingUtil.findClass;
import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.DeclaredClass;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

public class AbiCheckerClassLoaderTest {

    /** Simple test that load() doesn't blow up */
    @Test
    public void testLoad() throws Exception {
        final Path outputDir = FilePathHelper.getPath("build/classes");
        try (Stream<Path> fileStream = Files.walk(outputDir)) {
            File someClass = fileStream
                    .map(Path::toFile)
                    .filter(file -> file.isFile() && file.getName().endsWith(".class"))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no classfiles in " + outputDir + " ?"));

            FileInputStream inputStream = new FileInputStream(someClass);
            final DeclaredClass declaredClass = AbiCheckerClassLoader.loadInternal(inputStream);
            assertThat(declaredClass).isNotNull();
            assertThat(declaredClass.methods()).isNotEmpty();
        }
    }

    @Test
    public void testLoadJdk() {
        List<Artifact> artifacts = new JdkModuleLoader().getJavaModuleArtifacts();
        assertThat(artifacts).isNotEmpty();
        for (Artifact artifact : artifacts) {
            assertThat(artifact.classes()).describedAs(artifact.name().name()).isNotEmpty();
            artifact.classes().forEach((classTypeDescriptor, classLocation) -> {
                try (InputStream classInputStream = classLocation.openStream()) {
                    DeclaredClass declaredClass = AbiCheckerClassLoader.loadInternal(classInputStream);
                    if (classTypeDescriptor.className().equals("java.lang.Object")) {
                        assertThat(declaredClass.parents())
                                .describedAs(classTypeDescriptor.className())
                                .isEmpty();
                    } else {
                        assertThat(declaredClass.parents())
                                .describedAs(classTypeDescriptor.className())
                                .isNotEmpty();
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to parse class: " + classLocation, e);
                }
            });
        }
    }

    @Test
    public void shouldHandleLoadingOfType() throws Exception {
        try (FileInputStream inputStream = findClass(LdcLoadType.class)) {
            DeclaredClass loaded = AbiCheckerClassLoader.loadInternal(inputStream);

            assertThat(loaded.className().className()).contains("LdcLoadType");
        }
    }

    @Test
    public void shouldHandleLoadingOfArrayOfType() throws Exception {
        try (FileInputStream inputStream = findClass(LdcLoadArrayOfType.class)) {
            DeclaredClass loaded = AbiCheckerClassLoader.loadInternal(inputStream);

            assertThat(loaded.className().className()).contains("LdcLoadArrayOfType");
            assertThat(loaded.loadedClasses()).containsExactly(TypeDescriptors.fromClassName(Object.class.getName()));
        }
    }

    @Test
    public void shouldHandleLoadingOfArrayOfPrimitive() throws Exception {
        try (FileInputStream inputStream = findClass(LdcLoadArrayOfPrimitive.class)) {
            DeclaredClass loaded = AbiCheckerClassLoader.loadInternal(inputStream);

            assertThat(loaded.className().className()).contains("LdcLoadArrayOfPrimitive");
            assertThat(loaded.loadedClasses()).isEmpty();
        }
    }

    static class LdcLoadType {
        static void test() {
            System.out.println(FileInputStream.class.toString());
        }
    }

    static class LdcLoadArrayOfType {
        static void test() {
            System.out.println(Object[].class);
        }
    }

    static class LdcLoadArrayOfPrimitive {
        static void test() {
            System.out.println(long[].class);
        }
    }
}
