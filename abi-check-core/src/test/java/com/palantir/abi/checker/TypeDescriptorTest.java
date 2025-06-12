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

package com.palantir.abi.checker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palantir.abi.checker.datamodel.types.ArrayTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.PrimitiveTypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptor;
import com.palantir.abi.checker.datamodel.types.TypeDescriptors;
import java.util.HashMap;
import java.util.InputMismatchException;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TypeDescriptorTest {

    @Test
    public void testEquality() {
        String[] signatures = {
            "B", "S", "I", "J", "F", "D", "Z", "C", "[D", "[[D", "[[[D", "LFoo;", "LBar;", "[LFoo;", "Lfoo/bar/Baz;"
        };
        for (String signature1 : signatures) {
            for (String signature2 : signatures) {
                if (signature1.equals(signature2)) {
                    assertThat(TypeDescriptors.fromRaw(signature1)).isEqualTo(TypeDescriptors.fromRaw(signature2));
                    assertThat(TypeDescriptors.fromRaw(signature1).hashCode())
                            .isEqualTo(TypeDescriptors.fromRaw(signature2).hashCode());
                } else {
                    assertThat(TypeDescriptors.fromRaw(signature1)).isNotEqualTo(TypeDescriptors.fromRaw(signature2));
                }
            }
        }
    }

    @Test
    public void testDescriptions() {
        Map<String, String> desc = new HashMap<>();
        desc.put("B", "byte");
        desc.put("S", "short");
        desc.put("I", "int");
        desc.put("J", "long");
        desc.put("F", "float");
        desc.put("D", "double");
        desc.put("Z", "boolean");
        desc.put("C", "char");
        desc.put("[D", "double[]");
        desc.put("[[D", "double[][]");
        desc.put("[[[D", "double[][][]");
        desc.put("LFoo;", "Foo");
        desc.put("[LFoo;", "Foo[]");
        desc.put("[[LFoo;", "Foo[][]");
        desc.put("Lfoo/bar/Baz;", "foo.bar.Baz");

        for (Map.Entry<String, String> entry : desc.entrySet()) {
            assertThat(entry.getValue())
                    .isEqualTo(TypeDescriptors.fromRaw(entry.getKey()).toString());
        }
    }

    @Test
    public void testTypes() {
        Map<String, Class<?>> desc = new HashMap<>();
        desc.put("B", PrimitiveTypeDescriptor.class);
        desc.put("S", PrimitiveTypeDescriptor.class);
        desc.put("I", PrimitiveTypeDescriptor.class);
        desc.put("J", PrimitiveTypeDescriptor.class);
        desc.put("F", PrimitiveTypeDescriptor.class);
        desc.put("D", PrimitiveTypeDescriptor.class);
        desc.put("Z", PrimitiveTypeDescriptor.class);
        desc.put("C", PrimitiveTypeDescriptor.class);
        desc.put("[D", ArrayTypeDescriptor.class);
        desc.put("LFoo;", ClassTypeDescriptor.class);
        desc.put("[LFoo;", ArrayTypeDescriptor.class);
        desc.put("Lfoo/bar/Baz;", ClassTypeDescriptor.class);

        for (Map.Entry<String, Class<?>> entry : desc.entrySet()) {
            assertThat(entry.getValue())
                    .isAssignableFrom(TypeDescriptors.fromRaw(entry.getKey()).getClass());
        }
    }

    @Test
    public void testInvalid() {
        assertThatThrownBy(() -> TypeDescriptors.fromRaw("X")).isInstanceOf(InputMismatchException.class);
    }

    @Test
    public void testMoarInvalid() {
        assertThatThrownBy(() -> TypeDescriptors.fromRaw("LFoo")).isInstanceOf(InputMismatchException.class);
    }

    @Test
    public void testMoastInvalid() {
        assertThatThrownBy(() -> TypeDescriptors.fromClassName("LFoo;")).isInstanceOf(InputMismatchException.class);
    }

    @Test
    public void testMoarDifferentInvalid() {
        assertThatThrownBy(() -> TypeDescriptors.fromRaw("JJ")).isInstanceOf(InputMismatchException.class);
    }

    @Test
    public void testCanonicalNames() throws Exception {
        final TypeDescriptor expected = TypeDescriptors.fromClassName("foo.Bar");
        final TypeDescriptor actual = TypeDescriptors.fromClassName("foo/Bar");
        assertThat(expected).isEqualTo(actual);
    }

    @Test
    public void testNewClassTypeDescriptor() throws Exception {
        final ClassTypeDescriptor a = TypeDescriptors.fromClassName("foo.Bar");
        final ClassTypeDescriptor b = TypeDescriptors.fromClassName("foo/Bar");
        assertThat(a).isEqualTo(b);
    }
}
