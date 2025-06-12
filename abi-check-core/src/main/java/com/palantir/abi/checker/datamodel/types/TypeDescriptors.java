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

import com.google.common.base.Preconditions;
import java.util.InputMismatchException;

public final class TypeDescriptors {

    public static ClassTypeDescriptor fromClassName(String className) {
        return new ClassTypeDescriptor(className);
    }

    public static ClassTypeDescriptor fromClassFilename(String classFilename) {
        Preconditions.checkArgument(
                !classFilename.startsWith("META-INF/versions"),
                "Class descriptors cannot include multi-release prefixes.");
        return new ClassTypeDescriptor(stripExtension(classFilename));
    }

    private static String stripExtension(String filename) {
        int classExtensionIndex = filename.indexOf(".class");
        if (classExtensionIndex > 0) {
            return filename.substring(0, classExtensionIndex);
        }

        return filename;
    }

    public static TypeDescriptor fromRaw(String raw) {
        final int length = raw.length();

        int dimensions = raw.lastIndexOf('[') + 1;

        final String subType = raw.substring(dimensions);

        final TypeDescriptor simpleType;
        if (subType.equals("V")) {
            simpleType = VoidTypeDescriptor.voidTypeDescriptor;
        } else if (subType.startsWith("L") && subType.endsWith(";")) {
            simpleType = fromClassName(subType.substring(1, length - dimensions - 1));
        } else {
            simpleType = PrimitiveTypeDescriptor.fromRaw(subType);
            if (simpleType == null) {
                throw new InputMismatchException("Invalid type descriptor: " + raw);
            }
        }

        if (dimensions > 0) {
            return ImmutableArrayTypeDescriptor.of(simpleType, dimensions);
        }
        return simpleType;
    }

    private TypeDescriptors() {}
}
