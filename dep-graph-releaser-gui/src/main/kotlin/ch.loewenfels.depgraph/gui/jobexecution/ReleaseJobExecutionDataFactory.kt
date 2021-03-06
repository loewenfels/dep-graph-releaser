package ch.loewenfels.depgraph.gui.jobexecution

import ch.loewenfels.depgraph.*
import ch.loewenfels.depgraph.data.Command
import ch.loewenfels.depgraph.data.Project
import ch.loewenfels.depgraph.data.ReleasePlan
import ch.loewenfels.depgraph.data.maven.MavenProjectId
import ch.loewenfels.depgraph.data.maven.jenkins.JenkinsNextDevReleaseCommand
import ch.loewenfels.depgraph.data.maven.jenkins.JenkinsUpdateDependency
import ch.loewenfels.depgraph.jobexecution.BuildWithParamFormat
import ch.tutteli.kbox.appendToStringBuilder

class ReleaseJobExecutionDataFactory(
    defaultJenkinsBaseUrl: String,
    releasePlan: ReleasePlan
) : BaseJobExecutionDataFactory(defaultJenkinsBaseUrl, releasePlan) {

    private val remoteRegex: List<Pair<Regex, String>>
    private val regexParametersList: List<Pair<Regex, List<String>>>
    private val jobMapping: Map<String, String>
    private val buildWithParamJobsList: List<Pair<Regex, BuildWithParamFormat>>

    init {
        checkConfig(releasePlan.config)
        remoteRegex = parseRemoteRegex(releasePlan)
        regexParametersList = parseRegexParams(releasePlan)
        jobMapping = parseJobMapping(releasePlan)
        buildWithParamJobsList = parseBuildWithParamJobs(releasePlan)
    }

    private fun checkConfig(config: Map<ConfigKey, String>) {
        requireConfigEntry(config, ConfigKey.UPDATE_DEPENDENCY_JOB)
        requireConfigEntry(config, ConfigKey.REMOTE_REGEX)
        requireConfigEntry(config, ConfigKey.COMMIT_PREFIX)
    }

    override fun create(project: Project, command: Command): JobExecutionData {
        return when (command) {
            is JenkinsUpdateDependency -> triggerUpdateDependency(project, command)
            is JenkinsNextDevReleaseCommand -> triggerRelease(project, command)
            else -> throw UnsupportedOperationException("We do not (yet) support the command: $command")
        }
    }

    private fun triggerUpdateDependency(project: Project, command: JenkinsUpdateDependency): JobExecutionData {
        val jobUrl = getJobUrl(ConfigKey.UPDATE_DEPENDENCY_JOB)
        val jobName = "update dependency of ${project.id.identifier}"
        val params = createUpdateDependencyParams(project, command)
        return JobExecutionData.buildWithParameters(jobName, jobUrl, toQueryParameters(params), params)
    }

    private fun createUpdateDependencyParams(project: Project, command: JenkinsUpdateDependency): Map<String, String> {
        val dependency = releasePlan.getProject(command.projectId)
        val dependencyMavenProjectId = dependency.id as MavenProjectId
        return mapOf(
            "pathToProject" to project.relativePath,
            "&groupId" to dependencyMavenProjectId.groupId,
            "&artifactId" to dependencyMavenProjectId.artifactId,
            "&newVersion" to dependency.releaseVersion,
            "&commitPrefix" to getConfig(ConfigKey.COMMIT_PREFIX),
            "&releaseId" to releasePlan.releaseId
        )
    }

    private fun triggerRelease(project: Project, command: JenkinsNextDevReleaseCommand): JobExecutionData {
        val mavenProjectId = project.id as MavenProjectId
        val jobName = getJobName(project)
        val jenkinsBaseUrl = getMatchingEntries(remoteRegex, mavenProjectId).firstOrNull() ?: defaultJenkinsBaseUrl
        val jobUrl = getJobUrl(jenkinsBaseUrl, jobName)
        val relevantParams = getMatchingEntries(regexParametersList, mavenProjectId).flatMap { it.asSequence() }
        val buildWithParamFormat = getMatchingEntries(buildWithParamJobsList, mavenProjectId).firstOrNull()

        return if (buildWithParamFormat != null) {
            triggerBuildWithParamRelease(buildWithParamFormat, relevantParams, project, command, jobUrl)
        } else {
            triggerM2Release(relevantParams, project, command, jobUrl)
        }
    }

    private fun getJobName(project: Project): String {
        val mavenProjectId = project.id as MavenProjectId
        return jobMapping[mavenProjectId.identifier] ?: mavenProjectId.artifactId
    }

    private fun triggerBuildWithParamRelease(
        buildWithParamFormat: BuildWithParamFormat,
        relevantParams: Sequence<String>,
        project: Project,
        command: JenkinsNextDevReleaseCommand,
        jobUrl: String
    ): JobExecutionData {
        val identifyingParams = buildWithParamFormat.format(project.releaseVersion, command.nextDevVersion)
        return JobExecutionData.buildWithParameters(
            "release ${project.id.identifier}",
            jobUrl,
            toQueryParameters(identifyingParams) + "&" + relevantParams.joinToString("&"),
            identifyingParams
        )
    }

    private fun triggerM2Release(
        relevantParams: Sequence<String>,
        project: Project,
        command: JenkinsNextDevReleaseCommand,
        jobUrl: String
    ): JobExecutionData {
        val parameters = StringBuilder()
        relevantParams.appendToStringBuilder(parameters, ",") {
            val (name, value) = it.split('=')
            parameters.append("{\"name\":\"$name\",\"value\":\"$value\"}")
        }
        val params = "releaseVersion=${project.releaseVersion}" +
            "&developmentVersion=${command.nextDevVersion}" +
            "&json={parameter=[$parameters]}"
        return JobExecutionData.m2ReleaseSubmit(
            "release ${project.id.identifier}",
            jobUrl,
            params,
            project.releaseVersion,
            command.nextDevVersion
        )
    }

    private fun <T> getMatchingEntries(
        regex: List<Pair<Regex, T>>,
        mavenProjectId: MavenProjectId
    ) = regex.asSequence().filter { (regex, _) -> regex.matches(mavenProjectId.identifier) }.map { it.second }
}
