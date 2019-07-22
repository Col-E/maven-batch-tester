package me.coley.autotest;

import org.apache.commons.io.FileUtils;
import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Logger;
import org.pmw.tinylog.writers.FileWriter;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

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
	@CommandLine.Option(names = {"-m"}, description = "Set the maven home directory.")
	public File mavenHome;
	@CommandLine.Option(names = {"-r"}, description = "Number of successful test collections to terminate on.", defaultValue = "1")
	public int runs = 1;
	@CommandLine.Option(names = {"-x"}, description = "Maximum number of times to run tests before skipping the project.", defaultValue = "20")
	public int maxRuns = 20;
	@CommandLine.Option(names = {"-s"}, description = "Emit maven's logging.")
	public boolean emitMvnLogging;
	@CommandLine.Option(names = {"-k"}, description = "Kill on test failure.")
	public boolean killOnFail;
	@CommandLine.Option(names = {"-p"}, description = "Specify to only run one of the phases of the batch tester. Options: STANDARD, CUSTOM, FORKSCRIPT")
	public String phase;

	public static void main(String[] args) {
		// Setup logging
		Configurator.defaultConfig()
				.formatPattern("{level}-{date}: {message|indent=4}")
				.addWriter(new FileWriter("autotest-stdout.txt"))
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
		Set<TestResultGroups> projectResults = new HashSet<>();
		for (File dir : dirs) {
			try {
				TestResultGroups result = new TestInvokeThread(runs,maxRuns,emitMvnLogging,killOnFail, phase,dir).call();
				projectResults.add(result);
			} catch(Exception e) {
				Logger.error(e, "Skipping \"{}\" due to exception", dir);
			}
		}
		// Dump timing to CSV
		StringBuilder sb = new StringBuilder("PROJECT,CONFIG,TOTAL,FAILS,ERRORS,SKIPPED,TEST_TIME\n");
		for (TestResultGroups group : projectResults) {
			for(TestResults result : group.getStandard()) {
				sb.append(group.name()+",STANDARD," + result.total + "," + result.fails + "," + result.errors
						+ "," + result.skipped + "," + result.elapsed + "\n");
			}
			for(TestResults result : group.getForkscript()) {
				sb.append(group.name()+",FORKSCRIPT," + result.total + "," + result.fails + "," + result.errors
						+ "," + result.skipped + "," + result.elapsed + "\n");
			}
			for(TestResults result : group.getCustom()) {
				sb.append(group.name()+",CUSTOM," + result.total + "," + result.fails + "," + result.errors
						+ "," + result.skipped + "," + result.elapsed + "\n");
			}
		}
		// Dump logging
		try {
			File logsDir = new File("batch-logs");
			if(logsDir.exists()) {
				FileUtils.deleteDirectory(logsDir);
			}
			logsDir.mkdir();
			for (TestResultGroups group : projectResults) {
				File groupDir = new File(logsDir, group.name());
				groupDir.mkdir();
				File standardDir = new File(groupDir, "standard");
				File forkscriptDir = new File(groupDir, "forkscript");
				File customDir = new File(groupDir, "custom");
				standardDir.mkdir();
				forkscriptDir.mkdir();
				customDir.mkdir();
				int i = 0;
				for(TestResults result : group.getStandard())
					FileUtils.write(new File(standardDir, "log-" + (i++) + ".txt"), result.log, "UTF-8");
				i = 0;
				for(TestResults result : group.getForkscript())
					FileUtils.write(new File(forkscriptDir, "log-" + (i++) + ".txt"), result.log, "UTF-8");
				i = 0;
				for(TestResults result : group.getCustom())
					FileUtils.write(new File(customDir, "log-" + (i++) + ".txt"), result.log, "UTF-8");
			}
		} catch(IOException ex) {
			Logger.error("Failed to dump collected log files", ex);
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
