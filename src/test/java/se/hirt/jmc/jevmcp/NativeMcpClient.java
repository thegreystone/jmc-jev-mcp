/*
 * Copyright (c) 2026, Marcus Hirt. All rights reserved.
 *
 * Licensed under the MIT License. See LICENSE file in the project root for details.
 */
package se.hirt.jmc.jevmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Talks MCP over STDIO to a native binary, for the native image integration tests. Responses are
 * read on a separate thread so that a hung binary fails the test instead of blocking it.
 */
final class NativeMcpClient implements AutoCloseable {

	private static final String EOF = "\0EOF";

	private final ObjectMapper mapper = new ObjectMapper();
	private final Process process;
	private final OutputStream stdin;
	private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
	private int nextId;

	NativeMcpClient(Path binary) throws IOException {
		process = new ProcessBuilder(binary.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		stdin = process.getOutputStream();
		Thread reader = new Thread(() -> {
			try (BufferedReader in = new BufferedReader(
					new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				for (String line; (line = in.readLine()) != null;) {
					lines.add(line);
				}
			} catch (IOException e) {
				// The process was killed
			}
			lines.add(EOF);
		}, "native-mcp-reader");
		reader.setDaemon(true);
		reader.start();
	}

	void initialize(Duration timeout) throws Exception {
		request("initialize", Map.of("protocolVersion", "2025-11-25", "capabilities", Map.of(), "clientInfo",
				Map.of("name", "native-it", "version", "1.0")), timeout);
		send(message("notifications/initialized", null));
	}

	/**
	 * @return the text content of the tool's result
	 */
	String callTool(String name, Map<String, Object> arguments, Duration timeout) throws Exception {
		JsonNode result = request("tools/call", Map.of("name", name, "arguments", arguments), timeout);
		StringBuilder sb = new StringBuilder();
		for (JsonNode content : result.path("content")) {
			sb.append(content.path("text").asText());
		}
		return sb.toString();
	}

	JsonNode request(String method, Object params, Duration timeout) throws Exception {
		int id = nextId++;
		ObjectNode message = message(method, params);
		message.put("id", id);
		send(message);
		long deadline = System.nanoTime() + timeout.toNanos();
		while (true) {
			String line = lines.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
			if (line == null) {
				throw new AssertionError("No response to " + method + " within " + timeout);
			}
			if (line == EOF) {
				throw new AssertionError("Native binary exited before responding to " + method);
			}
			JsonNode response = mapper.readTree(line);
			if (response.path("id").asInt(-1) != id) {
				continue;
			}
			if (response.has("error")) {
				throw new AssertionError(method + " failed: " + response.get("error"));
			}
			return response.get("result");
		}
	}

	private ObjectNode message(String method, Object params) {
		ObjectNode message = mapper.createObjectNode();
		message.put("jsonrpc", "2.0");
		message.put("method", method);
		if (params != null) {
			message.set("params", mapper.valueToTree(params));
		}
		return message;
	}

	private void send(JsonNode message) throws IOException {
		stdin.write((mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8));
		stdin.flush();
	}

	@Override
	public void close() throws InterruptedException {
		process.destroyForcibly();
		process.waitFor();
	}
}
