package com.ouisani.aios.user.container;

import java.util.Collections;
import java.util.Map;

public record AgentImageConfig(
        String baseImage,
        long tokenLimit,
        Map<String, String> volumeMounts,
        String wasmPath,
        String entrypoint
) {
    public AgentImageConfig {
        volumeMounts = volumeMounts == null
                ? Collections.emptyMap()
                : Map.copyOf(volumeMounts);
    }
}
