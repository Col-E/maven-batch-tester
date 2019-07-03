package me.coley.autotest;

import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Logger;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

import static java.nio.charset.Charset.defaultCharset;

public class AutoTest implements Runnable {
	/**
	 * Time to wait between checking if projects have finished being tested.
	 */
	private static final long COLLECTION_WAIT = 1000L;
	// CLI args
	@CommandLine.Parameters(index = "0", description = "The directory containing repositories to analyze.")
	public File repositoriesDir;
	@CommandLine.Parameters(index = "1", description = "The file to write the analysis report to.")
	public File reportFile;
	@CommandLine.Option(names = {"-m2"}, description = "Set the maven home directory.")
	public File mavenHome;
	@CommandLine.Option(names = {"-t"}, description = "Number of threads to use. Default uses all possible CPUs.")
	public int threads = -1;

	public static void main(String[] args) {
		// Setup logging
		Configurator.defaultConfig()
				.formatPattern("{level}-{date}: {message|indent=4}")
				.activate();
		// Invoke
		new CommandLine(new AutoTest()).execute(args);
	}

	public void run() {
		long start = System.currentTimeMillis();
		// Setup maven home
		if(mavenHome != null) {
			System.setProperty("maven.home", mavenHome.getAbsolutePath());
		}
		// Begin
		String path = repositoriesDir.getAbsolutePath();
		File[] dirs = repositoriesDir.listFiles(f -> f.isDirectory());
		Logger.info("Repositories directory: \"{}\" ({} projects)", path, dirs.length);
		// Start threads for each project
		Set<Future<TestResults>> futures = new HashSet<>();
		int size = threads == -1 ? Runtime.getRuntime().availableProcessors() - 1 : threads;
		ExecutorService executor = Executors.newFixedThreadPool(size);
		for (File dir : dirs) {
			futures.add( executor.submit(new TestInvokeThread(dir)));
		}
		// Collect results
		Set<TestResults> results = new HashSet<>();
		while (!futures.isEmpty()) {
			try {
				for (Future<TestResults> future : new HashSet<>(futures)) {
					// check if result has been computed.
					if (future.isDone()) {
						// remove from the to-check list
						futures.remove(future);
						TestResults result = future.get();
						if (result != null) {
							results.add(result);
						} else {
							// TODO: Should we throw an exception and force the user to look
							// into which test did not yield results?
						}
					}
				}
				// Wait and check back
				Thread.sleep(COLLECTION_WAIT);
			} catch(InterruptedException e) {} catch(ExecutionException e) {
				Logger.error(e, "Failure when computing results");
			}
		}
		// Dump reports
		StringBuilder sb = new StringBuilder("PROJECT,TOTAL,FAILS,ERRORS,SKIPPED,TEST_TIME\n");
		for(TestResults result : results) {
			Logger.info("{}: Ran {} tests in {}ms", result.name, result.total, result.elapsed);
			sb.append(result.name + "," + result.total + "," + result.fails + "," + result.errors
					+ "," + result.skipped + "," + result.elapsed + "\n");
		}
		Logger.info("Results:\n{}", sb.toString());
		try {
			byte[] out = sb.toString().getBytes(defaultCharset());
			Files.write(Paths.get(reportFile.toURI()), out, StandardOpenOption.CREATE);
		} catch(Exception e) {
			Logger.error(e, "Failed to generate reports file");
		}
		long now = System.currentTimeMillis();
		Logger.info("AutoTest - Completion time: {}", now - start);
		// Force close
		System.exit(0);
	}
}
