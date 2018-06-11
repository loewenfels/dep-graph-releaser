package ch.loewenfels.depgraph.runner.commands

import ch.loewenfels.depgraph.runner.console.ErrorHandler
import ch.loewenfels.depgraph.runner.Orchestrator

object UpdateDependency : ConsoleCommand {

    override val name = "update"
    override val description = "updates the given dependency to the given version"
    override val example = "./dgr $name ./pom.xml com.example example-project 1.2.0"
    override val arguments = """
        |$name requires the following arguments in the given order:
        |pom         // path to the pom file which shall be updated
        |groupId     // maven groupId of the dependency which shall be updated
        |artifactId  // maven artifactId of the dependency which shall be updated
        |newVersion  // the new version which shall be used for the dependency
        """.trimMargin()

    override fun numOfArgsNotOk(number: Int) = number != 5

    override fun execute(args: Array<out String>, errorHandler: ErrorHandler) {
        val (_, pomFile, groupId, artifactId, newVersion) = args

        val pom = toVerifiedExistingFile(
            pomFile, "pom file", this, args, errorHandler
        )

        Orchestrator.updateDependency(pom, groupId, artifactId, newVersion)
    }
}
