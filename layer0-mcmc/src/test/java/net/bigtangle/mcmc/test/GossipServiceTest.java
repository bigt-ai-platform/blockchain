package net.bigtangle.mcmc.test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.server.service.GossipService;

public class GossipServiceTest extends AbstractIntegrationTest {

    @Autowired
    private GossipService gossipService;

    @Override
    @BeforeEach
    public void setUp() throws Exception {
        super.setUp();
        mcmcService.update(store);
        mcmcService.calcNewBlockPrototype(store);
    }

    @Test
    public void testBroadcastAttestationWithEmptyPeers() {
        AttestationData att = new AttestationData();
        att.setSlot(1);
        att.setEpoch(0);
        att.setBeaconBlockHash(Sha256Hash.of("test".getBytes()));
        att.setValidatorPubkey(PQKey.createNew();

        assertDoesNotThrow(() -> gossipService.broadcastAttestation(att));
    }

    @Test
    public void testBroadcastSlashingProofWithEmptyPeers() {
        AttestationData att1 = new AttestationData();
        att1.setSlot(1);
        att1.setValidatorPubkey(PQKey.createNew();
        att1.setBeaconBlockHash(Sha256Hash.of("blockA".getBytes()));

        AttestationData att2 = new AttestationData();
        att2.setSlot(2);
        att2.setValidatorPubkey(PQKey.createNew();
        att2.setBeaconBlockHash(Sha256Hash.of("blockB".getBytes()));

        assertDoesNotThrow(() -> gossipService.broadcastSlashingProof(att1, att2));
    }

    @Test
    public void testBroadcastBeaconBlockHashWithEmptyPeers() {
        Sha256Hash blockHash = Sha256Hash.of("beacon".getBytes());
        assertDoesNotThrow(() -> gossipService.broadcastBeaconBlockHash(blockHash, 42));
    }

    @Test
    public void testGossipServiceNotNull() {
        assertNotNull(gossipService);
    }
}
