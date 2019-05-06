package is.ru.cadia.ggp.propnet.structure.dynamic;

import is.ru.cadia.ggp.propnet.structure.BitSetIterator;
import it.unimi.dsi.fastutil.ints.IntArraySet;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.ints.IntSets.Singleton;

import java.util.BitSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.Set;

/**
 * a Set<DynamicComponent> that is backed by an IntSet containing the indices of the components
 * @author stephan
 *
 */
public class DynamicComponentSet implements Set<DynamicComponent> {

	private static final int MIN_SIZE_FOR_HASH_SET = 5;
	private static final int MAX_SIZE_FOR_ARRAYSET = 20;
	private IntSet indices;
	private DynamicComponentPool pool;

	/**
	 * creates a new DynamicComponentSet that is a copy of the old one
	 * @param set
	 */
	public DynamicComponentSet(DynamicComponentSet set) {
		this(set.pool, set.indices);
	}

	public DynamicComponentSet(DynamicComponentPool pool) {
		this.pool = pool;
		this.indices = null;
	}

	public DynamicComponentSet(DynamicComponentPool pool, IntCollection indices) {
		this.pool = pool;
		if (indices == null || indices.size() == 0) {
			this.indices = null;
		} else if (indices.size() == 1) {
			this.indices = IntSets.singleton(indices.iterator().nextInt());
		} else if (indices.size() < MAX_SIZE_FOR_ARRAYSET)  {
			this.indices = new IntArraySet(MAX_SIZE_FOR_ARRAYSET);
			this.indices.addAll(indices);
		} else {
			this.indices = new IntOpenHashSet(indices);
		}
	}

	public DynamicComponentSet(DynamicComponentPool pool, final BitSet bitSet) {
		this.pool = pool;
		if (bitSet.isEmpty()) {
			this.indices = null;
		} else {
			this.indices = new IntOpenHashSet(new BitSetIterator(bitSet));
		}
	}

	/*
	 * undefined behavior if this Set is empty
	 */
	public DynamicComponent getFirst() {
		return pool.get(indices.iterator().nextInt());
	}

//	/*
//	 * undefined behavior if this Set is empty, otherwise returns an element of the set and removes it from the set
//	 */
//	public DynamicComponent pop() {
//		IntIterator it = indices.iterator();
//		int id = it.nextInt();
//		it.remove();
//		return pool.get(id);
//	}

	/**
	 *
	 * @return an unmodifiable version of the set of indices underlying this set
	 */
	public IntSet getIndices() {
		if (indices == null) {
			return IntSets.EMPTY_SET;
		}
		return IntSets.unmodifiable(indices);
	}

	@Override
	public boolean add(DynamicComponent e) {
		if (indices == null) {
			indices = IntSets.singleton(e.id);
			return true;
		} else {
			ensureCapacity(size() + 1);
			return indices.add(e.id);
		}
	}

	private void ensureCapacity(int capacity) {
		if (capacity > MAX_SIZE_FOR_ARRAYSET && ! (indices instanceof IntOpenHashSet)) {
			IntSet newIndices = new IntOpenHashSet(Math.max(capacity, 1024));
			if (indices != null) newIndices.addAll(indices);
			indices = newIndices;
		} else if (capacity > 1 && ! (indices instanceof IntOpenHashSet) && ! (indices instanceof IntArraySet)) {
			IntSet newIndices = new IntArraySet(MAX_SIZE_FOR_ARRAYSET);
			if (indices != null) newIndices.addAll(indices);
			indices = newIndices;
		} else if (capacity == 0) {
			indices = null;
		} else if (capacity < MIN_SIZE_FOR_HASH_SET) {
			IntSet newIndices = new IntArraySet(MAX_SIZE_FOR_ARRAYSET);
			if (indices != null) newIndices.addAll(indices);
			indices = newIndices;
		}
	}

	@Override
	public boolean addAll(Collection<? extends DynamicComponent> collection) {
		int newCapacity = size() + collection.size();
		if (newCapacity <= 1) {
			boolean result = false;
			for (DynamicComponent c : collection) {
				result = add(c) || result;
			}
			return result;
		} else {
			ensureCapacity(newCapacity);
			boolean result = false;
			for (DynamicComponent c : collection) {
				result = indices.add(c.id) || result;
			}
			return result;
		}
	}

	@Override
	public void clear() {
		indices = null;
	}

	@Override
	public boolean contains(Object o) {
		return indices != null && indices.contains(((DynamicComponent)o).id);
	}

	@Override
	public boolean containsAll(Collection<?> collection) {
		boolean result = (indices != null);
		for (Iterator<?> i = collection.iterator() ; result && i.hasNext(); ) {
			result = contains(i.next());
		}
		return result;
	}

	@Override
	public boolean isEmpty() {
		return indices == null || indices.isEmpty();
	}

	@Override
	public Iterator<DynamicComponent> iterator() {
		final IntIterator it = getIndices().iterator();
		return new Iterator<DynamicComponent>() {
			@Override
			public boolean hasNext() {
				return it.hasNext();
			}
			@Override
			public DynamicComponent next() {
				return pool.get(it.next());
			}
			@Override
			public void remove() {
				it.remove();
			}};
	}

	@Override
	public boolean remove(Object o) {
		if (indices == null)
			return false;
		if (indices instanceof Singleton) {
			if (indices.contains(((DynamicComponent)o).id)) {
				indices = null;
				return true;
			} else {
				return false;
			}
		}
		boolean result = indices.remove(((DynamicComponent)o).id);
		ensureCapacity(size());
		return result;
	}

	@Override
	public boolean removeAll(Collection<?> collection) {
		if (indices == null)
			return false;
		if (collection == this) {
			clear();
			return true;
		}
		if (indices instanceof Singleton) {
			boolean result = false;
			for (Object c : collection) {
				result = remove(c) || result;
			}
			return result;
		}
		boolean result = false;
		for (Object c : collection) {
			result = indices.remove(((DynamicComponent)c).id) || result;
		}
		ensureCapacity(size());
		return result;
	}

	@Override
	public boolean retainAll(Collection<?> c) {
		if (indices == null)
			return false;
		if (indices instanceof Singleton) {
			if (!c.contains(pool.get(indices.iterator().nextInt()))) {
				indices = null;
				return true;
			}
			return false;
		}
		// case when indices is an actual set (arrayset or hashset)
		boolean retVal = false;
		int n = indices.size();
		final IntIterator i = indices.iterator();
		while (n-- != 0) {
			if (!c.contains(pool.get(i.nextInt()))) {
				i.remove();
				retVal = true;
			}
		}
		ensureCapacity(size());
		return retVal;
	}

	@Override
	public int size() {
		return (indices == null ? 0 : indices.size());
	}

	@Override
	public Object[] toArray() {
		assert false;
		return null;
	}

	@Override
	public <T> T[] toArray(T[] a) {
		assert false;
		return null;
	}

	/**
	 * rename input and output ids to the staticId of the component
	 */
	public void reorganize() {
		if (indices == null)
			return;
		if (indices instanceof Singleton) {
			indices = IntSets.singleton(pool.get(indices.iterator().nextInt()).staticId);
		} else {
			IntSet newIndices;
			if (indices.size() > MAX_SIZE_FOR_ARRAYSET) {
				newIndices = new IntOpenHashSet(indices.size());
			} else {
				newIndices = new IntArraySet(MAX_SIZE_FOR_ARRAYSET);
			}
			for (DynamicComponent c : this) {
				newIndices.add(c.staticId);
			}
			indices = newIndices;
		}
	}


}