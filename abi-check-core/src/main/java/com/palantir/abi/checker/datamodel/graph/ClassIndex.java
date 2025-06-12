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

package com.palantir.abi.checker.datamodel.graph;

import com.palantir.abi.checker.datamodel.Artifact;
import com.palantir.abi.checker.datamodel.ArtifactName;
import com.palantir.abi.checker.datamodel.classlocation.ClassLocation;
import com.palantir.abi.checker.datamodel.types.ClassTypeDescriptor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.immutables.value.Value;

@Value.Immutable
public interface ClassIndex {
    Map<ClassTypeDescriptor, ArtifactName> sourceMappings();

    Map<ClassTypeDescriptor, ClassLocation> knownClasses();

    /**
     * Create a canonical mapping of which classes are kept. First come first serve in the classpath.
     *
     * @param allArtifacts maven artifacts to populate checker state with
     */
    static ClassIndex create(List<Artifact> allArtifacts) {
        final ImmutableClassIndex.Builder indexBuilder = ImmutableClassIndex.builder();
        Map<ClassTypeDescriptor, ClassLocation> knownClasses = new HashMap<>();
        for (Artifact artifact : allArtifacts) {
            for (ClassLocation clazz : artifact.classes().values()) {
                if (knownClasses.putIfAbsent(clazz.className(), clazz) == null) {
                    indexBuilder.putSourceMappings(clazz.className(), artifact.name());
                }
            }
        }
        indexBuilder.knownClasses(knownClasses);
        return indexBuilder.build();
    }
}
