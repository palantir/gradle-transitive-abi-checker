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

package com.palantir.abi.checker.datamodel.classlocation;

import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * Represents a class file that comes from the jdk.
 *
 * URI isn't great as it uses more memory than needed (storing e.g. twice the entire path), but we don't currently
 *   have a simpler way to represent a JDK class.
 */
public record JdkBasedClassLocation(ClassTypeDescriptor classTypeDescriptor, URI classLocation)
        implements ClassLocation {
    @Override
    public ClassTypeDescriptor className() {
        return classTypeDescriptor;
    }

    @Override
    public InputStream openStream() throws IOException {
        return classLocation.toURL().openStream();
    }
}
