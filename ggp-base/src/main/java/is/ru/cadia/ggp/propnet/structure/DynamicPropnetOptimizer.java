package is.ru.cadia.ggp.propnet.structure;

import is.ru.cadia.ggp.propnet.structure.components.StaticComponent.Type;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponent;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponentPool;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponentSet;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import org.ggp.base.util.Pair;
import org.ggp.base.util.concurrency.ConcurrencyUtils;

// TODO: add optimizations:
// - if two INPUTS or BASES have the same outputs, combine them (may be tricky to convert them back to legal moves and fluents)
// - if an AND/OR has only negations as inputs, use De Morgan's rule reversed

public class DynamicPropnetOptimizer {

	private static enum OptimizationType {NO_OUTPUT, PIPE_AS_ONLY_OUTPUT,
	KEEP_NOT_NOT,
	KEEP_FALSE, KEEP_TRUE,
	KEEP_PIPE,
	FALSE_INPUT, TRUE_INPUT, UNUSED_INPUT,
	AND_WITH_AND_OUTPUT,
	OR_WITH_OR_OUTPUT,
	NOT_NOT,
	PIPE,
	FALSE, TRUE,
	PUSH_FACTOR, FACTOR, DUPLICATE_NOT_OUTPUT, SELF_LOOP, DEMORGAN}

	private int[] optimizationsDone = new int[OptimizationType.values().length];

	private DynamicComponentPool componentPool;
	private BitSet changedComponentIds;
	private BitSetIterator changedComponentIterator;

	public DynamicPropnetOptimizer(DynamicComponentPool componentPool) {
		this.componentPool = componentPool;
	}

	public Set<DynamicComponent> run() throws InterruptedException {
		ConcurrencyUtils.checkForInterruption();
		resetOptimizationStats();
		int iterations = 0;

		// first run one fast round without the expensive optimizations
		// this should cut down on the number of components a lot and make things easier later on
		changedComponentIds = new BitSet(componentPool.getHighestId());
		setBits(changedComponentIds, componentPool);
		changedComponentIterator = new BitSetIterator(changedComponentIds);
		while (changedComponentIterator.hasNext()) {
			ConcurrencyUtils.checkForInterruption();
			DynamicComponent c = componentPool.get(changedComponentIterator.nextInt());
			changedComponentIterator.remove();
			iterations++;
//			removeSelfLoops(c);
			if (!removeUnusedComponent(c)) {
				optimizeComponent(c);
			}
			if (iterations % 10000 == 0) {
				System.out.println("iteration " + iterations + ": " + componentPool.size() + " components left");
			}
		}
		assert changedComponentIds.cardinality() == 0;
		System.out.println("optimization: first round checked on " + iterations + " components");

		PropNetStructureFactory.packComponents(componentPool);

		// printOptimizationStats();
		System.out.println("#components left: " + componentPool.size());

		// now: check for possible values of components
		// to find components that can never be true or never be false and
		// turn those components into constants (that can be removed later)
		findAdditionalConstantComponents();
		System.out.println("#components left: " + componentPool.size());

		// until fixpoint:
		//  - remove FALSE components
		//  - remove AND components with only one output if output is an AND
		//  - remove OR components with only one output if output is an OR
		//  - remove NOT NOT, if inner NOT has only one output
		//  - remove PIPEs
		//  - remove INPUTs if they are always false
		//  - remove BASEs if they are always false
		//  Note: removing can only be done if components are not technically needed (are not base, input or any other recognized proposition)

		iterations = 0;
		changedComponentIds = new BitSet(componentPool.getHighestId());
		setBits(changedComponentIds, componentPool);
		changedComponentIterator = new BitSetIterator(changedComponentIds);
		while (changedComponentIterator.hasNext()) {
			ConcurrencyUtils.checkForInterruption();
			DynamicComponent c = componentPool.get(changedComponentIterator.nextInt());
			changedComponentIterator.remove();
			iterations++;
//			removeSelfLoops(c);
			if (!removeUnusedComponent(c)) {
//				if (removeDuplicateInputsAndOutputs(c)) {
//					changedComponents.addAll(c.getInputs());
//					changedComponents.addAll(c.getOutputs());
//				}
				optimizeNotOutputs(c);
				optimizeCommonFactors(c, Type.AND);
				optimizeCommonFactors(c, Type.OR);
				pushFactorToCommonOutputs(c); // TODO: find better name
				optimizeComponent(c);
			}
			if (iterations % 10000 == 0) {
				System.out.println("iteration " + iterations + ": " + componentPool.size() + " components left");
			}
		}
		System.out.println("optimization: checked on " + iterations + " components");
		printOptimizationStats();
		return componentPool;
	}

