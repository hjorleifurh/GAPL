package is.ru.cadia.ggp.propnet.structure;

import is.ru.cadia.ggp.propnet.structure.LParseRules.Rule;
import is.ru.cadia.ggp.propnet.structure.components.StaticComponent.Type;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponent;
import is.ru.cadia.ggp.propnet.structure.dynamic.DynamicComponentPool;
import is.ru.cadia.ggp.utils.IOUtils;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.ggp.base.util.concurrency.ConcurrencyUtils;
import org.ggp.base.util.gdl.factory.GdlFactory;
import org.ggp.base.util.gdl.factory.exceptions.GdlFormatException;
import org.ggp.base.util.gdl.grammar.Gdl;
import org.ggp.base.util.gdl.grammar.GdlPool;
import org.ggp.base.util.gdl.grammar.GdlSentence;
import org.ggp.base.util.gdl.grammar.GdlTerm;
import org.ggp.base.util.statemachine.Role;
import org.ggp.base.util.symbol.factory.exceptions.SymbolFormatException;

import com.google.common.base.Stopwatch;

public class ASPPropNetStructureFactory extends PropNetStructureFactory {
	private String matchId;
    private List<Gdl> description;
	private Map<Integer, DynamicComponent> componentMap;
	private List<Role> roles;
	private DynamicComponentPool componentPool;
	private Map<Integer, GdlSentence> symbolTable;
//	private Map<GdlSentence, DynamicComponent> symbolToComponent;

    public ASPPropNetStructureFactory() {
    	this("amatch");
    }

    public ASPPropNetStructureFactory(String matchId) {
    	assert matchId != null;
    	this.matchId = matchId;
	}

