package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.pq.PQConstants;
import net.bigtangle.crypto.pq.PQScriptUtils;
import net.bigtangle.crypto.pq.SignatureBundle;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

/**
 * Integration test for PQ signature serialization through the
 * {@code submitTransaction} HTTP endpoint (DispatcherController).
 */
public class PqSerializationIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PqSerializationIT.class);

    @Test
    public void testPqSigningThenDeserializeSanity() throws Exception {
        PQKey key = PQKey.createNew();
        Sha256Hash hash = Sha256Hash.of("test payload".getBytes());
        SignatureBundle sigBundle = key.sign(hash);

        Transaction tx = new Transaction(networkParameters);
        tx.setVersion(PQConstants.TX_PQ_VERSION);

        Script scriptSig = ScriptBuilder.createInputScriptForPQ(sigBundle, key);
        TransactionInput input = TransactionInput.fromScriptBytes(
                networkParameters, tx, scriptSig.getProgram());
        tx.addInput(input);
        tx.setPqSignatureBundle(sigBundle.serialize());

        byte[] serialized = tx.bitcoinSerialize();
        Transaction deser = networkParameters.getDefaultSerializer()
                .makeTransaction(serialized);

        assertTrue(deser.getInputs().size() > 0);
        byte[] roundTrippedScript = deser.getInput(0).getScriptBytes();
        assertNotNull(roundTrippedScript);
        assertTrue(roundTrippedScript.length > 2);
        assertEquals(scriptSig.getProgram().length, roundTrippedScript.length);
    }

    @Test
    public void testFundAddressesKeyHashMatches() throws Exception {
        PQKey key = PQKey.createNew();
        byte[] originalHash = key.getPubKeyHash();
        byte[] originalPubkey = key.getPubKey();

        // Reconstruct key as fundAddresses does
        PQKey reconstructed = PQKey.fromPublicOnly(originalPubkey);
        byte[] reconstructedHash = reconstructed.getPubKeyHash();
        byte[] reconstructedPubkey = reconstructed.getPubKey();

        log.info("originalPubkey hex:     {}", Utils.HEX.encode(originalPubkey));
        log.info("reconstructedPubkey hex: {}", Utils.HEX.encode(reconstructedPubkey));
        log.info("originalHash hex:       {}", Utils.HEX.encode(originalHash));
        log.info("reconstructedHash hex:   {}", Utils.HEX.encode(reconstructedHash));

        assertArrayEquals(originalPubkey, reconstructedPubkey,
                "pubkey must survive fromPublicOnly round-trip");
        assertArrayEquals(originalHash, reconstructedHash,
                "pubKeyHash must survive fromPublicOnly round-trip");
    }

    @Test
    public void testFundAddressesUtxoHashMatches() throws Exception {
        // The coin-minting /fundAddresses faucet has been removed (bootstrap is
        // now done via the genesis block CSV). Verify the same P2PKH UTXO
        // behaviour using the genesis wallet's injected genesis coinbase UTXO.
        PQKey genesisKey = wallet.walletKeys(null).get(0);
        byte[] expectedHash = genesisKey.getPubKeyHash();

        Wallet w = wallet;
        List<UTXO> utxos = w.calculateAllSpendCandidatesUTXO(null, false);
        assertTrue(utxos.size() > 0, "genesis wallet must have at least one UTXO");
        UTXO utxo = utxos.get(0);

        Script scriptPubKey = utxo.getScript();
        assertTrue(scriptPubKey.isSentToAddress(), "UTXO script must be P2PKH");
        byte[] utxoHash = scriptPubKey.getPubKeyHash();

        log.info("genesis key pubKeyHash: {}", Utils.HEX.encode(expectedHash));
        log.info("UTXO scriptPubKey hash: {}", Utils.HEX.encode(utxoHash));

        assertArrayEquals(expectedHash, utxoHash,
                "UTXO scriptPubKey hash must match the funded key's pubKeyHash");

        // Now try a full submitTransaction through HTTP
        PQKey bobKey = PQKey.createNew();
        HashMap<String, BigInteger> payment = new HashMap<>();
        payment.put(
                Address.fromHash160(networkParameters, bobKey.getPubKeyHash()).toBase58(),
                BigInteger.valueOf(1000));

        List<FreeStandingTransactionOutput> coinList = w.calculateAllSpendCandidates(null, false);
        assertTrue(coinList.size() > 0, "must have spend candidates");

        Transaction tx = w.payToListTransaction(null, payment,
                NetworkParameters.BIGTANGLE_TOKENID, "pqtest", coinList);
        assertNotNull(tx);
        w.signTransaction(tx, null);
        assertTrue(tx.getVersion() >= PQConstants.TX_PQ_VERSION,
                "tx version must be >= TX_PQ_VERSION");
        assertNotNull(tx.getPqSignatureBundle(),
                "pqSignatureBundle must be set after signing");

        w.submitTransaction(tx);

        List<Transaction> pending = mempoolService.drainAll();
        boolean found = pending.stream()
                .anyMatch(t -> t.getHash().equals(tx.getHash()));
        assertTrue(found, "submitted tx must be accepted into the mempool");
        log.info("SUCCESS: PQ-signed tx submitted and accepted ({})", tx.getHash());
    }
}
