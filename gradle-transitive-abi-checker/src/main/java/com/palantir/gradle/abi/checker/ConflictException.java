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

package com.palantir.gradle.abi.checker;

import com.palantir.abi.checker.datamodel.conflict.Conflict;
import java.util.Collection;

public final class ConflictException extends RuntimeException {
    private final Collection<Conflict> conflicts;

    public ConflictException(String message, Collection<Conflict> conflicts) {
        // We are using an exception as part of participating in the gradle lifecycle
        // however there is no "reason" and we do not want a stacktrace.
        super(message, null, false, false);
        this.conflicts = conflicts;
    }

    public Collection<Conflict> getConflicts() {
        return conflicts;
    }
}
