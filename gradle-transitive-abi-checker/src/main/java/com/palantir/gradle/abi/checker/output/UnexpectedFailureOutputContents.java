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

package com.palantir.gradle.abi.checker.output;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * This represents the output contents of the ABI checker task when an unexpected failure occurs.
 * For instance, if the ABI checker throws on some unexpected edge case, we will write it to the output file.
 *
 * This lets us capture such errors at the same time as conflicts, for better analysis of the plugin's behavior.
 */
@Value.Immutable
@JsonSerialize(as = ImmutableUnexpectedFailureOutputContents.class)
public interface UnexpectedFailureOutputContents extends OutputContents {
    @Override
    default String type() {
        return "unexpected_failure";
    }

    Optional<String> message();

    String stacktrace();

    static UnexpectedFailureOutputContents of(Exception exception) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        exception.printStackTrace(pw);
        String stacktrace = sw.toString();

        return ImmutableUnexpectedFailureOutputContents.builder()
                .message(Optional.ofNullable(exception.toString()))
                .stacktrace(stacktrace)
                .build();
    }
}
