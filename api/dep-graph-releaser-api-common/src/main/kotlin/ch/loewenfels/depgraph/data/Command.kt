package ch.loewenfels.depgraph.data

import ch.loewenfels.depgraph.data.serialization.PolymorphSerializable

interface Command: PolymorphSerializable {

    val state: CommandState

    /**
     * Makes a copy of this command but with a [newState].
     */
    fun asNewState(newState: CommandState): Command

    /**
     * Makes a copy of this command but with [CommandState.Deactivated] as [state].
     *
     * @throws IllegalStateException in case the state was already [CommandState.Deactivated]
     */
    fun asDeactivated(): Command {
        check(state !is CommandState.Deactivated) {
            "Cannot deactivate an already deactivated command: $this"
        }
        return asNewState(CommandState.Deactivated(state))
    }

    /**
     * Makes a copy of this command but with [CommandState.Disabled] as [state].
     *
     * @throws IllegalStateException in case the state was already [CommandState.Disabled]
     */
    fun asDisabled(): Command {
        check(state !== CommandState.Disabled) {
            "Cannot disable an already disabled command: $this"
        }
        return asNewState(CommandState.Disabled)
    }
}

