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
package com.palantir.abi.checker.datamodel;

import com.palantir.abi.checker.datamodel.field.FieldDescriptor;
import com.palantir.abi.checker.datamodel.field.FieldReference;
import com.palantir.abi.checker.datamodel.method.DeclaredMethod;
import com.palantir.abi.checker.datamodel.method.MethodDescriptor;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.util.Map;
import java.util.Set;
import org.immutables.value.Value;

@Value.Immutable
public interface DeclaredClass {

    // names are com/foo/bar/Baz
    ClassTypeDescriptor className();

    // parent are class names: com/foo/bar/Baz
    Set<ClassTypeDescriptor> parents();

    // also includes other classes that are loaded by this class, even though
    // no methods on those classes are explicitly called
    Set<ClassTypeDescriptor> loadedClasses();

    Map<MethodDescriptor, DeclaredMethod> methods();

    Map<FieldDescriptor, FieldReference> fields();

    static ImmutableDeclaredClass.Builder builder() {
        return ImmutableDeclaredClass.builder();
    }
}
