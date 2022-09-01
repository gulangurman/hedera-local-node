/*
 * Copyright (C) 2022 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import gradle.kotlin.dsl.accessors._de3ff27eccbd9efdc5c099f60a1d8f4c.check
import net.swiftzer.semver.SemVer
import java.text.SimpleDateFormat
import java.util.*

plugins {
    `java-library`
    `maven-publish`
    jacoco
    id("com.hedera.hashgraph.spotless-conventions")
    id("com.hedera.hashgraph.spotless-java-conventions")
    id("com.hedera.hashgraph.spotless-kotlin-conventions")
    id("lazy.zoo.gradle.git-data-plugin")
}

group = "com.hedera.hashgraph"

// Specify the JDK Version and vendor that we will support
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        @Suppress("UnstableApiUsage")
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

// Define the repositories from which we will pull dependencies
repositories {
    mavenLocal()
    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots")
    }
    maven {
        url = uri("https://us-maven.pkg.dev/swirlds-registry/maven-prerelease-channel")
    }
    maven {
        url = uri("https://us-maven.pkg.dev/swirlds-registry/maven-develop-commits")
    }
    maven {
        url = uri("https://us-maven.pkg.dev/swirlds-registry/maven-adhoc-commits")
    }
    maven {
        url = uri("https://us-maven.pkg.dev/swirlds-registry/maven-develop-daily-snapshots")
    }
    maven {
        url = uri("https://us-maven.pkg.dev/swirlds-registry/maven-develop-snapshots")
    }
    maven {
        url = uri("https://hyperledger.jfrog.io/artifactory/besu-maven")
    }
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/comhederahashgraph-1502")
    }
    maven {
        url = uri("https://oss.sonatype.org/content/repositories/comhederahashgraph-1531")
    }
}

// Enable maven publications
publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

// Make sure we use UTF-8 encoding when compiling
tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc> {
    options.encoding = "UTF-8"
}

testing {
    suites {
        // Configure the normal unit test suite to use JUnit Jupiter.
        @Suppress("UnstableApiUsage")
        val test by getting(JvmTestSuite::class) {
            // Enable JUnit as our test engine
            useJUnitJupiter()
        }

        // Configure the integration test suite
        @Suppress("UNUSED_VARIABLE", "UnstableApiUsage")
        val itest by registering(JvmTestSuite::class) {
            testType.set(TestSuiteType.INTEGRATION_TEST)
            dependencies {
                implementation(project)
            }

            // "shouldRunAfter" will only make sure if both test and itest are run concurrently,
            // that "test" completes first. If you run "itest" directly, it doesn't force "test" to run.
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }

        // Configure the hammer test suite
        @Suppress("UNUSED_VARIABLE", "UnstableApiUsage")
        val hammer by registering(JvmTestSuite::class) {
            testType.set("hammer-test")
            dependencies {
                implementation(project)
            }

            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }

        // Add the EET task for executing end-to-end tests
        testing {
            suites {
                @Suppress("UnstableApiUsage", "UNUSED_VARIABLE")
                val eet by registering(JvmTestSuite::class) {
                    testType.set("end-to-end-test")
                    dependencies {
                        implementation(project)
                    }

                    // "shouldRunAfter" will only make sure if both test and eet are run concurrently,
                    // that "test" completes first. If you run "eet" directly, it doesn't force "test" to run.
                    targets {
                        all {
                            testTask.configure {
                                shouldRunAfter(tasks.test)
                            }
                        }
                    }
                }
            }
        }
    }
}

// Increase the heap size for the unit tests
tasks.test {
    maxHeapSize = "1024m"
}

tasks.getByName<Test>("itest") {
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {
            logger.lifecycle("=====> Starting Suite: " + suite.displayName + " <=====")
        }

        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            logger.lifecycle(
                SimpleDateFormat.getDateTimeInstance()
                    .format(Date()) + ": " + testDescriptor.displayName + " " + result.resultType.name
            )
        }

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {}
    })
}

// Configure Jacoco so it outputs XML reports (needed by SonarCloud), and so that it combines the code
// coverage from both unit and integration tests into a single report from `jacocoTestReport`
tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val testExtension: JacocoTaskExtension =
        tasks.test.get().extensions.getByType<JacocoTaskExtension>()
    val iTestExtension: JacocoTaskExtension =
        tasks.getByName("itest").extensions.getByType<JacocoTaskExtension>()
    executionData.from(testExtension.destinationFile, iTestExtension.destinationFile)
}

tasks.check {
    dependsOn(tasks.named<JacocoReport>("jacocoTestReport"))
}


tasks.create("showVersion") {
    doLast {
        println(project.version)
    }
}

tasks.create("versionAsPrefixedCommit") {
    doLast {
        gitData.lastCommitHash?.let {
            val prefix = findProperty("commitPrefix")?.toString() ?: "adhoc"
            val newPrerel = prefix + ".x" + it.take(8)
            val currVer = SemVer.parse(rootProject.version.toString())
            try {
                val newVer = SemVer(currVer.major, currVer.minor, currVer.patch, newPrerel)
                Utils.updateVersion(rootProject, newVer)
            } catch (e: java.lang.IllegalArgumentException) {
                throw IllegalArgumentException(String.format("%s: %s", e.message, newPrerel), e)
            }
        }
    }
}

tasks.create("versionAsSnapshot") {
    doLast {
        val currVer = SemVer.parse(rootProject.version.toString())
        val newVer = SemVer(currVer.major, currVer.minor, currVer.patch, "SNAPSHOT")

        Utils.updateVersion(rootProject, newVer)
    }
}

tasks.create("versionAsSpecified") {
    doLast {
        val verStr = findProperty("newVersion")?.toString()

        if (verStr == null) {
            throw IllegalArgumentException("No newVersion property provided! Please add the parameter -PnewVersion=<version> when running this task.")
        }

        val newVer = SemVer.parse(verStr)
        Utils.updateVersion(rootProject, newVer)
    }
}

tasks.withType<Jar> {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    fileMode = 664
    dirMode = 775
}
