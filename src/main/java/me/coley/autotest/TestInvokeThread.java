package me.coley.autotest;

import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.*;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.Charset.defaultCharset;

/**
 * Thread for analyzing a given project.
 *
 * @author Matt
 */
public class TestInvokeThread implements Callable<TestResultGroups> {
	/**
	 * Opts to use when running the projects, useful since we're going to want to skip rat since
	 * we need to generate a log-file containing the runtime analysis of the test phase.
	 */
	private static final String INVOKE_OPTS = "-Dcobertura.skip=true -Djacoco.skip=true -Drat.skip=true -Denforcer.skip=true -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Dcpd.skip=true -Dfindbugs.skip=true";
	/**
	 * Timeout before terminating a sub-process in seconds.
	 */
	private static final int TIMEOUT_SECONDS = 60 * 60 * 2;
	/**
	 * Number of times to collect results. If a test invoke fails we'll run again unless we hit the max rerun count.
	 */
	private final int runs;
	/**
	 * Maximum number of runs before skipping the project.
	 */
	private final int maxRuns;
	/**
	 * Flag for allowing emitting maven's logging.
	 */
	private final boolean emitMvnLogging;
	/**
	 * Flag for killing the thread if mvn test fails.
	 * If set to false, failures will be ignored and given dummy timing values
	 * to indicate the failure.
	 */
	private final boolean killOnFail;
	/**
	 * Phase to run.
	 */
	private final String phase;
	/**
	 * Project directory of the maven project to test.
	 */
	private final File dir;
	/**
	 * Project's pom file.
	 */
	private final File rootPom;
	/**
	 * Project name.
	 */
	private final String name;
	/**
	 * Maven invoker to run goals.
	 */
	private Invoker invoker = new DefaultInvoker();

	public TestInvokeThread(int runs, int maxRuns, boolean emitMvnLogging, boolean killOnFail, String phase, File dir) {
		this.runs = runs;
		this.maxRuns = maxRuns;
		this.emitMvnLogging = emitMvnLogging;
		this.killOnFail = killOnFail;
		this.dir = dir;
		if (phase == null)
			phase = "ALL";
		this.phase = phase;
		this.rootPom = new File(dir, "pom.xml");
		String name = dir.getName();
		if (name.contains("-master"))
			name = name.substring(0, name.length()-7);
		this.name = name;
	}

	@Override
	public TestResultGroups call() throws Exception {
		// results
		List<TestResults> standard = new ArrayList<>();
		List<TestResults> forkscript = new ArrayList<>();
		List<TestResults> custom = new ArrayList<>();
		// Run tests with different plugin versions
		// - Standard current release
		// - Our custom release
		// - Forkscript release
		//
		// List of pom files to update (since some projects are modular)
		File pom = new File(dir, "pom.xml");
		switch(phase) {
			case "STANDARD":
				exec(pom, standard, "3.0.0-M3", "Standard");
				break;
			case "CUSTOM":
				exec(pom, custom, "3.0.0-SNAPSHOT", "Custom");
				break;
			case "FORKSCRIPT":
				exec(pom, forkscript, "2.21.0", "Forkscript");
				break;
			case "NONE":
				break;
			default:
				exec(pom, standard, "3.0.0-M3", "Standard");
				exec(pom, custom, "3.0.0-SNAPSHOT", "Custom");
				exec(pom, forkscript, "2.21.0", "Forkscript");
				break;
		}
		return new TestResultGroups(name, standard, forkscript, custom);
	}

	private void exec(File pom, List<TestResults> results, String version, String phase)
			throws Exception {
		setupPom(version, pom);
		int iteration = 0;
		int collections = 0;
		while(true) {
			TestResults res = runTests(iteration++, phase);
			if(res != null) {
				// Record all results
				results.add(res);
				// But ensure that we get at least <runs> many collections of valid executions
				if(!res.failed)
					collections++;
				if(collections >= runs)
					break;
			}
			// If we're failing don't beat a dead horse
			if (iteration >= maxRuns)
				break;
		}
	}

	private void setupPom(String versionStr, File pom) throws Exception {
		String pomText = FileUtils.readFileToString(pom, "UTF-8");
		int start = pomText.indexOf("<MSPV>");
		int end = pomText.indexOf("</MSPV>") + 7;
		String pre = pomText.substring(0, start);
		String post = pomText.substring(end);
		String newPom = pre + "<MSPV>" + versionStr + "</MSPV>" + post;
		FileUtils.write(pom, newPom, "UTF-8");
	}

	private TestResults runTests(int run, String version) throws Exception {
		// Run tests
		StringBuilder log = new StringBuilder();
		InvocationRequest test = new DefaultInvocationRequest();
		test.setPomFile(rootPom);
		test.setGoals(Arrays.asList("test"));
		test.setMavenOpts(INVOKE_OPTS);
		test.setTimeoutInSeconds(TIMEOUT_SECONDS);
		Logger.info("Running tests for \"{}\" [{}/{}] - {}", name, run+1, runs, version);
		AtomicInteger total = new AtomicInteger(0);
		AtomicInteger fails = new AtomicInteger(0);
		AtomicInteger errors = new AtomicInteger(0);
		AtomicInteger skipped = new AtomicInteger(0);
		test.setOutputHandler(line -> {
			// record log
			log.append(line).append('\n');
			// emit log if prompted
			if (emitMvnLogging) {
				Logger.trace(line);
			}
			if(line.contains("Tests run:") && !line.contains(" in ")) {
				// forkscript double responds due to the embedded nature
				// so skip the dummy line with 0 results
				if (line.contains("Tests run: 0,")) return;
				// substring for consistent behaviors across different terminals
				String sub = line.substring(line.indexOf("Tests run:"));
				Pattern pattern = Pattern.compile("\\d+");
				Matcher matcher = pattern.matcher(sub);
				int i = 0;
				while(matcher.find()) {
 					int val = Integer.parseInt(matcher.group());
					switch(i) {
						case 0:
							total.addAndGet(val);
							break;
						case 1:
							fails.addAndGet(val);
							break;
						case 2:
							errors.addAndGet(val);
							break;
						case 3:
							skipped.addAndGet(val);
							break;
					}
					i++;
				}
			}
		});
		InvocationResult res = invoker.execute(test);
		boolean failed = res.getExitCode() != 0;
		if(failed && killOnFail) {
			Logger.error(res.getExecutionException(), "Test invoke failed.");
			throw new IllegalStateException("Test invoke failed.", res.getExecutionException());
		}
		// Fetch results
		File phaseLog = new File(dir, "maven.build.log");
		if (!phaseLog.exists()) {
			throw new IllegalStateException("Missing \"maven.build.log\" for \"" + name + "\"");
		}
		List<String> lines = Files.readAllLines(Paths.get(phaseLog.toURI()), defaultCharset());
		int time = 0;
		for(String line : lines) {
			// The line containing the test phase will have this pattern
			if(line.contains("org.apache.maven.plugins:maven-surefire-plugin")) {
				String[] args = line.split("\t");
				// Rather than setting and returning, increment.
				// There may be multiple if the project is modular.
				time += Integer.parseInt(args[5]);
			}
		}
		TestResults ret = new TestResults(total.get(), fails.get(), errors.get(), skipped.get(), time, log.toString(), failed);
		if (failed) {
			Logger.error(ret);
		} else {
			Logger.info(ret);
		}
		return ret;
	}
}
