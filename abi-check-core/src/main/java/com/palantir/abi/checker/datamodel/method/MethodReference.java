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

package com.palantir.abi.checker.datamodel.method;

import com.fasterxml.jackson.annotation.JsonValue;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;

/**
 * Represents a reference to a specific method in a given class.
 */
public record MethodReference(ClassTypeDescriptor clazz, MethodDescriptor method, boolean isStatic)
        implements Reference {

    public String pretty() {
        return method.returnType() + " " + clazz + "." + method.prettyWithoutReturnType();
    }

    @JsonValue
    public String json() {
        return (isStatic ? "static " : "") + method.pretty();
    }

    public static MethodReference of(ClassTypeDescriptor clazz, MethodDescriptor method, boolean isStatic) {
        return new MethodReference(clazz, method, isStatic);
    }

    public static MethodReference ofStatic(ClassTypeDescriptor clazz, MethodDescriptor method) {
        return new MethodReference(clazz, method, true);
    }
}
