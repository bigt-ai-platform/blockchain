package net.bigtangle.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

/**
 * Requester endpoint resolution: the static {@code server.requester} config is
 * the operator override used alone; when it is blank the node falls back to
 * the network seeds ({@code serverSeeds()}, DNS enrtree, discovered peers)
 * instead of hardcoding the mesh in every application.yml.
 */
public class RequesterSeedDiscoveryTest {

    private static void inject(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /** NetworkParameters stub: serverSeeds overridable, dnsSeeds injectable. */
    private NetworkParameters params(String[] serverSeeds, String[] dnsSeeds) throws Exception {
        NetworkParameters p = new NetworkParameters() {
            @Override
            public String[] serverSeeds() {
                return serverSeeds;
            }
        };
        Field f = NetworkParameters.class.getDeclaredField("dnsSeeds");
        f.setAccessible(true);
        f.set(p, dnsSeeds);
        return p;
    }

    private RequesterSeedDiscovery discovery(String requester, NetworkParameters p) throws Exception {
        RequesterSeedDiscovery d = new RequesterSeedDiscovery();
        ServerConfiguration c = new ServerConfiguration();
        c.setRequester(requester);
        inject(d, "networkParameters", p);
        inject(d, "serverConfiguration", c);
        return d;
    }

    @Test
    public void staticConfigUsedAloneInOrder() throws Exception {
        RequesterSeedDiscovery d = discovery("http://a:1, http://b:2 ,http://a:1,",
                params(new String[] { "5.6.7.8:80" }, new String[0]));
        d.refresh();
        // Operator override: no seed/peer merge, config order, trimmed+deduped.
        assertEquals(List.of("http://a:1", "http://b:2"), d.getRequesters());
    }

    @Test
    public void blankConfigFallsBackToServerSeeds() throws Exception {
        RequesterSeedDiscovery d = discovery(null, params(new String[] { "5.6.7.8:8081" }, new String[0]));
        d.refresh();
        assertEquals(List.of("http://5.6.7.8:8081"), d.getRequesters());

        RequesterSeedDiscovery d2 = discovery("  ", params(new String[] { "5.6.7.8:8081" }, new String[0]));
        d2.refresh();
        assertEquals(List.of("http://5.6.7.8:8081"), d2.getRequesters());
    }

    @Test
    public void unresolvableDnsSeedIsTolerated() throws Exception {
        // Malformed/unresolvable enrtree must be skipped, not break refresh.
        RequesterSeedDiscovery d = discovery(null,
                params(new String[] { "5.6.7.8:8081" }, new String[] { "enrtree://bad@requester-seed.invalid" }));
        d.refresh();
        assertEquals(List.of("http://5.6.7.8:8081"), d.getRequesters());
    }

    @Test
    public void noSeedsYieldsEmptyNeverNull() throws Exception {
        RequesterSeedDiscovery d = discovery(null, params(new String[0], new String[0]));
        d.refresh();
        assertNotNull(d.getRequesters());
        assertTrue(d.getRequesters().isEmpty());
    }
}
