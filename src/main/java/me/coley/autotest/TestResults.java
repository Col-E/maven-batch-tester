package me.coley.autotest;

/**
 * Basic results wrapper.
 *
 * @author Matt
 */
public class TestResults {
	/**
	 * Tests run.
	 */
	public final int total, fails, errors, skipped;
	/**
	 * Time elapsed in maven's test phase.
	 */
	public final int elapsed;
	/**
	 * Text of mvn test output.
	 */
	public final String log;

	public TestResults(int total, int fails, int errors, int skipped, int elapsed, String log) {
		this.total = total;
		this.fails = fails;
		this.errors = errors;
		this.skipped = skipped;
		this.elapsed = elapsed;
		this.log = log;
	}

	@Override
	public String toString() {
		return "Results[" + total + ", " + fails + ", " + errors + ", " + skipped + "] in " + elapsed + "ms";
	}
}
