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

package com.palantir.gradle.abi.checker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;
import com.palantir.abi.checker.ConflictChecker;
import com.palantir.abi.checker.ConflictCheckerConfiguration;
import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.ArtifactName;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.conflict.Conflict;
import com.palantir.gradle.abi.checker.output.ConflictsOutputContents;
import com.palantir.gradle.abi.checker.output.OutputContents;
import com.palantir.gradle.abi.checker.output.UnexpectedFailureOutputContents;
import com.palantir.gradle.abi.checker.services.AbiCheckerBuildService;
import com.palantir.gradle.abi.checker.util.ResolvedArtifactDefinition;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.slf4j.Logger;

public abstract class TransitiveAbiCheckerTask extends DefaultTask {
    private static final Logger log = Logging.getLogger(TransitiveAbiCheckerTask.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    public static final String SKIP_NO_CLASS_MESSAGE =
            "Skipped due to absence of any class in main source set - use checkCompletely to run anyway";

    /**
     * The class files of the project being checked.
     */
    @InputFiles
    public abstract ConfigurableFileCollection getProjectClassFiles();

    /**
     * Contains the resolved artifacts for the project's runtime dependencies.
     * These can be either local project dependencies or third party jars.
     * The ordering is important as this will represent the runtime classpath.
     */
    @Nested
    public abstract ListProperty<ResolvedArtifactDefinition> getResolvedRuntimeClasspathArtifacts();

    /**
     * Contains all the dependencies that we know have a transitive with a version mismatch.
     */
    @Input
    public abstract SetProperty<String> getDependenciesToCheck();

    @OutputFile
    public abstract RegularFileProperty getErrorsOutputFile();

    @Nested
    public abstract Property<TransitiveAbiCheckerExtension> getCheckerExtension();

    @Internal
    public abstract Property<AbiCheckerBuildService> getAbiCheckerService();

    public TransitiveAbiCheckerTask() {
        setDescription("Checks the runtime classpath for ABI incompatibilities");

        // Skip the task entirely if there are no class files to check
        // Note that getProjectClassFiles might actually return the classes directory, rather than the files
        //   but it's expected to not exist if there are no classes, which can happen if the java plugin is applied
        //   to a project that doesn't have any java sources, or only test sources
        onlyIf(
                SKIP_NO_CLASS_MESSAGE,
                _ignored -> getProjectClassFiles().getFiles().stream().anyMatch(File::exists)
                        || getCheckerExtension().get().getCheckCompletely().get());
    }

    @TaskAction
    public final void checkAbiConflicts() {
        try {
            internalCheckAbiConflicts();

            overwriteFile(getErrorsOutputFile().get().getAsFile().toPath(), "{}");
        } catch (Exception e) {
            final OutputContents outputContents;

            if (e instanceof ConflictException conflictException) {
                outputContents = ConflictsOutputContents.of(conflictException.getConflicts());
            } else {
                // See comment in UnexpectedFailureOutputContents for why we're outputting non-conflict failures too
                outputContents = UnexpectedFailureOutputContents.of(e);
            }

            try {
                overwriteFile(
                        getErrorsOutputFile().get().getAsFile().toPath(), MAPPER.writeValueAsString(outputContents));
            } catch (JsonProcessingException ex) {
                RuntimeException jsonException = new RuntimeException("Failed to write exception to output file", ex);
                jsonException.addSuppressed(e);

                throw jsonException;
            }

            throw e;
        }
    }

    private void internalCheckAbiConflicts() {
        TransitiveAbiCheckerExtension extension = getCheckerExtension().get();
        // Acts as the "entry point" for analyzing what classes are reachable and thus worth validating
        List<Artifact> currentProjectArtifacts = getCurrentProjectArtifacts();
        Collection<ClassLocation> currentProjectClasses = currentProjectArtifacts.stream()
                .flatMap(artifact -> artifact.classes().values().stream())
                .collect(Collectors.toSet());

        boolean checkCompletely = extension.getCheckCompletely().get();
        // Allow a consumer to check the classes completely in cases where they know better
        if (currentProjectClasses.isEmpty() && !checkCompletely) {
            log.warn("Skipping ABI check, no source classes, see info log for details");
            log.info(
                    """

                    {} has the java-library plugin applied, but has no classes in its main sourceset.
                    This either indicates some form of misconfiguration i.e. overly applying the java-library to
                    'allProjects', or some sort of 'container' project intended for shaded dependencies etc.
                    If you are certain you need to check the ABI compatibility in this scenario add the following
                    to your task configuration:

                    transitiveAbiChecker {
                        checkCompletely = true
                    }

                    """,
                    getProject().getName());

            return;
        }

        // We only want to "check" against the dependencies of this project.
        List<Artifact> currentProjectRuntimeArtifacts = getRuntimeArtifacts();

        // Represents the complete classpath using the resolved dependencies for this project
        // This is one take on the classpath, which may not be the same as the one used to run the project
        //   which could cause issues if there are duplicate classes on the classpath
        // We have chosen to mimic the classpath as defined by
        // https://github.com/palantir/sls-packaging/blob/4a96288316281b6e4020fa410e351b91c27ca1ab/gradle-sls-packaging/src/main/java/com/palantir/gradle/dist/service/JavaServiceDistributionPlugin.java#L318-L325
        //   which relies on the runtime classpath configuration
        List<Artifact> jdkArtifacts =
                getAbiCheckerService().get().jdkModuleLoader().getJavaModuleArtifacts();
        List<Artifact> runtimeClasspath = ImmutableList.<Artifact>builder()
                // We need to include the JDK itself for ABI analysis, and it should always be first on the classpath
                .addAll(jdkArtifacts)
                // Include the current project classes, right after the jdk
                .addAll(currentProjectArtifacts)
                // Include all other runtime artifacts from the classpath
                // This set of artifacts purposely includes jars produced by other projects in this repo
                // since there are circular dependency cases where an external dep relies on an API produced
                // by this repo and incorrectly alerts on them :-/
                .addAll(currentProjectRuntimeArtifacts)
                .build();

        ConflictCheckerConfiguration configuration = ConflictCheckerConfiguration.builder()
                .from(extension.toConfiguration())
                // Don't analyze the jdk nor the current project
                .addAllIgnoredArtifactPrefixes(Stream.concat(jdkArtifacts.stream(), currentProjectArtifacts.stream())
                        .map(Artifact::name)
                        .map(ArtifactName::name)
                        .collect(Collectors.toSet()))
                .build();

        List<Conflict> conflicts = ConflictChecker.checkWithEntryPoints(
                configuration, getAbiCheckerService().get().classLoader(), runtimeClasspath, currentProjectClasses);

        if (!conflicts.isEmpty()) {
            String output = ConflictPrinter.outputConflicts(conflicts);
            throw new ConflictException(output, conflicts);
        }
    }

    private List<Artifact> getCurrentProjectArtifacts() {
        return getProjectClassFiles().getFiles().stream()
                .map(File::toPath)
                // There might be multiple source dirs - get the corresponding artifact for each one
                .map(sourceDirPath -> getAbiCheckerService()
                        .get()
                        .artifactLoader()
                        .load(sourceDirPath, ArtifactName.of(sourceDirPath.toString())))
                .collect(Collectors.toList());
    }

    private List<Artifact> getRuntimeArtifacts() {
        return getResolvedRuntimeClasspathArtifacts().get().stream()
                .map(result -> getAbiCheckerService()
                        .get()
                        .artifactLoader()
                        .load(
                                result.getArtifactPath().get().getAsFile().toPath(),
                                ArtifactName.of(result.getIdentifier().get())))
                .collect(Collectors.toList());
    }

    private static void overwriteFile(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());

            Files.writeString(file, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Error writing contents to file " + file, e);
        }
    }
}
