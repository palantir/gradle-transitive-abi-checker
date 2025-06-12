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

package com.palantir.gradle.abi.checker.output;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.palantir.abi.checker.datamodel.conflict.Conflict;
import java.util.Collection;
import org.immutables.value.Value;

/**
 * This represents the output contents of the ABI checker task when there are conflicts that are detected.
 */
@Value.Immutable
@JsonSerialize(as = ImmutableConflictsOutputContents.class)
public interface ConflictsOutputContents extends OutputContents {
    @Override
    default String type() {
        return "conflicts";
    }

    Collection<Conflict> conflicts();

    static ConflictsOutputContents of(Collection<Conflict> conflicts) {
        return ImmutableConflictsOutputContents.builder().conflicts(conflicts).build();
    }
}
