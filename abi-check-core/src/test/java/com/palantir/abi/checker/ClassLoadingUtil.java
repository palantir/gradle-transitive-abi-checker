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

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public final class ClassLoadingUtil {

    public static FileInputStream findClass(Class<?> aClass) throws Exception {
        final String name = aClass.getName().replace('.', '/') + ".class";
        final Path outputDir = FilePathHelper.getPath("build/classes");
        try (Stream<Path> fileStream = Files.walk(outputDir)) {
            List<File> files = fileStream
                    .map(Path::toFile)
                    .filter(file -> file.isFile() && file.getAbsolutePath().endsWith(name))
                    .toList();
            if (files.isEmpty()) {
                throw new IllegalStateException("no file matching " + aClass + " found in " + outputDir + " ?");
            }
            if (files.size() >= 2) {
                throw new IllegalStateException(
                        "too many files matching " + aClass + " found in " + outputDir + ": " + files);
            }
            return new FileInputStream(files.get(0));
        }
    }

    private ClassLoadingUtil() {}
}
