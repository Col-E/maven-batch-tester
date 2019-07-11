package me.coley.autotest;

import java.util.Collection;

/**
 * Wrapper for multiple executions of tests using three separate configs
 *
 * @author Matt
 */
public class TestResultGroups {
	private final String name;
	private final Collection<TestResults> standard;
	private final Collection<TestResults> forkscript;
	private final Collection<TestResults> custom;

	public TestResultGroups(String name, Collection<TestResults> standard, Collection<TestResults> forkscript,
							Collection<TestResults> custom) {
		this.name = name;
		this.standard = standard;
		this.forkscript = forkscript;
		this.custom = custom;
	}

	public String name() {return name;}
	public Collection<TestResults> getStandard() {return standard;}
	public Collection<TestResults> getForkscript() {return forkscript;}
	public Collection<TestResults> getCustom() {return custom;}
}
