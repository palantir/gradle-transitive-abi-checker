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

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * Root plugin which applies the core plugin to all relevant subprojects.
 * It itself needs to be applied to the root project.
 *
 * Doing it in this way lets users configure the extension with defaults by doing (e.g. through a wrapper plugin)
 *   subProject.plugins.withType(TransitiveAbiCheckerPlugin.class) { ... }
 */
public class TransitiveAbiCheckerRootPlugin implements Plugin<Project> {

    static final String ROOT_ERROR_MESSAGE = "The 'com.palantir.transitive-abi-checker' plugin has to be applied "
            + "in your root project's build.gradle";

    @Override
    public final void apply(@NotNull Project appliedProject) {
        if (!appliedProject.equals(appliedProject.getRootProject())) {
            throw new IllegalArgumentException(ROOT_ERROR_MESSAGE);
        }

        appliedProject.subprojects(subproject -> {
            subproject.getPlugins().withType(JavaPlugin.class, _p -> {
                subproject.getPlugins().apply(TransitiveAbiCheckerPlugin.class);
            });
        });
    }
}
