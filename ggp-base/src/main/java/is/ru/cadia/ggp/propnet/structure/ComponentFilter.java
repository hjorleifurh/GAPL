package is.ru.cadia.ggp.propnet.structure;

public interface ComponentFilter {

	public static final ComponentFilter ACCEPT_ALL = new ComponentFilter() {
		@Override
		public boolean accept(int cId) {
			return true;
		}
	};

	public boolean accept(int componentId);

}
