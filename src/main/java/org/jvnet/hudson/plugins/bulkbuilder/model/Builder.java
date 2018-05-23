/*
 * The MIT License
 *
 * Copyright (c) 2010-2011 Simon Westcott
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jvnet.hudson.plugins.bulkbuilder.model;

import hudson.Plugin;
import hudson.Util;
import hudson.model.ParameterValue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.Hudson;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.model.View;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import java.util.regex.Pattern;

/**
 * @author simon
 */
public class Builder {

    private static final Logger LOGGER = Logger.getLogger(Builder.class.getName());

    private BuildAction action;

    /**
     * Key/value map of user parameters
     */
    private Map<String, String> param;

    private String pattern;

    private String view;

    public Builder(BuildAction action) {
        this.action = action;
    }

    public void setUserParams(Map<String, String> param) {
        this.param = param;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public void setView(String view) {
        this.view = view;
    }

    /**
     * Build Jenkins projects
     */
    protected int build(ArrayList filters){
        int i = 0;

        // Build composite predicate of all build prefs
        Predicate<Job<?, ?>> compositePredicate = Predicates.and(filters);

        List<Job<?, ?>> projects = getProjects(this.view);

        // Use composite predicate to identify target projects
        Iterable<Job<?, ?>> targetProjects = Iterables.filter(projects, compositePredicate);

        for (Job<?, ?> project : targetProjects) {
            LOGGER.log(Level.FINE, "Scheduling build for job '" + project.getDisplayName() + "'");
            performBuildProject(project);
            i++;
        }

        return i;
    }

    private int buildWorseOrEqualsTo(final Result r) {
        LOGGER.log(Level.FINE, "Starting to build " + r.toString() + " jobs.");

        ArrayList<Predicate<Job<?, ?>>> filters = new ArrayList<Predicate<Job<?, ?>>>();

        filters.add(new Predicate<Job<?, ?>>() {
            @Override
	    public boolean apply(Job<?, ?> project) {
                Run<?, ?> build = project.getLastCompletedBuild();
		return build == null || build.getResult().isWorseOrEqualTo(r);
	    }
	});

        filters = addSubFilters(filters);

        int i = build(filters);

        LOGGER.log(Level.FINE, "Finished building " + r.toString() + " jobs.");

	return i;
    }

    private int buildExactStatus(final Result r) {
        LOGGER.log(Level.FINE, "Starting to build " + r.toString() + " jobs.");

        ArrayList<Predicate<Job<?, ?>>> filters = new ArrayList<Predicate<Job<?, ?>>>();

        filters.add(new Predicate<Job<?, ?>>() {
            @Override
	    public boolean apply(Job<?, ?> project) {
                Run<?, ?> build = project.getLastCompletedBuild();
                return build != null && build.getResult() == r;
	    }
	});

        filters = addSubFilters(filters);

        int i = build(filters);

        LOGGER.log(Level.FINE, "Finished building " + r.toString() + " jobs.");

	return i;
    }

    private ArrayList addSubFilters(ArrayList filters) {
        if (this.pattern != null) {
            Predicate<Job<?, ?>> patternPred = new Predicate<Job<?, ?>>() {
                @Override
                public boolean apply(Job<?, ?> project) {
                    String patternReg = Builder.this.pattern.replaceAll("\\*", "\\.\\*");
                    return Pattern.matches(patternReg, project.getDisplayName());
                }
            };
            filters.add(patternPred);
        }

        return filters;
    }

    /**
     * Build all Jenkins projects
     */
    public final int buildAll() {
        return buildWorseOrEqualsTo(Result.SUCCESS);
    }

    /**
     * Build all unstable builds.
     *
     * This includes projects that are unstable, have not been built before,
     * failed and aborted projects.
     */
    public final int buildUnstable() {
	return buildWorseOrEqualsTo(Result.UNSTABLE);
    }

    /**
     * Build all unstable builds only.
     */
    public final int buildUnstableOnly() {
	return buildExactStatus(Result.UNSTABLE);
    }

    /**
     * Build failed Jenkins projects.
     *
     * This includes projects that have not been built before and failed and
     * aborted projects.
     */
    public final int buildFailed() {
	return buildWorseOrEqualsTo(Result.FAILURE);
    }

    /**
     * Build all failed builds only.
     */
    public int buildFailedOnly() {
	return buildExactStatus(Result.FAILURE);
    }

    /**
     * Build all not built jobs.
     *
     * This includes projects that are have not been built before and aborted
     * projects.
     */
    public int buildNotBuilt() {
	return buildWorseOrEqualsTo(Result.NOT_BUILT);
    }

    /**
     * Build all not built jobs only.
     */
    public int buildNotBuildOnly() {
	return buildExactStatus(Result.NOT_BUILT);
    }

    /**
     * Build all aborted builds.
     */
    public int buildAborted() {
	return buildWorseOrEqualsTo(Result.ABORTED);
    }

    /**
     * Return a list of projects which can be built
     *
     * @return
     */
    protected final List<Job<?, ?>> getProjects(String viewName) {

	List<Job<?, ?>> projects = new ArrayList<Job<?, ?>>();
        Collection<TopLevelItem> items = Hudson.getInstance().getItems();

        if (viewName != null) {
            View view = Hudson.getInstance().getView(viewName);

            if (view != null) {
                items = view.getItems();
            }
        }

        for (Job<?, ?> project : Util.createSubList(items, Job.class)) {
            if (!project.isBuildable()) {
                continue;
            }
            projects.add(project);
        }

	return projects;
    }

    /**
     * Actually build a project, passing in parameters where appropriate
     *
     * @param project
     * @return
     */
    protected final void performBuildProject(Job<?, ?> project) {
	    if (!project.hasPermission(Job.BUILD)) {
            LOGGER.log(Level.WARNING, "Insufficient permissions to build " + project.getName());
	        return;
	    }
	    
	    boolean isWorkflowJobPluginAvailable = isWorkflowJobPluginAvailable();
	    if(!(project instanceof AbstractProject) 
	    		&& (isWorkflowJobPluginAvailable && !(project instanceof WorkflowJob)))
	    	return; //No valid job type
	    
	    
	    ParametersDefinitionProperty pp = (ParametersDefinitionProperty) project
			    .getProperty(ParametersDefinitionProperty.class);
	    
	    boolean performed = false;
	    if(project instanceof AbstractProject) {
	    	performed = performBuildProject((AbstractProject<?, ?>)project, pp);
	    }else if(isWorkflowJobPluginAvailable && (project instanceof WorkflowJob)) {
	    	performed = performBuildProject((WorkflowJob)project, pp);
	    }
	    if(performed)
	    	return;

	    List<ParameterDefinition> parameterDefinitions = pp
	    	    .getParameterDefinitions();
	    List<ParameterValue> values = new ArrayList<ParameterValue>();

	    for (ParameterDefinition paramDef : parameterDefinitions) {

	        if (!(paramDef instanceof StringParameterDefinition)) {
	            // TODO add support for other parameter types
		        values.add(paramDef.getDefaultParameterValue());
		        continue;
	        }

	        StringParameterDefinition stringParamDef = (StringParameterDefinition) paramDef;
	        ParameterValue value;

	        // Did user supply this parameter?
	        if (param.containsKey(paramDef.getName())) {
		    value = stringParamDef.createValue(param
			    .get(stringParamDef.getName()));
	        } else {
		    // No, then use the default value
		    value = stringParamDef.createValue(stringParamDef
			    .getDefaultValue());
	        }

	        values.add(value);
	    }

	    Hudson.getInstance().getQueue()
			.schedule(pp.getOwner(), 1, new ParametersAction(values));
    }
    
    protected boolean performBuildProject(AbstractProject<?, ?> project, ParametersDefinitionProperty pp) {
		if (action.equals(BuildAction.POLL_SCM)) {
            project.schedulePolling();
            return true;
        }

	    // no user parameters provided, just build it
	    if (param == null) {
	        project.scheduleBuild(new Cause.UserCause());
	        return true;
	    }

	    // project does not except any parameters, just build it
	    if (pp == null) {
	        project.scheduleBuild(new Cause.UserCause());
	        return true;
	    }
	    
	    return false;
	}
    
    protected boolean performBuildProject(WorkflowJob project, ParametersDefinitionProperty pp) {
	    // no user parameters provided, just build it
	    if (param == null) {
	        project.scheduleBuild2(0);
	        return true;
	    }

	    // project does not except any parameters, just build it
	    if (pp == null) {
	        project.scheduleBuild2(0);
	        return true;
	    }
	    
	    return false;
	}
    
    private boolean isWorkflowJobPluginAvailable() {
    	Plugin plugin = Jenkins.getInstance().getPlugin("workflow-job");
    	if(plugin == null)
    		return false;
    	return true;
    }

}
