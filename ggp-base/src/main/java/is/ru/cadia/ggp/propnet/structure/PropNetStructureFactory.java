package is.ru.cadia.ggp.propnet.structure;

import is.ru.cadia.ggp.propnet.PropNetMove;
import is.ru.cadia.ggp.propnet.structure.components.BaseProposition;
import is.ru.cadia.ggp.propnet.structure.components.StaticComponent;
import is.ru.cadia.ggp.propnet.structure.components.StaticComponent.Type;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponent;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponentPool;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.ggp.base.util.Pair;
import org.ggp.base.util.concurrency.ConcurrencyUtils;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlConstant;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.Move;
import org.ggp.base.util.statemachine.Role;

import com.google.common.base.Stopwatch;

public abstract class PropNetStructureFactory {

	private static final String PROPNETSPATH = "propnets";

	private DynamicComponentPool componentPool;
	private List<Role> roles;
	private Set<GdlSentence> initialState;

	// the following attributes are initialized by sanitizeAndSortComponents()

	// mapping of roles to integers and vice versa
	private int nbRoles;
	private Map<Role, Integer> role2id;
	private Role[] roleByIds;

    // mapping from GdlSentence to base proposition for that sentence
	private Map<GdlSentence, DynamicComponent> basePropBySentence = null;

    // map from Move to input proposition for each role
	private Map<Move, Pair<DynamicComponent,DynamicComponent>>[] inputAndLegalPropsPerRole = null;

    /* the list of all goal propositions per role
     *  goalProps[roleid] is the list of goal proposition for that role */
	private List<DynamicComponent>[] goalPropsPerRoles = null;
	private IntList[] goalValuesPerRoles = null;

    // the terminal proposition of the propnet
	private DynamicComponent terminalProposition = null;

	// the set of all cyclic components; gets set by giveIdsToComponents()
	private BitSet cyclicComponents = null;

	public abstract PropNetStructure create(List<Gdl> description) throws InterruptedException;

	/**
	 * tries to load an existing propnet structure for the given game, assuming that the game name uniquely identifies the game
	 *
	 * if no file exists, creates the propnet and saves it to a file
	 * @throws IOException
	 */
	public PropNetStructure create(String gameName, List<Gdl> description) throws InterruptedException, IOException {
		if (gameName == null) {
			return create(description);
		}
		File propNetFile = getPropNetFileForGame(gameName);
		PropNetStructure propnetStructure = null;
		try {
			propnetStructure = PropNetStructure.readFromFile(propNetFile);
			System.out.println("propnet loaded from '" + propNetFile + "'");
		} catch (ClassNotFoundException	| IOException e) {
			System.out.println("File " + propNetFile + " does not exist or is not readable!");
		}
		if (propnetStructure == null) {
			propnetStructure = create(description);
			System.out.println("saving propnet to '" + propNetFile + "' ...");
			propnetStructure.writeToFile(propNetFile);
		}
		return propnetStructure;
	}

	private File getPropNetFileForGame(String gameName) {
		String factoryIdentifier;
		if (this instanceof ASPPropNetStructureFactory) {
			factoryIdentifier = "asp";
		} else {
			factoryIdentifier = "base";
		};
		return new File(PROPNETSPATH, gameName + "_" + factoryIdentifier + ".propnet");
	}

	////////////////////////////////
	// below here are convenience methods than can be used for implementing create(List<Gdl> description)
	////////////////////////////////

