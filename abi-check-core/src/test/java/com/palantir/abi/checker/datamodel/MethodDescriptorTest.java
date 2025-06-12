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

import static org.assertj.core.api.Assertions.assertThat;

import com.palantir.abi.checker.datamodel.method.MethodDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import org.junit.jupiter.api.Test;

public class MethodDescriptorTest {

    @Test
    public void testMethodDescriptionParser() {
        // See https://docs.oracle.com/javase/specs/jvms/se23/html/jvms-4.html#jvms-4.3 for docs on these descriptors
        MethodDescriptor desc1 = MethodDescriptor.ofDescriptor("([I[[Lfoo/Bar;Z)V", "baz");
        MethodDescriptor desc2 = MethodDescriptor.builder()
                .returnType(TypeDescriptors.fromRaw("V"))
                .addParameterTypes(
                        TypeDescriptors.fromRaw("[I"),
                        TypeDescriptors.fromRaw("[[Lfoo/Bar;"),
                        TypeDescriptors.fromRaw("Z"))
                .name("baz")
                .build();
        assertThat(desc1).isEqualTo(desc2).describedAs("Method descriptors should be identical");
    }

    @Test
    public void testPrettyParameters() {
        MethodDescriptor desc = MethodDescriptor.ofDescriptor("([I[[Lfoo/Bar;Z)V", "baz");
        assertThat(desc.prettyParameters()).isEqualTo("(int[], foo.Bar[][], boolean)");
    }
}
