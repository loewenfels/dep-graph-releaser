package ch.loewenfels.depgraph.gui.jobexecution

import ch.loewenfels.depgraph.gui.jobexecution.BuilderNumberExtractor.Companion.numberRegex
import ch.loewenfels.depgraph.gui.jobexecution.exceptions.JobNotExistingException
import ch.loewenfels.depgraph.gui.unwrapPromise
import org.w3c.fetch.RequestInit
import kotlin.browser.window
import kotlin.js.Promise

class BuildHistoryBasedBuildNumberExtractor(
    private val crumbWithId: CrumbWithId?,
    private val jobExecutionData: JobExecutionData
) : BuilderNumberExtractor {

    override fun extract(): Promise<Int> {
        val headers = createHeaderWithCrumb(crumbWithId)
        val init = createGetRequest(headers)
        return window.fetch("${jobExecutionData.jobBaseUrl}api/xml?xpath=//build/number&wrapper=builds", init)
            .then(::checkStatusOk)
            .then { searchBuildNumber(it.second, init) }
            .unwrapPromise()
    }

    private fun searchBuildNumber(body: String, init: RequestInit): Promise<Int> {
        val matchResult = numberRegex.find(body) ?: throw JobNotExistingException(
            "no job run at ${jobExecutionData.jobBaseUrl} so far, as consequence we cannot extract a build number."
        )
        val parametersRegex = Regex(createParameterRegexPattern(jobExecutionData.identifyingParams))
        return searchBuildNumber(matchResult, parametersRegex, init)
    }

    private fun searchBuildNumber(matchResult: MatchResult, parametersRegex: Regex, init: RequestInit): Promise<Int> {
        val buildNumber = matchResult.groupValues[1].toInt()
        return window.fetch("${jobExecutionData.jobBaseUrl}$buildNumber/api/xml", init)
            .then(::checkStatusOk)
            .then { (_, body) ->
                if (parametersRegex.containsMatchIn(body)) {
                    Promise.resolve(buildNumber)
                } else {
                    val newMatchResult = matchResult.next() ?: throw JobNotExistingException(
                        "No job matches the given identifying parameters at ${jobExecutionData.jobBaseUrl}." +
                            "\nRegex used: ${parametersRegex.pattern}"
                    )
                    searchBuildNumber(newMatchResult, parametersRegex, init)
                }
            }.unwrapPromise()
    }
}
