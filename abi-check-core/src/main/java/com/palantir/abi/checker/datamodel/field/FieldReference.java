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

package com.palantir.abi.checker.datamodel.field;

import com.fasterxml.jackson.annotation.JsonValue;
import com.palantir.abi.checker.datamodel.method.Reference;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptor;

/**
 * Represents a reference to a specific field in a given class.
 */
public record FieldReference(ClassTypeDescriptor clazz, FieldDescriptor field, boolean isStatic) implements Reference {

    public TypeDescriptor type() {
        return field.type();
    }

    public String name() {
        return field.name();
    }

    public String pretty() {
        return clazz + "#" + name() + " (" + type() + ")";
    }

    @JsonValue
    public String json() {
        return type().toString() + " " + clazz().toString() + "." + name();
    }

    public static FieldReference of(ClassTypeDescriptor clazz, TypeDescriptor type, String name, boolean isStatic) {
        return of(clazz, FieldDescriptor.of(type, name), isStatic);
    }

    public static FieldReference of(ClassTypeDescriptor clazz, FieldDescriptor descriptor, boolean isStatic) {
        return new FieldReference(clazz, descriptor, isStatic);
    }
}
