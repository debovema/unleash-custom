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
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;

import com.google.common.io.Files;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.annotations.RollbackOnError;
import com.itemis.maven.plugins.cdi.logging.Logger;

@ProcessingStep(id = "copyPOMs", description = "Copy POMs")
public class CopyPOMs implements CDIMojoProcessingStep {

	@Inject
	@Named("reactorProjects")
	private List<MavenProject> reactorProjects;

	@Inject
	private Logger log;

	@Inject
	@Named("unleashOutputFolder")
	private File unleashOutputFolder;

	@Override
	public void execute(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
		this.log.info("Copy POMs.");

		try {
			for (MavenProject p : this.reactorProjects) {
				File pomToKeep = new File(p.getBuild().getDirectory(), p.getArtifactId() + "-" + p.getVersion() + ".pom");
				File pomKept;
				if (p.isExecutionRoot()) {
					pomKept = new File(unleashOutputFolder, "pom.xml");
				} else {
					pomKept = new File(unleashOutputFolder, p.getArtifactId() + "/pom.xml");
				}
				Files.copy(pomToKeep, pomKept);
			}
		} catch (Throwable t) {
			throw new MojoFailureException("Could not perform replacement in POMs.", t);
		}
	}


	@RollbackOnError
	public void rollback() throws MojoExecutionException {
		// nothing to do
	}
}
