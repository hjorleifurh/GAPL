package is.ru.cadia.ggp.propnet.structure.dynamic;

import is.ru.cadia.ggp.propnet.structure.components.StaticComponent;
import is.ru.cadia.ggp.propnet.structure.components.StaticComponent.Type;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.ggp.base.util.gdl.grammar.GdlSentence;

/**
 * only used for intermediate steps while creating the PropNet
 * @author stephan
 *
 */
public class DynamicComponent /*implements Comparable<DynamicComponent>*/ {

	private DynamicComponentPool pool;
	public int id;
//	PropType propType; // should be set in case this is a proposition component (i.e., has a symbol)
	public Type type;
	public boolean isView;
	private DynamicComponentSet inputs;
	private DynamicComponentSet outputs;
	private List<GdlSentence> symbols;
//	DynamicComponent linkedComponent; // this is the next(X) for a true(X) or the legal(R,M) for a does(R,M)
	public StaticComponent staticComponent;
	public int staticId; // used to renumber the components

	protected DynamicComponent(DynamicComponentPool pool, int id) {
		this.pool = pool;
		initialize(id);
	}

	protected void initialize(int id) {
		this.id = id;
//		propType = PropType.NONE;
		type = Type.FALSE;
		isView = false;
		inputs = new DynamicComponentSet(pool);
		outputs = new DynamicComponentSet(pool);
//		linkedComponent = null;
		symbols = Collections.emptyList();
		staticComponent = null;
		staticId = -1;
	}

	public DynamicComponentSet getInputs() {
		return inputs;
	}

	public DynamicComponentSet getOutputs() {
		return outputs;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("DynamicComponent {type: ");
		sb.append(type);
		sb.append(", #inputs: ").append(inputs.size());
		sb.append(", #outputs: ").append(outputs.size());
		sb.append(", symbols: [");
		for(GdlSentence s : getSymbols()) {
			sb.append(s).append(", ");
		}
		sb.append("]}");
		return sb.toString();
	}

//	@Override
//	public int compareTo(DynamicComponent o) {
//		return Integer.compare(hashCode(), o.hashCode());
//	}

	public DynamicComponent getFirstInput() {
		return inputs.getFirst();
	}

	public DynamicComponent getFirstOutput() {
		return outputs.getFirst();
	}

	public int getNbInputs() {
		return inputs.size();
	}

	public int getNbOutputs() {
		return outputs.size();
	}

	public Collection<GdlSentence> getSymbols() {
		return symbols;
	}

	public GdlSentence getFirstSymbol() {
		return (symbols.isEmpty() ? null : symbols.get(0));
	}

	public void addSymbol(GdlSentence symbol) {
		addSymbols(Collections.singleton(symbol));
	}

	public void addSymbols(Collection<GdlSentence> symbols) {
		if (symbols.isEmpty()) return;
		if (this.symbols.isEmpty()) {
			if (symbols.size() == 1) {
				this.symbols = Collections.singletonList(symbols.iterator().next());
			} else {
				this.symbols = new ArrayList<>(symbols);
			}
		} else if (this.symbols.size() == 1) {
			List<GdlSentence> newcoll = new ArrayList<>(symbols.size() + 1);
			newcoll.addAll(this.symbols);
			newcoll.addAll(symbols);
			this.symbols = newcoll;
		} else {
			this.symbols.addAll(symbols);
		}
	}

	public void removeSymbol(GdlSentence symbol) {
		if (symbols.size() == 1 && symbols.contains(symbol)) {
			symbols = Collections.emptyList();
		} else if (symbols.size()>1) {
			symbols.remove(symbol);
		}
		isView = isView && symbols.size()>0;
	}

	public void removeAllSymbols() {
		symbols = Collections.emptyList();
		isView = false;
	}
}