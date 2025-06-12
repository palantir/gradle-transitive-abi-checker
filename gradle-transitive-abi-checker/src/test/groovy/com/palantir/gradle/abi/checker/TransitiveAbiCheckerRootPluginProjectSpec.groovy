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

package com.palantir.gradle.abi.checker

import nebula.test.ProjectSpec
import org.gradle.api.Project
import org.gradle.api.internal.plugins.PluginApplicationException
import org.gradle.testfixtures.ProjectBuilder

class TransitiveAbiCheckerRootPluginProjectSpec extends ProjectSpec {
    String pluginName = "com.palantir.transitive-abi-checker"

    def 'apply does not throw exceptions'() {
        when:
        project.apply plugin: pluginName

        then:
        noExceptionThrown()
    }

    def 'apply is only allowed on root project'() {
        def sub = createSubproject(project, 'sub')
        project.subprojects.add(sub)

        when:
        sub.apply plugin: pluginName

        then:
        PluginApplicationException ex = thrown()
        ex.cause.message == TransitiveAbiCheckerRootPlugin.ROOT_ERROR_MESSAGE
    }

    def 'core plugin should be applied on java subprojects'() {
        def sub = createSubproject(project, 'sub-java')
        project.subprojects.add(sub)
        sub.plugins.apply('java')

        when:
        project.apply plugin: pluginName

        then:
        sub.plugins.hasPlugin(TransitiveAbiCheckerPlugin)
    }

    def 'core plugin should be applied on java-library subprojects'() {
        def sub = createSubproject(project, 'sub-java-library')
        project.subprojects.add(sub)
        sub.plugins.apply('java-library')

        when:
        project.apply plugin: pluginName

        then:
        sub.plugins.hasPlugin(TransitiveAbiCheckerPlugin)
    }

    def 'core plugin should not be applied on non-java subprojects'() {
        def sub = createSubproject(project, 'sub')
        project.subprojects.add(sub)

        when:
        project.apply plugin: pluginName

        then:
        !sub.plugins.hasPlugin(TransitiveAbiCheckerPlugin)
    }

    Project createSubproject(Project parentProject, String name) {
        ProjectBuilder.builder()
                .withName(name)
                .withProjectDir(new File(projectDir, name))
                .withParent(parentProject)
                .build()
    }
}
