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

import com.google.common.collect.Maps;
import com.palantir.abi.checker.datamodel.conflict.Conflict;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ConflictPrinter {

    /**
     * This outputs conflicts in the following format:
     *  "dependency that contains the break"
     *    |- "transitive that broke its ABI"
     *    |   |- "class that broke"
     *    |   |   |- "path to reach class"
     *    |   |   |- "conflict reason"
     *    |   |   |   |- "method/line that broke"
     *    |   |   |   \- "method/line that broke"
     *    |   |   |- ...
     *    |   |- ...
     *    |- ...
     *  etc...
     */
    public static String outputConflicts(Collection<Conflict> conflicts) {
        StringBuilder sb = new StringBuilder();

        // First output the dependency library that broke (this might be unknown for classes not found)
        final SortedMap<String, List<Conflict>> byBrokenDependency =
                groupByKeySorted(conflicts, c -> c.usedBy().name());

        outputRecommendations(sb, byBrokenDependency);

        byBrokenDependency.forEach((artifactName, conflictsForTransitive) -> {
            sb.append("Breaks found in: " + artifactName + "\n");

            // Then output the conflicts related to that transitive
            outputConflictsForBrokenDependency(sb, conflictsForTransitive);
            sb.append("===========================\n\n");
        });

        return sb.toString();
    }

    private static void outputRecommendations(StringBuilder sb, SortedMap<String, List<Conflict>> byBrokenDependency) {
        sb.append("ABI Incompatibilities were detected between the following libraries. ");
        sb.append("You should upgrade or downgrade one for each pair. Exact conflicts are detailed below.\n\n");

        final SortedMap<String, List<String>> incompatibleDependencies =
                Maps.transformValues(byBrokenDependency, conflicts -> conflicts.stream()
                        .map(c -> c.existsIn().name())
                        .distinct()
                        .toList());

        incompatibleDependencies.forEach((dependency, brokenTransitives) -> {
            if (brokenTransitives.contains(Conflict.UNKNOWN_ARTIFACT_NAME_STRING)) {
                sb.append("\t" + dependency + " refers to unknown classes, "
                        + "so we couldn't determine the transitive library that broke its ABI.\n");
            }
            List<String> knownBrokenTransitives = brokenTransitives.stream()
                    .filter(brokenTransitive -> !brokenTransitive.equals(Conflict.UNKNOWN_ARTIFACT_NAME_STRING))
                    .toList();

            if (knownBrokenTransitives.isEmpty()) {
                sb.append("\n");
                return;
            }
            sb.append("\t" + dependency);
            if (knownBrokenTransitives.size() == 1) {
                sb.append(" with " + knownBrokenTransitives.get(0));
            } else {
                sb.append(" with:\n");
                knownBrokenTransitives.forEach(transitive -> sb.append("\t\t" + transitive));
            }
            sb.append("\n\n");
        });
    }

    /**
     * Outputs the conflicts relating to a given transitive library, by grouping them by library that has failures
     *   due to the break and outputting a section for each.
     */
    private static void outputConflictsForBrokenDependency(
            StringBuilder sb, List<Conflict> conflictsInBrokenDependency) {
        final Map<String, List<Conflict>> byTransitive =
                groupByKeySorted(conflictsInBrokenDependency, c -> c.existsIn().name());
        forEachKnownLast(byTransitive, (dependencyName, conflictsInDependency, isLastDep) -> {
            sb.append("  " + indentCharItem(isLastDep) + "- Using transitive: " + dependencyName + "\n");

            String indentDep = "  " + indentCharSubItems(isLastDep);

            outputConflictsByClassForDependency(sb, indentDep, conflictsInDependency);
        });
    }

    /**
     * Outputs the conflicts that are pertaining to a given dependency, by grouping them by originating class.
     */
    private static void outputConflictsByClassForDependency(
            StringBuilder sb, String indentPrefix, List<Conflict> conflictsInDependency) {
        final Map<String, List<Conflict>> byClassName = groupByKeySorted(
                conflictsInDependency, c -> c.dependency().fromClass().toString());

        forEachKnownLast(byClassName, (classDesc, conflictsInClass, isLastClass) -> {
            sb.append(indentPrefix + "  " + indentCharItem(isLastClass) + "- In class: " + classDesc + "\n");

            String indentClass = indentPrefix + "  " + indentCharSubItems(isLastClass);

            // Indicate the path we used to reach the class
            // Note: this isn't perfect, since class reachability doesn't mean all methods are used,
            //   but it's the best we have right now
            // We also assume the same path for all conflicts (which should be the case based on current code)
            outputPathForClass(
                    sb, indentClass, conflictsInClass.get(0).dependency().reachabilityPath());

            outputConflictsForClass(sb, indentClass, conflictsInClass);
        });
    }

    /**
     * Outputs the reachability path we've determined for a given class.
     */
    private static void outputPathForClass(
            StringBuilder sb, String indentPrefix, List<ClassTypeDescriptor> reachabilityPath) {
        String path = String.join(
                "\n" + indentPrefix + "  |    -> ",
                reachabilityPath.stream().map(ClassTypeDescriptor::toString).toList());
        sb.append(indentPrefix + "  |  With path:\n");
        sb.append(indentPrefix + "  |    *  " + path + "\n");
    }

    /**
     * Outputs the conflict for a given class, by grouping them by reason, then outputting the different callsites.
     */
    private static void outputConflictsForClass(
            StringBuilder sb, String indentPrefix, List<Conflict> conflictsInClass) {
        // Output each conflict (e.g. class X not found / method Y not found / etc)
        final Map<String, List<Conflict>> byConflictReason = groupByKeySorted(conflictsInClass, Conflict::reason);

        forEachKnownLast(byConflictReason, (reason, conflictsForReason, isLastReason) -> {
            // Add an extra line before each new reason, to space a bit more and make the output more
            // readable
            sb.append(indentPrefix + "  |\n");
            sb.append(indentPrefix + "  " + indentCharItem(isLastReason) + "- " + reason + "\n");

            String indentReason = indentPrefix + "  " + indentCharSubItems(isLastReason);

            outputCallsitesForConflictReasons(sb, indentReason, conflictsForReason);
        });
    }

    /**
     * Outputs the different callsites for a given conflict reason.
     */
    private static void outputCallsitesForConflictReasons(
            StringBuilder sb, String indentPrefix, List<Conflict> conflictsForReason) {
        // For each conflict, show the methods and lines that are breaking
        Map<String, List<Conflict>> byCallSite = groupByKeySorted(
                conflictsForReason, c -> c.dependency().fromMethod().method().pretty());
        forEachKnownLast(byCallSite, (callSite, callSiteConflicts, isLastCallSite) -> {
            List<String> conflictLineNumbers = callSiteConflicts.stream()
                    .map(c -> c.dependency().fromLineNumber())
                    .sorted()
                    .distinct()
                    .map(String::valueOf)
                    .toList();
            sb.append(indentPrefix + "  " + indentCharItem(isLastCallSite) + "- In " + callSite + " (line "
                    + String.join(", ", conflictLineNumbers) + ")\n");
        });
    }

    /**
     * Returns a sorted map of the values grouped by the key, and sorted by the key.
     * Lets us output in a somewhat more consistent manner, which is nice for readability.
     */
    private static <K, V> SortedMap<K, List<V>> groupByKeySorted(Collection<V> values, Function<V, K> keyMapper) {
        // Use a tree map to sort the conflicts by artifact name
        return values.stream().collect(Collectors.groupingBy(keyMapper, TreeMap::new, Collectors.toList()));
    }

    @FunctionalInterface
    interface TriConsumer<U, V, W> {
        @SuppressWarnings("checkstyle:ParameterName")
        void accept(U u, V v, W w);
    }

    private static <K, V> void forEachKnownLast(Map<K, V> map, TriConsumer<K, V, Boolean> consumer) {
        int size = map.size();
        int index = 0;
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (index == size - 1) {
                consumer.accept(entry.getKey(), entry.getValue(), true);
            } else {
                consumer.accept(entry.getKey(), entry.getValue(), false);
            }
            index++;
        }
    }

    private static String indentCharItem(boolean isLastItem) {
        return isLastItem ? "\\" : "|";
    }

    private static String indentCharSubItems(boolean isLastItem) {
        return isLastItem ? " " : "|";
    }

    private ConflictPrinter() {
        // cannot instantiate
    }
}
