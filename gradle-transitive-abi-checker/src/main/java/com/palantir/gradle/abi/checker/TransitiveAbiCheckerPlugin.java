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

import com.palantir.gradle.abi.checker.services.AbiCheckerBuildService;
import com.palantir.gradle.abi.checker.util.ResolvedArtifactDefinition;
import java.util.List;
import java.util.stream.Collectors;
import org.gradle.api.NamedDomainObjectProvider;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.language.base.plugins.LifecycleBasePlugin;
import org.jetbrains.annotations.NotNull;

/**
 * This plugin is the core part which configures the task and extension on a subproject.
 * It should not be applied manually directly and instead is applied by {@link TransitiveAbiCheckerRootPlugin}.
 */
public class TransitiveAbiCheckerPlugin implements Plugin<Project> {

    @Override
    public final void apply(@NotNull Project subproject) {
        // This broad plugin check causes the checker to run on test only modules
        //  (i.e. only has src/test), however these are skipped by the check for an empty main source set
        if (!subproject.getPlugins().hasPlugin(JavaPlugin.class)) {
            throw new IllegalArgumentException(
                    "Cannot apply TransitiveAbiCheckerPlugin to a project without the Java plugin");
        }

        Provider<AbiCheckerBuildService> abiCheckerBuildService = subproject
                .getRootProject()
                .getGradle()
                .getSharedServices()
                .registerIfAbsent("abiCheckerBuildService", AbiCheckerBuildService.class);

        // The extension is intentionally scoped to the current project to allow for granular control
        TransitiveAbiCheckerExtension abiCheckerExtension =
                subproject.getExtensions().create("transitiveAbiChecker", TransitiveAbiCheckerExtension.class);

        TaskProvider<TransitiveAbiCheckerTask> abiCheckTask = subproject
                .getTasks()
                .register("checkAbiCompatibility", TransitiveAbiCheckerTask.class, task -> {
                    task.getErrorsOutputFile()
                            .set(subproject
                                    .getLayout()
                                    .getBuildDirectory()
                                    .file("abi-checker/abi-checker-conflicts.json"));

                    // Pass along per project configuration
                    task.getCheckerExtension().set(abiCheckerExtension);

                    NamedDomainObjectProvider<Configuration> runtimeClasspath =
                            subproject.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME);

                    // Pull the "main" source set so that we can use it for the entry point analysis
                    SourceSetContainer sourceSets = subproject.getExtensions().getByType(SourceSetContainer.class);
                    SourceSet mainSourceSet = sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME);

                    // This creates a dependency such that we can load the compiled class files
                    task.dependsOn(mainSourceSet.getClassesTaskName());
                    // Which we then pass to the plugin for loading the entry points
                    task.getProjectClassFiles()
                            .setFrom(mainSourceSet.getOutput().getClassesDirs());

                    setResolvedRuntimeArtifacts(subproject, task, runtimeClasspath);

                    task.getAbiCheckerService().set(abiCheckerBuildService);

                    task.usesService(abiCheckerBuildService);
                });

        // Run ABI checking as part of "check"
        subproject
                .getTasks()
                .named(LifecycleBasePlugin.CHECK_TASK_NAME)
                .configure(check -> check.dependsOn(abiCheckTask));
    }

    /**
     * Finds all the resolved artifacts for this project's runtime classpath.
     */
    private static void setResolvedRuntimeArtifacts(
            Project subproject,
            TransitiveAbiCheckerTask task,
            NamedDomainObjectProvider<Configuration> runtimeClasspath) {
        Provider<List<ResolvedArtifactResult>> resolvedArtifacts = runtimeClasspath.flatMap(r -> r.getIncoming()
                .artifactView(view -> view.attributes(attributeContainer -> attributeContainer.attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        subproject.getObjects().named(LibraryElements.class, LibraryElements.CLASSES))))
                .getArtifacts()
                .getResolvedArtifacts()
                .map(artifactResults -> artifactResults.stream()
                        // In some cases, the file might not exist
                        // This for instance can happen when we apply java-library to a project that doesn't in fact
                        //   have any java classes
                        // In this case, Gradle will believe there are classes located under the /classes directory,
                        //   when in fact there is nothing there
                        // Ultimately, since the file/directory doesn't exist, it wouldn't have any classes to load,
                        //   so it doesn't matter for the purpose of this analysis
                        .filter(resolvedArtifact -> resolvedArtifact.getFile().exists())
                        .collect(Collectors.toList())));

        // Because some dependencies are runtime-only, we also need to ensure that runtime-only project
        //   dependencies are also compiled first, as otherwise we'll try to read their classes without
        //   a dependency on their compileJava task, which gradle will complain about
        // Unfortunately, there seems to be a gradle bug that makes it not properly set up the dependencies from the
        //   properties below (possibly due to the nesting)
        // See also https://github.com/gradle/gradle/issues/13590
        task.dependsOn(resolvedArtifacts);

        // Note: We want to make sure to pass exactly the runtime classpath in the correct order to the gradle task
        //   so we can make sure we analyze the correct classes
        // This is among others the classpath ordering that ends up being used in
        // https://github.com/palantir/sls-packaging/blob/4a96288316281b6e4020fa410e351b91c27ca1ab/gradle-sls-packaging/src/main/java/com/palantir/gradle/dist/service/JavaServiceDistributionPlugin.java#L318-L325
        task.getResolvedRuntimeClasspathArtifacts().set(resolvedArtifacts.map(resolved -> resolved.stream()
                .map(resolvedArtifact -> {
                    ResolvedArtifactDefinition definition =
                            subproject.getObjects().newInstance(ResolvedArtifactDefinition.class);
                    definition.setup(resolvedArtifact);
                    return definition;
                })
                .collect(Collectors.toList())));
    }
}
