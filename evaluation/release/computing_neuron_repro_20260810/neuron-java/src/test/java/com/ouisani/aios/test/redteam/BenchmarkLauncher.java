package com.ouisani.aios.test.redteam;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Minimal dependency-light runner used by the Linux artifact container. */
public final class BenchmarkLauncher {
    private BenchmarkLauncher() {}

    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(DiscoverySelectors.selectClass(PermissionStarvationJvmBenchmark.class))
                .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);
        listener.getSummary().printTo(new java.io.PrintWriter(System.out));
        if (listener.getSummary().getFailures().stream().findAny().isPresent()) {
            System.exit(1);
        }
    }
}
