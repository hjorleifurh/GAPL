package is.ru.cadia.ggp.propnet.structure;

import it.unimi.dsi.fastutil.ints.AbstractIntIterator;

import java.util.BitSet;

public final class BitSetIterator extends AbstractIntIterator {
	private final BitSet bitSet;
	int nextIndex;
	int lastIndex;

	public BitSetIterator(BitSet bitSet) {
		this.bitSet = bitSet;
		nextIndex = bitSet.nextSetBit(0);
		lastIndex = -1;
	}

	@Override
	public int nextInt() {
		lastIndex = nextIndex;
		nextIndex = bitSet.nextSetBit(nextIndex + 1);
		return lastIndex;
	}

	@Override
	public boolean hasNext() {
		return nextIndex >= 0;
	}

	@Override
	public void remove() {
		bitSet.clear(lastIndex);
	}

	/**
	 * resets the iterator to position id, if that the current position of the iterator is after id
	 * @param id
	 */
	public void resetTo(int id) {
		if (nextIndex>id || nextIndex < 0) {
			nextIndex = bitSet.nextSetBit(id);
		}
	}

	/**
	 * this function must be called if a bit in the underlying bitset was cleared or the iterator might
	 * return an invalid index on the next call to nextInt()
	 * @param id
	 */
	public void removedIndex(int id) {
		if (nextIndex == id) {
			nextIndex = bitSet.nextSetBit(nextIndex + 1);
		}
	}

}