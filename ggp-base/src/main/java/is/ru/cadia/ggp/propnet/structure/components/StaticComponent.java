package is.ru.cadia.ggp.propnet.structure.components;

import is.ru.cadia.ggp.propnet.structure.ComponentFilter;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Serializable;

import org.ggp.base.util.gdl.grammar.GdlSentence;

@SuppressWarnings("serial")
public class StaticComponent implements Serializable {

	public static enum Type {
		INIT /* is true in the initial state only */, 
		TRUE, 
		FALSE, 
		BASE /* true(X) */, 
		INPUT /* does(R,M) */, 
		AND, 
		NOT, 
		OR, 
		PIPE /* is essentially an AND (or OR) with a single input */
	}

	public int id;
	public Type type;
	public int[] inputs;
	public int[] outputs;
	public boolean isCyclic;

	public StaticComponent(int id, Type type, int[] inputs, int[] outputs, boolean isCyclic) {
		this.id = id;
		this.type = type;
		this.inputs = inputs;
		this.outputs = outputs;
		this.isCyclic = isCyclic;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("StaticComponent {");
		sb.append("id: ").append(id);
		sb.append(", type: ").append(type);
		sb.append(", #inputs: ").append(inputs.length);
		sb.append(", #outputs: ").append(outputs.length);
		sb.append(", cyclic: ").append(isCyclic);
		sb.append("}");
		return sb.toString();
	}

    /**
     * writes a representation of the component to the stream in .dot format.
     *
     * @param os
     * 		The stream to write to.
     * @throws IOException
     */
 	public void toDot(OutputStreamWriter os, ComponentFilter filter) throws IOException {
		String shape;
		String fillcolor = "grey";
		String label;

		switch (type) {
		case AND:
			shape = "invhouse";
			break;
		case OR:
			shape = "ellipse";
			break;
		case NOT:
			shape = "invtriangle";
			break;
		case PIPE:
			shape = "circle";
			break;
		case TRUE:
			shape = "doublecircle";
			fillcolor = "green";
			break;
		case FALSE:
			shape = "doublecircle";
			fillcolor = "red";
			break;
		case INPUT:
			shape = "doublecircle";
			break;
		case BASE:
			shape = "doublecircle";
			break;
		default:
			shape = "square";
			break;
		}
		if (this instanceof BaseProposition) {
			label = "";
			for (GdlSentence sentence : ((BaseProposition)this).sentences) {
				label = label + sentence.toString() + "\n";
			}
		} else {
			label = Integer.toString(id);
		}

        os.write("\"@");
        os.write(Integer.toString(id));
        os.write("\"[shape=");
        os.write(shape);
        os.write(", style=filled, fillcolor=");
        os.write(fillcolor);
        os.write(", label=\"");
        os.write(label);
        os.write("\"];\n");
        for ( int input : inputs ) {
        	if (filter.accept(input)) {
	            os.write("\"@");
	            os.write(Integer.toString(input));
	            os.write("\"->\"@");
	            os.write(Integer.toString(id));
	            os.write("\"; ");
        	}
        }
        os.write("\n");
	}
}
