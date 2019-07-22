# Maven Batch Tester

Compile all projects in a given directory using `mvn clean install` then follows up with `mvn test` and logs the results and how long it takes for the `test` phase to execute _(Assumes [maven-lifecycle-logger](https://github.com/jon-bell/maven-lifecycle-logger) is active. Ensure it is active before running this)_.

This applies the following skip flags to focus in on logging proper result timings for unit tests:

* `-Dcobertura.skip`
* `-Djacoco.skip`
* `-Drat.skip`
* `-Denforcer.skip`
* `-Dmaven.javadoc.skip`
* `-Dcheckstyle.skip`
* `-Dpmd.skip`
* `-Dcpd.skip`
* `-Dfindbugs.skip`

All logs of run tests will be dumped in the adjacent folder by the following pattern: `batch-logs/{project}/{phase}/log-{run-iteration}.txt`

### Usage:

```
java -jar autotest-{version}.jar [-ks] [-m=<mavenHome>] [-p=<phase>] [-r=<runs>] <repositoriesDir> <reportFile>
      <repositoriesDir>   The directory containing repositories to analyze.
      <reportFile>        The file to write the analysis report to.
      -m=<mavenHome>      Set the maven home directory. Default uses %MAVEN_HOME% environment variable.
      -r=<runs>           Number of successful test collections to terminate on.
                              Default: 1
      -x=<maxRuns>        Maximum number of times to run tests before skipping the project.
                              Default: 20
      -p=<phase>          Specify to only run one of the phases of the batch tester. 
                              Default: ALL
                              Options: ALL, STANDARD, CUSTOM, FORKSCRIPT
      -k                  Kill on test failure.
                              Default: false
      -s                  Emit maven's logging.
                              Default: false
```