	/**
	 * method expects a list of roles and a completely setup set of StaticComponents with only
	 * their IDs missing
	 * @param components (with IDs all set to -1)
	 * @return
	 * @throws InterruptedException
	 */
	protected PropNetStructure create(StaticComponent[] componentById, boolean isCyclic) throws InterruptedException {
		ConcurrencyUtils.checkForInterruption();

		// convert maps from dynamic to static components
		Map<GdlSentence, BaseProposition> basePropBySentenceStatic = new HashMap<>(basePropBySentence.size());
		for (Entry<GdlSentence, DynamicComponent> entry : basePropBySentence.entrySet()) {
			basePropBySentenceStatic.put(entry.getKey(), (BaseProposition)entry.getValue().staticComponent);
		}
		basePropBySentence = null;

		ConcurrencyUtils.checkForInterruption();
		PropNetMove[][] possibleMoves = new PropNetMove[nbRoles][];
		StaticComponent[][] goalPropsStatic = new StaticComponent[nbRoles][];
		int[][] goalValues = new int[nbRoles][];
		for (int roleId = 0; roleId<nbRoles ; roleId++) {
			// create possible moves lists
			possibleMoves[roleId] = new PropNetMove[inputAndLegalPropsPerRole[roleId].size()];
			int i = 0;
			for (Entry<Move, Pair<DynamicComponent, DynamicComponent>> entry : inputAndLegalPropsPerRole[roleId].entrySet()) {
				StaticComponent input = entry.getValue().left.staticComponent;
				StaticComponent legal = entry.getValue().right.staticComponent;
				GdlTerm moveTerm = entry.getKey().getContents();
				possibleMoves[roleId][i] = new PropNetMove(input, legal, moveTerm);
				i++;
			}

			// create lists of goal components and goal values
			goalPropsStatic[roleId] = new StaticComponent[goalPropsPerRoles[roleId].size()];
			i = 0;
			for (DynamicComponent c : goalPropsPerRoles[roleId]) {
				goalPropsStatic[roleId][i] = c.staticComponent;
				i++;
			}
			goalValues[roleId] = goalValuesPerRoles[roleId].toIntArray();

		}
		goalPropsPerRoles = null;
		inputAndLegalPropsPerRole = null;

		StaticComponent terminalProposition = this.terminalProposition.staticComponent;
		this.terminalProposition = null;
		componentPool = null;

		return new PropNetStructure(
				componentById,
				role2id,
				roleByIds,
				basePropBySentenceStatic,
				possibleMoves,
				goalPropsStatic,
				goalValues,
				terminalProposition,
				isCyclic
				);
	}

