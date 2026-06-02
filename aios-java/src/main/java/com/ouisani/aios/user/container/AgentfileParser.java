package com.ouisani.aios.user.container;

import java.util.LinkedHashMap;
import java.util.Map;

public class AgentfileParser {

    public AgentImageConfig parse(String agentfileContent) {
        String baseImage = null;
        long tokenLimit = 0;
        Map<String, String> volumeMounts = new LinkedHashMap<>();
        String wasmPath = null;
        String entrypoint = null;

        String[] lines = agentfileContent.split("\\R");
        int lineNumber = 0;

        for (String rawLine : lines) {
            lineNumber++;
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split("\\s+");
            String directive = parts[0].toUpperCase();

            switch (directive) {
                case "FROM" -> {
                    if (parts.length < 2) {
                        throw new IllegalArgumentException(
                                "[Agentfile] Line " + lineNumber + ": FROM requires an image name");
                    }
                    baseImage = parts[1];
                    System.out.println("[Agentfile] FROM " + baseImage);
                }
                case "LIMIT_TOKENS" -> {
                    if (parts.length < 2) {
                        throw new IllegalArgumentException(
                                "[Agentfile] Line " + lineNumber + ": LIMIT_TOKENS requires a number");
                    }
                    try {
                        tokenLimit = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                                "[Agentfile] Line " + lineNumber + ": LIMIT_TOKENS value is not a valid long: " + parts[1]);
                    }
                    System.out.println("[Agentfile] LIMIT_TOKENS " + tokenLimit);
                }
                case "MOUNT" -> {
                    if (parts.length < 3) {
                        throw new IllegalArgumentException(
                                "[Agentfile] Line " + lineNumber + ": MOUNT requires <host_path> <container_path>");
                    }
                    String hostPath = parts[1];
                    String containerPath = parts[2];
                    volumeMounts.put(hostPath, containerPath);
                    System.out.println("[Agentfile] MOUNT " + hostPath + " -> " + containerPath);
                }
                case "COPY" -> {
                    if (parts.length < 3) {
                        throw new IllegalArgumentException(
                                "[Agentfile] Line " + lineNumber + ": COPY requires <local_wasm_file> <target_wasm_file>");
                    }
                    wasmPath = parts[2];
                    System.out.println("[Agentfile] COPY " + parts[1] + " -> " + wasmPath);
                }
                case "ENTRYPOINT" -> {
                    if (parts.length < 2) {
                        throw new IllegalArgumentException(
                                "[Agentfile] Line " + lineNumber + ": ENTRYPOINT requires a function name");
                    }
                    entrypoint = parts[1];
                    System.out.println("[Agentfile] ENTRYPOINT " + entrypoint);
                }
                default -> throw new IllegalArgumentException(
                        "[Agentfile] Line " + lineNumber + ": Unknown directive '" + parts[0] + "'");
            }
        }

        if (baseImage == null) {
            throw new IllegalArgumentException("[Agentfile] Missing required FROM directive");
        }

        AgentImageConfig config = new AgentImageConfig(baseImage, tokenLimit, volumeMounts, wasmPath, entrypoint);
        System.out.println("[Agentfile] Parse complete -> " + config);
        return config;
    }
}
