package org.ggp.base.player.gamer.statemachine;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.ggp.base.player.gamer.exception.GamePreviewException;
import org.ggp.base.util.game.Game;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.PropNetStateMachine;
import org.ggp.base.util.statemachine.StateMachine;
import org.ggp.base.util.statemachine.exceptions.GoalDefinitionException;
import org.ggp.base.util.statemachine.exceptions.MoveDefinitionException;
import org.ggp.base.util.statemachine.exceptions.TransitionDefinitionException;

public class PropNetStateMachineGamer extends StateMachineGamer 
{
    private PropNetStateMachine stateMachine;

    public PropNetStateMachineGamer()
    {
        stateMachine = new PropNetStateMachine();
    }

    @Override
    public StateMachine getInitialStateMachine() 
    {
        return stateMachine;
    }

    @Override
    public void stateMachineMetaGame(long timeout)
            throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException 
    {

    }

    @Override
    public Move stateMachineSelectMove(long timeout)
            throws TransitionDefinitionException, MoveDefinitionException, GoalDefinitionException 
    {
        List<Move> legalMoves = stateMachine.getLegalMoves(getCurrentState(), getRole());
        Move selection = (legalMoves.get(ThreadLocalRandom.current().nextInt(legalMoves.size())));

        return selection;
    }

    @Override
    public void stateMachineStop() {

    }

    @Override
    public void stateMachineAbort() {

    }

    @Override
    public void preview(Game g, long timeout) throws GamePreviewException {

    }

    @Override
    public String getName() {
        return "PropNetStateMachineGamer";
    }
    
}