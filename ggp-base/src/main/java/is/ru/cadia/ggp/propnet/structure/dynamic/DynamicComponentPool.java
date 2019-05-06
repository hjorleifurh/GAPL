package is.ru.cadia.ggp.propnet.structure.dynamic;

import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

public class DynamicComponentPool extends AbstractCollection<DynamicComponent> implements Set<DynamicComponent> {

	private static final int NB_FREE_COMPONENTS_TO_KEEP = 100;
	private ArrayList<DynamicComponent> components;
	private List<DynamicComponent> freeComponents;
	private int nextIdToCheck;
	private int nbComponents;
	private int highestId;

	public DynamicComponentPool() {
		this(10);
	}

	public DynamicComponentPool(int initialCapacity) {
		components = new ArrayList<>(initialCapacity);
		freeComponents = new ArrayList<>(NB_FREE_COMPONENTS_TO_KEEP);
		nextIdToCheck = 0;
		nbComponents = 0;
		highestId = 0;
	}

	public DynamicComponent create() {
		return create(nextFreeId());
	}

	private int nextFreeId() {
		while (nextIdToCheck<components.size() && components.get(nextIdToCheck) != null) {
			nextIdToCheck++;
		}
		return nextIdToCheck;
	}

	public DynamicComponent create(int id) {
		assert components.size()<=id || components.get(id) == null;
		DynamicComponent c;
		if (freeComponents.size()>0) {
			c = freeComponents.remove(freeComponents.size()-1);
			c.initialize(id);
		} else {
			c = new DynamicComponent(this, id);
		}

		if (components.size() <= id) {
			components.ensureCapacity(id);
			while (components.size() < id) {
				components.add(null);
			}
			components.add(c);
		} else {
			components.set(id, c);
		}
		nbComponents++;
		highestId = Math.max(highestId, id);
		return c;
	}

	public DynamicComponent get(int id) {
		DynamicComponent c = components.get(id);
		assert c != null;
		return c;
	}

	public void free(DynamicComponent c) {
		assert !freeComponents.contains(c);
		assert components.get(c.id) == c;
		components.set(c.id, null);
		nextIdToCheck = Math.min(nextIdToCheck, c.id);
		nbComponents--;
		if (freeComponents.size()<NB_FREE_COMPONENTS_TO_KEEP) {
			freeComponents.add(c);
		}
		if(c.id == highestId) {
			shrink();
		}
	}

	private void shrink() {
		while (components.get(highestId) == null) {
			components.remove(highestId);
			highestId--;
		}
	}

	@Override
	public Iterator<DynamicComponent> iterator() {
		return new SkipNullIterator<>(components);
	}

	@Override
	public int size() {
		return nbComponents;
	}

	private static class SkipNullIterator<E> implements Iterator<E> {

		Iterator<E> it;
		E next;
		public SkipNullIterator(Collection<E> collection) {
			this.it = collection.iterator();
			findNextNonEmpty();
		}

		private void findNextNonEmpty() {
			next = null;
			while (it.hasNext() && next == null) {
				next = it.next();
			}
		}

		@Override
		public boolean hasNext() {
			return next != null;
		}

		@Override
		public E next() {
			if (next == null)
				throw new NoSuchElementException();
			E result = next;
			findNextNonEmpty();
			return result;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 *
	 * @return the highest id of any component currently in the pool
	 */
	public int getHighestId() {
		return highestId;
	}

	/**
	 * changes the id from every component to its staticId (which requires changing inputs and outputs of all components)
	 */
	public void reorganize() {
		// first change components' inputs and outputs while all components are still in their original place
		for (DynamicComponent c : this) {
			int i = c.getNbInputs();
			c.getInputs().reorganize();
			assert c.getNbInputs()==i;
			i = c.getNbOutputs();
			c.getOutputs().reorganize();
			assert c.getNbOutputs()==i;
		}
		// now change the components ids and put them in the new place
		for (int i = 0 ; i < components.size() ; i++) {
			DynamicComponent c = components.get(i);
			if (c != null && c.staticId != c.id) {
				components.set(i, null); // take c out of its current place
				int oldId = i;
				while (c != null) {
					assert c.id == oldId;
					// preserve whatever is at the new position for c
					int newId = c.staticId;
					DynamicComponent c2 = components.get(newId);
					// put c into its new position and change its id
					c.id = newId;
					components.set(newId, c);
					c = c2; // continue with whatever was at newId
					oldId = newId; // for c2 newId is the oldId
				}
			}
		}
		shrink();
//		for (int i = 0 ; i < components.size() ; i++) {
//			DynamicComponent c = components.get(i);
//			if (c != null) {
//				assert c.id == i;
//				for (int j : c.getInputs().getIndices()) {
//					assert j < components.size();
//					assert components.get(j).id == j;
//				}
//				for (int j : c.getOutputs().getIndices()) {
//					assert j < components.size();
//					assert components.get(j).id == j;
//				}
//			}
//		}
	}

	public static void disconnect(DynamicComponent from, DynamicComponent to) {
		to.getInputs().remove(from);
		from.getOutputs().remove(to);
	}

	public static void disconnect(DynamicComponent from, Collection<DynamicComponent> tos) {
		for (DynamicComponent to : tos) {
			to.getInputs().remove(from);
		}
		from.getOutputs().removeAll(tos);
	}

	public static void disconnect(Collection<DynamicComponent> froms, DynamicComponent to) {
		for (DynamicComponent from : froms) {
			from.getOutputs().remove(to);
		}
		to.getInputs().removeAll(froms);
	}

	public static void disconnect(Collection<DynamicComponent> froms, Collection<DynamicComponent> tos) {
		for (DynamicComponent from : froms) {
			from.getOutputs().removeAll(tos);
		}
		for (DynamicComponent to : tos) {
			to.getInputs().removeAll(froms);
		}
	}

	public static void connect(DynamicComponent from, DynamicComponent to) {
			//assert from!=to;
	//		if (!to.inputs.contains(from)) {
				to.getInputs().add(from);
				from.getOutputs().add(to);
	//		}
		}

	public static void connect(DynamicComponent from, Collection<DynamicComponent> tos) {
			from.getOutputs().addAll(tos);
			for (DynamicComponent to : tos) {
				//assert from!=to;
	//			if (to.inputs.contains(from)) {
	//				from.outputs.remove(to);
	//			} else {
					to.getInputs().add(from);
				//}
			}
		}

	public static void connect(Collection<DynamicComponent> froms, DynamicComponent to) {
			to.getInputs().addAll(froms);
			for (DynamicComponent from : froms) {
				//assert from!=to;
	//			if (from.outputs.contains(to)) {
	//				to.inputs.remove(from);
	//			} else {
					from.getOutputs().add(to);
				//}
			}
		}

	public static void connect(Collection<DynamicComponent> froms, Collection<DynamicComponent> tos) {
		for (DynamicComponent to : tos) {
			to.getInputs().addAll(froms);
		}
		for (DynamicComponent from : froms) {
			from.getOutputs().addAll(tos);
		}
	}

}
