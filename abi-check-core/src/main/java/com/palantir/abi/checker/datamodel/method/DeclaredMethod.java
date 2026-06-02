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

import com.palantir.abi.checker.datamodel.field.FieldReference;
import com.palantir.abi.checker.datamodel.reference.ClassReference;
import java.util.Set;
import org.immutables.value.Value;

/**
 * Represents the details of a declared method within a class,
 *   including which methods it's calling and which fields it's accessing.
 */
@Value.Immutable
public interface DeclaredMethod {
    MethodReference reference();

    /**
     * Exceptions caught by this method. These trigger NoClassDefFoundError
     * at Class verification time if not found (i.e. even if the method itself is unused).
     *
     * See https://docs.oracle.com/javase/specs/jvms/se23/html/jvms-4.html#jvms-4.9.2
     * "Each class mentioned in a catch_type item of the exception_table array of the method's
     * Code_attribute structure must be Throwable or a subclass of Throwable."
     */
    Set<CallSite<ClassReference>> caughtExceptions();

    /** Calls that this method makes to other methods. */
    Set<CallSite<MethodReference>> methodCalls();

    Set<CallSite<FieldReference>> fieldAccesses();

    static ImmutableDeclaredMethod.Builder builder() {
        return ImmutableDeclaredMethod.builder();
    }
}
