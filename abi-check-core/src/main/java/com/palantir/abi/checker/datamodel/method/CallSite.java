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

import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.util.Set;

/**
 * Represents a call site within a method, which accesses a field or calls a method.
 *
 * This record contains what is being accessed, the line number where the call site is located, as well
 *   as exceptions that are caught around the call site.
 */
public record CallSite<T extends Reference>(T reference, int lineNumber, Set<ClassTypeDescriptor> caughtExceptions) {

    public ClassTypeDescriptor owner() {
        return reference.clazz();
    }

    public static <T extends Reference> CallSite<T> of(
            T reference, int lineNumber, Set<ClassTypeDescriptor> caughtExceptions) {
        return new CallSite<>(reference, lineNumber, caughtExceptions);
    }
}
