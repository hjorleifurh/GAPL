package is.ru.cadia.ggp.propnet.structure;

import is.ru.cadia.ggp.propnet.PropNetMove;
import is.ru.cadia.ggp.propnet.structure.components.BaseProposition;
import is.ru.cadia.ggp.propnet.structure.components.StaticComponent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStreamWriter;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.logging.GamerLogger;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

/**
 * contains the structure of a propnet without a state in such a form that using a BitSet
 * as a state becomes easy
 *
 * This means that each component of the propnet has an integer ID and components know their ID,
 * type as well as inputs and outputs
 *
 * @author stephan
 *
 */
@SuppressWarnings("serial")
public class PropNetStructure implements Serializable {

	/**
     * number of components in the propnet
     */
    private int nbComponents = 0;

    /**
     * all components by their ID
     */
    private StaticComponent[] componentById = null;

	/**
	 * the number of roles in the game
	 */
	private int nbRoles;

	/**
     * each Role is mapped to a unique id (counting from 0 to n-1 in the order of the roles) (and back)
     */
    private Map<Role, Integer> role2id = null;
    private Role[] roleById = null;

    /**
     * mapping from GdlSentence to base proposition for that sentence
     */
	private Map<GdlSentence, BaseProposition> basePropBySentence = null;

    /**
     * all base propositions (ordered by their id)
     *
     * base propositions always have consecutive IDs starting at 0
     */
    private BaseProposition[] basePropositions = null;

    /**
     * map from Move to PropNetMove (the internal move representation) for each role
     */
    private Map<Move, PropNetMove>[] propNetMovesPerRole = null;

    /**
     *  the list of all possible moves per role
     *
     *  possibleMoves[roleid] is the list of possible moves for that role
     */
    private PropNetMove[][] possibleMoves = null;

    /**
     *  the list of all goal propositions per role
     *
     *  goalProps[roleid] is the list of goal proposition for that role
     */
    private StaticComponent[][] goalProps = null;

    /**
     * the goal values associated with the goalProps
     *
     * if goalProps[roleid][i] is true then role with roleid gets goal value goalValues[roleid][i]
     */
    private int[][] goalValues = null;

    /**
	 * number of base propositions
	 */
	private int nbBasePropositions;

	/**
	 * the one terminal proposition in the propnet
	 */
	private StaticComponent terminalProposition = null;

	private boolean isCyclic;

	/**
	 * This method is used by PropNetStructureFactory classes to create the PropNetStructure.
	 * Don't call it directly.
	 */
	protected PropNetStructure(StaticComponent[] componentById,
			Map<Role, Integer> role2id, Role[] roleById,
			Map<GdlSentence, BaseProposition> basePropBySentence,
			PropNetMove[][] possibleMoves, StaticComponent[][] goalProps, int[][] goalValues,
			StaticComponent terminalProposition, boolean isCyclic) {
		super();
		nbComponents = componentById.length;
		this.componentById = componentById;
		assert !Arrays.asList(componentById).contains(null);

		assert roleById != null;
		assert role2id != null;
		assert roleById.length == role2id.size();
		this.role2id = role2id;
		this.roleById = roleById;
		nbRoles = roleById.length;
		assert basePropBySentence != null;
		this.basePropBySentence = basePropBySentence;

		nbBasePropositions = basePropBySentence.size();
		basePropositions = new BaseProposition[nbBasePropositions];
		for (BaseProposition p : basePropBySentence.values()) {
			basePropositions[p.id] = p;
		}


		assert possibleMoves != null && possibleMoves.length == nbRoles;
		this.possibleMoves = possibleMoves;

		@SuppressWarnings("unchecked")
		Map<Move, PropNetMove>[] map = new Map[nbRoles];
		this.propNetMovesPerRole = map;
		for (int rid = 0 ; rid < nbRoles ; rid++) {
			propNetMovesPerRole[rid] = new HashMap<Move, PropNetMove>();
			for (PropNetMove m : possibleMoves[rid]) {
				propNetMovesPerRole[rid].put(m, m);
			}
		}
		assert goalProps != null  && goalProps.length == nbRoles;
		this.goalProps = goalProps;
		assert goalValues != null  && goalValues.length == nbRoles;
		this.goalValues = goalValues;
		assert terminalProposition != null;
		this.terminalProposition = terminalProposition;

		this.isCyclic = isCyclic;
	}

//	private void checkInfluencedComponents(BaseProposition p) {
//		BitSet influenced = new BitSet();
//		Deque<Integer> queue = new LinkedList<Integer>();
//		queue.push(p.id);
//		while (!queue.isEmpty()) {
//			StaticComponent c = componentById[queue.poll()];
//			for (int output : c.outputs) {
//				if (!influenced.get(output)) {
//					influenced.set(output);
//					queue.push(output);
//				}
//			}
//		}
//		p.influencedComponentIDs = influenced;
//	}

	public int getNbBasePropositions() {
		return nbBasePropositions;
	}

	public BaseProposition[] getBasePropositions() {
		return basePropositions;
	}

	public int getRoleId(Role role) {
		return role2id.get(role);
	}

