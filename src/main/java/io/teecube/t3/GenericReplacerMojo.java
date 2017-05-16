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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.execution.MavenSession;
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
import org.apache.tools.ant.types.resources.FileResource;
import org.apache.tools.ant.util.ResourceUtils;
import org.apache.tools.ant.util.regexp.RegexpUtil;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.w3c.dom.Document;

import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.itemis.maven.plugins.cdi.CDIMojoProcessingStep;
import com.itemis.maven.plugins.cdi.ExecutionContext;
import com.itemis.maven.plugins.cdi.annotations.ProcessingStep;
import com.itemis.maven.plugins.cdi.annotations.RollbackOnError;
import com.itemis.maven.plugins.cdi.logging.Logger;
import com.itemis.maven.plugins.unleash.util.PomUtil;
import com.itemis.maven.plugins.unleash.util.ReleaseUtil;

import t3.CommonMojo;

@ProcessingStep(id = "genericReplacer", description = "Generic replacer.")
public class GenericReplacerMojo implements CDIMojoProcessingStep {

	@Inject
	@Named("reactorProjects")
	private List<MavenProject> reactorProjects;

	@Inject
	private MavenSession session;

	@Inject
	@Named("maven.home")
	private String mavenHome;

	@Inject
	private Settings settings;

	@Inject
	private Logger log;

	private Map<MavenProject, Document> cachedPOMs;

	private String encoding = "UTF-8";

	private boolean preserveLastModified = true;

	private Pattern p1;
	private Pattern p2;
	private Pattern p3;

	private int qualifier;
	private Boolean signGPG;

	private CommonMojo propertiesManager;

	private File tempSettingsFile;


	@Override
	public void execute(ExecutionContext context) throws MojoExecutionException, MojoFailureException {
		this.log.info("Perform replacement in POMs.");

		// prepare for rollback
		this.cachedPOMs = Maps.newHashMap();
		for (MavenProject p : this.reactorProjects) {
			this.cachedPOMs.put(p, PomUtil.parsePOM(p));
		}

	    // retrieve qualifier (for instance the 1 in genericReplacer[1])
		String _qualifier = context.getQualifier() != null ? context.getQualifier() : "1";
		String _signGPG = "false";
		if (_qualifier.contains(",")) {
			_signGPG = _qualifier.substring(_qualifier.indexOf(",")+1).trim();
			_qualifier = _qualifier.substring(0, _qualifier.indexOf(",")).trim();
		}
		qualifier = Integer.parseInt(_qualifier); // TODO: handle parse error
		signGPG = new Boolean(_signGPG);
		this.log.info("Step number is " + qualifier);

		try {
			for (MavenProject p : this.reactorProjects) {
				propertiesManager = CommonMojo.propertiesManager(session, p);

				if (doReplace(p.getFile(), RegexpUtil.asOptions(""))) {
					if (signGPG) {
						signGPG(p);
					}
				}
			}
		} catch (Throwable t) {
			throw new MojoFailureException("Could not perform replacement in POMs.", t);
		} finally {
			deleteTempSettings();
		}
	}

