package org.ggp.base.util.statemachine;

import java.util.Set;

import org.ggp.base.util.gdl.grammar.GdlSentence;

public abstract class MachineState {

    /**
     * getContents returns the GDL sentences which determine the current state
     * of the game being played. Two given states with identical GDL sentences
     * should be identical states of the game.
     */
    public abstract Set<GdlSentence> getContents();

    @Override
    public abstract MachineState clone();

    /* Utility methods */
    @Override
    public int hashCode() {
    	int hash = 0;
    	for (GdlSentence s : getContents()) {
    		hash ^= s.hashCode();
    		// TODO: make better hashCode for GdlSentences
    	}
        return hash;
    }

    @Override
    public String toString()
    {
        Set<GdlSentence> contents = getContents();
        if(contents == null)
            return "(MachineState with null contents)";
        else
            return contents.toString();
    }

    @Override
    public boolean equals(Object o)
    {
        if ((o != null) && (o instanceof MachineState))
        {
            MachineState state = (MachineState) o;
            return state.getContents().equals(getContents());
        }
        return false;
    }

    /**
     * this default implementation just returns the state itself, but the
     * intention is to return a smaller (memory) version of the same state
     * in order to save memory when storing many states
     * @return
     */
	public MachineState compress() {
		return this;
	}
}