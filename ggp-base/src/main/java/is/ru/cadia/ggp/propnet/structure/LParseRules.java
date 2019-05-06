package is.ru.cadia.ggp.propnet.structure;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.LinkedList;
import java.util.List;

/**
 * represents the rules returned from the GDL grounder in (modified) lparse format
 *
 * Each rule has a head atom and any number of positive and negated atoms in the body.
 * Each atom is represented by and integer ID.
 * The symbol table maps some of those IDs to their respective GDL terms. Atoms without a mapping
 * are anonymous.
 * @author stephan
 *
 */
public class LParseRules {

	public Int2ObjectMap<List<Rule>> rulesByHeadAtom;
	public Int2ObjectMap<String> symbolTable;
	private int size;
	public IntSet facts;

	private LParseRules() {
		rulesByHeadAtom = new Int2ObjectOpenHashMap<>(1000000);
		symbolTable = new Int2ObjectOpenHashMap<>(1000000);
		facts = new IntOpenHashSet(100000);
	}

	public int size() {
		return size;
	}


	public static LParseRules readLParseRules(InputStream is) throws IOException {
		LParseRules result = new LParseRules();
		BufferedReader br = new BufferedReader(new InputStreamReader(is));
		String line;
		boolean readRulesMode = true;
		boolean doneReading = false;
		int nbRules = 0;
		while (!doneReading && (line = br.readLine()) != null) {
			line = line.trim();
			if (line.equals("")) {
				break;
			}
			String[] parts = line.split("\\s+", 2);
			int type = Integer.parseInt(parts[0]);
			if (readRulesMode) {
				switch (type) {
					case 0: // end of rules section
						readRulesMode = false;
						break;
					case 1: // basic rule
						parts = parts[1].split("\\s+");
						int head = Integer.parseInt(parts[0]);
						int nbLiterals = Integer.parseInt(parts[1]);
						if (nbLiterals == 0) {
							result.facts.add(head);
						} else {
							int nbNegatives = Integer.parseInt(parts[2]);
							int firstNegative = 3;
							int[] negatives = new int[nbNegatives];
							for (int i = 0; i < nbNegatives; i++) {
								negatives[i] = Integer.parseInt(parts[firstNegative+i]);
							}
							int firstPositive = firstNegative+nbNegatives;
							int[] positives = new int[nbLiterals-nbNegatives];
							for (int i = 0; i < positives.length; i++) {
								positives[i] = Integer.parseInt(parts[firstPositive+i]);
							}
							List<Rule> rulesForHeadAtom = result.rulesByHeadAtom.get(head);
							if (rulesForHeadAtom == null) {
								rulesForHeadAtom = new LinkedList<>();
								result.rulesByHeadAtom.put(head, rulesForHeadAtom);
							}
							rulesForHeadAtom.add(new Rule(head, positives, negatives));
						}
						nbRules++;
						if (nbRules % 100000 == 0) {
							System.out.println("read " + nbRules + " rules");
						}
						break;
					default:
						throw new RuntimeException("format of rule not supported: " + line);
				}
			} else { // symbol table
				if (type == 0) {
					doneReading = true;
				} else {
					int id = type;
					String symbol = parts[1];
					result.symbolTable.put(id, symbol);
				}
			}
		}
		System.out.println("read " + nbRules + " rules and " + result.symbolTable.size() + " symbols");
		result.size = nbRules;
		return result;
	}

	public static class Rule {
		// only basic rules for now, that is rules with a single head atom and arbitrary nb. of positive and negative body literals without any aggregates
		public int head;
		public int[] positives;
		public int[] negatives;

		public Rule(int head, int[] positives, int[] negatives) {
			this.head = head;
			this.positives = positives;
			this.negatives = negatives;
		}

		public boolean isFact() {
			return positives.length + negatives.length == 0;
		}
	}
}
