package org.ggp.base.util.statemachine;

import java.util.Set;
import java.util.BitSet;
import java.util.HashSet;

import org.ggp.base.util.gdl.grammar.GdlSentence;

import is.ru.cadia.ggp.propnet.structure.PropNetStructure;
import is.ru.cadia.ggp.propnet.structure.components.BaseProposition;


public class PropNetMachineState extends MachineState {
    private BitSet gameState;
    private PropNetStructure propNet;


    public PropNetMachineState(BitSet gameState, PropNetStructure propNet)
    {
        this.gameState = gameState;
        this.propNet = propNet;
    }

    public BitSet getCurrentState()
    {
        return gameState;
    }

    @Override
    public Set<GdlSentence> getContents() 
    {
        Set<GdlSentence> sentences = new HashSet<GdlSentence>();
        for (BaseProposition prop : propNet.getBasePropositions())
        {
            if (gameState.get(prop.id))
            {
                for (GdlSentence sentence : prop.sentences)
                {
                    sentences.add(sentence);
                }
            }
        }
        return sentences;
    }

    @Override
    public MachineState clone() 
    {
        return new PropNetMachineState((BitSet)gameState.clone(), propNet);
    }
    
}