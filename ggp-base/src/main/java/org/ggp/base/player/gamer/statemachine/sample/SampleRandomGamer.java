package org.ggp.base.player.gamer.statemachine.sample;

import java.util.List;
import java.util.Random;
import org.ggp.base.util.statemachine.Move;

public class SampleRandomGamer extends SampleGamer {
    @Override
    public Move stateMachineSelectMove(long timeout)
    {
        try {
            List<Move> moves = getStateMachine().getLegalMoves(getCurrentState(), getRole());
            
            int noMoves = moves.size();
            if (noMoves > 0) {
                Random r = new Random();
                return moves.get(r.nextInt(noMoves));
            }

        } catch (Exception e) {
            return null;
        }
        return null;
    }
}