	/**
	 * runs a fixed-point iteration of possible value propagation to find if
	 * there are components that can never be true / never be false and
	 * turns those components into constants disconnecting them from their inputs
	 * @throws InterruptedException
	 */
	private void findAdditionalConstantComponents() throws InterruptedException {
		BitSet possiblyTrue = new BitSet(componentPool.getHighestId());
		BitSet possiblyFalse = new BitSet(componentPool.getHighestId());
		LinkedList<Pair<Integer, Boolean>> queue = new LinkedList<>();
		// to start with base props in the initial state can be true, those not in the initial state can be false
		for (DynamicComponent c : componentPool) {
			switch (c.type) {
			case INIT:
				// the special INIT component can be both true and false, but we don't want to propagate this
				possiblyTrue.set(c.id);
				possiblyFalse.set(c.id);
				break;
			case TRUE:
				possiblyTrue.set(c.id);
				queue.add(Pair.of(c.id,true));
				break;
			case FALSE:
				possiblyFalse.set(c.id);
				queue.add(Pair.of(c.id,false));
				break;
			case BASE: {
				boolean initialValue = false; // is the fluent in the initial state?
				boolean hasNext = false; // does the fluent have a next component (if not it is for sure false in the next state)
				for (DynamicComponent input : c.getInputs()) {
					if (input.type == Type.INIT) {
						initialValue = true;
					} else {
						hasNext = true;
					}
				}
				if (initialValue) {
					possiblyTrue.set(c.id);
				} else {
					possiblyFalse.set(c.id);
				}
				if (!hasNext) {
					possiblyFalse.set(c.id);
				}
				queue.add(Pair.of(c.id, initialValue));
				break; }
			case INPUT:
				 // all does(r,a) can be false (except in weird cases)
				possiblyFalse.set(c.id);
				queue.add(Pair.of(c.id, false));
				break;
			default:
			}
		}
		// now propagate the values
		while (!queue.isEmpty()) {
			ConcurrencyUtils.checkForInterruption();
			Pair<Integer, Boolean> p = queue.pop();
			DynamicComponent c = componentPool.get(p.left);
			boolean value = p.right;
			assert value && possiblyTrue.get(c.id) || !value && possiblyFalse.get(c.id);
			for (DynamicComponent cout : c.getOutputs()) {
				switch (cout.type) {
				case AND:
					if (!value) {
						// if an AND input can be false then the AND can be false
						if (!possiblyFalse.get(cout.id)) {
							possiblyFalse.set(cout.id);
							queue.add(Pair.of(cout.id, false));
						}
					} else {
						// if all AND inputs can be true then the AND can be true
						if (!possiblyTrue.get(cout.id)) {
							boolean canBeTrue = true;
							for (DynamicComponent cin : cout.getInputs()) {
								canBeTrue = canBeTrue && possiblyTrue.get(cin.id);
							}
							if (canBeTrue) {
								possiblyTrue.set(cout.id);
								queue.add(Pair.of(cout.id, true));
							}
						}
					}
					break;
				case OR:
					if (value) {
						// if an OR input can be true then the OR can be true
						if (!possiblyTrue.get(cout.id)) {
							possiblyTrue.set(cout.id);
							queue.add(Pair.of(cout.id, true));
						}
					} else {
						// if all OR inputs can be false then the OR can be false
						if (!possiblyFalse.get(cout.id)) {
							boolean canBeFalse = true;
							for (DynamicComponent cin : cout.getInputs()) {
								canBeFalse = canBeFalse && possiblyFalse.get(cin.id);
							}
							if (canBeFalse) {
								possiblyFalse.set(cout.id);
								queue.add(Pair.of(cout.id, false));
							}
						}
					}
					break;
				case FALSE:
				case TRUE:
					assert false; // these components should not have inputs
					break;
				case NOT:
					if (value) {
						if (!possiblyFalse.get(cout.id)) {
							possiblyFalse.set(cout.id);
							queue.add(Pair.of(cout.id, false));
						}
					} else {
						if (!possiblyTrue.get(cout.id)) {
							possiblyTrue.set(cout.id);
							queue.add(Pair.of(cout.id, true));
						}
					}
					break;
				case BASE:
				case INPUT:
				case PIPE:
					if (value) {
						if (!possiblyTrue.get(cout.id)) {
							possiblyTrue.set(cout.id);
							queue.add(Pair.of(cout.id, true));
						}
					} else {
						if (!possiblyFalse.get(cout.id)) {
							possiblyFalse.set(cout.id);
							queue.add(Pair.of(cout.id, false));
						}
					}
				default:
					break;

				}
			}
		}
		int nbConstantsFound = 0;
		// done propagating values, now we can turn components with only one possible value into constants
		for (DynamicComponent c : componentPool) {
			ConcurrencyUtils.checkForInterruption();
			if (possiblyTrue.get(c.id)) {
				if (!possiblyFalse.get(c.id)) {
					if (c.type != Type.TRUE) {
						nbConstantsFound++;
						c.type = Type.TRUE;
						detachInputs(c);
						System.out.println("constant true component: " + c);
					}
				}
				// else both values possible -> nothing to do
			} else {
				if (possiblyFalse.get(c.id)) {
					if (c.type != Type.FALSE) {
						nbConstantsFound++;
						c.isView = false;
						c.type = Type.FALSE;
						detachInputs(c);
						System.out.println("constant false component: " + c);
					}
				} else {
					// component with no possible value can only mean that the component is not connected
					// to either base or input propositions, but is not a constant either
					// Probably, this can't actually happen in a valid GDL.
					// TODO: check if this breaks things in other components, because the value of this component was not propagated!
					// TODO: what happens with strange cycles, e.g., (<= p p) (<= terminal p (not (true a))) (<= terminal (true a))?
					assert false;
					System.out.println("strange component with no possible value: " + c);
					detachInputs(c);
					detachOutputs(c);
					removeComponent(c);
				}
			}
		}
		System.out.println("found " + nbConstantsFound + " constant valued components");
	}

//	private void removeSelfLoops(DynamicComponent c) {
//		if (c.getOutputs().contains(c)) {
//			disconnect(c, c);
//			didOptimize(OptimizationType.SELF_LOOP);
//		}
//	}

