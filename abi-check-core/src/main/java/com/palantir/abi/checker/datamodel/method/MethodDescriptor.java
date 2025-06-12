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

package com.palantir.abi.checker.datamodel.method;

import static java.util.stream.Collectors.toList;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.palantir.abi.checker.datamodel.types.TypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;
import org.immutables.value.Value;
import org.objectweb.asm.Type;

/**
 * Describes a method, not necessarily as tied to a specific class.
 *
 * See https://docs.oracle.com/javase/specs/jvms/se24/html/jvms-4.html#jvms-4.3.3.
 */
@Value.Immutable
@JsonSerialize(as = ImmutableMethodDescriptor.class)
public interface MethodDescriptor {

    TypeDescriptor returnType();

    String name();

    List<TypeDescriptor> parameterTypes();

    @JsonValue
    default String pretty() {
        return returnType().toString() + " " + name() + prettyParameters();
    }

    default String prettyWithoutReturnType() {
        return name() + prettyParameters();
    }

    default String prettyParameters() {
        StringJoiner joiner = new StringJoiner(", ", "(", ")");

        parameterTypes().stream().map(TypeDescriptor::toString).forEach(joiner::add);

        return joiner.toString();
    }

    static MethodDescriptor ofDescriptor(String descriptor, String name) {
        Type type = Type.getMethodType(descriptor);

        List<TypeDescriptor> params = Arrays.stream(type.getArgumentTypes())
                .map(Type::getDescriptor)
                .map(TypeDescriptors::fromRaw)
                .collect(toList());

        return builder()
                .returnType(TypeDescriptors.fromRaw(type.getReturnType().getDescriptor()))
                .name(name)
                .parameterTypes(params)
                .build();
    }

    static MethodDescriptor of(String returnType, String name, String... parameterTypes) {
        return of(
                TypeDescriptors.fromRaw(returnType),
                name,
                Stream.of(parameterTypes).map(TypeDescriptors::fromRaw).toList());
    }

    static MethodDescriptor of(TypeDescriptor returnType, String name, List<TypeDescriptor> parameterTypes) {
        return builder()
                .returnType(returnType)
                .name(name)
                .addAllParameterTypes(parameterTypes)
                .build();
    }

    static ImmutableMethodDescriptor.Builder builder() {
        return ImmutableMethodDescriptor.builder();
    }
}
