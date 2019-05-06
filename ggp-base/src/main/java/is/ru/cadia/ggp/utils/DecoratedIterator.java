package is.ru.cadia.ggp.utils;

import java.util.Iterator;

public abstract class DecoratedIterator<I1, I2> implements Iterator<I2> {

	private Iterator<I1> it;

	public DecoratedIterator(Iterator<I1> it) {
		this.it = it;
	}

	@Override
	public boolean hasNext() {
		return it.hasNext();
	}

	@Override
	public I2 next() {
		return decorate(it.next());
	}

	protected abstract I2 decorate(I1 next);

	@Override
	public void remove() {
		it.remove();
	}

}