	private void addChangedComponent(int id) {
		changedComponentIds.set(id);
		changedComponentIterator.resetTo(id);
	}
	private void addChangedComponent(DynamicComponent c) {
		addChangedComponent(c.id);
	}
	private void addChangedComponents(DynamicComponentSet cs) {
		for (IntIterator it = cs.getIndices().iterator() ; it.hasNext() ; ) {
			addChangedComponent(it.nextInt());
		}
	}
	private void removeChangedComponent(int id) {
		changedComponentIds.clear(id);
		changedComponentIterator.removedIndex(id);
	}
	private void removeChangedComponent(DynamicComponent c) {
		removeChangedComponent(c.id);
	}


//	private boolean removeDuplicateInputsAndOutputs(DynamicComponent c) {
//		boolean changed = false;
//		changed = removeDuplicateInputs(c) || changed;
//		changed = removeDuplicateOutputs(c) || changed;
//		return changed;
//	}
//
//	private boolean removeDuplicateInputs(DynamicComponent c) {
//	    int size = c.getNbInputs();
//	    int out = 0;
//	    boolean changed = false;
//        Set<DynamicComponent> encountered = new HashSet<>();
//        for (int in = 0; in < size; in++) {
//        	DynamicComponent t = c.inputs.get(in);
//            boolean first = encountered.add(t);
//            if (first) {
//            	c.inputs.set(out++, t);
//            } else {
//            	t.outputs.remove(this);
//            }
//        }
//	    while (out < size) {
//	    	c.inputs.remove(--size);
//	        changed = true;
//	    }
//	    return changed;
//	}
//
//	private boolean removeDuplicateOutputs(DynamicComponent c) {
//	    int size = c.getNbOutputs();
//	    int out = 0;
//	    boolean changed = false;
//        Set<DynamicComponent> encountered = new HashSet<>();
//        for (int in = 0; in < size; in++) {
//        	DynamicComponent t = c.outputs.get(in);
//            boolean first = encountered.add(t);
//            if (first) {
//            	c.outputs.set(out++, t);
//            } else {
//            	t.inputs.remove(this);
//            }
//        }
//	    while (out < size) {
//	    	c.outputs.remove(--size);
//	        changed = true;
//	    }
//	    return changed;
//	}


	private void bypassComponent(DynamicComponent c) {
		bypassComponentWithoutRemoval(c);
		detachInputs(c);
		removeComponent(c);
	}

	private void bypassComponentWithoutRemoval(DynamicComponent c) {
		// this is neutral to the size of the network, if c has exactly one input
		// remove c as input from its outputs
		DynamicComponentSet outputs = new DynamicComponentSet(c.getOutputs());
		DynamicComponentPool.disconnect(c, outputs);
		addChangedComponents(outputs);
		// move all of c's inputs to its output
		DynamicComponentPool.connect(c.getInputs(), outputs);
		addChangedComponents(c.getInputs());
	}

	// removes the connections from all of c's inputs to c
	private void detachInputs(DynamicComponent c) {
		for (DynamicComponent input : c.getInputs()) {
			input.getOutputs().remove(c);
		}
		addChangedComponents(c.getInputs());
		c.getInputs().clear();
	}

	private void detachOutputs(DynamicComponent c) {
		for (DynamicComponent output : c.getOutputs()) {
			output.getInputs().remove(c);
		}
		addChangedComponents(c.getOutputs());
		c.getOutputs().clear();
	}

