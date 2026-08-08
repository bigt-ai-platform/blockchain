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
        long startIndex = FUND_UTXO_INDEX.addAndGet(entries.size()) - entries.size();
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
            utxo.setIndex(startIndex + i);
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
        store.addUnspentTransactionOutput(utxos);
        log.warn("fundAddresses: minted {} confirmed UTXO(s) (test/bootstrap only)", utxos.size());
        return OkResponse.create();
    }
}
