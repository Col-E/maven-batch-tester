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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
	private static final String INVOKE_OPTS = "-Drat.skip=true -Denforcer.skip=true -Dmaven.javadoc.skip=true -Dcheckstyle.skip=true -Dpmd.skip=true -Dcpd.skip=true -Dfindbugs.skip=true";
	/**
	 * Timeout before terminating a sub-process in seconds.
	 */
	private static final int TIMEOUT_SECONDS = 60 * 60 * 2;
	/**
	 * Number of times to re-execute tests.
	 */
	private final int runs;
	/**
	 * Flag for silencing maven's initial compile output.
	 * Enabled by default but may be useful to disable for manual monitoring.
	 */
	private final boolean silentCompile;
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

	public TestInvokeThread(int runs, boolean silentCompile, File dir) {
		this.runs = runs;
		this.silentCompile = silentCompile;
		this.dir = dir;
		this.rootPom = new File(dir, "pom.xml");
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
		List<TestResults> forkscript = new ArrayList<>();
		List<TestResults> custom = new ArrayList<>();
		// Initial compile
		compile();
		// Run tests with different plugin versions
		// - Standard current release
		// - Our custom release
		// - Forkscript release
		//
		// List of pom files to update (since some projects are modular)
		List<File> poms = Files.walk(dir.toPath())
				.filter(f -> f.toString().endsWith("pom.xml"))
				.map(Path::toFile)
				.sorted()
				.collect(Collectors.toList());
		setupPoms("3.0.0-M3", poms);
		for(int i = 0; i < runs; i++) {
			TestResults res = runTests(i, "Standard");
			if(res != null)
				standard.add(res);
		}
		setupPoms("3.0.0-SNAPSHOT", poms);
		for(int i = 0; i < runs; i++) {
			TestResults res = runTests(i, "Custom");
			if(res != null)
				custom.add(res);
		}
		setupPoms("2.21.0", poms);
		for(int i = 0; i < runs; i++) {
			TestResults res = runTests(i, "Forkscript");
			if(res != null)
				forkscript.add(res);
		}
		return new TestResultGroups(name, standard, forkscript, custom);
	}

	private void setupPoms(String versionStr, Collection<File> poms) throws Exception {
		for(File pom : poms) {
			setupPom(versionStr, pom);
		}
	}

	private void setupPom(String versionStr, File pom) throws Exception {
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
				} else if (version == null && foundSurefire && attribute.getParentNode().equals(plugin) && attribute.getNodeName().equals("version")) {
					version = attribute;
				}  else if (configuration == null && foundSurefire && attribute.getParentNode().equals(plugin) && attribute.getNodeName().equals("configuration")) {
					configuration = attribute;
				}
			}
			if (foundSurefire)
				break;
		}
		if (!foundSurefire) {
			if (pom.equals(rootPom)) {
				// The root MUST specify the plugin
				Logger.warn("Failed to find surefire plugin in \"{}\" - inserting plugin and restarting", name);
				// Get <build>
				NodeList builds = doc.getElementsByTagName("build");
				if (builds.getLength() > 1) {
					throw new IllegalStateException("Can't determine which <build> to insert maven-surefire-plugin into");
				}
				NodeList buildChildren = builds.item(0).getChildNodes();
				// Get <build><plugins>
				Node buildPlugins = null;
				for (int i = 0; i < buildChildren.getLength(); i++) {
					Node child = buildChildren.item(i);
					if (child.getNodeName().equals("plugins")) {
						buildPlugins = child;
						break;
					}
				}
				// Insert maven-surefire-plugin
				surefire =  doc.createElement("plugin");
				Node sfGroup =  doc.createElement("groupId");
				Node sfArtifact =  doc.createElement("artifactId");
				version =  doc.createElement("version");
				sfGroup.setTextContent("org.apache.maven.plugins");
				sfArtifact.setTextContent("maven-surefire-plugin");
				version.setTextContent(versionStr);
				surefire.appendChild(sfGroup);
				surefire.appendChild(sfArtifact);
				surefire.appendChild(version);
				buildPlugins.appendChild(surefire);
			} else {
				// subprojects will defer to the root
				return;
			}
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
		setup.setPomFile(rootPom);
		setup.setGoals(Arrays.asList("clean", "compile"));
		setup.setMavenOpts(INVOKE_OPTS);
		setup.setTimeoutInSeconds(TIMEOUT_SECONDS);
		if (silentCompile) {
			setup.setOutputHandler(line -> {
				// Silence the compilation stdOut
			});
		}
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

	private TestResults runTests(int run, String version) throws Exception {
		// Run tests
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
		if(res.getExitCode() != 0) {
			throw new IllegalStateException("Test invoke failed.", res.getExecutionException());
		}
		// Fetch results
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
				TestResults ret = new TestResults(total.get(), fails.get(), errors.get(), skipped.get(), time);
				Logger.info(ret);
				return ret;
			}
		}
		return null;
	}
}