	private void detachOutputs(DynamicComponent c, DynamicComponentSet outputs) {
		for (DynamicComponent output : outputs) {
			output.getInputs().remove(c);
		}
		addChangedComponents(outputs);
		c.getOutputs().removeAll(outputs);
	}

	public static <E> Collection<E> getIntersection(Collection<E> set1, Collection<E> set2) {
		Collection<E> result;
		if (set1.size()>20) {
			result = new HashSet<>();
		} else {
			result = new ArrayList<>(set1.size());
		}
		for (E e : set1) {
			if(set2.contains(e))
				result.add(e);
		}
	    return result;
	}

	private void didOptimize(OptimizationType o) {
		// System.out.println(o);
		optimizationsDone[o.ordinal()]++;
	}

	private void optimizeAnd(DynamicComponent c) {
		if (c.getNbInputs() == 0) {
			c.type = Type.TRUE;
			propagateTrueValue(c);
		} else if (c.getNbInputs() == 1) {
			c.type = Type.PIPE;
			addChangedComponents(c.getInputs());
			optimizePipe(c);
		} else if (!c.isView && c.getNbOutputs() == 1 && c.getFirstOutput().type==Type.AND) {
			bypassComponent(c);
			didOptimize(OptimizationType.AND_WITH_AND_OUTPUT);
//		} else if (!c.isView && c.getNbOutputs() == 1 && c.getFirstOutput().type==Type.NOT) {
//			useDeMorgan(c);
		} else if (onlyNotInputs(c)) {
			useDeMorganReversed(c);
		}
	}

	// this seems generally not a good idea because it increases the number of components significantly
//	private void useDeMorgan(DynamicComponent c) {
//		assert c.type == Type.AND || c.type == Type.OR;
//		assert c.getNbOutputs() == 1;
//		assert c.getFirstOutput().type == Type.NOT;
//		assert !c.isView;
//		// find or create negations for all inputs of c
//		DynamicComponentSet negInputs = new DynamicComponentSet(componentPool);
//		int nbNewComponents = 0;
//		for (DynamicComponent input : c.getInputs()) {
//			DynamicComponent negInput = null;
//			if (input.type == Type.NOT) {
//				negInput = input.getFirstInput();
//			} else {
//				for (DynamicComponent inputsOutput : input.getOutputs()) {
//					if (inputsOutput.type == Type.NOT) {
//						negInput = inputsOutput;
//						break;
//					}
//				}
//				if (negInput == null) {
//					negInput = componentPool.create();
//					negInput.type = Type.NOT;
//					DynamicComponentPool.connect(input, negInput);
//					nbNewComponents++;
//				}
//			}
//			negInputs.add(negInput);
//		}
//		detachInputs(c);
//		DynamicComponentPool.connect(negInputs, c);
//		addChangedComponents(negInputs);
//		DynamicComponent notOutput = c.getFirstOutput();
//		if (c.type == Type.AND) {
//			notOutput.type = Type.OR;
//		} else { // c.type == Type.OR
//			notOutput.type = Type.AND;
//		}
//		bypassComponent(c);
//		didOptimize(OptimizationType.DEMORGAN);
//		System.out.println("using DeMorgan creating " + (nbNewComponents-1) + " new negations in the process");
//	}

	private void useDeMorganReversed(DynamicComponent c) {
		assert c.type == Type.AND || c.type == Type.OR;
		assert onlyNotInputs(c);
		// find or create negations for all inputs of c
		DynamicComponentSet negInputs = new DynamicComponentSet(componentPool);
		for (DynamicComponent input : c.getInputs()) {
			negInputs.add(input.getFirstInput());
		}
		DynamicComponent newC = componentPool.create();
		if (c.type == Type.AND) {
			newC.type = Type.OR;
		} else { // c.type == Type.OR
			newC.type = Type.AND;
		}
		detachInputs(c);
		DynamicComponentPool.connect(negInputs, newC);
		DynamicComponentPool.connect(newC, c);
		c.type=Type.NOT;
		addChangedComponents(negInputs);
		addChangedComponent(c);
		didOptimize(OptimizationType.DEMORGAN);
	}

	private boolean onlyNotInputs(DynamicComponent c) {
		for (DynamicComponent input : c.getInputs()) {
			if (input.type != Type.NOT) return false;
		}
		return true;
	}

