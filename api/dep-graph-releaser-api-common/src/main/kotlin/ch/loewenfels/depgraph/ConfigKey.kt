package ch.loewenfels.depgraph

enum class ConfigKey(private val key: kotlin.String) {
    COMMIT_PREFIX("commitPrefix"),
    UPDATE_DEPENDENCY_JOB("updateDependencyJob"),
    DRY_RUN_JOB("dryRunJob"),
    REMOTE_REGEX("remoteRegex"),
    RELATIVE_PATH_EXCLUDE_PROJECT_REGEX("relativePathExcludeProjectsRegex"),
    RELATIVE_PATH_TO_GIT_REPO_REGEX("relativePathToGitRepoRegex"),
    RELATIVE_PATH_TO_GIT_REPO_REPLACEMENT("relativePathToGitRepoReplacement"),
    REGEX_PARAMS("regexParams"),
    JOB_MAPPING("jobMapping"),
    BUILD_WITH_PARAM_JOBS("buildWithParamJobs"),
    INITIAL_RELEASE_JSON("initialJson"),
    ;

    fun asString(): String = key

    companion object {
        fun fromString(key: String): ConfigKey = values().first { it.asString() == key  }
    }
}
