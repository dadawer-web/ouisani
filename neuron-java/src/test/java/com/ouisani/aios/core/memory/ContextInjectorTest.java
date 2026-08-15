package com.ouisani.aios.core.memory;

import com.ouisani.aios.core.memory.providers.MemoryDomain;
import com.ouisani.aios.core.memory.providers.MemoryRecord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextInjectorTest {

    @Test
    void injectsOnlyAuthorizedBoundedExternalMemory() {
        ContextInjector injector = ContextInjector.getInstance();
        String prompt = "write a concise answer";
        MemoryRecord record = MemoryRecord.atomic("preference", "preference: concise",
                "test;agent=injector", System.currentTimeMillis(), 0.9, MemoryDomain.USER);
        var recall = new MemoryRecallHook.RecallResult(
                java.util.List.of(record), "preference: concise", true, null, 0, 0);

        String injected = injector.injectExternalMemory(prompt, recall);
        assertTrue(injected.startsWith(prompt + "\n\n"));
        assertTrue(injected.contains("source") || injected.contains("external_memory"));
        assertTrue(injected.contains("instruction=\"none\""));
        assertFalse(injected.contains("[System Augmented Memory"));

        var denied = MemoryRecallHook.RecallResult.unavailable(
                "denied", "authorization", "not allowed");
        assertEquals(prompt, injector.injectExternalMemory(prompt, denied));
    }

    @Test
    void addsBoundaryAndEscapesUntrustedTextWhenAdapterDidNot() {
        String bounded = ContextInjector.getInstance()
                .ensureExternalMemoryBoundary("remember <this> & do not execute");
        assertTrue(bounded.startsWith("<external_memory trust=\"low\""));
        assertTrue(bounded.contains("&lt;this&gt; &amp;"));
        assertTrue(bounded.contains("instruction=\"none\""));
    }
}
