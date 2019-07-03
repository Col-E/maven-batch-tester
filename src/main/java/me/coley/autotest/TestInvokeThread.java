package me.coley.autotest;

import org.apache.maven.shared.invoker.*;
import org.pmw.tinylog.Logger;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.Charset.defaultCharset;

/**
 * @author Matt
 */
public class TestInvokeThread implements Callable<TestResults> {
	/**
	 * Opts to use when running the projects, useful since we're going to want to skip rat since
	 * we need to generate a log-file containing the runtime analysis of the test phase.
	 */
	private static final String INVOKE_OPTS = "-Drat.skip=true -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true";
	/**
	 * Project directory of the maven project to test.
	 */
	private final File dir;
	/**
	 * Project name.
	 */
	private final String name;
	/**
	 * Maven invoker to run goals.
	 */
	private Invoker invoker = new DefaultInvoker();

	public TestInvokeThread(File dir) {
		this.dir = dir;
		String name = dir.getName();
		if (name.contains("-master")) {
			name = name.substring(0, name.length()-7);
		}
		this.name = name;
	}

	@Override
	public TestResults call() throws Exception {
		// Request to setup
		InvocationRequest setup = new DefaultInvocationRequest();
		setup.setPomFile(new File(dir, "pom.xml"));
		setup.setGoals(Arrays.asList("clean", "compile"));
		setup.setMavenOpts(INVOKE_OPTS);
		setup.setOutputHandler(line -> {
			// Silence the compilation stdOut
		});
		// Request to test
		InvocationRequest test = new DefaultInvocationRequest();
		test.setPomFile(new File(dir, "pom.xml"));
		test.setGoals(Arrays.asList("test"));
		test.setMavenOpts(INVOKE_OPTS);
		// Run setup compilation if target directory does not exist
		if (!new File(dir, "target").exists()) {
			Logger.info("Compiling \"{}\"", name);
			InvocationResult res = invoker.execute(setup);
			if(res.getExitCode() != 0) {
				throw new IllegalStateException("Compile invoke failed.", res.getExecutionException());
			}
		} else {
			Logger.info("Skipping compilation for \"{}\", already has \"target\"", name);
		}
		// Run test
		Logger.info("Running tests for \"{}\"", name);
		AtomicInteger total = new AtomicInteger(-1);
		AtomicInteger fails = new AtomicInteger(-1);
		AtomicInteger errors = new AtomicInteger(-1);
		AtomicInteger skipped = new AtomicInteger(-1);
		test.setOutputHandler(line -> {
			if(line.contains("Tests run:") && !line.contains(" in ")) {
				Pattern pattern = Pattern.compile("\\d+");
				Matcher matcher = pattern.matcher(line);
				int i = 0;
				while(matcher.find()) {
					int val = Integer.parseInt(matcher.group());
					switch(i) {
						case 0:
							total.set(val);
							break;
						case 1:
							fails.set(val);
							break;
						case 2:
							errors.set(val);
							break;
						case 3:
							skipped.set(val);
							break;
					}
					i++;
				}
				Logger.info("Test summary for \"{}\": total[{}] fails[{}] errors[{}] skipped[{}]",
						name, total, fails, errors, skipped);
			}
		});
		InvocationResult res = invoker.execute(test);
		if(res.getExitCode() != 0) {
			throw new IllegalStateException("Test invoke failed.", res.getExecutionException());
		}
		// Fetch results
		Logger.info("Collecting results for \"{}\"", name);
		File phaseLog = new File(dir, "maven.build.log");
		if (!phaseLog.exists()) {
			throw new IllegalStateException("Missing \"maven.build.log\" for \"" + name + "\"");
		}
		List<String> lines = Files.readAllLines(Paths.get(phaseLog.toURI()), defaultCharset());
		for (String line : lines) {
			// The line containing the test phase will have this pattern
			if (line.contains("test\ttest")) {
				String[] args = line.split("\t");
				String name = args[0];
				name = name.substring(name.lastIndexOf(":") + 1);
				int time = Integer.parseInt(args[5]);
				return new TestResults(name, total.get(), fails.get(), errors.get(), skipped.get(), time);
			}
		}
		return null;
	}
}