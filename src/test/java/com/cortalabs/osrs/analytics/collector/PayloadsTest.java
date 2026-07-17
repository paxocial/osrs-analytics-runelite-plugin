/*
 * Copyright (c) 2025, Corta Labs
 * BSD 2-Clause License. See LICENSE.
 */
package com.cortalabs.osrs.analytics.collector;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the single source of truth for the plugin version: build.gradle declares
 * it, the {@code createProperties} task renders it into the {@code /version.ini}
 * classpath resource, and {@link Payloads} reads that resource at load time. This
 * pins all three to one value so the emitted {@code plugin_version} can never drift
 * from the released build — the defect where every telemetry row claimed
 * {@code 1.0.0} through four releases.
 *
 * <p>The test is anchored to the build, never to a version literal: it reads the
 * expected version from build.gradle and the generated resource, so it keeps
 * holding across future version bumps. Hardcoding {@code readPluginVersion} (or the
 * constant) back to a literal is what these assertions catch.
 */
public class PayloadsTest
{
	/** Matches {@code version = '1.2.0'} (not the map-syntax {@code version:} deps). */
	private static final Pattern GRADLE_VERSION =
		Pattern.compile("(?m)^\\s*version\\s*=\\s*['\"]([^'\"]+)['\"]");

	@Test
	public void wireVersionEqualsBuildVersionThroughTheGeneratedResource() throws Exception
	{
		// (1) The build's declared version — the single source of truth.
		String buildVersion = readBuildGradleVersion();

		// (2) The generated resource the plugin ships in its jar and reads at runtime.
		String resourceVersion = readVersionResource();

		// build.gradle -> version.ini : the createProperties projection.
		assertEquals("version.ini must be rendered from build.gradle's version",
			buildVersion, resourceVersion);

		// version.ini -> Payloads : the runtime read. Transitively, build == wire.
		assertEquals("Payloads.PLUGIN_VERSION must be read from the generated resource, not restated",
			resourceVersion, Payloads.PLUGIN_VERSION);

		// And the emitted version still satisfies the backend's dotted-triple contract.
		assertTrue("emitted plugin_version must satisfy the server \\d+\\.\\d+\\.\\d+ contract",
			Payloads.PLUGIN_VERSION.matches("\\d+\\.\\d+\\.\\d+"));

		// Sanity: the defect is gone — the wire is no longer the stale 1.0.0 literal.
		assertEquals("the fix must move the wire version off the old hardcoded literal",
			false, "1.0.0".equals(Payloads.PLUGIN_VERSION));
	}

	@Test
	public void pluginVersionIsWhateverTheResourceHolds() throws Exception
	{
		// Direct proof the constant is DERIVED, not a literal: it equals both the live
		// derivation and the resource content. This is the mutation target — replace
		// readPluginVersion's body (or PLUGIN_VERSION's initializer) with a literal and
		// this equality breaks the moment the literal differs from the built version.
		assertEquals(readVersionResource(), Payloads.readPluginVersion());
		assertEquals(Payloads.PLUGIN_VERSION, Payloads.readPluginVersion());
	}

	@Test
	public void unavailableSentinelIsAValidFailureMarkerNotARealVersion() {
		// The fallback must satisfy the backend CHECK (so a packaging defect degrades
		// visibly rather than 422-ing every row) without being a real release number.
		assertTrue("sentinel must satisfy the \\d+\\.\\d+\\.\\d+ contract",
			Payloads.VERSION_UNAVAILABLE.matches("\\d+\\.\\d+\\.\\d+"));
	}

	private static String readVersionResource() throws Exception
	{
		try (InputStream in = Payloads.class.getResourceAsStream("/version.ini"))
		{
			assertNotNull("version.ini must be generated onto the classpath by createProperties", in);
			Properties props = new Properties();
			props.load(in);
			String version = props.getProperty("pluginVersion");
			assertNotNull("version.ini must carry a pluginVersion key", version);
			return version.trim();
		}
	}

	private static String readBuildGradleVersion() throws Exception
	{
		// Gradle runs the Test task from the project dir, so build.gradle is here.
		File buildFile = new File("build.gradle");
		assertTrue("expected build.gradle at the project root (test workingDir)", buildFile.isFile());
		String text = new String(Files.readAllBytes(buildFile.toPath()), StandardCharsets.UTF_8);
		Matcher m = GRADLE_VERSION.matcher(text);
		assertTrue("build.gradle must declare a project version", m.find());
		return m.group(1).trim();
	}
}
