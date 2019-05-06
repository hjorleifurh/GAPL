package is.ru.cadia.ggp.propnet.structure;

import is.ru.cadia.ggp.propnet.structure.components.StaticComponent.Type;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponent;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponentPool;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponentSet;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.ggp.base.util.concurrency.ConcurrencyUtils;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.propnet.architecture.Component;
import org.ggp.base.util.propnet.architecture.PropNet;
import org.ggp.base.util.propnet.architecture.components.And;
import org.ggp.base.util.propnet.architecture.components.Constant;
import org.ggp.base.util.propnet.architecture.components.Not;
import org.ggp.base.util.propnet.architecture.components.Or;
import org.ggp.base.util.propnet.architecture.components.Proposition;
import org.ggp.base.util.propnet.architecture.components.Transition;
import org.ggp.base.util.propnet.factory.OptimizingPropNetFactory;
import org.ggp.base.util.statemachine.Role;

public class GGPBasePropNetStructureFactory extends PropNetStructureFactory {

	@Override
	public PropNetStructure create(List<Gdl> description) throws InterruptedException {
    	long startTime = System.currentTimeMillis();
        PropNet propNet = OptimizingPropNetFactory.create(description);
        System.out.println("original #components: " + propNet.getSize()
        		+ ", #and: " + propNet.getNumAnds()
        		+ ", #or: " + propNet.getNumOrs()
        		+ ", #not: " + propNet.getNumNots()
        		+ ", #links: " + propNet.getNumLinks()
        		+ ", #base: " + propNet.getBasePropositions().size()
        		+ ", #input: " + propNet.getInputPropositions().size()
        		);
        // propNet.renderToFile("propnet.dot");

        long createPropNetEndTime = System.currentTimeMillis();
        System.out.println("making propnet took " + (createPropNetEndTime-startTime)/1000.0 + "s");

        System.out.println("making propnet structure ...");
        PropNetStructure propNetStructure = createFromPropNet(propNet);

        long endTime = System.currentTimeMillis();
        System.out.println("making propnet structure took " + (endTime-createPropNetEndTime)/1000.0 + "s");
        return propNetStructure;
	}

	private PropNetStructure createFromPropNet(PropNet propNet) throws InterruptedException {
        // setup roles
        List<Role> roles = propNet.getRoles();

		// turn components of the PropNet into our own objects
		Set<Component> propNetComponents = propNet.getComponents();
		int nbComponents = propNetComponents.size();
        DynamicComponentPool componentPool = new DynamicComponentPool(nbComponents);
		Map<Component, DynamicComponent> componentMapping = new HashMap<>(nbComponents);
		for (Component c:propNetComponents) {
			ConcurrencyUtils.checkForInterruption();
			DynamicComponent dc = getDynamicComponentForPropNetComponent(c, roles, componentPool);
			componentMapping.put(c,dc);
		}

		// set inputs and outputs of our components
		for (Entry<Component, DynamicComponent> e : componentMapping.entrySet()) {
			ConcurrencyUtils.checkForInterruption();
			DynamicComponent dc = e.getValue();
			Set<Component> inputs = e.getKey().getInputs();
			final DynamicComponentSet dcInputs = dc.getInputs();
			for (Component c:inputs) {
				dcInputs.add(componentMapping.get(c));
			}
			Set<Component> outputs = e.getKey().getOutputs();
			final DynamicComponentSet dcOutputs = dc.getOutputs();
			for (Component c:outputs) {
				dcOutputs.add(componentMapping.get(c));
			}
		}

		// set initial value of base propositions
		// search forward to next transition and to get the base propositions
		// that will be true in the initial state
		Set<GdlSentence> initialState = new HashSet<>();
		Proposition initProp = propNet.getInitProposition();
		if (initProp != null) {
			collectInitialState(initialState, componentMapping.get(initProp));
		} // else nothing is true initially

		DynamicComponent initComponent = componentPool.create();
		initComponent.type = Type.INIT;

		Map<GdlSentence, Proposition> inputMap = propNet.getInputPropositions();
		for (DynamicComponent c : componentPool) {
			PropType propType = getPropTypeForSymbol(c.getFirstSymbol());
			if (propType == PropType.TRUE) {
				assert c.getNbInputs()==1;
				DynamicComponent transition = c.getFirstInput();
				assert transition.getNbOutputs() == 1;
				assert transition.getFirstOutput() == c;
				assert c.type == Type.PIPE;
				c.type = Type.BASE;
				if (initialState.contains(c.getFirstSymbol())) {
					DynamicComponentPool.connect(initComponent, c);
				}
			} else if (propType == PropType.LEGAL) {
				// link does(r,m) with legal(r,m) components
				Proposition oldComponent = inputMap.get(getDoesForLegalSentence(c.getFirstSymbol()));
				DynamicComponent doesComponent = componentMapping.get(oldComponent);
				if (doesComponent != null) {
					DynamicComponentPool.connect(c, doesComponent);
				}
			}
		}
		return create(roles, initialState, componentPool);
	}

