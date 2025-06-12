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

package com.palantir.gradle.abi.checker.services;

import com.palantir.abi.checker.AbiCheckerClassLoader;
import com.palantir.abi.checker.ArtifactLoader;
import com.palantir.abi.checker.JdkModuleLoader;
import org.gradle.api.services.BuildService;
import org.gradle.api.services.BuildServiceParameters;

/**
 * Build service used to shared the various top-level classes that are needed to run the ABI checker.
 *
 * These classes contain caches, which allow us to avoid doing duplicative work across multiple tasks (such as loading
 *   the same JDK, artifact or classes multiple times).
 */
public abstract class AbiCheckerBuildService implements BuildService<BuildServiceParameters.None> {

    private final AbiCheckerClassLoader classLoader = new AbiCheckerClassLoader();
    private final ArtifactLoader artifactLoader = new ArtifactLoader();
    private final JdkModuleLoader jdkModuleLoader = new JdkModuleLoader();

    public final AbiCheckerClassLoader classLoader() {
        return classLoader;
    }

    public final ArtifactLoader artifactLoader() {
        return artifactLoader;
    }

    public final JdkModuleLoader jdkModuleLoader() {
        return jdkModuleLoader;
    }
}
