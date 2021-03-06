package ch.loewenfels.depgraph.gui.components

import ch.loewenfels.depgraph.data.*
import ch.loewenfels.depgraph.data.maven.MavenProjectId
import ch.loewenfels.depgraph.data.maven.jenkins.JenkinsCommand
import ch.loewenfels.depgraph.data.maven.jenkins.JenkinsMultiMavenReleasePlugin
import ch.loewenfels.depgraph.data.maven.jenkins.JenkinsSingleMavenReleaseCommand
import ch.loewenfels.depgraph.data.maven.jenkins.JenkinsUpdateDependency
import ch.loewenfels.depgraph.data.maven.syntheticRoot
import ch.loewenfels.depgraph.gui.components.Messages.Companion.showError
import ch.loewenfels.depgraph.gui.components.Messages.Companion.showThrowableAndThrow
import ch.loewenfels.depgraph.gui.elementById
import ch.loewenfels.depgraph.gui.getCheckbox
import ch.loewenfels.depgraph.gui.getUnderlyingHtmlElement
import ch.loewenfels.depgraph.gui.jobexecution.GITHUB_NEW_ISSUE
import ch.loewenfels.depgraph.gui.jobexecution.ProcessStarter
import ch.loewenfels.depgraph.gui.serialization.ModifiableState
import ch.loewenfels.depgraph.hasNextOnTheSameLevel
import ch.tutteli.kbox.toPeekingIterator
import kotlinx.html.*
import kotlinx.html.dom.append
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLElement
import kotlin.dom.addClass
import kotlin.dom.hasClass
import kotlin.dom.removeClass

class Pipeline(private val modifiableState: ModifiableState, private val menu: Menu, processStarter: ProcessStarter?) {
    private val contextMenu = ContextMenu(modifiableState, menu, processStarter)

    init {
        setUpProjects()
        Toggler(modifiableState, menu)
        contextMenu.setUpOnContextMenuForProjectsAndCommands()
    }

    private fun setUpProjects() {
        val releasePlan = modifiableState.releasePlan
        val set = hashSetOf<ProjectId>()
        val pipeline = elementById(PIPELINE_HTML_ID)
        pipeline.asDynamic().state = releasePlan.state
        pipeline.asDynamic().typeOfRun = releasePlan.typeOfRun
        pipeline.append {
            val itr = releasePlan.iterator().toPeekingIterator()
            var level: Int
            // skip synthetic root
            if (itr.hasNext() && itr.peek().id == syntheticRoot) {
                set.add(itr.next().id) // still count it though
            }
            while (itr.hasNext()) {
                val project = itr.next()
                level = project.level

                div("level l$level") {
                    if (!project.isSubmodule) {
                        project(project)
                    }
                    set.add(project.id)
                    while (itr.hasNextOnTheSameLevel(level)) {
                        val nextProject = itr.next()
                        if (!nextProject.isSubmodule) {
                            project(nextProject)
                        }
                        set.add(nextProject.id)
                    }
                }
            }
        }
        updateStatus(releasePlan, set)
    }

    private fun updateStatus(releasePlan: ReleasePlan, set: HashSet<ProjectId>) {
        val involvedProjects = set.size
        val status = elementById("status")
        status.innerText = "Projects involved: $involvedProjects"
        val numOfSubmodules = releasePlan.getProjects().count { it.isSubmodule }
        val numOfMultiModules = involvedProjects - numOfSubmodules
        status.title =
            "multi-module/single Projects: $numOfMultiModules, submodules: $numOfSubmodules"
        if (involvedProjects != releasePlan.getNumberOfProjects()) {
            showError(
                """
                    |Not all dependent projects are involved in the process.
                    |Please report a bug: $GITHUB_NEW_ISSUE
                    |The following projects where left out of the analysis:
                    |${(releasePlan.getProjectIds() - set).joinToString("\n") { it.identifier }}
                """.trimMargin()
            )
        }
    }

