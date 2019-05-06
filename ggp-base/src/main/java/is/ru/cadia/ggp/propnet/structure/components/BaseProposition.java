package is.ru.cadia.ggp.propnet.structure.components;

import is.ru.cadia.ggp.propnet.structure.ComponentFilter;

import java.io.IOException;
import java.io.OutputStreamWriter;

import org.ggp.base.util.gdl.grammar.GdlSentence;

@SuppressWarnings("serial")
public class BaseProposition extends StaticComponent {

	/**
	 *  the value of this proposition in the initial state
	 */
	public boolean initialValue = false;

	public StaticComponent nextComponent = null;

	public GdlSentence[] sentences = null;

	public BaseProposition(int id, Type type, int[] inputs, int[] outputs, GdlSentence[] sentences) {
		super(id, type, inputs, outputs, false);
		assert type == Type.BASE;
		assert sentences != null;
		this.sentences = sentences;
	}

	@Override
 	public void toDot(OutputStreamWriter os, ComponentFilter filter) throws IOException {
		super.toDot(os, filter);
		if (nextComponent != null && filter.accept(nextComponent.id)) {
	        int input = nextComponent.id;
            os.write("\"@");
            os.write(Integer.toString(input));
            os.write("\"->\"@");
            os.write(Integer.toString(id));
            os.write("\"; ");
        }
        os.write("\n");
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("BaseProposition {");
		sb.append("id: ").append(id);
//		sb.append(", type: ").append(type);
//		sb.append(", #inputs: ").append(inputs.length);
		sb.append(", #outputs: ").append(outputs.length);
		sb.append(", cyclic: ").append(isCyclic);
		sb.append(", symbols: [");
		for (GdlSentence sentence : sentences) {
			sb.append(sentence.toString()).append(", ");
		}
		sb.append("]}");
		return sb.toString();
	}
}