	private void signGPG(MavenProject p) throws MojoExecutionException, MavenInvocationException, MojoFailureException, IOException {
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
		if (!p.isExecutionRoot()) {
			File gpgTempDirectory = null;
			try {
				URL gpgTempDirectoryURL = new URL(request.getProperties().get("url").toString());
				gpgTempDirectory = new File(gpgTempDirectoryURL.getFile());
				File pomGpgSignature = new File(gpgTempDirectory, p.getGroupId().replaceAll("\\.", "/") + "/" + p.getArtifactId() + "/" + p.getVersion() + "/" + p.getArtifactId() + "-" + p.getVersion() + ".pom.asc");
				File gpgSignatureDest = new File(p.getBuild().getDirectory(), pomGpgSignature.getName());
				pomGpgSignature = new File(p.getFile().getParentFile(), "pom.xml.asc");
				org.apache.commons.io.FileUtils.copyFile(pomGpgSignature, gpgSignatureDest);
			} finally {
				if (gpgTempDirectory != null && gpgTempDirectory.exists()) {
					org.apache.commons.io.FileUtils.deleteDirectory(gpgTempDirectory);
				}
				File ascFile = new File(p.getFile().getParentFile(), "pom.xml.asc");
				if (ascFile.exists()) {
					ascFile.delete();
				}
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
		if (p.isExecutionRoot()) {
			request.setGoals(Lists.newArrayList("gpg:sign"));
		} else {
			File gpgDirectory = Files.createTempDir();
			request.setGoals(Lists.newArrayList("gpg:sign-and-deploy-file"));
			Properties properties = new Properties();
			properties.put("file", p.getFile().getAbsolutePath());
//			properties.put("pomFile", p.getFile().getAbsolutePath());
			properties.put("groupId", p.getGroupId());
			properties.put("artifactId", p.getArtifactId());
			properties.put("version", p.getVersion());
			properties.put("repositoryId", "null");
			properties.put("url", gpgDirectory.toURI().toURL().toString());
			request.setProperties(properties);
		}
		request.setRecursive(false);
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

	protected boolean doReplace(File f, int options) throws IOException, MojoExecutionException {
		File temp = File.createTempFile("replace", null);

		p1 = Pattern.compile("(\\s+)(.*) <!-- unleash: (.*) -->");
		p2 = Pattern.compile("([^ ]+)+");
		p3 = Pattern.compile("(\\d+)=(.*)");

		boolean changes = false;

		try {
			FileInputStream is = new FileInputStream(f);

			try {
				Object e = this.encoding != null ? new InputStreamReader(is, this.encoding) : new InputStreamReader(is);
				FileOutputStream os = new FileOutputStream(temp);

				try {
					Object w = this.encoding != null ? new OutputStreamWriter(os, this.encoding) : new OutputStreamWriter(os);

					e = new BufferedReader((Reader) e);
					w = new BufferedWriter((Writer) w);
					StringBuffer linebuf = new StringBuffer();
					boolean hasCR = false;

					int c;
					do {
						c = ((Reader) e).read();
						if (c == 13) {
							if (hasCR) {
								changes |= this.replaceAndWrite(linebuf.toString(), (Writer) w, options);
								((Writer) w).write(13);
								linebuf = new StringBuffer();
							} else {
								hasCR = true;
							}
						} else if (c == 10) {
							changes |= this.replaceAndWrite(linebuf.toString(), (Writer) w, options);
							if (hasCR) {
								((Writer) w).write(13);
								hasCR = false;
							}

							((Writer) w).write(10);
							linebuf = new StringBuffer();
						} else {
							if (hasCR || c < 0) {
								changes |= this.replaceAndWrite(linebuf.toString(), (Writer) w, options);
								if (hasCR) {
									((Writer) w).write(13);
									hasCR = false;
								}

								linebuf = new StringBuffer();
							}

							if (c >= 0) {
								linebuf.append((char) c);
							}
						}
					} while (c >= 0);

					((Reader) e).close();
					((Writer) w).close();
				} finally {
					os.close();
				}
			} finally {
				is.close();
			}

			if (changes) {
				try {
					long e1 = f.lastModified();
					FileUtils.rename(temp, f);
					if (this.preserveLastModified) {
						ResourceUtils.setLastModified(new FileResource(f), e1);
					}

					temp = null;
				} catch (IOException e) {
					throw new MojoExecutionException(e.getLocalizedMessage(), e);
				}
			} else {
				log.debug("No change made");
			}
		} finally {
			if (temp != null) {
				temp.delete();
			}
		}

		return changes;
	}

	private boolean replaceAndWrite(String s, Writer w, int options) throws IOException {
		String res = s;

		Matcher m1 = p1.matcher(s);
		if (m1.matches()) {
			String g1 = m1.group(1);
			String g3 = m1.group(3);

			Matcher m2 = p2.matcher(g3);
			while (m2.find()) {
				String r = m2.group();

				Matcher m3 = p3.matcher(r);
				if (m3.matches()) {
					int i = Integer.parseInt(m3.group(1));
					if (i != qualifier) {
						continue;
					}
					String replacement = m3.group(2);
					replacement = replaceProperties(replacement);
					replacement = replacement.replaceAll("&#36;", "\\$");

					res = g1 + replacement + " <!-- unleash: " + g3 + " -->";
				}

				break;
			}
		}
		w.write(res);
		return !res.equals(s);
	}

	public String replaceProperties(String string) {
		if (string == null) return null;

		Matcher m = CommonMojo.mavenPropertyPattern.matcher(string);

		while (m.find()) {
			StringBuffer sb = new StringBuffer();

			String propertyKey = m.group(1);
			String propertyValue = propertiesManager.getPropertyValue(propertyKey, false, false, true);
			if (propertyValue != null) {
			    m.appendReplacement(sb, Matcher.quoteReplacement(propertyValue));
			}
			m.appendTail(sb);
			if (propertyValue != null) {
				string = sb.toString();
				m = CommonMojo.mavenPropertyPattern.matcher(string);
			}
		}

		return string;
	}

	@RollbackOnError
	public void rollback() throws MojoExecutionException {
		this.log.info("Rollback replacement in POMs.");
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
