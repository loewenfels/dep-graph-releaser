package ch.loewenfels.depgraph.data

import ch.loewenfels.depgraph.ConfigKey
import ch.loewenfels.depgraph.LevelIterator

/**
 * Represents a release plan where the [Project] with the given [rootProjectId] shall be released.
 *
 * Dependent projects (incl. transitive) of the root project as well as the root project itself
 * are stored in [projects]. The relation between the projects are stored in [dependents]
 * where the key represents the dependency and the value the dependent.
 */
data class ReleasePlan(
    val releaseId: String,
    val state: ReleaseState,
    val typeOfRun: TypeOfRun,
    val rootProjectId: ProjectId,
    private val projects: Map<ProjectId, Project>,
    private val submodules: Map<ProjectId, Set<ProjectId>>,
    private val dependents: Map<ProjectId, Set<ProjectId>>,
    val warnings: List<String>,
    val infos: List<String>,
    val config: Map<ConfigKey, String>
) {

    /**
     * Copy constructor to replace [projects].
     */
    constructor(releasePlan: ReleasePlan, projects: Map<ProjectId, Project>) : this(
        releasePlan.releaseId,
        releasePlan.state,
        releasePlan.typeOfRun,
        releasePlan.rootProjectId,
        projects,
        releasePlan.submodules,
        releasePlan.dependents,
        releasePlan.warnings,
        releasePlan.infos,
        releasePlan.config
    )

    fun getRootProject(): Project = getProject(rootProjectId)
    fun getProject(projectId: ProjectId): Project =
        projects[projectId] ?: throw IllegalArgumentException("Could not find the project with id $projectId")

    fun hasSubmodules(projectId: ProjectId) = getSubmodules(projectId).isNotEmpty()
    fun getSubmodules(projectId: ProjectId): Set<ProjectId> =
        submodules[projectId] ?: throw IllegalArgumentException(
            "Could not find submodules for project with id $projectId"
        )

    fun getDependents(projectId: ProjectId): Set<ProjectId> =
        dependents[projectId] ?: throw IllegalArgumentException(
            "Could not find dependents for project with id $projectId"
        )

    fun getConfig(configKey: ConfigKey): String =
        config[configKey] ?: throw IllegalArgumentException("Unknown config key: $configKey")

    /**
     * Returns the dependents of the given [multiModuleId] as well as the dependents of submodules and
     * dependents of nested submodules.
     *
     * @return A pair where [Pair.first] is the [ProjectId] id of the multi module or one of the submodules and
     *   [Pair.second] is the project id of the dependent.
     */
    fun collectDependentsInclDependentsOfAllSubmodules(multiModuleId: ProjectId): HashSet<Pair<ProjectId, ProjectId>> {
        val projectIds = hashSetOf<Pair<ProjectId, ProjectId>>()
        val projectsToVisit = mutableListOf(multiModuleId)
        do {
            val projectId = projectsToVisit.removeAt(0)
            projectIds.addAll(getDependents(projectId).map { projectId to it })
            projectsToVisit.addAll(getSubmodules(projectId))
        } while (projectsToVisit.isNotEmpty())
        return projectIds
    }

    fun iterator(): Iterator<Project> = ReleasePlanIterator(this)

    fun getProjectIds(): Set<ProjectId> = projects.keys
    fun getProjects(): Collection<Project> = projects.values
    fun getNumberOfProjects(): Int = projects.size

    fun getNumberOfDependents(): Int = dependents.size
    fun getAllDependents(): Map<ProjectId, Set<ProjectId>> = dependents
    fun getAllSubmodules(): Map<ProjectId, Set<ProjectId>> = submodules


    @Suppress(
        "IteratorNotThrowingNoSuchElementException"
    )
    private class ReleasePlanIterator(
        private val releasePlan: ReleasePlan
    ) : Iterator<Project> {
        private val levelIterator = LevelIterator(releasePlan.rootProjectId to releasePlan.getRootProject())
        private val visitedProjects = hashSetOf<ProjectId>()

        override fun hasNext() = levelIterator.hasNext()
        override fun next(): Project {
            val project = levelIterator.next()
            visitedProjects.add(project.id)
            releasePlan.getDependents(project.id)
                .asSequence()
                .filter { !visitedProjects.contains(it) }
                .map { releasePlan.getProject(it) }
                .filter { it.level == project.level + 1 || (it.isSubmodule && it.level == project.level) }
                .forEach {
                    if (it.level == project.level) {
                        levelIterator.addToCurrentLevel(it.id to it)
                    } else {
                        levelIterator.addToNextLevel(it.id to it)
                    }
                }
            return project
        }
    }
}