    private fun DIV.project(project: Project) {
        div {
            getUnderlyingHtmlElement().asDynamic().project = project
            val hasCommands = project.commands.isNotEmpty()
            classes = setOf(
                PROJECT_CSS_CLASS,
                if (project.isSubmodule) "submodule" else "",
                if (!hasCommands) "withoutCommands" else "",
                if (modifiableState.releasePlan.hasSubmodules(project.id)) "withSubmodules" else ""
            )

            val identifier = project.id.identifier
            this.id = identifier
            div("title") {
                span {
                    projectId(project.id)
                }
            }
            if (!project.isSubmodule) {
                div("fields") {
                    textFieldReadOnlyWithLabel(
                        "$identifier:currentVersion", "Current Version", project.currentVersion, menu
                    )
                    textFieldWithLabel("$identifier:releaseVersion", "Release Version", project.releaseVersion, menu)
                }
                this@Pipeline.contextMenu.createProjectContextMenu(this, project)
            }
            commands(project)

            if (project.isSubmodule) {
                // means we are within a multi-module and might want to show submodules of this submodule
                submodules(project.id)
            }
        }
    }

    private fun CommonAttributeGroupFacade.projectId(id: ProjectId) {
        if (id is MavenProjectId) {
            title = id.identifier
            +id.artifactId
        } else {
            +id.identifier
        }
    }

    private fun INPUT.projectId(id: ProjectId) {
        if (id is MavenProjectId) {
            title = id.identifier
            value = id.artifactId
        } else {
            value = id.identifier
        }
    }

    private fun DIV.commands(project: Project) {
        project.commands.forEachIndexed { index, command ->
            div {
                val commandId = getCommandId(project, index)
                id = commandId
                classes = setOf("command", stateToCssClass(command.state))
                div("commandTitle") {
                    id = "$commandId$TITLE_SUFFIX"
                    +command::class.simpleNameNonNull
                }
                div("fields") {
                    fieldsForCommand(commandId, project, index, command)
                }
                val div = getUnderlyingHtmlElement().asDynamic()
                div.state = command.state
                if (command is JenkinsCommand) {
                    div.buildUrl = command.buildUrl
                }
            }
        }
    }

    private fun DIV.fieldsForCommand(idPrefix: String, project: Project, index: Int, command: Command) {
        commandToggle(command, idPrefix)
        commandState(idPrefix, command)

        this@Pipeline.contextMenu.createCommandContextMenu(this, idPrefix, project, index)

        when (command) {
            is JenkinsSingleMavenReleaseCommand ->
                appendJenkinsMavenReleasePluginField(idPrefix, command)
            is JenkinsMultiMavenReleasePlugin ->
                appendJenkinsMultiMavenReleasePluginFields(idPrefix, project.id, command)
            is JenkinsUpdateDependency ->
                appendJenkinsUpdateDependencyField(idPrefix, command)
            else ->
                showThrowableAndThrow(
                    IllegalStateException("Unknown command found, cannot display its fields.\n$command")
                )
        }
    }

    private fun DIV.commandToggle(command: Command, idPrefix: String) {
        val cssClass = if (command is ReleaseCommand) "release" else ""
        val isNotDeactivated = command.state !is CommandState.Deactivated
        toggle(
            "$idPrefix$DEACTIVATE_SUFFIX",
            if (isNotDeactivated) "Click to deactivate command" else "Click to activate command",
            isNotDeactivated,
            command.state === CommandState.Disabled,
            cssClass
        )
    }

    private fun DIV.commandState(idPrefix: String, command: Command) {
        a(classes = "state") {
            id = "$idPrefix$STATE_SUFFIX"
            i("material-icons") {
                span()
                id = "$idPrefix:status.icon"
            }
            if (command is JenkinsCommand) {
                href = command.buildUrl ?: ""
            }
            title = stateToTitle(command.state)
        }
    }

    private fun DIV.appendJenkinsMavenReleasePluginField(idPrefix: String, command: JenkinsSingleMavenReleaseCommand) {
        fieldNextDevVersion(idPrefix, command, command.nextDevVersion)
    }

    private fun DIV.fieldNextDevVersion(idPrefix: String, command: Command, nextDevVersion: String) {
        textFieldWithLabel("$idPrefix$NEXT_DEV_VERSION_SUFFIX", "Next Dev Version", nextDevVersion, menu) {
            if (command.state === CommandState.Disabled) {
                disabled = true
            }
        }
    }

    private fun DIV.appendJenkinsMultiMavenReleasePluginFields(
        idPrefix: String,
        projectId: ProjectId,
        command: JenkinsMultiMavenReleasePlugin
    ) {
        fieldNextDevVersion(idPrefix, command, command.nextDevVersion)
        submodules(projectId)
    }