	private static void intersectWithBitSet(BitSet bitSet, IntSet indices) {
		for (int i = bitSet.nextSetBit(0) ; i >= 0 ; i = bitSet.nextSetBit(i+1)) {
			if (!indices.contains(i)) {
				bitSet.clear(i);
			}
		}
	}

//	private boolean intersectionSizeAtLeast(int minNbIntersections, BitSet bitSet, IntSet indices) {
//		for (IntIterator it = indices.iterator() ; minNbIntersections>0 && it.hasNext() ; ) {
//			if (bitSet.get(it.nextInt())) {
//				minNbIntersections--;
//			}
//		}
//		return minNbIntersections == 0;
//	}

	public static void setBits(BitSet bitSet, IntSet indices) {
		for (IntIterator it = indices.iterator() ; it.hasNext() ; ) {
			bitSet.set(it.nextInt());
		}
	}
	public static void setBits(BitSet bitSet, Collection<DynamicComponent> collection) {
		for (DynamicComponent c : collection) {
			bitSet.set(c.id);
		}
	}


	private void optimizeComponent(DynamicComponent c) {
		switch (c.type) {
			case AND:
				optimizeAnd(c);
				break;
			case OR:
				optimizeOr(c);
				break;
			case NOT:
				optimizeNegation(c);
				break;
			case PIPE:
				optimizePipe(c);
				break;
			case FALSE:
				propagateFalseValue(c);
				break;
			case TRUE:
				propagateTrueValue(c);
				break;
			case BASE:
				optimizeBase(c);
				break;
			case INPUT:
				optimizeInput(c);
				break;
			case INIT:
				break;
		}
	}

	private void optimizeBase(DynamicComponent c) {
		assert c.type==Type.BASE && c.getNbInputs() <= 2;
		boolean allInputsFalse = true;
		boolean initiallyTrue = false;
		boolean allInputsTrueOrSelf = true;
		for (DynamicComponent input : c.getInputs()) {
			allInputsFalse = allInputsFalse && (input.type == Type.FALSE);
			initiallyTrue = initiallyTrue || (input.type == Type.INIT);
			allInputsTrueOrSelf = allInputsTrueOrSelf && (input.type == Type.INIT || input.type == Type.TRUE || input == c);
		}
		if (allInputsFalse) { // c is neither true initially, nor in any next state
			// remove the base and allow removal of the associated next(X) component
			removeFalseInput(c);
		} else if (c.getNbInputs() == 2 && initiallyTrue && allInputsTrueOrSelf) {
			// fluent is true initially and will stay true because next(x)=true(x) or next(x)=true
			removeTrueInput(c);
		}
	}

	private void optimizeInput(DynamicComponent c) {
		assert c.type==Type.INPUT && c.getNbInputs() <= 1;
		if (c.getNbInputs() == 0 || c.getFirstInput().type == Type.FALSE) {
			// remove the input and allow removal of the associated legal(R,M) component
			removeFalseInput(c);
		}
	}

	private void optimizeNegation(DynamicComponent c) {
		assert c.type == Type.NOT && c.getNbInputs() == 1;
		// for each output, if the output is a NOT, than bypass c and turn the output into a pipe that feeds from c's input
		DynamicComponent input = c.getFirstInput();
		boolean somethingChanged = false;
		Collection<DynamicComponent> notOutputs = new LinkedList<>();
		for (DynamicComponent output : c.getOutputs()) {
			if (output.type == Type.NOT) {
				assert output.getNbInputs() == 1;
				notOutputs.add(output);
			}
		}
		for (DynamicComponent output : notOutputs) {
			output.type = Type.PIPE;
			// remove c as output's input and add c's input as input to output
			DynamicComponentPool.disconnect(c, output);
			DynamicComponentPool.connect(input, output);
			addChangedComponent(output);
			somethingChanged = true;
		}
		if (somethingChanged) {
			addChangedComponent(input); // this component got new outputs
			if (c.getNbOutputs() == 0 && !c.isView) {
				// component can be removed
				detachInputs(c);
				removeComponent(c);
				didOptimize(OptimizationType.NOT_NOT);
			} else {
				// we still need to keep this component
				didOptimize(OptimizationType.KEEP_NOT_NOT);
			}
		}
	}

	private void optimizeNotOutputs(DynamicComponent c) {
		// if c has two or more outputs that are "NOT", then combine them
		if (c.getNbOutputs() <= 1) {
			return;
		}
		LinkedList<DynamicComponent> notOutputs = new LinkedList<>();
		for (DynamicComponent cout : c.getOutputs()) {
			if (cout.type == Type.NOT) {
				notOutputs.add(cout);
			}
		}
		if (notOutputs.size()>1) {
			System.out.println("found " + (notOutputs.size()) + " negations of component " + c);
			DynamicComponent notOutputToKeep = notOutputs.pop();
			for (DynamicComponent cout : notOutputs) {
				// move all of cout's links to notOutputToKeep
				DynamicComponentPool.connect(notOutputToKeep, cout.getOutputs());
				detachOutputs(cout);
				notOutputToKeep.isView = notOutputToKeep.isView || cout.isView;
				// move all of couts symbols over to notOutputToKeep
				notOutputToKeep.addSymbols(cout.getSymbols());
				cout.removeAllSymbols();
				detachInputs(cout);
				removeComponent(cout);
				didOptimize(OptimizationType.DUPLICATE_NOT_OUTPUT);
			}
			addChangedComponent(notOutputToKeep);
		}
	}

