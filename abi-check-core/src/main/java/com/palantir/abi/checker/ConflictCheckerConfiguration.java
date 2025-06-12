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

package com.palantir.abi.checker;

import com.google.common.collect.ImmutableSet;
import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.ArtifactName;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.util.Locale;
import java.util.Set;
import org.immutables.value.Value;

@Value.Immutable
public interface ConflictCheckerConfiguration {
    /**
     * Used for prefix matching artifacts to promote to an error when a conflict is discovered.
     * <p>
     * Specificity can be dialed up to a fully qualified Maven coordinate.
     * <p>
     * Note: If an artifact matches this configuration and the {@link #getIgnoredArtifactPrefixes()} configuration, then
     * the ignored setting wins. The reasoning being that enablement is likely wide while ignoring is likely targeted.
     */
    Set<String> getErrorArtifactPrefixes();

    /**
     * Used for prefix matching artifacts to ignore.
     * <p>
     * Specificity can be dialed up to a fully qualified Maven coordinate.
     * <p>
     * Note: If an artifact matches this configuration and the {@link #getErrorArtifactPrefixes()} configuration, then
     * this setting wins. The reasoning being that enablement is likely wide while ignoring is likely targeted.
     */
    Set<String> getIgnoredArtifactPrefixes();

    /**
     * Used for prefix matching on classes to ignore.
     */
    Set<String> getIgnoredClassPrefixes();

    /**
     * Used to match classnames by case-insensitive keyword.
     */
    Set<String> getIgnoredClassnameKeywords();

    @Value.Derived
    default Set<String> getLowercaseIgnoredClassnameKeywords() {
        return getIgnoredClassnameKeywords().stream().map(String::toLowerCase).collect(ImmutableSet.toImmutableSet());
    }

    /**
     * Signals to the checker to ignore the current modules "entry point classes" and instead "completely check"
     * all artifacts that match the above filtering.
     */
    @Value.Default
    default Boolean getCheckCompletely() {
        return false;
    }

    /**
     * Determines if the given {@link Artifact} should be analyzed for ABI conflicts.
     */
    default boolean shouldIgnoreArtifact(ArtifactName artifact) {
        String artifactName = artifact.name();

        // Ignored artifacts take precedence
        boolean isIgnored = getIgnoredArtifactPrefixes().stream().anyMatch(artifactName::startsWith);
        if (isIgnored) {
            return true;
        }

        boolean shouldAnalyze = getErrorArtifactPrefixes().isEmpty()
                || getErrorArtifactPrefixes().stream().anyMatch(artifactName::startsWith);
        return !shouldAnalyze;
    }

    /**
     * Determines if the given class should be ignored based on the configuration.
     */
    default boolean shouldIgnoreClass(ClassTypeDescriptor classTypeDescriptor) {
        return shouldIgnoreClass(classTypeDescriptor.className());
    }

    default boolean shouldIgnoreClass(String className) {
        boolean ignoredByPrefix = getIgnoredClassPrefixes().stream().anyMatch(className::startsWith);
        boolean ignoredByKeyword = getLowercaseIgnoredClassnameKeywords().stream()
                .anyMatch(keyword -> className.toLowerCase(Locale.ROOT).contains(keyword));
        return ignoredByPrefix || ignoredByKeyword;
    }

    static ImmutableConflictCheckerConfiguration.Builder builder() {
        return ImmutableConflictCheckerConfiguration.builder();
    }
}
