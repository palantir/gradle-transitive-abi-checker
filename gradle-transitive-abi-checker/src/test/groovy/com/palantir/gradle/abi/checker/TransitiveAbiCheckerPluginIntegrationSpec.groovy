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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nebula.test.IntegrationTestKitSpec
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome

class TransitiveAbiCheckerPluginIntegrationSpec extends IntegrationTestKitSpec {
    private static String SUB_PROJECT_NAME = "root"

    void setup() {
        // language=gradle
        buildFile << '''
            apply plugin: 'com.palantir.transitive-abi-checker'

            repositories {
                mavenCentral()
            }
        '''.stripIndent(true)

        keepFiles = true
        definePluginOutsideOfPluginBlock = true
        debug = true

        subproject(SUB_PROJECT_NAME)
    }

    def 'skip when there are no classes in main source set'() {
        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        result.task(":root:checkAbiCompatibility").getOutcome() == TaskOutcome.SKIPPED
        result.output.contains(TransitiveAbiCheckerTask.SKIP_NO_CLASS_MESSAGE)
    }

    def 'succeed when there is a version mismatch, but no ABI break'() {
        addDeps(SUB_PROJECT_NAME,
                "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
                // Will be different from the requested version (2.13.1), but still be entirely compatible
                "com.fasterxml.jackson.core:jackson-databind:2.14.0")

        // Use checkCompletely to ensure we're actually analyzing the code (simpler than manually forcing reachability)
        checkCompletely(SUB_PROJECT_NAME)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'succeed when there is a runtimeOnly dependency on another subproject'() {
        subproject("other")

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            dependencies {
                implementation 'com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0'

                // runtimeOnly dependencies don't create a dependency between the project's classes task
                //   and the dependency (whether the classes or compileJava tasks)
                // Gradle would fail if the plugin doesn't set the dependencies correctly for the abi checker tak
                runtimeOnly project(':other')
            }
        """.stripIndent(true)

        // Ensure we have some classes to compile as well
        file("other/src/main/java/Other.java") <<
                // language=java
                """
                public class Other {}
                """.stripIndent(true)
        emptyRootClass(SUB_PROJECT_NAME)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        result.task(":other:checkAbiCompatibility").getOutcome() == TaskOutcome.SUCCESS
        result.task(":${SUB_PROJECT_NAME}:checkAbiCompatibility").getOutcome() == TaskOutcome.SUCCESS
    }

    def 'succeed when there is an abi break, but classes are not reachable'() {
        addDeps(SUB_PROJECT_NAME,
                "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
                "com.fasterxml.jackson.core:jackson-databind:2.18.3")

        emptyRootClass(SUB_PROJECT_NAME)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'fail when there is an ABI break when using checkCompletely, even if classes are not reachable'() {
        addDeps(SUB_PROJECT_NAME,
                "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
                "com.fasterxml.jackson.core:jackson-databind:2.18.3")

        checkCompletely(SUB_PROJECT_NAME)

        emptyRootClass(SUB_PROJECT_NAME)

        when:
        def result = runTasksAndFail("checkAbiCompatibility", "--info")

        then:
        verifyConjureJacksonIncompat(SUB_PROJECT_NAME, result, "[\"com.palantir.conjure.java.serialization.PathDeserializer\"]")
    }

    def 'fail when there is an ABI break when using checkCompletely, even if there are no classes'() {
        addDeps(SUB_PROJECT_NAME,
                "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
                "com.fasterxml.jackson.core:jackson-databind:2.18.3")

        checkCompletely(SUB_PROJECT_NAME)

        when:
        def result = runTasksAndFail("checkAbiCompatibility", "--info")

        then:
        verifyConjureJacksonIncompat(SUB_PROJECT_NAME, result, "[\"com.palantir.conjure.java.serialization.PathDeserializer\"]")
    }

    def 'fail when there is a reachable abi break'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        when:
        def result = runTasksAndFail("checkAbiCompatibility", "--info")

        then:
        verifyConjureJacksonIncompat(SUB_PROJECT_NAME, result)
    }

    def 'fail when there is a reachable abi break with multiple source dirs'() {
        addDeps(SUB_PROJECT_NAME,
                "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
                "com.fasterxml.jackson.core:jackson-databind:2.18.3")

        // Specify a second source directory
        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            sourceSets.main.java.srcDirs += "${directory("${SUB_PROJECT_NAME}/src2/main/java").toString()}"
        """.stripIndent(true)

        // We specifically create reachability on the second source directory, to ensure this is included
        //   in the reachability analysis
        createFile("${SUB_PROJECT_NAME}/src2/main/java/Root2.java").text =
                // language=java
                """
            import com.palantir.conjure.java.serialization.PathDeserializer; 
            import java.io.IOException;

            public class Root2 {
                public static void main(String[] args) throws IOException {
                    PathDeserializer deserializer = new PathDeserializer();
                    // Calling deserialize since this is the method with the incompatibility
                    // null arguments as they don't matter for reachability analysis
                    deserializer.deserialize(null, null);
                }
            }
        """.stripIndent(true)

        emptyRootClass(SUB_PROJECT_NAME)

        when:
        def result = runTasksAndFail("checkAbiCompatibility", "--info")

        then:
        verifyConjureJacksonIncompat(SUB_PROJECT_NAME, result, "[\"Root2\",\"com.palantir.conjure.java.serialization.PathDeserializer\"]")
    }

    def 'fail when broken dependency is in error list'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                errorArtifactPrefixes = ['com.palantir']
            }
        """.stripIndent(true)

        when:
        def result = runTasksAndFail("checkAbiCompatibility", "--info")

        then:
        verifyConjureJacksonIncompat(SUB_PROJECT_NAME, result)
    }

    def 'succeed when broken dependency is not in error list'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                errorArtifactPrefixes = ['org']
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'succeed when broken dependency is in error list, but also ignored'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                errorArtifactPrefixes = ['com.palantir']
                ignoredArtifactPrefixes = ['com.palantir.conjure.java.runtime']
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'succeed when broken class is ignored by prefix'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                ignoredClassPrefixes = ['com.palantir.conjure.java.serialization']
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'succeed when broken class is ignored'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                ignoredClassPrefixes = ['com.palantir.conjure.java.serialization.PathDeserializer']
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'succeed when target class is ignored through prefix'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                ignoredClassPrefixes = ['com.fasterxml.jackson.databind']
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'succeed when broken class is ignored through keyword matching'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                ignoredClassnameKeywords = ['PathDeserializer']
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'succeed when target class is ignored through keyword matching'() {
        setupConjureJacksonIncompat(SUB_PROJECT_NAME)

        // language=gradle
        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                ignoredClassnameKeywords = ['databind']
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'success when ABI conflicts are between the same class that appears twice in the classpath'() {
        // These two libraries both contain identical classes (e.g. org.fusesource.jansi.Ansi) which aren't actually
        //  an exact match, and thus could be considered as a break
        // In this setup, the classes from jline are selected first, which are lacking fields and methods
        //   from fusesource's version
        // Specifically, we're going to test that we're not failing with conflicts by analysing fusesource's Ansi class
        //   and referring to its own fields and methods which are absent in jline's version
        // This can be done either by not analysing fusesource's version, or by ensuring we're getting its own fields
        addDeps(SUB_PROJECT_NAME,
                "jline:jline:2.14.6",
                "org.fusesource.jansi:jansi:2.4.1")

        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                checkCompletely = true
            
                ignoredClassPrefixes = [
                    // These don't exist in the jline version and tries to call classes that exist in the jline version
                    //   and thus are selected, but are missing members as compared to the fusesource one
                    'org.fusesource.jansi.AnsiMain',
                    'org.fusesource.jansi.WindowsSupport',
                    'org.fusesource.jansi.io.WindowsAnsiProcessor'
                ]
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    def 'duplicated JDK classes are not part of the classpath' () {
        addDeps(SUB_PROJECT_NAME,
                "org.wildfly.client:wildfly-client-config:1.0.1.Final",
                // This duplicates XMLInputFactory from the jdk, but is not the same and will be flagged as a break
                //   if the jdk isn't first in the classpath
                "xml-apis:xml-apis:1.4.01")

        subprojectBuild(SUB_PROJECT_NAME) << """
            transitiveAbiChecker {
                checkCompletely = true
                
                // jboss has some conflicts that are not relevant to this test
                ignoredArtifactPrefixes = ["org.jboss.logging:jboss-logging"]
            }
        """.stripIndent(true)

        when:
        def result = runTasks("checkAbiCompatibility", "--info")

        then:
        verifySuccess(SUB_PROJECT_NAME, result)
    }

    private File subprojectBuild(String name) {
        return file("${name}/build.gradle")
    }
    
    private void subproject(String name) {
        addSubproject(name)

        // language=gradle
        subprojectBuild(name) << """
            apply plugin: 'java'

