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

package com.palantir.gradle.abi.checker.util;

import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.file.FileSystemLocationProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;

/**
 * This type represents a resolved artifact (typically from the runtime classpath).
 *
 * It can either point to a local project dependency, using the classes directory path, or a third party jar.
 */
public interface ResolvedArtifactDefinition {
    @Input
    Property<String> getIdentifier();

    @Optional
    @InputDirectory
    DirectoryProperty getArtifactClassesPath();

    @Optional
    @InputFile
    RegularFileProperty getArtifactJarPath();

    @Internal
    default FileSystemLocationProperty<? extends FileSystemLocation> getArtifactPath() {
        if (getArtifactJarPath().isPresent() && getArtifactClassesPath().isPresent()) {
            throw new IllegalStateException("Only one of artifact jar path and classes path can be set - jar path: "
                    + getArtifactJarPath().get() + " / classes path : "
                    + getArtifactClassesPath().get());
        }
        if (getArtifactJarPath().isPresent()) {
            return getArtifactJarPath();
        }
        if (getArtifactClassesPath().isPresent()) {
            return getArtifactClassesPath();
        }
        throw new IllegalStateException("No artifact path is set");
    }

    default void setup(ResolvedArtifactResult resolvedArtifact) {
        this.getIdentifier().set(DependencyIdentifier.convertToSimpleIdentifier(resolvedArtifact));
        if (resolvedArtifact.getFile().isDirectory()) {
            this.getArtifactClassesPath().set(resolvedArtifact.getFile());
        } else {
            this.getArtifactJarPath().set(resolvedArtifact.getFile());
        }
    }
}
