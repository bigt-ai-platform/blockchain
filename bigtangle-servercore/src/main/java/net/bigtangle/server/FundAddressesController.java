package net.bigtangle.server;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.OkResponse;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.server.service.StoreService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;

/**
 * Test/bootstrap-only faucet. Mints confirmed UTXOs straight into the store so
 * test networks can bootstrap validators before any beacon exists (the PoS
 * chicken-and-egg: staking needs confirmed coins, confirming needs a validator).
 *
 * <p>Disabled by default ({@code server.fundEnabled=false}). When disabled this
 * controller is not registered at all, so the endpoint simply does not exist on
 * production nodes. Enable with {@code FUND_ENABLED=true} only in test,
 * benchmark and prodsim setups.
 */
@RestController
@ConditionalOnProperty(name = "server.fundEnabled", havingValue = "true")
@RequestMapping("/fundAddresses")
public class FundAddressesController {

    private static final Logger log = LoggerFactory.getLogger(FundAddressesController.class);

    /** Unique index counter for fundAddresses coinbases (see {@link #fund}). */
    private static final java.util.concurrent.atomic.AtomicLong FUND_UTXO_INDEX = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);

    /** pos_state key persisting the faucet counter across JVM restarts. */
    private static final String FAUCET_STATE_SERVICE = "faucet";
    private static final String FAUCET_INDEX_KEY = "utxo_index";

    /** Highest faucet index that survives 32-bit outpoint serialization. */
    private static final long INT_SAFE_MAX = Integer.MAX_VALUE - 1_000_000L;

    @Autowired
    private NetworkParameters networkParameters;
    @Autowired
    private StoreService storeService;

    @PostMapping
    public AbstractResponse fund(@RequestBody byte[] bodyByte) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> req = Json.jsonmapper().readValue(bodyByte, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) req.get("addresses");
        Block genesis = UtilGeneseBlock.createGenesis(networkParameters);
        Sha256Hash genesisHash = genesis.getHash();
        // All funded UTXOs share the genesis hash, so give each one a
        // globally-unique index to avoid colliding with other fundAddresses
        // calls (concurrent remote tests) or the genesis coinbase at index 0.
        //
        // The counter MUST survive JVM restarts: an in-memory reset re-mints
        // outpoints that earlier runs already SPENT. The stale rows then make
        // mempool verification fetch the OLD script for the outpoint and every
        // subsequent payment fails with OP_EQUALVERIFY (observed on prod after
        // a rolling redeploy). Persist the high-water mark in pos_state.
        long startIndex = nextFaucetIndex(entries.size());
        List<UTXO> utxos = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Map<String, Object> entry = entries.get(i);
            String addrStr = (String) entry.get("address");
            BigInteger value = entry.containsKey("value")
                    ? BigInteger.valueOf(((Number) entry.get("value")).longValue())
                    : NetworkParameters.BigtangleCoinTotal.divide(BigInteger.valueOf(entries.size()));
            String pubkeyHex = (String) entry.get("pubkey");
            UTXO utxo = new UTXO();
            utxo.setHash(genesisHash);
            // Optional per-entry "index" override lets the bootstrap fund
            // DIFFERENT validators at DIFFERENT outpoints. Without it every
            // node's first fundAddresses call lands on (genesis, 1e9) — the
            // resulting STAKE blocks spend the SAME outpoint, so a beacon that
            // references them all is rejected as conflicting and the chain
            // stalls at chainlength 0 on a fresh bootstrap.
            long index = entry.containsKey("index")
                    ? ((Number) entry.get("index")).longValue()
                    : startIndex + i;
            utxo.setIndex(index);
            utxo.setValue(new Coin(value, NetworkParameters.BIGTANGLE_TOKENID));
            utxo.setAddress(addrStr);
            if (pubkeyHex != null) {
                byte[] pubkeyBytes = Utils.HEX.decode(pubkeyHex);
                PQKey key = PQKey.fromPublicOnly(pubkeyBytes);
                utxo.setScript(ScriptBuilder.createOutputScript(key));
                byte[] pubKeyHash = Utils.sha256hash160(pubkeyBytes);
                utxo.setAddress(Address.fromHash160(networkParameters, pubKeyHash).toBase58());
            } else {
                utxo.setScript(ScriptBuilder
                        .createOutputScript(Address.fromBase58(networkParameters, addrStr)));
            }
            utxo.setCoinbase(true);
            utxo.setBlockHash(genesisHash);
            utxo.setTokenid(NetworkParameters.BIGTANGLE_TOKENID_STRING);
            utxo.setConfirmed(true);
            utxo.setSpent(false);
            utxos.add(utxo);
        }
        BlockStoreInterface store = storeService.getStore();
        try {
            store.addUnspentTransactionOutput(utxos);
            // Persist AFTER the mint is stored: a crash between the two writes
            // would re-mint at most this batch's outpoints, which are then
            // still unspent and harmless to overwrite.
            store.savePosState(FAUCET_STATE_SERVICE, FAUCET_INDEX_KEY,
                    String.valueOf(startIndex + entries.size()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            store.close();
        }
        log.warn("fundAddresses: minted {} confirmed UTXO(s) (test/bootstrap only)", utxos.size());
        return OkResponse.create();
    }

    /**
     * Next faucet index block of {@code count} entries: the maximum of the
     * in-memory counter and the persisted high-water mark, so restarts can
     * never rewind onto already-spent outpoints.
     *
     * <p>Indices MUST stay within unsigned-32-bit range: TransactionOutPoint
     * serializes the index as an int, so a larger value wraps modulo 2^32 and
     * the mempool then looks up an outpoint that does not exist (observed as
     * "UTXO not found" for truncated indices). Never seed from wall-clock.
     */
    private long nextFaucetIndex(int count) throws BlockStoreException {
        long persisted = 0;
        BlockStoreInterface store = storeService.getStore();
        try {
            byte[] raw = store.getPosState(FAUCET_STATE_SERVICE, FAUCET_INDEX_KEY);
            if (raw != null) {
                persisted = Long.parseLong(new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim());
            }
        } catch (Exception e) {
            log.warn("faucet index read failed, using in-memory counter", e);
        } finally {
            try {
                store.close();
            } catch (Exception e) {
                log.warn("store close failed", e);
            }
        }
        long base = Math.max(Math.min(persisted, INT_SAFE_MAX), Math.min(FUND_UTXO_INDEX.get(), INT_SAFE_MAX));
        long next = base + count;
        if (next >= Integer.MAX_VALUE) {
            throw new BlockStoreException("faucet index exhausted (next=" + next
                    + " would overflow the 32-bit outpoint index); reset pos_state faucet/"
                    + FAUCET_INDEX_KEY + " to reseed");
        }
        FUND_UTXO_INDEX.set(next);
        return base;
    }
}