            repositories {
                mavenCentral()
            }
        """.stripIndent(true)
    }

    private void addDeps(String subProject, String... deps) {
        subprojectBuild(subProject) << """
            dependencies {
                implementation '${deps.join('\'\n                implementation \'')}'
            }
        """.stripIndent(true)
    }

    private void checkCompletely(String subProject) {
        // language=gradle
        subprojectBuild(subProject) << """
            transitiveAbiChecker {
                checkCompletely = true
            }
        """.stripIndent(true)
    }

    private File rootClass(String subProject) {
        file("${subProject}/src/main/java/Root.java")
    }

    private void emptyRootClass(String subProject) {
        rootClass(subProject) <<
        // language=java
        """
            public class Root {
                public static void main(String[] args) {}
            }
        """.stripIndent(true)
    }

    private File outputFile(String subProject) {
        file("${subProject}/build/abi-checker/abi-checker-conflicts.json")
    }

    private void verifySuccess(String subProject, BuildResult result) {
        assert result.task(":${subProject}:checkAbiCompatibility").getOutcome() == TaskOutcome.SUCCESS
        assert outputFile(subProject).text == "{}"
    }

    /**
     * See comment on {@link #verifyConjureJacksonIncompat} for more details.
     */
    private void setupConjureJacksonIncompat(String subProject) {
        addDeps(subProject,
                "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
                "com.fasterxml.jackson.core:jackson-databind:2.18.3")

        // See verifyConjureJacksonIncompat which describes the exact break we expect
        rootClass(subProject).text =
                // language=java
                """
            import com.palantir.conjure.java.serialization.PathDeserializer; 
            import java.io.IOException;

            public class Root {
                public static void main(String[] args) throws IOException {
                    PathDeserializer deserializer = new PathDeserializer();
                    // Calling deserialize since this is the method with the incompatibility
                    // null arguments as they don't matter for reachability analysis
                    deserializer.deserialize(null, null);
                }
            }
        """.stripIndent(true)
    }

    /**
     * This is a known incompatibility between conjure-java-jackson-serialization 7.33.0 and jackson-databind 2.18.3.
     *
     * We merely verify that the check failed and that the output contains:
     *   - The dependency that isn't compatible and the transitive it isn't compatible with
     *   - The class and method where the incompatibility was detected
     *   - What was incompatible (in this case, the call to mappingException)
     *
     * See also
     *   https://github.com/palantir/conjure-java-runtime/blob/7.33.0/conjure-java-jackson-serialization/src/main/java/com/palantir/conjure/java/serialization/PathDeserializer.java
     * and
     *   https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.13.1/src/main/java/com/fasterxml/jackson/databind/DeserializationContext.java#L2185-L2193
     * as well as
     *   https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.18.3/src/main/java/com/fasterxml/jackson/databind/DeserializationContext.java
     * where we can indeed see the latter doesn't have the method mappingException(Class, JsonToken), which is called
     *   in conjure-java-jackson-serialization.
     */
    private void verifyConjureJacksonIncompat(String project, BuildResult result, String expectedReachabilityPath = "") {
        assert result.task(":${project}:checkAbiCompatibility").getOutcome() == TaskOutcome.FAILED

        def stringsToFind = [
            // Dependency which is broken
            "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
            // Which transitive the break is with
            "com.fasterxml.jackson.core:jackson-databind:2.18.3",
            // Which class the break is in
            "com.palantir.conjure.java.serialization.PathDeserializer",
            // Which method and line the break is in
            "deserialize(com.fasterxml.jackson.core.JsonParser, com.fasterxml.jackson.databind.DeserializationContext) (line 61)",
            // What was incompatible (the method that doesn't exist anymore)
            "com.fasterxml.jackson.databind.DeserializationContext.mappingException(java.lang.Class, com.fasterxml.jackson.core.JsonToken)"
        ]
        // We want to find these in the build's error
        stringsToFind.each {toFind ->
            assert result.output.contains(toFind)
        }

        def reachabilityPath = expectedReachabilityPath.isEmpty() ?
                "[\"Root\",\"com.palantir.conjure.java.serialization.PathDeserializer\"]"
                : expectedReachabilityPath
        // Note: We could generate an actual OutputContents and serialize it to compare,
        //   but the types like MethodDescriptor take e.g. Ljava/nio/file/Path as the type arguments
        //   which makes it less obvious what to expect as json
        // language=JSON
        def expectedOutputJson = """
            {
                "type": "conflicts",
                "conflicts": [
                    {
                        "dependency": {
                            "reachabilityPath": ${reachabilityPath},
                            "fromClass": "com.palantir.conjure.java.serialization.PathDeserializer",
                            "fromMethod": "java.nio.file.Path deserialize(com.fasterxml.jackson.core.JsonParser, com.fasterxml.jackson.databind.DeserializationContext)",
                            "fromLineNumber": 61,
                            "targetClass": "com.fasterxml.jackson.databind.DeserializationContext",
                            "targetMethod": "com.fasterxml.jackson.databind.JsonMappingException mappingException(java.lang.Class, com.fasterxml.jackson.core.JsonToken)"
                        },
                        "existsIn": "com.fasterxml.jackson.core:jackson-databind:2.18.3",
                        "usedBy": "com.palantir.conjure.java.runtime:conjure-java-jackson-serialization:7.33.0",
                        "category": "METHOD_SIGNATURE_NOT_FOUND"
                    }
                ]
            }
        """.stripIndent(true)
        ObjectMapper mapper = new ObjectMapper();

        JsonNode expected = mapper.readTree(expectedOutputJson);
        JsonNode actual = mapper.readTree(outputFile(project).text);

        assert actual == expected
    }

}
