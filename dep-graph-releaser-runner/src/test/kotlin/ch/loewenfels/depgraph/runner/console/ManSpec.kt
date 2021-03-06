package ch.loewenfels.depgraph.runner.console

import ch.loewenfels.depgraph.runner.commands.Json
import ch.loewenfels.depgraph.runner.commands.Man
import ch.tutteli.atrium.api.cc.en_GB.contains
import ch.tutteli.atrium.api.cc.en_GB.message
import ch.tutteli.atrium.api.cc.en_GB.toThrow
import ch.tutteli.atrium.expect
import org.spekframework.spek2.Spek
import org.spekframework.spek2.style.specification.describe

object ManSpec : Spek({
    describe("validation errors") {
        val man = Man(mapOf())

        it("throws an error if not enough arguments are supplied") {
            expect {
                dispatch(arrayOf(), errorHandler, listOf(man))
            }.toThrow<IllegalStateException> {
                message { contains("No arguments supplied") }
            }
        }

        it("throws an error if too many arguments are supplied") {
            expect {
                dispatch(arrayOf(man.name, "-command=json", "unexpectedAdditionalArg"), errorHandler, listOf(man))
            }.toThrow<IllegalStateException> {
                message { contains("Not enough or too many arguments supplied") }
            }
        }

        it("does not throw if two args are supplied") {
            dispatch(arrayOf(man.name, "-command=json"), errorHandler, listOf(man, Json))
        }
    }
})
