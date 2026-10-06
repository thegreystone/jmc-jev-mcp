/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Analyzes {@code wldf.jfr} with the native binary. Covers what the native image has to be told
 * about explicitly - the reflectively built JFR structs, the ServiceLoader-discovered rules and
 * their resource bundles - and, with JEV_KEY set, the HTTPS and JSON path to Jev. Skipped unless
 * {@code native.image.path} is set, see {@link NativeImageSanityIT}.
 */
@EnabledIfSystemProperty(named = "native.image.path", matches = ".+")
class NativeImageRecordingIT {

	private static final Duration TIMEOUT = Duration.ofSeconds(60);

	private NativeMcpClient client;

	@BeforeEach
	void loadRecording() throws Exception {
		client = new NativeMcpClient(Path.of(System.getProperty("native.image.path")));
		client.initialize(TIMEOUT);
		String loaded = client.callTool("loadRecording", Map.of("path", TestRecordings.wldf().getAbsolutePath()),
				TIMEOUT);
		assertTrue(loaded.startsWith("Loaded recording:"), loaded);
	}

	@AfterEach
	void close() throws Exception {
		client.close();
	}

	@Test
	void parsesRecording() throws Exception {
		String info = client.callTool("getRecordingInfo", Map.of(), TIMEOUT);
		assertTrue(info.matches("(?s).*Events: [1-9]\\d* across [1-9]\\d* event types.*"), info);
	}

	@Test
	void runsRules() throws Exception {
		String results = client.callTool("getRuleResults", Map.of("minSeverity", "INFO", "verbose", false), TIMEOUT);
		assertFalse(results.startsWith("Error"), results);
		assertTrue(results.contains("HighGc"), "Missing GC Pressure finding in:\n" + results);
		assertTrue(results.contains("GC Pressure"), "Missing rule name from resource bundle in:\n" + results);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "JEV_KEY", matches = ".+")
	void classifiesWorkloadWithJev() throws Exception {
		String profile = client.callTool("classifyWorkloadProfile", Map.of(), TIMEOUT);
		assertFalse(profile.startsWith("Error"), profile);
		assertTrue(profile.contains("throughputOriented"), profile);
	}
}
