package ch.loewenfels.depgraph.gui

import ch.loewenfels.depgraph.data.ReleasePlan
import org.w3c.fetch.Response
import kotlin.browser.window
import kotlin.js.Promise

class App {
    private val publishJobUrl: String?
    private val jenkinsUrl: String?
    private val menu: Menu

    init {
        switchLoader("loaderJs", "loaderApiToken")

        val jsonUrl = determineJsonUrl()
        publishJobUrl = determinePublishJob()
        jenkinsUrl = publishJobUrl?.substringBefore("/job/")
        menu = Menu()
        start(jsonUrl)
    }

    private fun determinePublishJob(): String? {
        return if (window.location.hash.contains(PUBLISH_JOB)) {
            getJobUrl(window.location.hash.substringAfter(PUBLISH_JOB))
        } else {
            null
        }
    }

    private fun getJobUrl(possiblyRelativePublishJobUrl: String): String {
        require(!possiblyRelativePublishJobUrl.contains("://") || possiblyRelativePublishJobUrl.startsWith("http")) {
            "The publish job URL does not start with http but contains ://"
        }

        val prefix = window.location.protocol + "//" + window.location.hostname + "/"
        val tmpUrl = if (possiblyRelativePublishJobUrl.contains("://")) {
            possiblyRelativePublishJobUrl
        } else {
            prefix + possiblyRelativePublishJobUrl
        }
        return if (tmpUrl.endsWith("/")) tmpUrl else "$tmpUrl/"
    }


    private fun determineJsonUrl(): String {
        return if (window.location.hash != "") {
            window.location.hash.substring(1).substringBefore("&")
        } else {
            showThrowableAndThrow(
                IllegalStateException(
                    "You need to specify a release.json." +
                        "\nAppend the path with preceding # to the url, e.g., ${window.location}#release.json"
                )
            )
        }
    }

    private fun start(jsonUrl: String) {
        retrieveUserAndApiToken().then { usernameToken ->
            display("gui", "block")

            loadJson(jsonUrl, usernameToken)
                .then(::checkStatusOk)
                .catch {
                    throw Error("Could not load json.", it)
                }.then { body: String ->
                    switchLoader("loaderApiToken", "loaderJson")
                    val modifiableJson = ModifiableJson(body)
                    val releasePlan = deserialize(body)
                    val dependencies = createDependencies(
                        jenkinsUrl, publishJobUrl, usernameToken, modifiableJson, releasePlan, menu
                    )
                    menu.initDependencies(releasePlan, Downloader(modifiableJson), dependencies, modifiableJson)
                    Gui(releasePlan, menu).load()
                    switchLoaderJsonWithPipeline()
                }.catch {
                    showThrowableAndThrow(it)
                }
        }
    }


    private fun retrieveUserAndApiToken(): Promise<UsernameToken?> {
        return if (jenkinsUrl == null) {
            menu.disableButtonsDueToNoPublishUrl()
            Promise.resolve(null as UsernameToken?)
        } else {
            window.fetch("$jenkinsUrl/me/configure", createFetchInitWithCredentials())
                .then(::checkStatusOkOr403)
                .then { body: String? ->
                    if (body == null) {
                        val info = "You need to log in if you want to use other functionality than Download."
                        menu.disableButtonsDueToNoAuth(info, "$info\n$jenkinsUrl/login?from=" + window.location)
                        null
                    } else {
                        val (username, name, apiToken) = extractNameAndApiToken(body)
                        menu.setVerifiedUser(username, name)
                        UsernameToken(username, apiToken)
                    }
                }
        }
    }

    private fun extractNameAndApiToken(body: String): Triple<String, String, String> {
        val usernameMatch = usernameRegex.find(body) ?: throw IllegalStateException("Could not find username")
        val fullNameMatch = fullNameRegex.find(body) ?: throw IllegalStateException("Could not find user's name")
        val apiTokenMatch = apiTokenRegex.find(body) ?: throw IllegalStateException("Could not find API token")
        return Triple(usernameMatch.groupValues[1], fullNameMatch.groupValues[1], apiTokenMatch.groupValues[1])
    }

    private fun loadJson(jsonUrl: String, usernameToken: UsernameToken?): Promise<Response> {
        val init = createFetchInitWithCredentials()
        val headers = js("({})")
        // not necessary if we deal with jenkins but e.g. localhost
        if (usernameToken != null) {
            addAuthentication(headers, usernameToken)
        }
        init.headers = headers
        return window.fetch(jsonUrl, init)
    }

    private fun switchLoaderJsonWithPipeline() {
        display("loaderJson", "none")
        display("pipeline", "table")
    }

    private fun switchLoader(firstLoader: String, secondLoader: String) {
        display(firstLoader, "none")
        display(secondLoader, "block")
    }

    companion object {
        const val PUBLISH_JOB = "&publishJob="
        private val fullNameRegex = Regex("<input[^>]+name=\"_\\.fullName\"[^>]+value=\"([^\"]+)\"")
        private val apiTokenRegex = Regex("<input[^>]+name=\"_\\.apiToken\"[^>]+value=\"([^\"]+)\"")
        private val usernameRegex = Regex("<a[^>]+href=\"[^\"]*/user/([^\"]+)\"")


        internal fun createDependencies(
            jenkinsUrl: String?,
            publishJobUrl: String?,
            usernameToken: UsernameToken?,
            modifiableJson: ModifiableJson,
            releasePlan: ReleasePlan,
            menu: Menu
        ): Menu.Dependencies? {
            return if (publishJobUrl != null && jenkinsUrl != null && usernameToken != null) {
                val publisher = Publisher(publishJobUrl, modifiableJson)
                val releaser = Releaser(jenkinsUrl, modifiableJson, menu)
                val jenkinsJobExecutor = JenkinsJobExecutor(jenkinsUrl, usernameToken)
                val simulatingJobExecutor = SimulatingJobExecutor()
                val releaseJobExecutionDataFactory = ReleaseJobExecutionDataFactory(jenkinsUrl, releasePlan)
                Menu.Dependencies(publisher, releaser, jenkinsJobExecutor, simulatingJobExecutor, releaseJobExecutionDataFactory)
            } else {
                null
            }
        }
    }
}
