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

/**
 * Driver; collects project analysis results.
 *
 * @author Matt
 */
public class AutoTest implements Runnable {
	// CLI args
	@CommandLine.Parameters(index = "0", description = "The directory containing repositories to analyze.")
	public File repositoriesDir;
	@CommandLine.Parameters(index = "1", description = "The file to write the analysis report to.")
	public File reportFile;
	@CommandLine.Option(names = {"-m2"}, description = "Set the maven home directory.")
	public File mavenHome;

	public static void main(String[] args) {
		// Setup logging
		Configurator.defaultConfig()
				.formatPattern("{level}-{date}: {message|indent=4}")
				.activate();
		// Invoke
		int exit = new CommandLine(new AutoTest()).execute(args);
		System.exit(exit);
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
		// Collect results
		Set<TestResults> results = new HashSet<>();
		for (File dir : dirs) {
			try {
				TestResults result = new TestInvokeThread(dir).call();
				results.add(result);
			} catch(Exception e) {
				Logger.error(e);
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
	}
}
