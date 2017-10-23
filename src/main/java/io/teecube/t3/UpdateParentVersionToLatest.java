/**
 * (C) Copyright 2016-2017 teecube
 * (http://teecu.be) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.teecube.t3;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.DefaultSettingsWriter;
import org.apache.maven.settings.io.SettingsWriter;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.w3c.dom.Document;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.annotations.RollbackOnError;
import com.itemis.maven.plugins.cdi.logging.Logger;
import com.itemis.maven.plugins.unleash.util.PomUtil;
import com.itemis.maven.plugins.unleash.util.ReleaseUtil;

@ProcessingStep(id = "updateParentVersionToLatest", description = "Update parent version of POM to latest using versions:update-parent.")
public class UpdateParentVersionToLatest implements CDIMojoProcessingStep {

	@Inject
	@Named("reactorProjects")
	private List<MavenProject> reactorProjects;

	@Inject
	@Named("maven.home")
	private String mavenHome;

	@Inject
	private Settings settings;

	@Inject
	private Logger log;

	private Map<MavenProject, Document> cachedPOMs;

	private File tempSettingsFile;

	@Override
	public void execute(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
		// prepare for rollback
		this.cachedPOMs = Maps.newHashMap();
		for (MavenProject p : this.reactorProjects) {
			this.cachedPOMs.put(p, PomUtil.parsePOM(p));
		}

		this.log.info("Update parent version in main POM.");

		try {
			for (MavenProject p : this.reactorProjects) {
				if (!p.isExecutionRoot()) continue;

				updateParentVersion(p);
			}
		} catch (Throwable t) {
			throw new MojoFailureException("Could not perform replacement in POMs.", t);
		} finally {
			deleteTempSettings();
		}
	}

	private void updateParentVersion(MavenProject p) throws MojoExecutionException, MavenInvocationException, MojoFailureException, IOException {
		InvocationRequest request = getInvocationRequest(p);
		Invoker invoker = getInvoker();

		InvocationResult result = invoker.execute(request);
		if (result.getExitCode() != 0) {
			CommandLineException executionException = result.getExecutionException();
			if (executionException != null) {
				throw new MojoFailureException("Error during project build: " + executionException.getMessage(), executionException);
			} else {
				throw new MojoFailureException("Error during project build: " + result.getExitCode());
			}
		}
	}

	private Invoker getInvoker() {
		Invoker invoker = new DefaultInvoker();
		File calculatedMavenHome = ReleaseUtil.getMavenHome(Optional.fromNullable(this.mavenHome));
		if (calculatedMavenHome != null) {
			this.log.debug("\tUsing maven home: " + calculatedMavenHome.getAbsolutePath());
			invoker.setMavenHome(calculatedMavenHome);
		}
		invoker.setOutputHandler(null);
		return invoker;
	}

	private InvocationRequest getInvocationRequest(MavenProject p) throws MojoExecutionException, IOException {
		InvocationRequest request = new DefaultInvocationRequest();
		request.setPomFile(p.getFile());
		request.setGoals(Lists.newArrayList("versions:update-parent"));
		request.setRecursive(false);
		request.setOffline(true);
	    this.tempSettingsFile = createAndSetTempSettings(request);
		return request;
	}

	private File createAndSetTempSettings(InvocationRequest request) throws MojoExecutionException, IOException {
		SettingsWriter settingsWriter = new DefaultSettingsWriter();
		File settingsFile = File.createTempFile("settings", null);
		try {
			settingsWriter.write(settingsFile, null, this.settings);
		} catch (IOException e) {
			throw new MojoExecutionException("Unable to store Maven settings for release build", e);
		}
		request.setUserSettingsFile(settingsFile);
		return settingsFile;
	}

	private void deleteTempSettings() {
		if (this.tempSettingsFile != null && this.tempSettingsFile.exists()) {
			this.tempSettingsFile.delete();
			this.tempSettingsFile = null;
		}
	}

	@RollbackOnError
	public void rollback() throws MojoExecutionException {
		this.log.info("Rollback parent version in main POM.");
		try {
			for (Entry<MavenProject, Document> entry : this.cachedPOMs.entrySet()) {
				PomUtil.writePOM(entry.getValue(), entry.getKey());
			}
			deleteTempSettings();
		} catch (Throwable t) {
			throw new MojoExecutionException("Could not rollback in POMs.", t);
		}
	}
}