	/**
	 * optimizes the set of components and then turns them into StaticComponent in a PropNetStructure
	 * @param roles the roles of the game in correct order
	 * @param initialState the initial state of the game as a set of GdlSentences of the form "(true X)"
	 * @param componentPool a propnet represented as dynamic components
	 * @param inputMap a mapping from "(does R M)" sentences to input propositions
	 * @return
	 * @throws InterruptedException
	 */
	protected PropNetStructure create(List<Role> roles, Set<GdlSentence> initialState, DynamicComponentPool componentPool) throws InterruptedException {

		this.roles = roles;
		this.initialState = initialState;
		this.componentPool = componentPool;

//		// optimize the propnet
		Stopwatch stopWatch = new Stopwatch().start();
		DynamicPropnetOptimizer optimizer = new DynamicPropnetOptimizer(componentPool);
		optimizer.run();
		System.out.println("optimizations took: " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");

		ConcurrencyUtils.checkForInterruption();
		// sanitize components:
		// - every move should have a legal and a does
		// - every true should have a next
		// - get rid of the INIT component
		sanitizeAndSortComponents();

		ConcurrencyUtils.checkForInterruption();
		// give new IDs to the components here
		// compute topological order with some constraints in mind for the creation of the structure later on
		// also figure out which components are cyclic
		giveIdsToComponents();
		boolean isCyclic = !cyclicComponents.isEmpty();
		if (isCyclic) {
			System.out.println("propnet is cyclic and has " + cyclicComponents.cardinality() + " components in cycles");
		} else {
			System.out.println("propnet is acyclic");
		}

		// now we need to turn the DynamicComponents into static ones
		stopWatch.reset().start();
		StaticComponent[] staticComponents = createStaticComponents();
		System.out.println("making static components took: " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");

		// now make a structure out of the static components
		stopWatch.reset().start();
		PropNetStructure structure = create(staticComponents, isCyclic);
		System.out.println("putting components in a structure took: " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");
		return structure;
	}


	public static void packComponents(DynamicComponentPool componentPool) {
		Stopwatch stopWatch = new Stopwatch().start();
		System.out.println("packing components (max ID: " + componentPool.getHighestId() + ")");
		List<DynamicComponent> componentOrder = tarjansSCCAlgorithm(componentPool);
		int newId = 0;
		for (DynamicComponent c : componentOrder) {
			c.staticId = newId;
			newId++;
		}
		componentPool.reorganize();
		for (DynamicComponent c : componentOrder) {
			assert c.id == c.staticId;
			c.staticId = -1;
		}
		System.out.println("ordering and packing components (new maxID: " + componentPool.getHighestId() + ") took: " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");
 	}

	private void giveIdsToComponents() {
		// make sensible IDs for our components
		int nextId = 0;
		// add base propositions
		for (DynamicComponent p : basePropBySentence.values()) {
			assert p.type == Type.BASE || p.type == Type.TRUE;
			p.staticId = nextId++;
		}

		// get the remaining components (the ones that don't have an id yet)
		Set<DynamicComponent> remainingComponents = new HashSet<>();
		for(DynamicComponent c : componentPool) {
			if (c.staticId == -1) {
				remainingComponents.add(c);
			}
		}
		cyclicComponents = new BitSet(componentPool.size());
		List<DynamicComponent> remainingComponentsInOrder = tarjansSCCAlgorithm(componentPool, remainingComponents, cyclicComponents);

		// now give IDs to the remaining components in order
		for(DynamicComponent c : remainingComponentsInOrder) {
			assert c.staticId == -1;
			c.staticId = nextId++;
		}
	}

	/**
	 * run Tarjan's algorithm for finding strongly connected components, but output the topological order it creates
	 *
	 * @param componentPool all components in the pool
	 * @param componentsToOrder the components that should be ordered (a subset of componentPool)
	 * @param cyclicComponents a BitSet which will have bit i set if component with ID i is part of some cycle (a non-singleton strongly connected component)
	 * @return a list of components in topological order with their staticIds set to the id in order (starting with nextId)
	 */
	private static List<DynamicComponent> tarjansSCCAlgorithm(DynamicComponentPool componentPool, Collection<DynamicComponent> componentsToOrder, BitSet cyclicComponents) {
		int maxNbComponents = componentPool.getHighestId()+1;
		if (cyclicComponents == null) {
			cyclicComponents = new BitSet(maxNbComponents);
		}
		IntList order = new IntArrayList(componentsToOrder.size());
		IntStack stack = new IntArrayList();
		BitSet componentSet = new BitSet(maxNbComponents);
		// TODO: move that function elsewhere
		DynamicPropnetOptimizer.setBits(componentSet, componentsToOrder);
		int[] indices = new int[maxNbComponents];
		int[] lowlink = new int[maxNbComponents];
		BitSet onStack = new BitSet(maxNbComponents);
		int index = 1;
		for (DynamicComponent c : componentsToOrder) {
			if (indices[c.id] == 0) {
				index = tarjanSCC(index, c, indices, lowlink, stack, onStack, componentSet, order, cyclicComponents);
			}
		}
		List<DynamicComponent> result = new ArrayList<>(componentsToOrder.size());
		for (int id : order) {
			result.add(componentPool.get(id));
		}
		return result;
	}
	private static List<DynamicComponent> tarjansSCCAlgorithm(DynamicComponentPool componentPool) {
		return tarjansSCCAlgorithm(componentPool, componentPool, null);
	}

	private static int tarjanSCC(int index, DynamicComponent v, int[] indices, int[] lowlink, IntStack stack, BitSet onStack, BitSet componentSet, IntList order, BitSet cyclicComponents) {
		indices[v.id] = index;
		lowlink[v.id] = index;
		index++;
		stack.push(v.id);
		onStack.set(v.id);

		// BASE and INPUT components are not supposed to have any inputs
		if (v.type != Type.BASE && v.type != Type.INPUT) {
			for (DynamicComponent w : v.getInputs()) {
				if (componentSet.get(w.id)) {
					if (indices[w.id] == 0) {
						index = tarjanSCC(index, w, indices, lowlink, stack, onStack, componentSet, order, cyclicComponents);
						lowlink[v.id] = Math.min(lowlink[v.id], lowlink[w.id]);
					} else if (onStack.get(w.id)) {
						lowlink[v.id] = Math.min(lowlink[v.id], lowlink[w.id]);
					}
				} // else we assume w is already ordered somehow
			}
		}

		if (indices[v.id] == lowlink[v.id]) {
			// start a new strongly connected component
			int componentSize = 0;
			int wID = -1;
			while (wID!=v.id) {
				wID = stack.popInt();
				onStack.clear(wID);
				// add w to strongly connected component
				order.add(wID);
				cyclicComponents.set(wID);
				componentSize++;
			}
			// output strongly connected component
			if (componentSize == 1) {
				// then the last component we added was not cyclic
				cyclicComponents.clear(v.id);
			}
		}
		return index;
	}

	@SuppressWarnings("unchecked")
	private void sanitizeAndSortComponents() {
		nbRoles = roles.size();
        role2id = new HashMap<>(nbRoles);
        roleByIds = new Role[nbRoles];
        for(int rid = 0; rid < nbRoles; rid++) {
        	role2id.put(roles.get(rid), rid);
        	roleByIds[rid] = roles.get(rid);
        }

		// first find bases, inputs, legals, goals and terminal
		basePropBySentence = new HashMap<GdlSentence, DynamicComponent>();
		goalPropsPerRoles = new List[nbRoles];
		goalValuesPerRoles = new IntList[nbRoles];
		inputAndLegalPropsPerRole = new Map[nbRoles];
		for (int rid = 0; rid < nbRoles; rid++) {
			goalPropsPerRoles[rid] = new LinkedList<>();
			goalValuesPerRoles[rid] = new IntArrayList();
			inputAndLegalPropsPerRole[rid] = new HashMap<>();
		}

		// remove the INIT component from the propnet
		DynamicComponent initComponent = null;
		for (DynamicComponent c : componentPool) {
			if (c.type == Type.INIT) {
				initComponent = c;
				break;
			}
		}
		if (initComponent != null) {
			DynamicComponentPool.disconnect(initComponent, initComponent.getOutputs());
			componentPool.free(initComponent);
		} else {
			System.out.println("no INIT component found");
		}

		// does(R,M) sentences for which there is no INPUT component yet
		Set<GdlSentence> missingInputs = new HashSet<>();

		// create a FALSE component as input for BASE that are only true initially
		DynamicComponent falseComponent = componentPool.create();
		falseComponent.type = Type.FALSE;

		for (DynamicComponent c : componentPool) {
			for(GdlSentence symbol : c.getSymbols()) {
				switch (getPropTypeForSymbol(symbol)) {
					case TRUE:
						if (c.type == Type.BASE) {
							basePropBySentence.put(symbol, c);
							if(c.getInputs().isEmpty()) {
								assert initialState.contains(symbol); // or something went wrong in the optimization step
								System.out.println("Creating next component for fluent that will be false (except initially): " + symbol);
								DynamicComponentPool.connect(falseComponent, c);
							} else {
								assert c.getInputs().size() == 1;
								assert c.getFirstInput().type != Type.INIT;
							}
						}
						break;
					case DOES:
						if (c.type == Type.INPUT) {
							assert c.getNbInputs() == 1;
							DynamicComponent legalComponent = c.getFirstInput();
							Role role = new Role((GdlConstant)symbol.get(0));
							Move move = new Move(symbol.get(1));
							Pair<DynamicComponent, DynamicComponent> entry = Pair.of(c, legalComponent);
							inputAndLegalPropsPerRole[role2id.get(role)].put(move, entry);
							missingInputs.remove(symbol);
						}
						break;
					case LEGAL: {
						Role role = new Role((GdlConstant)symbol.get(0));
						Move move = new Move(symbol.get(1));
						if (!inputAndLegalPropsPerRole[role2id.get(role)].containsKey(move)) {
							// no does component for this move yet
							Pair<DynamicComponent, DynamicComponent> entry = Pair.of(null, c);
							inputAndLegalPropsPerRole[role2id.get(role)].put(move, entry);
							missingInputs.add(getDoesForLegalSentence(symbol));
						}
						break; }
					case GOAL: {
						Role role = new Role((GdlConstant)symbol.get(0));
						int roleId = role2id.get(role);
						int goalValue = Integer.parseInt(((GdlConstant)symbol.get(1)).getValue());
						goalPropsPerRoles[roleId].add(c);
						goalValuesPerRoles[roleId].add(goalValue);
						break; }
					case TERMINAL:
						terminalProposition = c;
						break;
					case INIT:
						break;
					case NEXT:
						break;
					case NONE:
						break;
					case ROLE:
						break;
				}
			}
		}
		if (falseComponent.getOutputs().isEmpty()) {
			// remove unused component again
			componentPool.free(falseComponent);
		}

		if (!missingInputs.isEmpty()) {
			// make one input component, that does not have any outputs
			// this is used for all those legal moves that don't have any effect
			DynamicComponent inputComponent = componentPool.create();
			inputComponent.type = Type.INPUT;
			inputComponent.addSymbols(missingInputs);
			for (GdlSentence symbol : missingInputs) {
				Role role = new Role((GdlConstant)symbol.get(0));
				Move move = new Move(symbol.get(1));
				Pair<DynamicComponent, DynamicComponent> entry = inputAndLegalPropsPerRole[role2id.get(role)].get(move);
				assert entry.left==null;
				entry = Pair.of(inputComponent, entry.right);
				inputAndLegalPropsPerRole[role2id.get(role)].put(move, entry);
				System.out.println("Creating input component for useless move: " + symbol);
				DynamicComponentPool.connect(entry.right, inputComponent);
			}
		}
	}

	/**
	 * for each DynamicComponent create a static component that is setup correctly using the staticId of the dynamic components as ids
	 * @throws InterruptedException
	 */
	private StaticComponent[] createStaticComponents() throws InterruptedException {
		StaticComponent[] staticComponents = new StaticComponent[componentPool.size()];
		for (DynamicComponent c : componentPool) {
			ConcurrencyUtils.checkForInterruption();
			int id = c.staticId;
			StaticComponent sc = null;
			switch (c.type) {
				case BASE:
					// get base symbols
					List<GdlSentence> baseSymbols = new LinkedList<GdlSentence>();
					for (GdlSentence symbol : c.getSymbols()) {
						if (getPropTypeForSymbol(symbol) == PropType.TRUE) {
							baseSymbols.add(symbol);
						}
					}
					assert !baseSymbols.isEmpty();
					BaseProposition bp = new BaseProposition(id, c.type, null, null, baseSymbols.toArray(new GdlSentence[] {}));
					sc = bp;
					bp.initialValue = initialState.contains(baseSymbols.get(0));
					break;
				case AND:
				case OR:
				case NOT:
				case TRUE:
				case FALSE:
				case INPUT:
				case PIPE:
					sc = new StaticComponent(id, c.type, null, null, false);
					break;
				case INIT:
					assert false;
					break;
			}
			assert sc != null;
			sc.isCyclic = cyclicComponents.get(c.id);
			c.staticComponent = sc;
			assert sc.type != null;
			staticComponents[id]=sc;
		}
		ConcurrencyUtils.checkForInterruption();
		// remove connections between next(X)-true(X) and legal(R,M)-does(R,M)
		for (DynamicComponent c : componentPool) {
			if (c.type == Type.BASE) {
				assert c.getNbInputs()==1;
				((BaseProposition)c.staticComponent).nextComponent = c.getFirstInput().staticComponent;
				DynamicComponentPool.disconnect(c.getFirstInput(), c);
			}else if (c.type == Type.INPUT) {
				DynamicComponentPool.disconnect(c.getInputs(), c);
			}
		}
		// now connect inputs and outputs of the static components
		for (DynamicComponent c : componentPool) {
			ConcurrencyUtils.checkForInterruption();
			c.staticComponent.inputs = new int[c.getNbInputs()];
			int i = 0;
			for (DynamicComponent input : c.getInputs()) {
				//assert componentPool.contains(input);
				c.staticComponent.inputs[i] = input.staticComponent.id;
				i++;
			}
			c.staticComponent.outputs = new int[c.getNbOutputs()];
			i = 0;
			for (DynamicComponent output : c.getOutputs()) {
				// assert componentPool.contains(output);
				c.staticComponent.outputs[i] = output.staticComponent.id;
				i++;
			}
		}
		return staticComponents;
	}

	public static PropType getPropTypeForSymbol(GdlSentence symbol) {
		if (symbol == null) {
			return PropType.NONE;
		} else if (symbol.getName().equals(GdlPool.ROLE)) {
			return PropType.ROLE;
		} else if (symbol.getName().equals(GdlPool.INIT)) {
			return PropType.INIT;
		} else if (symbol.getName().equals(GdlPool.TRUE)) {
			return PropType.TRUE;
		} else if (symbol.getName().equals(GdlPool.NEXT)) {
			return PropType.NEXT;
		} else if (symbol.getName().equals(GdlPool.LEGAL)) {
			return PropType.LEGAL;
		} else if (symbol.getName().equals(GdlPool.DOES)) {
			return PropType.DOES;
		} else if (symbol.getName().equals(GdlPool.GOAL)) {
			return PropType.GOAL;
		} else if (symbol.getName().equals(GdlPool.TERMINAL)) {
			return PropType.TERMINAL;
		}
		return PropType.NONE;
	}

	public static GdlSentence getDoesForLegalSentence(GdlSentence symbol) {
		assert symbol.getName().equals(GdlPool.LEGAL);
		assert symbol.arity() == 2;
		return GdlPool.getRelation(GdlPool.DOES, symbol.getBody());
	}

	protected static boolean firstArgumentIsARole(GdlSentence symbol, Collection<Role> roles) {
		return symbol.arity()>0 &&
				symbol.get(0) instanceof GdlConstant &&
				roles.contains(new Role((GdlConstant)symbol.get(0)));
	}

	public void cleanup() {
		componentPool = null;
		roles = null;
		initialState = null;
		nbRoles = 0;
		role2id = null;
		roleByIds = null;
		basePropBySentence = null;
		inputAndLegalPropsPerRole = null;
		goalPropsPerRoles = null;
		goalValuesPerRoles = null;
		terminalProposition = null;
		cyclicComponents = null;
	}

}