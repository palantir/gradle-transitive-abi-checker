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

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.palantir.abi.checker.datamodel.field.FieldReference;
import com.palantir.abi.checker.datamodel.method.CallSite;
import com.palantir.abi.checker.datamodel.method.DeclaredMethod;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.util.List;
import org.immutables.value.Value;

/**
 * Represents a dependency between a method and the field that it accesses, used in Conflict when
 * reporting problems.
 */
@Value.Immutable
@JsonSerialize(as = ImmutableFieldDependency.class)
public interface FieldDependency extends Dependency {

    @Override
    @Value.Derived
    default ClassTypeDescriptor targetClass() {
        return field().clazz();
    }

    FieldReference field();

    static FieldDependency of(
            DeclaredMethod method, CallSite<FieldReference> field, List<ClassTypeDescriptor> reachabilityPath) {
        return ImmutableFieldDependency.builder()
                .reachabilityPath(reachabilityPath)
                .fromMethod(method.reference())
                .fromLineNumber(field.lineNumber())
                .field(field.reference())
                .build();
    }
}
