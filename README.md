# Maven Batch Tester

Compile all projects in a given directory using `mvn clean compile` then follows up with `mvn test` and logs the results and how long it takes for the `test` phase to execute _(Assumes [maven-lifecycle-logger](https://github.com/jon-bell/maven-lifecycle-logger) is active. Ensure it is active before running this)_.

### Usage:

```
java -jar autotest-{version}.jar [-m2=<mavenHome>] [-t=<threads>] <repositoriesDir> <reportFile>
      <repositoriesDir>   The directory containing repositories to analyze.
      <reportFile>        The file to write the analysis report to.
      -m2=<mavenHome>     Set the maven home directory. Default uses %M2_HOME% environment variable.
      -r=<runs>           Number of times to rerun tests.
```