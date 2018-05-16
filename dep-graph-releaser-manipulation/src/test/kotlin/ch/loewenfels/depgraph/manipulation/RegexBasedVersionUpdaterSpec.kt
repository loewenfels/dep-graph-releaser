package ch.loewenfels.depgraph.manipulation

import ch.loewenfels.depgraph.maven.getTestDirectory
import ch.tutteli.atrium.*
import ch.tutteli.atrium.api.cc.en_UK.contains
import ch.tutteli.atrium.api.cc.en_UK.message
import ch.tutteli.atrium.api.cc.en_UK.toThrow
import ch.tutteli.spek.extensions.TempFolder
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.*
import java.io.File

object RegexBasedVersionUpdaterSpec : Spek({
    val tempFolder = TempFolder.perTest()
    registerListener(tempFolder)

    val testee = RegexBasedVersionUpdater

    describe("error cases") {
        given("pom file which does not exist") {
            val errMessage = "pom file does not exist"
            it("throws an IllegalArgumentException, mentioning `$errMessage`") {
                val pom = File("nonExisting")
                expect {
                    testee.updateDependency(pom, "com.google.code.gson", "gson", "4.4")
                }.toThrow<IllegalArgumentException> {
                    message {
                        contains(errMessage, pom.absolutePath, "com.google.code.gson", "gson", "4.4")
                    }
                }
            }
        }

        given("single project with third party dependency without version") {
            val errMessage = "the dependency was not found"
            it("throws an IllegalStateException, mentioning `$errMessage`") {
                val pom = File(getTestDirectory("singleProject"), "pom.xml")
                val tmpPom = copyPom(tempFolder, pom)
                expect {
                    testee.updateDependency(tmpPom, "com.google.code.gson", "gson", "4.4")
                }.toThrow<IllegalStateException> { message { contains(errMessage) } }
            }
        }

        given("project with dependent and version partially static with property") {
            val errMessage = "Version was neither static nor a reference to a single property"
            it("throws an UnsupportedOperationException, mentioning `$errMessage`") {
                val pom = getPom("errorCases/versionPartiallyStaticAndProperty.pom")
                val tmpPom = copyPom(tempFolder, pom)
                expect {
                    updateDependency(testee, tmpPom, exampleA)
                }.toThrow<UnsupportedOperationException> { message { contains(errMessage, "1.0.\${a.fix}") } }
            }
        }

        given("dependency with two <version>") {
            val errMessage = "<dependency> has two <version>"
            it("throws an IllegalStateException, mentioning `$errMessage`") {
                val pom = getPom("errorCases/twoVersions.pom")
                val tmpPom = copyPom(tempFolder, pom)
                expect {
                    updateDependency(testee, tmpPom, exampleA)
                }.toThrow<IllegalStateException> { message { contains(errMessage, exampleA.id.identifier) } }
            }
        }

        given("property which contains another property") {
            val errMessage = "Property contains another property"
            it("throws an UnsupportedOperationException, mentioning `$errMessage`") {
                val pom = getPom("errorCases/propertyWithProperty.pom")
                val tmpPom = copyPom(tempFolder, pom)
                expect {
                    updateDependency(testee, tmpPom, exampleA)
                }.toThrow<UnsupportedOperationException> {
                    message { contains(errMessage, "a.version", "\${aVersion}") }
                }
            }
        }

        given("version via property but property is absent") {
            val errMessage = "version is managed via one or more properties but they are not present"
            it("throws an IllegalStateException, mentioning `$errMessage`") {
                val pom = getPom("errorCases/absentProperty.pom")
                val tmpPom = copyPom(tempFolder, pom)
                expect {
                    updateDependency(testee, tmpPom, exampleA)
                }.toThrow<IllegalStateException> { message { contains(errMessage, "a.version", "another.version") } }
            }
        }
        given("new version = old version") {
            val errMessage = "Version is already up-to-date; did you pass wrong argument for newVersion"
            it("throws an IllegalArgumentException, mentioning `$errMessage`") {
                val pom = getPom("errorCases/sameVersion.pom")
                val tmpPom = copyPom(tempFolder, pom)
                expect {
                    updateDependency(testee, tmpPom, exampleA)
                }.toThrow<IllegalArgumentException> { message { contains(errMessage, exampleA.releaseVersion) } }
            }
        }
    }

    given("single project with third party dependency") {
        val pom = File(getTestDirectory("singleProject"), "pom.xml")

        context("dependency shall be updated, new version") {
            it("updates the dependency") {
                val tmpPom = copyPom(tempFolder, pom)
                testee.updateDependency(tmpPom, "junit", "junit", "4.4")
                assertSameAsBeforeAfterReplace(tmpPom, pom, "4.12", "4.4")
            }
        }

        context("dependency occurs multiple times") {
            it("updates the dependency") {
                val tmpPom = copyPom(tempFolder, pom)
                testee.updateDependency(tmpPom, "test", "test", "3.0")
                assertSameAsBeforeAfterReplace(tmpPom, pom, "2.0", "3.0")
            }
        }

        context("dependency once without version") {
            it("updates the dependency with version") {
                val tmpPom = copyPom(tempFolder, pom)
                testee.updateDependency(tmpPom, "test", "onceWithoutVersion", "3.4")
                assertSameAsBeforeAfterReplace(tmpPom, pom, "3.0", "3.4")
            }
        }
    }

    given("project with dependency and version in dependency management") {
        val pom = File(getTestDirectory("managingVersions/viaDependencyManagement"), "b.pom")
        testWithExampleA(testee, tempFolder, pom)
    }

    given("project with dependent and version is \${project.version}") {
        val pom = getPom("versionIsProjectVersion.pom")
        testProjectVersionWithExampleA(tempFolder, pom, testee)
    }

    given("project with parent dependency") {
        val pom = File(getTestDirectory("parentRelations/parent"), "b.pom")
        testWithExampleA(testee, tempFolder, pom)
    }

    given("project with dependent and version in property") {
        val pom = File(getTestDirectory("managingVersions/viaProperty"), "b.pom")
        testWithExampleA(testee, tempFolder, pom)
    }

    given("project with dependent and version in property which is \${project.version}") {
        val pom = getPom("propertyIsProjectVersion.pom")
        testProjectVersionWithExampleA(tempFolder, pom, testee)
    }

    given("project with dependent and version in property which is in different profiles") {
        val pom = getPom("propertiesInProfile.pom")
        testWithExampleA(testee, tempFolder, pom)
    }

    given("project with dependent and empty <properties>") {
        val pom = getPom("emptyProperties.pom")
        testWithExampleA(testee, tempFolder, pom)
    }

    given("project which has a property which is built up by another property but not the one we want to update") {
        val pom = getPom("propertyWithProperty.pom")
        testWithExampleA(testee, tempFolder, pom)
    }
})