	private void optimizeOr(DynamicComponent c) {
		if (c.getNbInputs() == 0) {
			c.type = Type.FALSE;
			propagateFalseValue(c);
		} else if (c.getNbInputs() == 1) {
			c.type = Type.PIPE;
			addChangedComponents(c.getOutputs());
			optimizePipe(c);
		} else if (!c.isView && c.getNbOutputs()==1 && c.getFirstOutput().type==Type.OR) {
			bypassComponent(c);
			didOptimize(OptimizationType.OR_WITH_OR_OUTPUT);
//		} else if (!c.isView && c.getNbOutputs() == 1 && c.getFirstOutput().type==Type.NOT) {
//			useDeMorgan(c);
		} else if (onlyNotInputs(c)) {
			useDeMorganReversed(c);
		}
	}

	private void optimizePipe(DynamicComponent c) {
		assert c.getNbInputs() == 1 && c.type == Type.PIPE;
		DynamicComponent input = c.getFirstInput();
		input = c.getFirstInput();
		// move all of c's symbols over to input
		input.isView = input.isView || c.isView;
		input.addSymbols(c.getSymbols());
		c.removeAllSymbols();
		bypassComponent(c);
		didOptimize(OptimizationType.PIPE);
	}

	private void printOptimizationStats() {
		System.out.println("optimizations done:");
		for (OptimizationType o : OptimizationType.values()) {
			System.out.println(o.name().toLowerCase() + " = " + optimizationsDone[o.ordinal()]);
		}
	}

	private void propagateFalseValue(DynamicComponent c) {
		assert c.type==Type.FALSE && c.getNbInputs() == 0;
		for (DynamicComponent output : c.getOutputs()) {
			switch (output.type) {
			case AND:
				// replace AND with FALSE and remove all of its inputs
				output.type = Type.FALSE;
				output.getInputs().remove(c); // to make sure we don't modify c.outputs in the next step
				detachInputs(output);
				output.getInputs().add(c);
				break;
			case OR:
				break;
			case NOT:
				// replace NOT with TRUE
				output.type = Type.TRUE;
				break;
			case PIPE:
				// replace PIPE with FALSE
				output.type = Type.FALSE;
				break;
			case INPUT:
				output.type = Type.FALSE;
				break;
			case BASE:
				break;
			case INIT:
			case FALSE:
			case TRUE:
				assert false;
				break;
			}
		}
		detachOutputs(c);
		removeComponent(c);
		didOptimize(OptimizationType.FALSE);
	}

	private void propagateTrueValue(DynamicComponent c) {
		assert c.type==Type.TRUE && c.getNbInputs() == 0;
		DynamicComponentSet outputsToRemove = new DynamicComponentSet(componentPool);
		for (DynamicComponent output : c.getOutputs()) {
			switch (output.type) {
			case AND:
				outputsToRemove.add(output);
				break;
			case OR:
				// replace OR with TRUE and remove all of its (other) inputs
				output.type = Type.TRUE;
				output.getInputs().remove(c); // to make sure we don't modify c.outputs in the next step
				detachInputs(output);
				output.getInputs().add(c);
				outputsToRemove.add(output);
				break;
			case NOT:
				// replace NOT with FALSE
				output.type = Type.FALSE;
				outputsToRemove.add(output);
				break;
			case PIPE:
				// replace PIPE by TRUE
				output.type = Type.TRUE;
				outputsToRemove.add(output);
				break;
			case TRUE:
			case FALSE:
			case INIT:
				assert false;
				break;
			case BASE:
				break;
			case INPUT:
				break;
			}
		}
		detachOutputs(c, outputsToRemove);
		if (c.isView || c.getNbOutputs() > 0) {
			didOptimize(OptimizationType.KEEP_TRUE);
		} else {
			removeComponent(c);
			didOptimize(OptimizationType.TRUE);
		}
	}

