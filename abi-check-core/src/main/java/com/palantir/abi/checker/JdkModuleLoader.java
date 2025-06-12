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

import com.google.common.collect.ImmutableList;
import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.ArtifactName;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.classlocation.JdkBasedClassLocation;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

// Note: This will not properly load JDK modules below version 9.
public final class JdkModuleLoader {

    private final AtomicReference<List<Artifact>> javaModuleArtifacts = new AtomicReference<>();

    public List<Artifact> getJavaModuleArtifacts() {
        return javaModuleArtifacts.updateAndGet(
                artifacts -> artifacts == null ? getJavaModuleArtifactsInternal() : artifacts);
    }

    private List<Artifact> getJavaModuleArtifactsInternal() {
        ImmutableList.Builder<Artifact> artifactBuilder = ImmutableList.builder();
        ModuleFinder systemModuleFinder = ModuleFinder.ofSystem();
        Set<ModuleReference> moduleReferences = systemModuleFinder.findAll();

        for (final ModuleReference moduleReference : moduleReferences) {
            final ModuleDescriptor descriptor = moduleReference.descriptor();
            final String moduleName = descriptor.name();

            try (ModuleReader reader = moduleReference.open()) {
                final ArtifactName name = ArtifactName.of(moduleName);
                Map<ClassTypeDescriptor, ClassLocation> classes = new HashMap<>();
                final List<String> readerList = reader.list()
                        .filter(className -> className.endsWith(".class"))
                        .filter(className -> !className.equals("module-info.class"))
                        .toList();

                // Some modules contain only a module-info.class file
                if (readerList.isEmpty()) {
                    continue;
                }

                for (String className : readerList) {
                    final Optional<URI> classUri = reader.find(className);
                    if (classUri.isEmpty()) {
                        continue;
                    }
                    ClassTypeDescriptor classDescriptor = TypeDescriptors.fromClassFilename(className);
                    classes.put(classDescriptor, new JdkBasedClassLocation(classDescriptor, classUri.get()));
                }
                artifactBuilder.add(
                        Artifact.builder().name(name).classes(classes).build());
            } catch (IOException e) {
                throw new RuntimeException("Failed to read module " + moduleName, e);
            }
        }

        return artifactBuilder.build();
    }
}
