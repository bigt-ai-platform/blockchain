package net.bigtangle.performance;

import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * Runs the 10-client payment benchmark programmatically.
 * Usage: mvn exec:java -pl layer0-mcmc -Dexec.classpathScope=test
 *   -Dexec.mainClass=net.bigtangle.performance.RunBenchmark
 */
public class RunBenchmark {
    public static void main(String[] args) {
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(DiscoverySelectors.selectClass(PaymentBenchmark.class))
            .build();
        Launcher launcher = LauncherFactory.create();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.registerTestExecutionListeners(listener);
        launcher.execute(request);

        TestExecutionSummary summary = listener.getSummary();
        System.out.println("\n=========================================");
        System.out.println("  Tests run: " + summary.getTestsFoundCount()
            + ", succeeded: " + summary.getTestsSucceededCount()
            + ", failed: " + summary.getTestsFailedCount());
        System.out.println("  Time: " + summary.getTimeFinished() + " ms");
        System.out.println("=========================================\n");

        System.exit(summary.getTestsFailedCount() > 0 ? 1 : 0);
    }
}