	private BitSet occurs = null;
	private void optimizeCommonFactors(DynamicComponent c, Type type) {
		assert type == Type.AND || type == Type.OR;
		// if c outputs to several ANDs (or ORs) and these have other inputs in common
		// then add an intermediate AND (or OR) for the common inputs
		if (c.getNbOutputs() < 3) {
			return;
		}
//		if (c.getNbOutputs() > 1000) {
//			System.out.println("number of outputs is too high for optimizeCommonFactors: " + c.getNbOutputs());
//			return;
//		}
		// first find other inputs of c's outputs that occur more than once
		if (occurs == null) {
			occurs = new BitSet(componentPool.getHighestId()+1);
		} else {
			occurs.clear();
		}
		IntSet occursTwice = new IntOpenHashSet();
		IntSet relevantOutputIds = new IntOpenHashSet();
		for (DynamicComponent cout : c.getOutputs()) {
			if (cout.type == type && cout.getNbInputs()>1) {
				relevantOutputIds.add(cout.id);
				IntIterator it = cout.getInputs().getIndices().iterator();
				while (it.hasNext()) {
					int id = it.nextInt();
					if (occurs.get(id)) {
						occursTwice.add(id);
					} else {
						occurs.set(id);
					}
				}
			}
		}
		occursTwice.rem(c.id);
		// there is at least one element here anyway, because c occurs in all of c's outputs as input
		// TODO: maybe check for the biggest factor (potentially also without a)
		for (int commonId : occursTwice) {
			// find all outputs of c that have id as input and combine them
			IntList factoredComponentIds = new IntArrayList(c.getNbOutputs());
			factoredComponentIds.clear();
			BitSet factor = null;
			for (int i : relevantOutputIds) {
				DynamicComponent cout = componentPool.get(i);
				IntSet coutInputs = cout.getInputs().getIndices();
				if (coutInputs.contains(c.id) && coutInputs.contains(commonId)) {
					if (factor == null) {
						factor = new BitSet();
						setBits(factor, coutInputs);
					} else {
						intersectWithBitSet(factor, coutInputs);
					}
					factoredComponentIds.add(i);
				}
			}
			if (factoredComponentIds.size()>1) {
				assert factor.cardinality()>=2; // at least c.id and commonId are in here
				// deal with the found factor
				// create factor component of the same type as factored components
				DynamicComponent factorComponent = componentPool.create();
				factorComponent.type = componentPool.get(factoredComponentIds.get(0)).type;
				DynamicComponentSet componentsInFactor = new DynamicComponentSet(componentPool, factor);
				DynamicComponentSet factoredComponents = new DynamicComponentSet(componentPool, factoredComponentIds);
				// add all factors as inputs of the factorComponent
				DynamicComponentPool.connect(componentsInFactor, factorComponent);
				// add all factored components as output of the factorComponent
				DynamicComponentPool.connect(factorComponent, factoredComponents);
				// remove the connections between all the components in the factor and the factored components
				DynamicComponentPool.disconnect(componentsInFactor, factoredComponents);
				// Since the c is one element of componentsInFactor, we also need to
				// change relevantOutputIds here.
				relevantOutputIds.removeAll(factoredComponentIds);
				relevantOutputIds.add(factorComponent.id);

				// for debugging: report on nb of added components and removed links
				// int linksSaved = factor.size() * (factoredComponents.size() - 1) - factoredComponents.size();
				// the new component has 1 link per factor and 1 link per factored component, but we save #factors * #factoredComponents links
				// System.out.println("removing factor of size " + factor.size() + " from " + factoredComponents.size() + " components; saving " + linksSaved + " links");

				// add new component and note components that changed
				addChangedComponent(factorComponent);
				addChangedComponents(componentsInFactor);
				addChangedComponents(factoredComponents);
				didOptimize(OptimizationType.FACTOR);
			}
		}
	}