private fun SpecBody.testProjectVersionWithExampleA(
    tempFolder: TempFolder,
    pom: File,
    testee: RegexBasedVersionUpdater
) {
    context("dependency shall be updated, same version") {
        it("nevertheless replaces \${project.version} with the current version") {
            val tmpPom = copyPom(tempFolder, pom)
            updateDependency(testee, tmpPom, exampleA, "1.0.0")
            assertSameAsBeforeAfterReplace(tmpPom, pom, "\${project.version}", "1.0.0")
        }
    }

    context("dependency shall be updated, new version") {
        it("replaces \${project.version} with the new version") {
            val tmpPom = copyPom(tempFolder, pom)
            updateDependency(testee, tmpPom, exampleA)
            assertSameAsBeforeAfterReplace(tmpPom, pom, "\${project.version}", "1.1.1")
        }
    }
}

private fun SpecBody.testWithExampleA(
    testee: RegexBasedVersionUpdater,
    tempFolder: TempFolder,
    pom: File
) {
    context("dependency shall be updated, new version") {
        it("updates the property") {
            val tmpPom = copyPom(tempFolder, pom)
            updateDependency(testee, tmpPom, exampleA)
            assertSameAsBeforeAfterReplace(tmpPom, pom, "1.0.0", "1.1.1")
        }
    }
}

private fun getPom(pomName: String): File = File(RegexBasedVersionUpdaterSpec.javaClass.getResource("/$pomName").path)

private fun updateDependency(testee: RegexBasedVersionUpdater, tmpPom: File, project: IdAndVersions) {
    updateDependency(testee, tmpPom, project, project.releaseVersion)
}

private fun updateDependency(
    testee: RegexBasedVersionUpdater,
    tmpPom: File,
    project: IdAndVersions,
    oldVersion: String
) {
    testee.updateDependency(tmpPom, project.id.groupId, project.id.artifactId, oldVersion)
}