    private fun DIV.submodules(projectId: ProjectId) {
        val submodules = modifiableState.releasePlan.getSubmodules(projectId)
        if (submodules.isEmpty()) return

        div("submodules") {
            submodules.forEach {
                project(modifiableState.releasePlan.getProject(it))
            }
        }
    }

    private fun DIV.appendJenkinsUpdateDependencyField(idPrefix: String, command: JenkinsUpdateDependency) {
        textFieldReadOnlyWithLabel("$idPrefix:groupId", "Dependency", command.projectId.identifier, menu) {
            projectId(command.projectId)
        }
    }

    private fun DIV.toggle(
        idCheckbox: String,
        title: String,
        checked: Boolean,
        disabled: Boolean,
        checkboxCssClass: String = ""
    ) {
        label("toggle") {
            checkBoxInput(classes = checkboxCssClass) {
                this.id = idCheckbox
                this.checked = checked && !disabled
                this.disabled = disabled
            }
            span("slider") {
                this.id = "$idCheckbox$SLIDER_SUFFIX"
                this.title = title
                if (disabled) {
                    this.title = STATE_DISABLED
                }
            }
        }
    }

    companion object {
        private const val PIPELINE_HTML_ID = "pipeline"
        private const val PROJECT_CSS_CLASS = "project"

        private const val STATE_WAITING = "Wait for dependent projects to complete."
        private const val STATE_READY = "Ready to be queued for execution."
        private const val STATE_READY_TO_BE_TRIGGER = "Ready to be re-triggered."
        private const val STATE_READY_TO_RE_POLL = "Ready to be re-polled."
        private const val STATE_QUEUEING = "Currently queueing the job."
        private const val STATE_RE_POLLING = "Command is being re-polled."
        private const val STATE_IN_PROGRESS = "Command is running."
        private const val STATE_SUCCEEDED = "Command completed successfully."
        private const val STATE_FAILED =
            "Command failed - click to navigate to the console or the queue item of the job."
        private const val STATE_TIMEOUT =
            "Command run into a timeout - click to navigate to the console or the queue item of the job."
        private const val STATE_DEACTIVATED = "Currently deactivated, click to activate."
        private const val STATE_DISABLED = "Command disabled, cannot be reactivated."

        const val DEACTIVATE_SUFFIX = ":deactivate"
        const val SLIDER_SUFFIX = ":slider"
        const val NEXT_DEV_VERSION_SUFFIX = ":nextDevVersion"
        const val STATE_SUFFIX = ":state"
        const val TITLE_SUFFIX = ":title"


        fun getCommandId(project: Project, index: Int) = getCommandId(project.id, index)
        fun getCommandId(projectId: ProjectId, index: Int) = "${projectId.identifier}:$index"
        fun getCommand(project: Project, index: Int) = getCommand(project.id, index)
        fun getCommand(projectId: ProjectId, index: Int): HTMLElement = elementById(getCommandId(projectId, index))

        fun getToggle(project: Project, index: Int) =
            getCheckbox("${getCommandId(project.id, index)}$DEACTIVATE_SUFFIX")

        fun getCommandState(projectId: ProjectId, index: Int) = getCommandState(getCommandId(projectId, index))
        fun getCommandState(idPrefix: String) = elementById(idPrefix).asDynamic().state as CommandState

        fun changeStateOfCommandAndAddBuildUrl(
            project: Project,
            index: Int,
            newState: CommandState,
            buildUrl: String
        ) = changeStateOfCommandAndAddBuildUrlIfSet(project, index, newState, buildUrl)

        fun changeStateOfCommandAndAddBuildUrlIfSet(
            project: Project,
            index: Int,
            newState: CommandState,
            buildUrl: String?
        ) {
            changeStateOfCommand(project, index, newState)
            if (buildUrl != null) {
                changeBuildUrlOfCommand(project, index, buildUrl)
            }
        }

        fun changeBuildUrlOfCommand(project: Project, index: Int, buildUrl: String) {
            val commandId = getCommandId(project, index)
            elementById<HTMLAnchorElement>("$commandId$STATE_SUFFIX").href = buildUrl
            elementById(commandId).asDynamic().buildUrl = buildUrl
        }

        fun changeStateOfCommand(project: Project, index: Int, newState: CommandState) {
            changeStateOfCommand(project, index, newState) { previousState, commandId ->
                try {
                    previousState.checkTransitionAllowed(newState)
                } catch (e: IllegalStateException) {
                    //TODO use $this instead of $getToStringRepresentation(...) once
                    // https://youtrack.jetbrains.com/issue/KT-23970 is fixed
                    throw IllegalStateException(
                        "Cannot change the state of the command to ${newState.getToStringRepresentation()}." +
                            failureDiagnosticsStateTransition(project, index, previousState, commandId),
                        e
                    )
                }
            }
        }

        fun failureDiagnosticsStateTransition(
            project: Project,
            index: Int,
            previousState: CommandState,
            commandId: String
        ): String {
            //TODO use $this instead of $getToStringRepresentation(...) once
            // https://youtrack.jetbrains.com/issue/KT-23970 is fixed
            val commandTitle = elementById(commandId + TITLE_SUFFIX)
            return "\nProject: ${project.id.identifier}" +
                "\nCommand: ${commandTitle.innerText} (${index + 1}. command)" +
                "\nCurrent state: ${previousState.getToStringRepresentation()}"
        }

        internal fun changeStateOfCommand(
            project: Project,
            index: Int,
            newState: CommandState,
            checkStateTransition: (previousState: CommandState, commandId: String) -> CommandState
        ) {
            val commandId = getCommandId(project, index)
            val command = elementById(commandId)
            val dynCommand = command.asDynamic()
            val previousState = dynCommand.state as CommandState
            dynCommand.state = checkStateTransition(previousState, commandId)
            command.removeClass(stateToCssClass(previousState))
            command.addClass(stateToCssClass(newState))
            elementById("$commandId$STATE_SUFFIX").title = stateToTitle(newState)
        }

        fun getReleaseState() = getPipelineAsDynamic().state as ReleaseState
        fun getTypeOfRun() = getPipelineAsDynamic().typeOfRun as TypeOfRun

        fun changeReleaseState(newState: ReleaseState) {
            getPipelineAsDynamic().state = getReleaseState().checkTransitionAllowed(newState)
        }

        fun changeTypeOfRun(newTypeOfRun: TypeOfRun) {
            getPipelineAsDynamic().typeOfRun = newTypeOfRun
        }

        private fun getPipelineAsDynamic() = elementById(PIPELINE_HTML_ID).asDynamic()

        private fun stateToCssClass(state: CommandState) = when (state) {
            is CommandState.Waiting -> "waiting"
            CommandState.Ready -> "ready"
            CommandState.ReadyToReTrigger -> "readyToReTrigger"
            CommandState.ReadyToRePoll -> "readyToRePoll"
            CommandState.Queueing,
            CommandState.StillQueueing -> "queueing"
            CommandState.RePolling -> "rePolling"
            CommandState.InProgress -> "inProgress"
            CommandState.Succeeded -> "succeeded"
            CommandState.Failed -> "failed"
            is CommandState.Timeout -> "timeout"
            is CommandState.Deactivated -> "deactivated"
            CommandState.Disabled -> "disabled"
        }

        internal fun stateToTitle(state: CommandState) = when (state) {
            is CommandState.Waiting -> STATE_WAITING
            CommandState.Ready -> STATE_READY
            CommandState.ReadyToReTrigger -> STATE_READY_TO_BE_TRIGGER
            CommandState.ReadyToRePoll -> STATE_READY_TO_RE_POLL
            CommandState.Queueing,
            CommandState.StillQueueing -> STATE_QUEUEING
            CommandState.RePolling -> STATE_RE_POLLING
            CommandState.InProgress -> STATE_IN_PROGRESS
            CommandState.Succeeded -> STATE_SUCCEEDED
            CommandState.Failed -> STATE_FAILED
            is CommandState.Timeout -> STATE_TIMEOUT
            is CommandState.Deactivated -> STATE_DEACTIVATED
            CommandState.Disabled -> STATE_DISABLED
        }

        fun getSurroundingProject(id: String): Project {
            var node = elementById(id).parentNode
            while (node is HTMLElement && !node.hasClass(PROJECT_CSS_CLASS)) {
                node = node.parentNode
            }
            check(node is HTMLElement && node.hasClass(PROJECT_CSS_CLASS)) {
                "Cannot determine whether input field should be re-activated or not, could not get surrounding project"
            }
            return node.asDynamic().project as Project
        }
    }
}