	private void pushFactorToCommonOutputs(DynamicComponent c) {
		if (c.getNbOutputs()<2) {
			return;
		}
		// find AND/ORs in outputs of c that have only one output each, that is a an OR/AND, that they have all in common
		IntList outputsWithCommonOutputIds = new IntArrayList(c.getNbOutputs());
		BitSet factor = new BitSet();
		DynamicComponent commonOutputsOutput = null;
		IntList cOutputs = new IntArrayList(c.getOutputs().getIndices());
		for (int i = 0; i < cOutputs.size()-1; i++) {
			DynamicComponent cout = componentPool.get(cOutputs.getInt(i));
			if (!cout.isView &&
				(cout.type == Type.AND || cout.type == Type.OR) &&
				cout.getNbOutputs() == 1 ) {
				commonOutputsOutput = cout.getFirstOutput();
				if (cout.type == Type.AND && commonOutputsOutput.type == Type.OR
						||
					cout.type == Type.OR && commonOutputsOutput.type == Type.AND ) {

					outputsWithCommonOutputIds.clear();
					outputsWithCommonOutputIds.add(cout.id);
					factor.clear();
					setBits(factor, cout.getInputs().getIndices());
					for (int j = i + 1; j < cOutputs.size(); j++) {
						DynamicComponent cout2 = componentPool.get(cOutputs.getInt(j));
						if (!cout.isView &&
							cout2.type == cout.type &&
							cout2.getNbOutputs()==1 &&
							cout2.getFirstOutput() == commonOutputsOutput) {
							outputsWithCommonOutputIds.add(cout2.id);
							intersectWithBitSet(factor, cout2.getInputs().getIndices());
						}
					}
					if (outputsWithCommonOutputIds.size()>1) {
						assert factor.size()>=1; // at least c should be in the factor
						// disconnect outputsWithCommonOutput from commonOutputsOutput
						// put two new components in between:
						// - one of the type of commonOutputsOutput combining the outputsWithCommonOutput
						// - one of the type of outputsWithCommonOutput combining the first new component with the factor
						DynamicComponentSet outputsWithCommonOutput = new DynamicComponentSet(componentPool, outputsWithCommonOutputIds);
						DynamicComponentSet componentsInFactor = new DynamicComponentSet(componentPool, factor);
						DynamicComponentPool.disconnect(outputsWithCommonOutput, commonOutputsOutput);
						DynamicComponentPool.disconnect(componentsInFactor, outputsWithCommonOutput);
						Type type1 = commonOutputsOutput.type;
						Type type2 = (commonOutputsOutput.type == Type.AND ? Type.OR : Type.AND);
						DynamicComponent newC1 = componentPool.create();
						newC1.type = type1;
						DynamicComponentPool.connect(outputsWithCommonOutput, newC1);
						DynamicComponent newC2;
						if (commonOutputsOutput.getNbInputs() == 0) {
							// no other inputs but the common outputs
							// -> reuse the commonOutputsOutput for the second new component
							newC2 = commonOutputsOutput;
						} else {
							newC2 = componentPool.create();
							DynamicComponentPool.connect(newC2, commonOutputsOutput);
						}
						newC2.type = type2;
						DynamicComponentPool.connect(newC1, newC2);
						DynamicComponentPool.connect(componentsInFactor, newC2);
						// int linksSaved = (factor.size() + 1) * outputsWithCommonOutput.size() - outputsWithCommonOutput.size() - 2 - factor.size();
						// System.out.println("moving common factor of size " + factor.size() + " from " + outputsWithCommonOutput.size() + " components; saving " + linksSaved + " links");

						// Since the c is one element of componentsInFactor, we also need to
						// change cOutputs here. Because all outputsWithCommonOutputIds are occurring
						// from cout/i onwards in cOutputs nothing before cout/i changes in cOutputs.
						cOutputs.removeAll(outputsWithCommonOutputIds);
						cOutputs.add(newC2.id);
						// However, we have now removed cout (the i-th element) from cOutputs, so we
						// need to do the i-th element again.
						i--;
						assert cOutputs.size() == c.getNbOutputs();
						// even if we removed all remaining components, we added
						// a new on (factorComponent), so the next index should still be inside the array (but could be the last element)
						assert (i+1)<cOutputs.size();

						addChangedComponent(newC1);
						addChangedComponent(newC2);
						addChangedComponents(componentsInFactor);
						addChangedComponents(outputsWithCommonOutput);
						addChangedComponent(commonOutputsOutput);
						didOptimize(OptimizationType.PUSH_FACTOR);

						// immediately optimize AND/ORs that got rid of their inputs
						for (DynamicComponent cout2: outputsWithCommonOutput) {
							if (cout2.getNbInputs() == 1) {
								cout2.type = Type.PIPE;
								// System.out.println("created a pipe");
								optimizePipe(cout2);
							}
						}
					}
				}
			}
		}
	}

	private void removeFalseInput(DynamicComponent c) {
		c.type = Type.FALSE;
		c.isView = false; // to allow removal
		detachInputs(c);
		propagateFalseValue(c);
		didOptimize(OptimizationType.FALSE_INPUT);
	}

	private void removeTrueInput(DynamicComponent c) {
		// TODO: make this optional so we can actually read the complete state or at least save the always true components somewhere
		System.out.println("removing base that is always true: " + c);
		c.type = Type.TRUE;
		detachInputs(c);
		propagateTrueValue(c);
		didOptimize(OptimizationType.TRUE_INPUT);
	}

	private boolean removeUnusedComponent(DynamicComponent c) {
		if (c.getNbOutputs() == 0 && !c.isView) {
			// remove components that are not used anywhere
			detachInputs(c);
			removeComponent(c);
			didOptimize(OptimizationType.NO_OUTPUT);
			return true;
		} else {
			return false;
		}
	}

	private void removeComponent(DynamicComponent c) {
		if (c.type == Type.INIT) {
			System.out.println("removing INIT");
		}
		componentPool.free(c);
		removeChangedComponent(c);
	}

	private void resetOptimizationStats() {
		optimizationsDone = new int[OptimizationType.values().length];
	}

}
