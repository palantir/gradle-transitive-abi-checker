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
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import com.palantir.abi.checker.AbiCheckerClassLoader;
import com.palantir.abi.checker.ArtifactLoader;
import com.palantir.abi.checker.ConflictChecker;
import com.palantir.abi.checker.ConflictCheckerConfiguration;
import com.palantir.abi.checker.JdkModuleLoader;
import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.ArtifactName;
import com.palantir.abi.checker.datamodel.conflict.Conflict;
import com.palantir.abi.checker.datamodel.method.MethodDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.io.TempDir;

abstract class BaseConflictCheckerIntegrationTest {

    private static final String ROOT = "root";
    private static final String DEPENDENCY = "dependency";
    private static final String TRANSITIVE = "transitive";

    /**
     * This is a temporary directory in which the class files will be copied for the ArtifactLoader to load.
     * The directory will be cleaned up after the test.
     *
     * If you want to manually inspect the class files, you can remove the TempDir annotation and set it to e.g.
     *   Paths.get("build/tmp/abi-checker-test").
     */
    @TempDir
    public Path tempDir;

    protected static JavaFileObject file(String className, String source) {
        return JavaFileObjects.forSourceString(className, source);
    }

    /**
     * Generates class files for the provided sources. Specifically, it will generate in distinct folders:
     * - a project with a single class calling all classes in {@link JavaFiles#reachableDependencySources()}
     * - a direct dependency, which contains classes from {@link JavaFiles#reachableDependencySources()} and
     *     {@link JavaFiles#unreachableDependencySources()}
     * - a transitive dependency, with classes from {@link JavaFiles#transitiveBeforeSources()} for the initial
     *     compilation and eventually replacedby {@link JavaFiles#transitiveAfterSources()}
     *
     * We first compile all classes together, with the version of the transitive before changes, then we compile
     *   only the transitive classes from the updated sources and check for conflicts.
     *
     * The class files will be copied in the provided directory, each under their respective subdirectory.
     */
    protected static void generateClassFiles(Path baseDir, JavaFiles sourceFiles) {
        Compiler compiler = Compiler.javac();

        Compilation compilation = compiler.compile(sourceFiles.allBeforeSources());

        // We don't copy the classes for the transitive dependency since we want to replace them with the updated ones
        copyClassFiles(compilation, target(baseDir, DEPENDENCY), sourceFiles.reachableDependencySources());
        copyClassFiles(compilation, target(baseDir, DEPENDENCY), sourceFiles.unreachableDependencySources());
        copyClassFiles(compilation, target(baseDir, ROOT), sourceFiles.rootSources());

        if (!sourceFiles.transitiveAfterSources().isEmpty()) {
            Compilation compilation2 = compiler.compile(sourceFiles.transitiveAfterSources());

            copyClassFiles(compilation2, target(baseDir, TRANSITIVE), sourceFiles.transitiveAfterSources());
        }
    }

    /**
     * This will run the classes generated previously generated in baseDir (by calling {@link #generateClassFiles}).
     *
     * Specifically, it will load and instantiate each class in the root directory, assuming they each have
     *   no-argument constructors.
     */
    protected static void runClassFiles(Path baseDir)
            throws NoSuchMethodException, InvocationTargetException, InstantiationException, IllegalAccessException {
        Path rootDirectory = target(baseDir, ROOT);

        // Use one class loader with all of the available classes, so they can find each other
        try (URLClassLoader classLoader = URLClassLoader.newInstance(new URL[] {
            rootDirectory.toUri().toURL(),
            target(baseDir, DEPENDENCY).toUri().toURL(),
            target(baseDir, TRANSITIVE).toUri().toURL()
        })) {

            // Get all the class files in the root directory and instantiate them one by one
            List<Path> classFiles = classFiles(rootDirectory);
            for (Path classFile : classFiles) {
                String className = classFile.toString().replace("/", ".").replace(".class", "");
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    clazz.getConstructor().newInstance();
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException("Failed to load class " + className, e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load classes from " + rootDirectory, e);
        }
    }

    /**
     * Returns all the class files in the provided directory, using paths relative to it.
     */
    private static List<Path> classFiles(Path directory) {
        try (Stream<Path> fileStream = Files.walk(directory)) {

            return fileStream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".class"))
                    .map(directory::relativize)
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to load classes from " + directory, e);
        }
    }

    /**
     * Checks for conflicts for classes previously generated in baseDir by calling {@link #generateClassFiles}.
     *
     * This will load
     *   - the {@link #ROOT} directory's classes as the main project classes
     *   - the {@link #DEPENDENCY} directory's classes as the direct dependency
     *   - the {@link #TRANSITIVE} directory's classes as the transitive dependency, which might have conflicts
     */
    protected static List<Conflict> checkConflicts(Path baseDir) {
        Artifact root = loadArtifact(baseDir, ROOT);
        Artifact dependency = loadArtifact(baseDir, DEPENDENCY);
        Artifact transitive = loadArtifact(baseDir, TRANSITIVE);

        // Load the jdk as well, to get all runtime artifacts
        List<Artifact> artifacts = new ArrayList<>(getJdkArtifacts());
        artifacts.add(root);
        artifacts.add(dependency);
        artifacts.add(transitive);

        ConflictCheckerConfiguration configuration = ConflictCheckerConfiguration.builder()
                .addErrorArtifactPrefixes(DEPENDENCY)
                .build();

        // Use new loaders to avoid any caching between tests
        return ConflictChecker.checkWithEntryPoints(
                configuration,
                new AbiCheckerClassLoader(),
                artifacts,
                root.classes().values());
    }

    private static Path target(Path baseDir, String type) {
        return baseDir.resolve(type);
    }

    private static void copyClassFiles(Compilation compilation, Path targetDir, Set<JavaFileObject> sources) {
        sources.forEach(source -> {
            try {
                copyClassFile(compilation, targetDir, source);
            } catch (IOException e) {
                throw new RuntimeException("Unable to copy class file " + source.getName(), e);
            }
        });
    }

    /**
     * Gets a class file from the compilation results (which are in memory) and copies it to the target directory.
     */
    private static void copyClassFile(Compilation compilation, Path targetDir, JavaFileObject source)
            throws IOException {
        // The source's name will for instance be com/palantir/Test.java
        // We want to update it to com/palantir/Test.class
        String classFilePath = source.getName().substring(0, source.getName().length() - Kind.SOURCE.extension.length())
                + Kind.CLASS.extension;

        Optional<JavaFileObject> javaFileObject =
                compilation.generatedFile(StandardLocation.CLASS_OUTPUT, classFilePath);
        assertThat(javaFileObject).describedAs(classFilePath).isPresent();

        Path path = targetDir.resolve(classFilePath);
        Files.createDirectories(path);
        Files.copy(javaFileObject.get().openInputStream(), path, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Artifact loadArtifact(Path baseDir, String type) {
        // Use new loaders to avoid any caching between tests
        return new ArtifactLoader().load(target(baseDir, type), ArtifactName.of(type));
    }

    private static List<Artifact> getJdkArtifacts() {
        return new JdkModuleLoader().getJavaModuleArtifacts();
    }

    protected MethodDescriptor method(String returnType, String name, String... parameters) {
        return MethodDescriptor.of(returnType, name, parameters);
    }

    protected MethodDescriptor voidMethod(String name, String... parameters) {
        return method("V", name, parameters);
    }

    protected void assertNoConflicts(Path baseDir) {
        List<Conflict> conflicts = checkConflicts(baseDir);
        assertThat(conflicts).isEmpty();

        assertThatNoException().isThrownBy(() -> runClassFiles(baseDir));
    }
}
