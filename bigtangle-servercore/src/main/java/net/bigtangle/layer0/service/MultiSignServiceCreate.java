package net.bigtangle.layer0.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.MultiSignBy;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.NoBlockException;
import net.bigtangle.exception.VerificationException.InsufficientSignaturesException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.MultiSignByRequest;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.BlockSaveService;
import net.bigtangle.server.service.CacheBlockPrototypeService;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.UUIDUtil;

@Service
public class MultiSignServiceCreate {

    private static final Logger log = LoggerFactory.getLogger(MultiSignServiceCreate.class);
    @Autowired
    protected TokenDomainnameService tokenDomainnameService;
    @Autowired
    protected CacheBlockService cacheBlockService;
    @Autowired
    protected CacheBlockPrototypeService cacheBlockPrototypeService;
    @Autowired
    protected ServerConfiguration serverConfiguration;
    @Autowired
    private BlockSaveService blockSaveService;
    @Autowired
    protected ObjectMapper jsonmapper;

    @Autowired
    private NetworkParameters networkParameters;

    public void saveMultiSign(Block block, BlockStoreInterface store) throws Exception {
        try {
            store.beginDatabaseBatchWrite();
            Transaction transaction = block.getTransactions().get(0);
            byte[] buf = transaction.getData();
            TokenInfo tokenInfo = new TokenInfo().parse(buf);
            final Token token = tokenInfo.getToken();

            List<MultiSignAddress> multiSignAddresses = tokenInfo.getMultiSignAddresses();

            multiSignAddresses.addAll(tokenDomainnameService
                    .queryDomainnameTokenMultiSignAddresses(Sha256Hash.wrap(token.getDomainNameBlockHash()), store));

            for (MultiSignAddress multiSignAddress : multiSignAddresses) {
                byte[] pubKey = Utils.HEX.decode(multiSignAddress.getPubKeyHex());
                multiSignAddress.setAddress(PQKey.fromPublicOnly(pubKey).toAddress(networkParameters).toBase58());

                String tokenid = token.getTokenid();
                long tokenindex = token.getTokenindex();
                String address = multiSignAddress.getAddress();
                int count = store.getCountMultiSignAlready(tokenid, tokenindex, address);
                if (count == 0) {
                    MultiSign multiSign = new MultiSign();
                    multiSign.setTokenid(tokenid);
                    multiSign.setTokenindex(tokenindex);
                    multiSign.setAddress(address);
                    multiSign.setBlockbytes(block.bitcoinSerialize());
                    multiSign.setId(UUIDUtil.randomUUID());
                    multiSign.setSign(0);
                    store.saveMultiSign(multiSign);
                }
            }
            if (transaction.getDataSignature() != null) {
                String jsonStr = new String(transaction.getDataSignature());
                MultiSignByRequest multiSignByRequest = Json.jsonmapper().readValue(jsonStr, MultiSignByRequest.class);
                for (MultiSignBy multiSignBy : multiSignByRequest.getMultiSignBies()) {
                    String tokenid = multiSignBy.getTokenid();
                    int tokenindex = (int) multiSignBy.getTokenindex();
                    String address = multiSignBy.getAddress();
                    store.updateMultiSign(tokenid, tokenindex, address, block.bitcoinSerialize(), 1);
                }
            }
            store.updateMultiSignBlockBitcoinSerialize(token.getTokenid(), token.getTokenindex(),
                    block.bitcoinSerialize());
            store.commitDatabaseBatchWrite();
        } catch (Exception e) {
            log.error("", e);
            store.abortDatabaseBatchWrite();
        } finally {
            store.defaultDatabaseBatchWrite();
        }
    }

    public void deleteMultiSign(Block block, BlockStoreInterface store) {
        try {

            Transaction transaction = block.getTransactions().get(0);
            byte[] buf = transaction.getData();
            TokenInfo tokenInfo = new TokenInfo().parse(buf);
            final Token token = tokenInfo.getToken();
            store.deleteMultiSign(token.getTokenid());
        } catch (Exception e) {
            // ignore
        }
    }

    public void signTokenAndSaveBlock(Block block, BlockStoreInterface store) throws Exception {
        try {
            ServiceBaseCheck serviceBase = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,
                    jsonmapper);
            serviceBase.checkTokenUnique(block, store);
            if (serviceBase.checkFullTokenSolidity(block, 0, true, store) == SolidityState.getSuccessState()) {
                this.saveMultiSign(block, store);
                blockSaveService.saveBlockPermissive(checkBlockPrototype(block, store), store);
                deleteMultiSign(block, store);
            } else {
                this.saveMultiSign(block, store);
            }
        } catch (InsufficientSignaturesException e) {
            this.saveMultiSign(block, store);
        }
    }

    private Block checkBlockPrototype(Block oldBlock, BlockStoreInterface store)
            throws BlockStoreException, NoBlockException {

        int time = 60 * 60 * 8;
        if (System.currentTimeMillis() / 1000 - oldBlock.getTimeSeconds() > time) {
            Block block = cacheBlockPrototypeService.getBlockPrototype(store);
            block.setBlockType(oldBlock.getBlockType());
            for (Transaction transaction : oldBlock.getTransactions()) {
                block.addTransaction(transaction);
            }
            return block;
        } else {
            return oldBlock;
        }
    }
}
