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

package com.palantir.abi.checker.util;

import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;

public final class ExceptionsChecker {

    public static final String NO_CLASS_DEF_FOUND = "java.lang.NoClassDefFoundError";
    public static final String CLASS_NOT_FOUND = "java.lang.ClassNotFoundException";

    public static final String METHOD_NOT_FOUND = "java.lang.NoSuchMethodError";

    public static final String NO_SUCH_FIELD = "java.lang.NoSuchFieldError";

    public static boolean isClassLoadingExceptionType(ClassTypeDescriptor exceptionType) {
        String exceptionClassName = exceptionType.className();
        return NO_CLASS_DEF_FOUND.equals(exceptionClassName) || CLASS_NOT_FOUND.equals(exceptionClassName);
    }

    public static boolean isMethodNotFoundExceptionType(ClassTypeDescriptor exceptionType) {
        return METHOD_NOT_FOUND.equals(exceptionType.className());
    }

    public static boolean isFieldNotFoundExceptionType(ClassTypeDescriptor exceptionType) {
        return NO_SUCH_FIELD.equals(exceptionType.className());
    }

    private ExceptionsChecker() {}
}
