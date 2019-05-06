package is.ru.cadia.ggp.propnet;

import is.ru.cadia.ggp.propnet.structure.components.StaticComponent;

import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.Move;

/**
 * PropNetMoves are role specific (they link to a specific legal proposition)
 * @author stephan
 *
 */
@SuppressWarnings("serial")
public class PropNetMove extends Move {

	/**
	 * the legal proposition in the PropNet for this move
	 */
	private StaticComponent inputComponent;
	private StaticComponent legalComponent;

	public PropNetMove(StaticComponent inputComponent, StaticComponent legalComponent, GdlTerm moveTerm) {
		super(moveTerm);
		assert moveTerm!=null;
		assert inputComponent!=null;
		assert legalComponent!=null;
		this.inputComponent = inputComponent;
		this.legalComponent = legalComponent;
	}

	public StaticComponent getInputComponent() {
		return inputComponent;
	}
	public StaticComponent getLegalComponent() {
		return legalComponent;
	}
}
