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

package com.palantir.abi.checker.datamodel.types;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.InputMismatchException;
import java.util.Objects;

public record ClassTypeDescriptor(String className) implements TypeDescriptor {
    public ClassTypeDescriptor(String className) {
        this.className = Objects.requireNonNull(className).replace('/', '.');
        if (className.endsWith(";")) {
            throw new InputMismatchException("Got a signature where a class name was expected: " + className);
        }
    }

    public String toJarPath() {
        return className.replace('.', '/') + ".class";
    }

    @JsonValue
    @Override
    public String toString() {
        return className;
    }
}