	public StaticComponent[] getGoalPropositions(int roleId) {
		return goalProps[roleId];
	}

	public int[] getGoalValues(int roleId) {
		return goalValues[roleId];
	}

	public StaticComponent getTerminalProposition() {
		return terminalProposition;
	}

	public PropNetMove[] getPossibleMoves(int roleId) {
		return possibleMoves[roleId];
	}

	public PropNetMove getPropNetMove(int rid, Move m) {
		return propNetMovesPerRole[rid].get(m);
	}

	public BaseProposition getBaseProposition(GdlSentence sentence) {
		return basePropBySentence.get(sentence);
	}

	public int getNbComponents() {
		return nbComponents;
	}

	public StaticComponent[] getComponents() {
		return componentById;
	}

//	/**
//	 * sets the given input components in the state to true (and all the others to false)
//	 * also resets the computed bits for anything that might have been influenced by that change
//	 * @param state
//	 * @param inputs
//	 */
//	public void setInputs(InternalState state, StaticComponent[] inputs) {
//		for (int id = firstInputPropId; id < lastInputPropId; id++) {
//			setFalse(state, componentById[id]);
//		}
//		// set inputs according to the joint move
//		for(StaticComponent input:inputs) {
//			setTrue(state, input);
//		}
//	}

	public Role[] getRoles() {
		return roleById;
	}

	private static class Stats {
		int nbComponents = 0;
		int nbAnds = 0;
		int nbOrs = 0;
		int nbNots = 0;
		int nbLinks = 0;
		int nbBases = 0;
		int nbInputs = 0;
		int nbConstants = 0;
		int nbViews = 0;
	}

	private Stats stats = null;

	private Stats getStats() {
		if (stats == null) {
			stats = new Stats();
			for (StaticComponent c : componentById) {
				stats.nbComponents++;
				switch (c.type) {
					case AND: stats.nbAnds++; break;
					case OR: stats.nbOrs++; break;
					case NOT: stats.nbNots++; break;
					case FALSE:
					case TRUE: stats.nbConstants++; break;
					case INPUT: stats.nbInputs++; break;
					default:
				}
				stats.nbLinks += c.inputs.length;
			}
			stats.nbBases = nbBasePropositions;
		}
		return stats;
	}

	public void printStats() {
		getStats();
		StringBuilder sb = new StringBuilder();
		sb.append("#components: ").append(stats.nbComponents);
		sb.append(", #and: ").append(stats.nbAnds);
		sb.append(", #or: ").append(stats.nbOrs);
		sb.append(", #not: ").append(stats.nbNots);
		sb.append(", #constants: ").append(stats.nbConstants);
		sb.append(", #other: ").append(stats.nbComponents - stats.nbAnds - stats.nbOrs - stats.nbNots);
		sb.append(", #links: ").append(stats.nbLinks);
		sb.append(", #base: ").append(stats.nbBases);
		sb.append(", #input: ").append(stats.nbInputs);
		sb.append(", #views: ").append(stats.nbViews);
		System.out.println(sb.toString());
//		System.out.println("bases:");
//		for (BaseProposition p : basePropositions) {
//			System.out.println(p);
//		}
	}

	/**
	 * writes the propnet structure to a file in .dot format for rendering with xdot, graphviz or similar tools
	 * @param file
	 */
	public final void renderToFile(File file) {
		renderToFile(file, ComponentFilter.ACCEPT_ALL);
	}
	public void renderToFile(File file, ComponentFilter filter) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            OutputStreamWriter fout = new OutputStreamWriter(fos, "UTF-8");
            toDot(fout, filter);
            fout.close();
            fos.close();
        } catch(Exception e) {
            GamerLogger.logStackTrace("StateMachine", e);
        }

	}

	private void toDot(OutputStreamWriter os, ComponentFilter filter) throws IOException {
		os.write("digraph propNet {\n");
        for ( StaticComponent c : componentById ) {
        	if (filter.accept(c.id)) {
        		c.toDot(os, filter);
        	}
        }
        os.write("}\n");
	}

	public PropNetMove getPropNetMove(Role r, Move m) {
		return propNetMovesPerRole[role2id.get(r)].get(m);
	}

	public StaticComponent getComponent(int id) {
		return componentById[id];
	}

	// all bits in the state after that are free to be used for special purposes
	public int getMaxComponentId() {
		return componentById.length-1;
	}

	public boolean isCylic() {
		return isCyclic;
	}

	public void writeToFile(File file) throws FileNotFoundException, IOException {
		file.getParentFile().mkdirs();
		ObjectOutputStream os = new ObjectOutputStream(new FileOutputStream(file));
		os.writeObject(this);
		os.close();
	}

	public static PropNetStructure readFromFile(File file) throws FileNotFoundException, IOException, ClassNotFoundException {
		ObjectInputStream is = new ObjectInputStream(new FileInputStream(file));
		Object o = is.readObject();
		is.close();
		if (o instanceof PropNetStructure) {
			return (PropNetStructure)o;
		} else {
			throw new InvalidObjectException("Object is not of type PropNetStructure: " + o.getClass());
		}
	}
}