    protected LParseRules readRulesFromFile() throws InterruptedException, IOException {
    	InputStream is = null;
    	try {
	    	Stopwatch stopWatch = new Stopwatch().start();

	        //run gdlgrounder
	        String filename = matchId + ".lparse";
	        System.out.println("reading lparse rules from " + filename);
	        is = new BufferedInputStream(new FileInputStream(filename));

	        // read GDLish version of lparse file with proposition structure
	        LParseRules rules = LParseRules.readLParseRules(is);
	        is.close();
	        System.out.println("reading rules took " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");
	        return rules;
    	} catch (InterruptedIOException e) {
    		throw new InterruptedException(e.getMessage());
    	} finally {
    		if (is != null) is.close();
    	}
	}

	protected LParseRules runAspGrounder() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        Process p = null;
        try {
	        //run gdlgrounder
	        System.out.println("Running GDL grounder ...");
	        String GROUNDERPATH = "groundgdl/groundgdl.sh";
	        p = Runtime.getRuntime().exec(new String[]{
	        		GROUNDERPATH,
	        		"--matchid=" + matchId,
	        		"--time-limit=600", // don't take longer than 10 minutes
	        		"-",
	        		"-"
	        	});
	        IOUtils.connectStreams(p.getErrorStream(), System.err);

	        // output rules to process
	        BufferedWriter os = new BufferedWriter(new OutputStreamWriter(p.getOutputStream()));
	        for (Gdl gdl : description) {
	        	os.write(gdl.toString() + " ");
	        }
	        os.close();
	        // we can first write and then read here, because groundgdl.sh will first
	        // read the whole input before producing any output, so it should not block

	        // read GDLish version of lparse file with proposition structure
	        LParseRules rules = LParseRules.readLParseRules(p.getInputStream());
			p.waitFor();
	        if (p.exitValue() != 0) {
	        	throw new RuntimeException("Running GDL grounder failed: non zero exit value!");
	        }
	        long grounderEndTime = System.currentTimeMillis();
	        System.out.println("GDL grounder took " + ((grounderEndTime - startTime) / 1000.0) + "s");
	        return rules;
        } catch (InterruptedIOException e) {
        	throw new InterruptedException(e.getMessage());
        } catch (IOException e) {
        	throw new RuntimeException(e);
		} finally {
        	if (p!=null) p.destroy();
        }
	}
	/* (non-Javadoc)
	 * @see is.ru.cadia.ggp.propnet.structure.PropNetStructureFactory#create()
	 */
	@Override
	public PropNetStructure create(List<Gdl> description) throws InterruptedException {
		this.description = description;
		LParseRules rules;
		// rules = readRulesFromFile();
		rules = runAspGrounder();

		// map from id of proposition in grounder result to component for that proposition
		componentMap = new HashMap<>(rules.size());
		// all components
		componentPool = new DynamicComponentPool(rules.size());

        // convert lparse rules to propnet
        //    - each named id is a component
        //    - ids with several rules have a disjunction as input or are the disjunction
        //    - the body of each rule becomes a conjunction (if it has more than 1 element)
        //    - negated body atoms are routed through a negation (we only make one negation per atom)

		// get roles from the rules directly, so we have them in the right order
		roles = Role.computeRoles(description);
		Set<GdlSentence> initialState = new HashSet<>();
		Map<GdlTerm, DynamicComponent> nextMap = new HashMap<>();
		Map<GdlSentence, DynamicComponent> legalMap = new HashMap<>(); // maps does(R,M) to the legal component

		// Start from the symbol table (role, init, next, legal, goal, terminal) and create components
		// for the symbols in the table. From those create all inputs that are needed.
		// This way we automatically skip creating components, that we don't need.

		Stopwatch stopWatch = new Stopwatch().start();
		// mapping id to GDL sentence
		symbolTable = new HashMap<>(rules.symbolTable.size());
		// reverse symbol table (mapping GDL sentence to id)
		Map<GdlSentence, Integer> symbol2Id = new HashMap<>(rules.symbolTable.size());
		// keeping track of ids of propositions that we need to create components for
		IntLinkedOpenHashSet neededIds = new IntLinkedOpenHashSet(rules.size());

		for (Entry<Integer, String> entry : rules.symbolTable.entrySet()) {
			ConcurrencyUtils.checkForInterruption();
			int id = entry.getKey();
			String symbolString = entry.getValue();
			GdlSentence symbol = null;
			try {
				symbol = (GdlSentence)GdlFactory.create(symbolString);
			} catch (GdlFormatException | SymbolFormatException e) {
				System.err.println("error parsing \"" + symbolString + "\"");
				e.printStackTrace();
			}
			if (symbol != null) {
				PropType propType = getPropTypeForSymbol(symbol);
				if (propType == PropType.ROLE
					|| propType == PropType.INIT
					|| propType == PropType.TRUE
					|| propType == PropType.NEXT
					|| propType == PropType.LEGAL && firstArgumentIsARole(symbol, roles)
					|| propType == PropType.DOES && firstArgumentIsARole(symbol, roles)
					|| propType == PropType.GOAL && firstArgumentIsARole(symbol, roles)
					|| propType == PropType.TERMINAL) {
					symbolTable.put(id, symbol);
					symbol2Id.put(symbol, id);

					if (propType == PropType.TRUE
//						|| symbolName.equals(GdlPool.INIT) // we only need those, if there is a true that we need
//						|| symbolName.equals(GdlPool.NEXT)
						|| propType == PropType.LEGAL
						|| propType == PropType.GOAL
						|| propType == PropType.TERMINAL) {
						neededIds.add(id);
					}
				} else {
					symbol = null;
				}
			}
		}
		System.out.println("creating symbol table with " + symbolTable.size() + " symbols took " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");
		rules.symbolTable = null;
		stopWatch.reset();

		// keeping track of which ids of propositions we already have components for
		IntSet processedIds = new IntOpenHashSet(rules.size());
		Set<DynamicComponent> baseComponents = new HashSet<>();
		Set<DynamicComponent> inputComponents = new HashSet<>();
		int nbProcessedComponents = 0;
		while (!neededIds.isEmpty()) {
			ConcurrencyUtils.checkForInterruption();
			int headId = neededIds.removeFirstInt();
			if (processedIds.contains(headId)) continue;
			processedIds.add(headId);
			nbProcessedComponents++;
			if (nbProcessedComponents % 100000 == 0) {
				System.out.println("processed " + nbProcessedComponents + " components");
			}

			// if the symbol for the current id is true(...), add the ids for next(..) and init(..) to the neededIds
			GdlSentence symbol = symbolTable.get(headId);
			PropType propType = getPropTypeForSymbol(symbol);
			if (propType == PropType.TRUE) {
				GdlSentence initSymbol = GdlPool.getRelation(GdlPool.INIT, symbol.getBody());
				Integer initId = symbol2Id.get(initSymbol);
				if (initId != null) {
					neededIds.add(initId);
				}
				GdlSentence nextSymbol = GdlPool.getRelation(GdlPool.NEXT, symbol.getBody());
				Integer nextId = symbol2Id.get(nextSymbol);
				if (nextId != null) {
					neededIds.add(nextId);
				}
				DynamicComponent base = getDynamicComponent(headId);
				baseComponents.add(base);
				base.type = Type.BASE;
			} else if (propType == PropType.DOES) {
				DynamicComponent input = getDynamicComponent(headId);
				inputComponents.add(input);
				input.type = Type.INPUT;
			} else {
				if (rules.facts.rem(headId)) {
					DynamicComponent headComponent = getDynamicComponent(headId);
					headComponent.type = Type.TRUE;
					if (propType == PropType.INIT && symbol.arity()==1) {
						initialState.add(GdlPool.getRelation(GdlPool.TRUE, symbol.getBody()));
					}
					processComponent(symbol, headComponent, nextMap, legalMap);
				} else {
					List<Rule> rulesForAtom = rules.rulesByHeadAtom.remove(headId);
					if (rulesForAtom != null) {
						DynamicComponent headComponent = getDynamicComponent(headId);
						makeComponentForRules(headComponent, rulesForAtom, neededIds);
						processComponent(symbol, headComponent, nextMap, legalMap);
					} else {
						System.out.println("component does not have rules: " + headId + " - " + symbolTable.get(headId));
					}
				}
			}
		}
		// we should not need the symbol table anymore from here on
		symbolTable = null;
		componentMap = null;
		rules = null;
		processedIds = null;
		neededIds = null;
		System.out.println(componentPool.size() + " components created from rules in " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");
		stopWatch.reset();

		// at this point we have all the dynamic components
		// now we should connect true(X) with next(X) and does(R,M) with legal(R,M)

		DynamicComponent initComponent = componentPool.create();
		initComponent.type = Type.INIT;

		for (DynamicComponent c : baseComponents) {
			GdlSentence trueSymbol = c.getFirstSymbol();
			// connect the right next component as linkedComponent to this one
			GdlTerm fluent = trueSymbol.get(0);
			DynamicComponent nextComponent = nextMap.get(fluent);
			if (nextComponent == null) {
				System.out.println("no next for: " + trueSymbol);
				// this fluent is false by default (could be true in the initial state though)
				// make a transition component for it that is false by default
				nextComponent = componentPool.create();
				nextComponent.type = Type.FALSE;
				nextComponent.isView = false;
				nextComponent.addSymbol(GdlPool.getRelation(GdlPool.NEXT, new GdlTerm[] {fluent}));
				// in the optimization step we will check if the fluent is true initially, and if not, remove both components
			}
			// base components have a next component as input
			DynamicComponentPool.connect(nextComponent, c);

			// base components have the INIT component as input, iff they are true initially
			if (initialState.contains(trueSymbol)) {
				DynamicComponentPool.connect(initComponent, c);
			}
		}
		baseComponents = null;

		for (DynamicComponent c : inputComponents) {
			GdlSentence doesSymbol = c.getFirstSymbol();
			// connect the right legal component as input to this one
			DynamicComponent legalComponent = legalMap.get(doesSymbol);
			if (legalComponent == null) {
				System.out.println("no legal for: " + doesSymbol);
				// this move can't be possible, just set it to FALSE and let the component be unimportant
				c.type = Type.FALSE;
				c.isView = false;
			} else {
				DynamicComponentPool.connect(legalComponent, c);
			}
		}
		inputComponents = null;
		System.out.println("connecting components took " + stopWatch.elapsed(TimeUnit.MILLISECONDS)/1000.0 + "s");


		return create(roles, initialState, componentPool);
	}

	private void processComponent(GdlSentence symbol, DynamicComponent headComponent, Map<GdlTerm, DynamicComponent> nextMap, Map<GdlSentence, DynamicComponent> legalMap) {
		if (symbol == null) {
			return;
		} else if (symbol.getName().equals(GdlPool.NEXT) && symbol.arity() == 1) {
			nextMap.put(symbol.get(0), headComponent);
		} else if (symbol.getName().equals(GdlPool.LEGAL) && symbol.arity() == 2 && firstArgumentIsARole(symbol, roles)) {
			legalMap.put(getDoesForLegalSentence(symbol), headComponent);
		}
	}

	private void makeComponentForRules(DynamicComponent head, List<Rule> rulesForAtom, IntSet neededIds) {
		if (rulesForAtom.size()==1 && rulesForAtom.get(0).isFact()) {
			head.type = Type.TRUE;
		} else if (rulesForAtom.size()>1) {
			head.type = Type.OR;
			for (Rule rule : rulesForAtom) {
				DynamicComponent ruleComponent = componentPool.create();
				makeComponentForRule(ruleComponent, rule, neededIds);
				DynamicComponentPool.connect(ruleComponent, head);
			}

		} else { // only one rule, no need to create an or
			makeComponentForRule(head, rulesForAtom.get(0), neededIds);
		}
	}

	private void makeComponentForRule(DynamicComponent head, Rule rule, IntSet neededIds) {
		if (rule.negatives.length + rule.positives.length == 0) {
			head.type = Type.TRUE;
		} else {
			head.type = Type.AND;
			for (int id : rule.positives) {
				DynamicComponent c = getDynamicComponent(id);
				DynamicComponentPool.connect(c, head);
				neededIds.add(id);
			}
			for (int id : rule.negatives) {
				DynamicComponent c = getDynamicComponent(-id);
				DynamicComponentPool.connect(c, head);
				neededIds.add(id);
			}
			if (head.getNbInputs()==1) {
				head.type = Type.PIPE;
			}
		}
	}

	private DynamicComponent getDynamicComponent(int id) {
		DynamicComponent c = componentMap.get(id);
		if (c == null) {
			c = componentPool.create();
			componentMap.put(id, c);
			if (id<0) {
				c.type = Type.NOT;
				DynamicComponent notC = getDynamicComponent(-id);
				DynamicComponentPool.connect(notC, c);
			} else {
				GdlSentence symbol = symbolTable.get(id);
				if (symbol != null) {
					PropType propType = getPropTypeForSymbol(symbol);
					if (propType != PropType.NONE) {
						c.addSymbol(symbol);
						if (propType == PropType.LEGAL || propType == PropType.GOAL || propType == PropType.TERMINAL) {
							c.isView = true;
						}
					}
				}
			}
		}
		return c;
	}

	@Override
	public void cleanup() {
		super.cleanup();
		matchId = null;
	    description = null;
		componentMap = null;
		roles = null;
		componentPool = null;
		symbolTable = null;
	}

}
