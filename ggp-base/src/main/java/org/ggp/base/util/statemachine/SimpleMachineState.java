package org.ggp.base.util.statemachine;

import java.util.HashSet;
import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;

public class SimpleMachineState extends MachineState {

    public SimpleMachineState() {
    	super();
        this.contents = null;
    }

    /**
     * Starts with a simple implementation of a MachineState. StateMachines that
     * want to do more advanced things can subclass this implementation, but for
     * many cases this will do exactly what we want.
     */
    private final Set<GdlSentence> contents;
    public SimpleMachineState(Set<GdlSentence> contents) {
    	super();
        this.contents = contents;
    }

    @Override
	public Set<GdlSentence> getContents() {
        return contents;
    }

    @Override
    public MachineState clone() {
        return new SimpleMachineState(new HashSet<GdlSentence>(contents));
    }
}
