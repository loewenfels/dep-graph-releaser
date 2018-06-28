package ch.loewenfels.depgraph.maven

import ch.loewenfels.depgraph.data.Relation
import ch.loewenfels.depgraph.data.maven.MavenProjectId
import ch.tutteli.kbox.appendToStringBuilder
import ch.tutteli.kbox.mapParents
import fr.lteconsulting.pomexplorer.*
import fr.lteconsulting.pomexplorer.graph.relation.DependencyLikeRelation
import fr.lteconsulting.pomexplorer.graph.relation.ParentRelation
import fr.lteconsulting.pomexplorer.model.Gav
import java.io.File
import java.util.logging.Logger

class Analyser internal constructor(
    directoryWithProjects: File,
    private val session: Session,
    pomFileLoader: PomFileLoader,
    options: Options = Options()
) {
    constructor(directoryWithProjects: File, options: Options)
        : this(directoryWithProjects, Session(), options)

    private constructor(directoryWithProjects: File, session: Session, options: Options)
        : this(directoryWithProjects, session, DefaultPomFileLoader(session, true), options)

    private val logger = Logger.getLogger(Analyser::class.qualifiedName)
    private val dependents: Map<String, Set<Relation<MavenProjectId>>>
    private val projectsData: ProjectDataCollection
    private val pomAnalysis: PomAnalysis

    init {
        require(directoryWithProjects.exists()) {
            "Cannot analyse because the given directory does not exists: ${directoryWithProjects.absolutePath}"
        }
        pomAnalysis = analyseDirectory(directoryWithProjects, pomFileLoader)
        val analysedProjects = getInternalAnalysedProjects()
        require(analysedProjects.isNotEmpty()) {
            "No pom files found in the given directory (which exists): ${directoryWithProjects.absolutePath}"
        }

        projectsData = ProjectDataCollection(session, directoryWithProjects, getInternalAnalysedGavsAsSequence())

        val duplicates = collectDuplicates(pomAnalysis)
        //TODO maybe we should still emit a warning if missing parent analysis is turned off?
        // => introduce warn in addition to off maybe?
        val parentsNotInAnalysis = collectParentsNotInAnalysis(options, analysedProjects)
        //TODO error if a submodule is not part of the analysis
        //val submodulesNotInAnalysis = collectSubmodulesNotInAnalysis(options, analysedProjects)
        reportDuplicatesAndMissingParentsIfNecessary(directoryWithProjects, duplicates, parentsNotInAnalysis)

        dependents = analyseDependents(analysedProjects)
    }

    private fun analyseDirectory(directoryWithProjects: File, pomFileLoader: PomFileLoader): PomAnalysis {
        val nullLogger = Log { }
        return PomAnalysis.runFullRecursiveAnalysis(
            directoryWithProjects.absolutePath,
            session,
            pomFileLoader,
            null,
            false,
            nullLogger
        )
    }

    private fun getInternalAnalysedProjects(): Set<String> {
        return getInternalAnalysedGavsAsSequence()
            .map { it.toMapKey() }
            .toHashSet()
    }

    private fun collectDuplicates(pomAnalysis: PomAnalysis): Map<String, List<Project>> {
        val sequence = pomAnalysis.duplicatedProjects.asSequence() + getInternalAnalysedProjectsAsSequence()
        return sequence
            .groupBy { it.gav.toMapKey() }
            .filterValues { it.size > 1 }
    }


    private fun collectParentsNotInAnalysis(options: Options, analysedProjects: Set<String>): Map<Project, Gav> {
        if (options.missingParentAnalysis) {
            return getInternalAnalysedProjectsAsSequence()
                .filter { it ->
                    val parentGav = it.parentGav
                    parentGav != null && !analysedProjects.contains(parentGav.toMapKey())
                }
                .associateBy({ it }, { it.parentGav })
        }
        logger.info("skipping missing parent analysis")
        return mapOf()
    }

    private fun analyseDependents(analysedProjects: Set<String>): Map<String, Set<Relation<MavenProjectId>>> {
        val dependents = hashMapOf<String, MutableSet<Relation<MavenProjectId>>>()
        getInternalAnalysedGavsAsSequence().forEach { gav ->
            session.graph().read().relations(gav)
                .asSequence()
                .filter { analysedProjects.contains(it.targetToMapKey()) }
                .forEach { relation ->
                    val set = dependents.getOrPut(relation.targetToMapKey(), { mutableSetOf() })
                    when (relation) {
                        is DependencyLikeRelation ->
                            set.add(gav.toRelation(relation.dependency.isVersionSelfManaged.orElse(false)))

                        is ParentRelation ->
                            set.add(gav.toRelation(true))

                    //we ignore other relations at the moment (such as BuildDependencyRelation)
                    }
                }
        }
        return dependents
    }

    private fun getInternalAnalysedGavsAsSequence() = session.projects()
        .keySet()
        .asSequence()
        .filter { it.toProject().isNotExternal }

    private fun getInternalAnalysedProjectsAsSequence() = session.projects()
        .keySet()
        .asSequence()
        .map { it.toProject() }
        .filter { it.isNotExternal }

    private fun Gav.toProject() = session.projects().forGav(this)


    private fun reportDuplicatesAndMissingParentsIfNecessary(
        directoryWithProjects: File,
        duplicates: Map<String, List<Project>>,
        parentsNotInAnalysis: Map<Project, Gav>
    ) {
        val sb = StringBuilder()

        if (duplicates.isNotEmpty()) {
            sb.append("Found duplicated projects in the given `directoryWithProjects`.\n")
                .append("directory: ${directoryWithProjects.absolutePath}\n")
                .append("duplicates:\n\n")
            duplicates.values.appendToStringBuilder(sb, "\n\n") { projects ->
                projects.appendToStringBuilder(sb, "\n") { project ->
                    sb.append(projectToString(project))
                }
            }
        }

        if (parentsNotInAnalysis.isNotEmpty()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("Found projects with parents where the parents are not part of this analysis.\n\n")
            parentsNotInAnalysis.entries.appendToStringBuilder(sb, "\n\n") { (project, parent) ->
                sb.append("project: ").append(projectToString(project)).append("\n")
                sb.append("parent: ").append(parent.groupId).append(":").append(parent.artifactId).append(":")
                    .append(parent.version)
            }
        }

        val erroneousPomFiles = getErroneousPomFiles()
        if (sb.isNotEmpty() && erroneousPomFiles.isNotEmpty()) { //only report if other errors are found
            sb.append("\n\n")
            erroneousPomFiles.appendToStringBuilder(sb, "\n\n") { sb.append(it) }
        }

        val erroneousProjects = getErroneousProjects()
        if (sb.isNotEmpty() && erroneousPomFiles.isNotEmpty()) { //only report if other errors are found
            sb.append("\n\n")
            erroneousProjects.appendToStringBuilder(sb, "\n\n") { sb.append(it) }
        }

        check(sb.isEmpty()) { sb.toString() }
    }

    private fun projectToString(project: Project): String = "${project.gav} (${project.pomFile.absolutePath})"


    /**
     * Returns the current version for the given [projectId] if it was involved in the analysis; `null` otherwise.
     * @return The current version or `null` if [projectId] was not part of the analysis.
     */
    fun getCurrentVersion(projectId: MavenProjectId): String? = projectsData.getProjectIfPresent(projectId)?.version


    /**
     * Returns the [MavenProjectId]s of the analysed projects together with the current version.
     */
    fun getAnalysedProjectsAsString() = projectsData.getIdentifiersWithVersion()

    /**
     * Returns a set of dependent [MavenProjectId]s for the given [projectId] where only the
     * [MavenProjectId.groupId] and [MavenProjectId.artifactId] of the given [projectId] are considered.
     *
     * Meaning, if a project ch.loewenfels:A has a dependency on Project ch.loewenfels:B:1.0 and
     * the analysed project B is in version 2.0-SNAPSHOT then project A is still dependent of project B.
     */
    fun getDependentsOf(projectId: MavenProjectId): Set<Relation<MavenProjectId>> {
        return dependents[projectId.identifier] ?: emptySetIfPartOfAnalysisOrThrow(projectId)
    }

    /**
     * Returns the number of analysed projects.
     */
    fun getNumberOfProjects(): Int = projectsData.size

    fun getErroneousPomFiles(): List<String> = pomAnalysis.erroneousPomFiles.map {
        "Error reading pom file.\nFile: ${it.pomFile.absolutePath}\nMessage: ${it.cause!!.message}"
    }

    fun getErroneousProjects(): List<String> = pomAnalysis.erroneousProjects.map {
        "Project is erroneous and could not be analysed entirely.\nProject ${it.project.gav} \nPom-file: ${it.project.pomFile.absolutePath}\nMessage: ${it.cause!!.message}"
    }

    fun getUnresolvedProperties(): List<String> = pomAnalysis.unresolvedProperties
        .filter{ dependents.containsKey(it.project.gav.toMapKey()) }
        .map {
            "Property ${it.propertyName} could not be resolved.\nProject ${it.project.gav} \nPom-file: ${it.project.pomFile.absolutePath}"
        }


    fun hasSubmodules(projectId: MavenProjectId) = getSubmodules(projectId).isNotEmpty()

    /**
     * Returns all submodules of the multi module project with the given [projectId]
     * or an empty set if the project is not a multi module (has not any modules).
     */
    fun getSubmodules(projectId: MavenProjectId): Set<MavenProjectId> = projectsData.getSubmodules(projectId)

    /**
     * Returns all multi modules of the given submodule project including super multi modules (multi module of multi module)
     * or an empty set if the project is not a submodule.
     */
    fun getMultiModules(projectId: MavenProjectId): LinkedHashSet<MavenProjectId> {
        return projectsData.getProject(projectId).multiModules
    }

    /**
     * Indicates whether the given [projectId] is a submodule of a multi module project or not.
     */
    fun isSubmodule(projectId: MavenProjectId): Boolean = getMultiModules(projectId).isNotEmpty()

    /**
     * Indicates whether the given [submoduleId] is a submodule (can also be a nested submodule) of the multi module
     * project with the given [multiModuleId] or not.
     */
    fun isSubmoduleOf(submoduleId: MavenProjectId, multiModuleId: MavenProjectId): Boolean {
        val submodules = projectsData.getSubmodules(multiModuleId)
        if (submodules.contains(submoduleId)) return true

        return isNestedSubmodule(submodules, submoduleId)
    }

    private tailrec fun isNestedSubmodule(
        submodules: Set<MavenProjectId>,
        submoduleId: MavenProjectId
    ): Boolean {
        if (submodules.isEmpty()) return false

        val submodulesToVisit = hashSetOf<MavenProjectId>()
        submodules.forEach {
            if (it == submoduleId) return true
            submodulesToVisit.addAll(projectsData.getSubmodules(it))
        }
        return isNestedSubmodule(submodulesToVisit, submoduleId)
    }

    /**
     * Returns all identifier
     */
    fun getAllReleasableProjects(): Set<MavenProjectId> {
        return getInternalAnalysedGavsAsSequence()
            .map { it.toMavenProjectId() }
            .filter { !isSubmodule(it) }
            .toHashSet()
    }

    fun getRelativePath(projectId: MavenProjectId): String = projectsData.getProject(projectId).relativePath


    private fun <T> emptySetIfPartOfAnalysisOrThrow(projectId: MavenProjectId): Set<T> {
        //throws if project does not exist
        projectsData.getProject(projectId)
        return emptySet()
    }

    companion object {
        private val Project.isNotExternal get() = !isExternal

        private fun Gav.toRelation(isVersionSelfManaged: Boolean): Relation<MavenProjectId> =
            Relation(toMavenProjectId(), version, isVersionSelfManaged)

        private fun fr.lteconsulting.pomexplorer.graph.relation.Relation.targetToMapKey() = target.toMapKey()
        private fun Gav.toMapKey() = toMavenProjectId().identifier
        private fun Gav.toMavenProjectId() = MavenProjectId(groupId, artifactId)

        private fun throwProjectNotPartOfAnalysis(projectId: MavenProjectId): Nothing {
            throw IllegalArgumentException("project is not part of the analysis: $projectId")
        }
    }

    private class ProjectDataCollection(
        private val session: Session,
        private val directoryWithProjects: File,
        private val internalAnalysedGavs: Sequence<Gav>
    ) {
        private val projects = HashMap<MavenProjectId, ProjectData>()
        val size get() = projects.size

        init {
            val (submodulesOfProjectId, multiModuleOfSubmodule) = analyseSubmodules()
            val allMultiModules = complementMultiModules(submodulesOfProjectId, multiModuleOfSubmodule)
            internalAnalysedGavs.forEach { gav ->
                val mavenProjectId = gav.toMavenProjectId()
                val submodules = submodulesOfProjectId[mavenProjectId] ?: emptySet()
                val multiModules = allMultiModules[mavenProjectId] ?: LinkedHashSet(0)
                val relativePath = calculateRelativePath(gav)

                projects[mavenProjectId] = ProjectData(gav.version, submodules, multiModules, relativePath)
            }
        }

        private fun analyseSubmodules(): kotlin.Pair<Map<MavenProjectId, Set<MavenProjectId>>, Map<MavenProjectId, MavenProjectId>> {
            val submodulesOfProjectId = hashMapOf<MavenProjectId, HashSet<MavenProjectId>>()
            val multiModuleOfSubmodule = hashMapOf<MavenProjectId, MavenProjectId>()
            internalAnalysedGavs.forEach { gav ->
                val gavsToVisit = linkedSetOf(gav)
                while (gavsToVisit.isNotEmpty()) {
                    val multiModuleGav = gavsToVisit.iterator().next()
                    gavsToVisit.remove(multiModuleGav)
                    val multiModuleProjectId = multiModuleGav.toMavenProjectId()

                    session.projects().getSubmodulesAsStream(multiModuleGav).forEach { submoduleGav ->
                        val submoduleProjectId = submoduleGav.toMavenProjectId()
                        val submodules = submodulesOfProjectId.getOrPut(multiModuleProjectId, { hashSetOf() })
                        val notAlreadyContained = submodules.add(submoduleProjectId)
                        //just to prevent from maniac projects which have cyclic modules defined :)
                        if (notAlreadyContained && !gavsToVisit.contains(submoduleGav)) {
                            gavsToVisit.add(submoduleGav)
                        }
                        multiModuleOfSubmodule[submoduleProjectId] = multiModuleProjectId
                    }
                }
            }
            return submodulesOfProjectId to multiModuleOfSubmodule
        }

        private fun complementMultiModules(
            submodulesOfProjectId: Map<MavenProjectId, Set<MavenProjectId>>,
            multiModuleOfSubmodule: Map<MavenProjectId, MavenProjectId>
        ): Map<MavenProjectId, LinkedHashSet<MavenProjectId>> {
            val map = hashMapOf<MavenProjectId, LinkedHashSet<MavenProjectId>>()
            submodulesOfProjectId.forEach { multiModuleId, submodules ->
                submodules.forEach { submoduleId ->
                    val set = linkedSetOf(multiModuleId)
                    //TODO would go forever if there are multi modules which have one another as modules, should we add a check?
                    set.addAll(multiModuleOfSubmodule.mapParents(multiModuleId))
                    map[submoduleId] = set
                }
            }
            return map
        }

        private fun calculateRelativePath(gav: Gav): String {
            val projectDir = session.projects().forGav(gav).pomFile.parentFile
            val path = directoryWithProjects.toURI().relativize(projectDir.toURI()).path
            return if (path.isNotEmpty()) path else "./"
        }

        fun getProjectIfPresent(projectId: MavenProjectId): ProjectData? = projects[projectId]
        fun getProject(projectId: MavenProjectId): ProjectData {
            return projects[projectId] ?: throwProjectNotPartOfAnalysis(projectId)
        }

        fun getSubmodules(projectId: MavenProjectId): Set<MavenProjectId> {
            return getProject(projectId).submodules
        }

        fun getIdentifiersWithVersion(): String {
            return projects.entries.joinToString("\n") { (mavenProjectId, project) ->
                "${mavenProjectId.identifier}:${project.version}"
            }
        }
    }

    private class ProjectData(
        val version: String,
        val submodules: Set<MavenProjectId>,
        val multiModules: LinkedHashSet<MavenProjectId>,
        val relativePath: String
    )

    /**
     * Options for the [Analyser].
     */
    data class Options(
        val missingParentAnalysis: Boolean = true
    )
}
