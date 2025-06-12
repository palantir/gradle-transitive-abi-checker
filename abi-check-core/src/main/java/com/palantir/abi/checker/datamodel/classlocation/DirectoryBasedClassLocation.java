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

package com.palantir.abi.checker.datamodel.classlocation;

import com.google.common.base.Preconditions;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Represents a class file that is located in a directory.
 *
 * We're keeping both references to the class directory and the class location separate, so that the strings
 *   are shared in memory, since these locations are going to be retained in memory for pretty much the entire run.
 */
public record DirectoryBasedClassLocation(
        ClassTypeDescriptor classTypeDescriptor, String classDirectory, String classLocation) implements ClassLocation {
    @Override
    public ClassTypeDescriptor className() {
        return classTypeDescriptor;
    }

    @Override
    public InputStream openStream() throws IOException {
        File classfile = Paths.get(classDirectory, classLocation).toFile();
        return new FileInputStream(classfile);
    }

    public static DirectoryBasedClassLocation of(ClassTypeDescriptor classTypeDescriptor, Path classFile) {
        Path parent = Preconditions.checkNotNull(classFile.getParent(), "Class file must have a parent directory");
        String classDirectory = parent.toAbsolutePath().toString();
        String classLocation = classFile.getFileName().toString();
        return new DirectoryBasedClassLocation(classTypeDescriptor, classDirectory, classLocation);
    }
}
