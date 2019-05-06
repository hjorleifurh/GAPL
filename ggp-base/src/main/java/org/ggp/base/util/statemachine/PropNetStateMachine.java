package org.ggp.base.util.statemachine;

import java.util.List;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.BitSet;

import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;

import is.ru.cadia.ggp.propnet.PropNetMove; 
import is.ru.cadia.ggp.propnet.structure.GGPBasePropNetStructureFactory;
import is.ru.cadia.ggp.propnet.structure.PropNetStructure;
import is.ru.cadia.ggp.propnet.structure.PropNetStructureFactory;
import is.ru.cadia.ggp.propnet.structure.components.BaseProposition;
import is.ru.cadia.ggp.propnet.structure.components.StaticComponent;
import is.ru.cadia.ggp.propnet.structure.components.StaticComponent.Type;

public class PropNetStateMachine extends StateMachine
{
    PropNetStructure propNet;
    boolean initialized = false;

    @Override
    public void initialize(List<Gdl> description) 
    {
        PropNetStructureFactory factory = new GGPBasePropNetStructureFactory();
        try 
        {
            propNet = factory.create(description);
        } 
        catch (InterruptedException e) 
        {
            throw new RuntimeException(e);
        }
        initialized = true;
    }

    @Override
    public int getGoal(MachineState state, Role role) throws GoalDefinitionException
    {
        if (!isTerminal(state))
        {
            throw new GoalDefinitionException(state, role);
        }

        int roleId = propNet.getRoleId(role);

        int[] goalValues = propNet.getGoalValues(roleId);
        StaticComponent[] goalProps = propNet.getGoalPropositions(roleId);

        BitSet stateBits = ((PropNetMachineState)state).getCurrentState();
        BitSet proved = (BitSet)(stateBits.clone());

        int goalPropIdx = 0;
        for (StaticComponent goalProp : goalProps)
        {
            prove(proved, stateBits, goalProp);
            if (stateBits.get(goalProp.id))
            {
                return goalValues[goalPropIdx];
            }
            goalPropIdx++;
        }

        throw new GoalDefinitionException(state, role);
    }

    @Override
    public boolean isTerminal(MachineState state) 
    {
        BitSet currentState = ((PropNetMachineState)state).getCurrentState();
        StaticComponent termComp = propNet.getTerminalProposition();

        BitSet proved = new BitSet(propNet.getNbComponents());

        for (StaticComponent comp : propNet.getComponents())
        {
            if (comp.type == Type.BASE || comp.type == Type.INPUT)
            {
                proved.set(comp.id, true);
            }
        }

        prove(proved, currentState, termComp);

        return currentState.get(termComp.id);
    }

    @Override
    public List<Role> getRoles()
    {
        List<Role> roles = Lists.newArrayList();
        for (Role role : propNet.getRoles())
        {
            roles.add(role);
        }
        return roles;
    }

    @Override
    public MachineState getInitialState() 
    {   
        if (!initialized) 
        {
            throw new RuntimeException("Getting initial state from uninitialized state machine");
        }

        BitSet set = new BitSet(propNet.getNbComponents());

        for (BaseProposition prop : propNet.getBasePropositions())
        {
            set.set(prop.id, prop.initialValue);
        }

        return new PropNetMachineState(set, propNet);
    }

    public void prove(BitSet proved, BitSet set, StaticComponent comp)
    {
        // Don't try to prove components already proven
        if (proved.get(comp.id))
        {
            return;
        }

        boolean proven = false;

        for (Integer parentId : comp.inputs)
        {
            if (!proved.get(parentId))
            {
                prove(proved, set, propNet.getComponent(parentId));
            }
        }

        if (comp.type == Type.TRUE)
        {
            proven = true;
        }

        if (comp.type == Type.AND) proven = true;

        for (Integer parentId : comp.inputs)
        {
            boolean parentValue = set.get(parentId);

            if (comp.type == Type.AND) 
            {
                proven = proven && parentValue;
            }

            if (comp.type == Type.OR) 
            {
                proven = proven || parentValue;
            }

            if (comp.type == Type.NOT)
            {
                proven = !parentValue;
            }
 
            if (comp.type == Type.PIPE)
            {
                proven = parentValue;
            }
        }

        set.set(comp.id, proven);
        proved.set(comp.id, true);
    }

    @Override 
    public List<Move> getLegalMoves(MachineState state, Role role)
    {
        List<Move> moves = new ArrayList<Move>();
        PropNetMove[] possibleMoves = propNet.getPossibleMoves(propNet.getRoleId(role));
        PropNetMachineState propNetState = (PropNetMachineState)(state);

        BitSet currentState = propNetState.getCurrentState();

        BitSet proven = new BitSet(propNet.getNbComponents());

        for (BaseProposition baseProp : propNet.getBasePropositions())
        {
            proven.set(baseProp.id, true);
        }

        for (PropNetMove move : possibleMoves)
        {
            prove(proven, currentState, move.getLegalComponent());
            if (currentState.get(move.getLegalComponent().id))
            {
                moves.add(move);
            }
        }

        return moves;
    }

    @Override
    public MachineState getNextState(MachineState state, List<Move> moves) throws TransitionDefinitionException
    {
        BitSet currentState = (BitSet)((PropNetMachineState)state).getCurrentState().clone();
        BitSet nextState = new BitSet(propNet.getNbComponents());
        
        for (Move move : moves)
        {
            currentState.set(((PropNetMove)move).getInputComponent().id, true);
        }
        
        BitSet proved = new BitSet(propNet.getNbComponents());

        for (StaticComponent comp : propNet.getComponents())
        {
            if (comp.type == Type.BASE || comp.type == Type.INPUT)
            {
                proved.set(comp.id, true);
            }
        }

        for (BaseProposition baseProp : propNet.getBasePropositions())
        {
            prove(proved, currentState, baseProp.nextComponent);
            if (currentState.get(baseProp.nextComponent.id))
            {
                nextState.set(baseProp.id, true);
            }
        }

        return new PropNetMachineState(nextState, propNet);
    }

    @Override
    public Move getMoveFromTerm(GdlTerm term)
    {
        Move move = new Move(term);

        Role[] roles = propNet.getRoles();

        for (Role role : roles) 
        {
            PropNetMove propNetMove = null;
            if ((propNetMove = propNet.getPropNetMove(role, move)) != null)
            {
                return propNetMove;               
            }
        }

        return null;
    }


}