	/**
	 * Search forward through the propnet on all paths until a base proposition is found.
	 * Mark it as initially true.
	 *
	 * Bases should be right after a transition and thus this should not take too long if p is
	 * the INIT proposition to start with.
	 * @param c
	 * @param componentMapping
	 */
	private void collectInitialState(Set<GdlSentence> initialState, DynamicComponent dc) {
		if (getPropTypeForSymbol(dc.getFirstSymbol()) == PropType.TRUE) {
			initialState.add(dc.getFirstSymbol());
		} else {
			for (DynamicComponent c : dc.getOutputs()) {
				collectInitialState(initialState, c);
			}
		}
	}

	private DynamicComponent getDynamicComponentForPropNetComponent(Component c, Collection<Role> roles, DynamicComponentPool componentPool) {
		DynamicComponent dc = componentPool.create();
		PropType propType = PropType.NONE;
		if(c instanceof Constant)
			dc.type = (c.getValue() ? Type.TRUE : Type.FALSE);
		else if(c instanceof And)
			dc.type = Type.AND;
		else if(c instanceof Or)
			dc.type = Type.OR;
		else if(c instanceof Not)
			dc.type = Type.NOT;
		else if(c instanceof Proposition) {
			Proposition p = (Proposition) c;
			GdlSentence sentence = p.getName();
			dc.addSymbol(sentence);
			if (sentence.getName() == GdlPool.ROLE) {
				propType = PropType.ROLE;
			} else if (sentence.getName() == GdlPool.INIT && sentence.arity() == 1) {
				propType = PropType.INIT;
			} else if (sentence.getName() == GdlPool.TRUE) {
				propType = PropType.TRUE;
			} else if (sentence.getName() == GdlPool.NEXT) {
				// this should not happen, because these propositions were
				// turned into transitions
				assert false;
			} else if (sentence.getName() == GdlPool.LEGAL) {
				propType = PropType.LEGAL;
			} else if (sentence.getName() == GdlPool.DOES) {
				propType = PropType.DOES;
			} else if (sentence.getName() == GdlPool.GOAL) {
				propType = PropType.GOAL;
			} else if (sentence.getName() == GdlPool.TERMINAL) {
				propType = PropType.TERMINAL;
			}
			// if the symbol is "legal" or "goal", but the first argument is not a role, we
			// treat the proposition as an anonymous one
			if ( (propType == PropType.LEGAL || propType == PropType.GOAL)
					&& ! firstArgumentIsARole(sentence, roles) ) {
					System.out.println("not a role in: " + sentence);
					propType = PropType.NONE;
				}
			if( c.getInputs().size() == 0 ) {
				if (propType == PropType.DOES) {
					dc.type = Type.INPUT;
				} else {
					System.out.println("found proposition with no inputs: " + p.getName() + ", value="+p.getValue());
					dc.type = Type.FALSE;
				}
			} else {
				dc.type = Type.PIPE;
			}
		} else if(c instanceof Transition) {
			dc.type = Type.PIPE;
			propType = PropType.NEXT;
			assert c.getOutputs().size()==1;
			assert c.getSingleOutput() instanceof Proposition;
			GdlSentence trueSentence = ((Proposition)c.getSingleOutput()).getName();
			assert trueSentence.getName().equals(GdlPool.TRUE);
			dc.addSymbol(GdlPool.getRelation(GdlPool.NEXT, ((Proposition)c.getSingleOutput()).getName().getBody()));
		} else {
			throw new RuntimeException("unknown propnet component type: " + c);
		}
		if (propType == PropType.LEGAL || propType == PropType.GOAL || propType == PropType.TERMINAL) {
			dc.isView = true;
		}
		return dc;
	}

}
