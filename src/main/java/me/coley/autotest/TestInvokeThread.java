package me.coley.autotest;

import org.apache.maven.shared.invoker.*;
import org.pmw.tinylog.Logger;
import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
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
	private static final String INVOKE_OPTS = "-Drat.skip=true -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true";
	/**
	 * Number of times to re-execute tests.
	 */
	private final int runs;
	/**
	 * Project directory of the maven project to test.
	 */
	private final File dir;
	/**
	 * Project's pom file.
	 */
	private final File pom;
	/**
	 * Project name.
	 */
	private final String name;
	/**
	 * Maven invoker to run goals.
	 */
	private Invoker invoker = new DefaultInvoker();

	public TestInvokeThread(int runs, File dir) {
		this.runs = runs;
		this.dir = dir;
		this.pom = new File(dir, "pom.xml");
		String name = dir.getName();
		if (name.contains("-master")) {
			name = name.substring(0, name.length()-7);
		}
		this.name = name;

	}

	@Override
	public TestResultGroups call() throws Exception {
		// results
		List<TestResults> standard = new ArrayList<>();
		List<TestResults> snapshot = new ArrayList<>();
		List<TestResults> custom = new ArrayList<>();
		// Initial compile
		compile();
		// Run tests with different plugin versions
		// - Standard current release
		// - Forkscript release
		// - Our custom release
		setupPom("3.0.0-M3");
		for(int i = 0; i < runs; i++) {
			TestResults res = runTests();
			if(res != null)
				standard.add(res);
		}
		setupPom("2.21.0");
		for(int i = 0; i < runs; i++) {
			TestResults res = runTests();
			if(res != null)
				snapshot.add(res);
		}
		setupPom("3.0.0-SNAPSHOT");
		for(int i = 0; i < runs; i++) {
			TestResults res = runTests();
			if(res != null)
				custom.add(res);
		}
		return new TestResultGroups(name, standard, snapshot, custom);
	}

	private void setupPom(String versionStr) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(pom);
		// Nodes to update
		Node surefire = null, version = null, configuration = null;
		boolean foundSurefire = false;
		// Iterate over plugins
		NodeList plugins = doc.getElementsByTagName("plugin");
		for (int i = 0; i < plugins.getLength(); i++) {
			Node plugin = plugins.item(i);
			NodeList attributes = plugin.getChildNodes();
			// Get version and config version of the surefire plugin
			// - If the plugin is not surefire, we skip this iteration of i.
			for (int j = 0; j < attributes.getLength(); j++) {
				Node attribute = attributes.item(j);
				if (attribute.getNodeName().equals("artifactId") && attribute.getTextContent().equals("maven-surefire-plugin")) {
					surefire = plugin;
					foundSurefire = true;
				} else if (version == null && foundSurefire && attribute.getNodeName().equals("version")) {
					version = attribute;
				}  else if (configuration == null && foundSurefire && attribute.getNodeName().equals("configuration")) {
					configuration = attribute;
				}
			}
		}
		if (!foundSurefire) {
			Logger.error("Failed to find surefire plugin in: " + name);
			throw new IllegalStateException("No surefire plugin specified in project pom.xml for: " + name);
		}
		// Update version
		if (version == null)  {
			version = doc.createElement("version");
			version.appendChild(doc.createTextNode(versionStr));
			surefire.appendChild(version);
		} else {
			version.setTextContent(versionStr);
		}
		// Validate config
		Node forkCount = null, reuseForks = null;
		if (configuration == null) {
			// Create the config attributes if needed
			configuration = doc.createElement("configuration");
			configuration.appendChild(forkCount = doc.createElement("forkCount"));
			configuration.appendChild(reuseForks = doc.createElement("reuseForks"));
			surefire.appendChild(configuration);
		} else {
			// Fetch the config attributes
			NodeList attributes = configuration.getChildNodes();
			for(int i = 0; i < attributes.getLength(); i++) {
				Node attribute = attributes.item(i);
				if(attribute.getNodeName().equals("forkCount")) {
					forkCount = attribute;
				} else if(attribute.getNodeName().equals("reuseForks")) {
					reuseForks = attribute;
				}
			}
			// Add if the attributes don't exist
			if(forkCount == null) {
				configuration.appendChild(forkCount = doc.createElement("forkCount"));
			}
			if(reuseForks == null) {
				configuration.appendChild(reuseForks = doc.createElement("reuseForks"));
			}
		}
		// Set the desired config
		forkCount.setTextContent("1");
		reuseForks.setTextContent("false");
		// Save changes
		Transformer transformer = TransformerFactory.newInstance().newTransformer();
		Result output = new StreamResult(pom);
		Source input = new DOMSource(doc);
		transformer.transform(input, output);
	}

	private void compile() throws Exception {
		// Request to setup
		InvocationRequest setup = new DefaultInvocationRequest();
		setup.setPomFile(pom);
		setup.setGoals(Arrays.asList("clean", "compile"));
		setup.setMavenOpts(INVOKE_OPTS);
		setup.setOutputHandler(line -> {
			// Silence the compilation stdOut
		});
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
	}

	private TestResults runTests() throws Exception {
		// Run tests
		InvocationRequest test = new DefaultInvocationRequest();
		test.setPomFile(pom);
		test.setGoals(Arrays.asList("test"));
		test.setMavenOpts(INVOKE_OPTS);
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
				int time = Integer.parseInt(args[5]);
				return new TestResults(total.get(), fails.get(), errors.get(), skipped.get(), time);
			}
		}
		return null;
	}


}