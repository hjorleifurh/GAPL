package is.ru.cadia.ggp.propnet.structure;

public enum PropType {
	NONE, ROLE, INIT, TRUE, NEXT, LEGAL, DOES, GOAL, TERMINAL;

	public int getBitMask() {
		if (this.equals(NONE)) return 0;
		else return 2<<this.ordinal();
	}

	public boolean isIn(int bits) {
		int bitmask = getBitMask();
		return (bits & bitmask) == bitmask;
	}

}