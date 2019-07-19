# Maven Batch Tester

Compile all projects in a given directory using `mvn clean compile` then follows up with `mvn test` and logs the results and how long it takes for the `test` phase to execute _(Assumes [maven-lifecycle-logger](https://github.com/jon-bell/maven-lifecycle-logger) is active. Ensure it is active before running this)_.

### Usage:

```
java -jar autotest-{version}.jar [-ks] [-m=<mavenHome>] [-p=<phase>] [-r=<runs>] <repositoriesDir> <reportFile>
      <repositoriesDir>   The directory containing repositories to analyze.
      <reportFile>        The file to write the analysis report to.
      -m=<mavenHome>      Set the maven home directory. Default uses %MAVEN_HOME% environment variable.
      -r=<runs>           Number of times to rerun tests.
      -p=<phase>          Specify to only run one of the phases of the batch tester. 
                              Options: STANDARD, CUSTOM, FORKSCRIPT, ALL (default)
      -k                  Kill on test failure.
      -s                  Emit maven's logging.
```