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

package com.palantir.abi.checker.datamodel.conflict;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Preconditions;
import com.palantir.abi.checker.datamodel.ArtifactName;
import javax.annotation.Nullable;
import org.immutables.value.Value;

@Value.Immutable
@JsonSerialize(as = ImmutableConflict.class)
public interface Conflict {
    String UNKNOWN_ARTIFACT_NAME_STRING = "<unknown>";
    ArtifactName UNKNOWN_ARTIFACT_NAME = ArtifactName.of(UNKNOWN_ARTIFACT_NAME_STRING);

    Dependency dependency();

    ArtifactName existsIn();

    ArtifactName usedBy();

    ConflictCategory category();

    @JsonIgnore
    @Value.Derived
    default String reason() {
        switch (category()) {
            case CLASS_NOT_FOUND:
                return "Class not found: " + dependency().targetClass();
            case METHOD_SIGNATURE_NOT_FOUND:
                Preconditions.checkState(
                        dependency() instanceof MethodDependency,
                        "Method signature not found should only be used with MethodDependency");
                return "Method not found: "
                        + ((MethodDependency) dependency()).targetMethod().pretty();
            case FIELD_NOT_FOUND:
                Preconditions.checkState(
                        dependency() instanceof FieldDependency,
                        "Field not found should only be used with FieldDependency");
                return "Field not found: "
                        + ((FieldDependency) dependency()).field().pretty();
            default:
                throw new IllegalStateException("Unknown conflict category: " + category());
        }
    }

    static Conflict classNotFound(Dependency dependency, ArtifactName usedBy, @Nullable ArtifactName existsIn) {
        return conflict(ConflictCategory.CLASS_NOT_FOUND, dependency, usedBy, existsIn);
    }

    static Conflict methodNotFound(MethodDependency dependency, ArtifactName usedBy, @Nullable ArtifactName existsIn) {
        return conflict(ConflictCategory.METHOD_SIGNATURE_NOT_FOUND, dependency, usedBy, existsIn);
    }

    static Conflict fieldNotFound(FieldDependency dependency, ArtifactName usedBy, @Nullable ArtifactName existsIn) {
        return conflict(ConflictCategory.FIELD_NOT_FOUND, dependency, usedBy, existsIn);
    }

    static Conflict conflict(
            ConflictCategory category, Dependency dependency, ArtifactName usedBy, @Nullable ArtifactName existsIn) {
        return ImmutableConflict.builder()
                .category(category)
                .dependency(dependency)
                .usedBy(usedBy)
                .existsIn(existsIn == null ? UNKNOWN_ARTIFACT_NAME : existsIn)
                .build();
    }

    enum ConflictCategory {
        CLASS_NOT_FOUND,
        METHOD_SIGNATURE_NOT_FOUND,
        FIELD_NOT_FOUND,
        ;
    }
}
