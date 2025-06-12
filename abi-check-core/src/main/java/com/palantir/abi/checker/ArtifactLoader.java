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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.ArtifactName;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.classlocation.DirectoryBasedClassLocation;
import com.palantir.abi.checker.datamodel.classlocation.JarBasedClassLocation;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ArtifactLoader {
    private static final Pattern META_INF_VERSIONS_PATTERN = Pattern.compile("META-INF/versions/(\\d+)/");

    public Artifact load(Path location, ArtifactName artifactName) {
        return Artifact.builder()
                .name(artifactName)
                .classes(loadClasses(location))
                .build();
    }

    private Map<ClassTypeDescriptor, ClassLocation> loadClasses(Path location) {
        Map<ClassTypeDescriptor, ClassLocation> classes = loadClassesInternal(location);
        return classes == null ? Collections.emptyMap() : classes;
    }

    private Map<ClassTypeDescriptor, ClassLocation> loadClassesInternal(Path location) {
        File classesLocation = location.toFile();

        try {
            if (!classesLocation.exists() && classesLocation.getName().endsWith("classes/")) {
                // Note: It is _incredibly_ common to apply java-library to allProjects which
                // tricks gradle into believing that it has a valid output from classes, but it doesn't
                // and even worse the directory isn't even made. This is only an issue with project dependencies.
                return Collections.emptyMap();
            } else if (classesLocation.isDirectory()) {
                // Directory of class files, i.e. local project dependency
                return loadClassesFromDirectory(classesLocation.toPath());

            } else if (classesLocation.getName().endsWith(".jar")) {
                // Jar file, "traditional" dependency
                return loadClassesFromJar(classesLocation);
            } else {
                return Collections.emptyMap();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load artifact located at: " + classesLocation, e);
        }
    }

    @VisibleForTesting
    Map<ClassTypeDescriptor, ClassLocation> loadClassesFromDirectory(Path classesDirectory) throws IOException {
        ImmutableMap.Builder<ClassTypeDescriptor, ClassLocation> classes = ImmutableMap.builder();

        for (Path classFile : listClassFiles(classesDirectory)) {
            ClassTypeDescriptor descriptor =
                    TypeDescriptors.fromClassFilename(getClassFilePath(classFile, classesDirectory));
            classes.put(descriptor, DirectoryBasedClassLocation.of(descriptor, classFile));
        }

        return classes.buildOrThrow();
    }

    private static Set<Path> listClassFiles(Path classDirectory) throws IOException {
        try (Stream<Path> stream = Files.walk(classDirectory)) {
            return stream.filter(location -> location.toString().endsWith(".class"))
                    .collect(Collectors.toSet());
        }
    }

    private static String getClassFilePath(Path classFile, Path classesDirectory) {
        return classesDirectory.relativize(classFile).toString();
    }

    @VisibleForTesting
    Map<ClassTypeDescriptor, ClassLocation> loadClassesFromJar(File jarLocation) throws IOException {
        ImmutableMap.Builder<ClassTypeDescriptor, ClassLocation> classes = ImmutableMap.builder();

        try (JarFile jarFile = new JarFile(jarLocation)) {
            Map<ClassTypeDescriptor, JarEntry> classesToIndex =
                    getClassesForCurrentJavaVersion(Collections.list(jarFile.entries()));
            for (Entry<ClassTypeDescriptor, JarEntry> entry : classesToIndex.entrySet()) {
                ClassTypeDescriptor descriptor = entry.getKey();
                JarEntry jarEntry = entry.getValue();
                String classLocation = jarEntry.getName();
                classes.put(
                        descriptor,
                        new JarBasedClassLocation(descriptor, jarLocation.getAbsolutePath(), classLocation));
            }
        }

        return classes.buildOrThrow();
    }

    // Note: I attempted to re-author this logic using the "ModuleFinder" abstraction that shipped in Java 9
    //  given that we our lowest supported version is 17. However, that abstraction chokes on non-module JARs
    //  of which many of our dependencies do not declare. IMO it's just not a very robust way to abstract over
    //  multi-generational jar structures. Hence the more direct, and manual, handling of the Jar specification.
    //
    // Original comment:
    //  This is designed to handle Multi-Release JAR files, where there are class files for multiple
    //  versions of JVM in one jar.
    //  You don't want to end up trying to parse a new class file when running on an old JVM.
    //  https://openjdk.java.net/jeps/238
    private static Map<ClassTypeDescriptor, JarEntry> getClassesForCurrentJavaVersion(Iterable<JarEntry> entries) {
        // First categorize all the found class files by their target JVM
        // classFilesPerJavaVersion: target JVM version -> type descriptor -> JarEntry
        SortedMap<Integer, Map<ClassTypeDescriptor, JarEntry>> classFilesPerJavaVersion = new TreeMap<>();
        for (JarEntry entry : entries) {
            String fileFullName = entry.getName();
            if (fileFullName.endsWith(".class")) {
                Matcher matcher = META_INF_VERSIONS_PATTERN.matcher(fileFullName);
                final int targetJavaVersion;
                final String normalizedFileFullName;
                if (matcher.find()) {
                    targetJavaVersion = Integer.parseInt(matcher.group(1));
                    normalizedFileFullName = fileFullName.replaceAll(META_INF_VERSIONS_PATTERN.pattern(), "");
                } else {
                    // If a class file is not in the META-INF/versions directory, we should assume it is for
                    //   whichever JVM version the jar is targeting.
                    // We use 0 as the target, to ensure we will include the file below in the target vs current Java
                    //   version check
                    targetJavaVersion = 0;
                    normalizedFileFullName = fileFullName;
                }

                if (!classFilesPerJavaVersion.containsKey(targetJavaVersion)) {
                    classFilesPerJavaVersion.put(targetJavaVersion, new HashMap<>());
                }
                classFilesPerJavaVersion
                        .get(targetJavaVersion)
                        .put(TypeDescriptors.fromClassFilename(normalizedFileFullName), entry);
            }
        }

        // We have to figure out what JVM version we're running on.
        Integer currentJavaVersion;
        List<String> javaVersionElements =
                Splitter.on('.').splitToList(System.getProperty("java.version").replaceAll("\\-ea", ""));
        // Pre-Java 9 versions have a version string like "1.8.0_40"
        if (javaVersionElements.get(0).equals("1")) {
            currentJavaVersion = Integer.parseInt(javaVersionElements.get(1));
        } else {
            currentJavaVersion = Integer.parseInt(javaVersionElements.get(0));
        }

        // Start layering the class files from old JVM version to new and thus effectively override the
        // old files by the new ones.
        Map<ClassTypeDescriptor, JarEntry> selectedClassFiles = new HashMap<>();
        for (Map.Entry<Integer, Map<ClassTypeDescriptor, JarEntry>> entry : classFilesPerJavaVersion.entrySet()) {
            Integer targetJavaVersion = entry.getKey();
            if (targetJavaVersion > currentJavaVersion) {
                break;
            }
            Map<ClassTypeDescriptor, JarEntry> pathToClassfile = entry.getValue();
            selectedClassFiles.putAll(pathToClassfile);
        }

        return selectedClassFiles;
    }
}
