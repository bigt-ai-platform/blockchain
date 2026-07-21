package net.bigtangle.server.layer1.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.layer1.handler.NftHandler;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.layer0.service.MultiSignServiceCreate;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Layer-1 service for validating and creating NFT blocks.
 */
@Service
public class NftService {

    private static final Logger log = LoggerFactory.getLogger(NftService.class);

    @Autowired
    private NetworkParameters networkParameters;

    @Autowired
    private ServerConfiguration serverConfiguration;

    @Autowired
    private CacheBlockService cacheBlockService;

    @Autowired
    private MultiSignServiceCreate multiSignServiceCreate;

    @Autowired
    private ObjectMapper jsonmapper;

    private ServiceBaseCheck newServiceBaseCheck() {
        ServiceBaseCheck check = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,
                jsonmapper);
        check.handlerRegistry().register(BlockType.BLOCKTYPE_NFT, new NftHandler());
        return check;
    }

    /**
     * Full NFT solidity check via the registered handler.
     */
    public SolidityState validateNft(Block block, BlockStoreInterface store) throws Exception {
        ServiceBaseCheck check = newServiceBaseCheck();
        SolidityContext ctx = SolidityContext.builder().block(block).store(store).height(0).throwExceptions(true)
                .base(check).build();
        return check.handlerFor(BlockType.BLOCKTYPE_NFT).get().checkFull(ctx);
    }

    /**
     * Formal NFT validation without dependency checks, via the registered handler.
     */
    public SolidityState validateNftFormal(Block block) throws Exception {
        ServiceBaseCheck check = newServiceBaseCheck();
        SolidityContext ctx = SolidityContext.builder().block(block).throwExceptions(false).base(check).build();
        return check.handlerFor(BlockType.BLOCKTYPE_NFT).get().checkFormal(ctx);
    }

    /**
     * Creates and saves an NFT block: validates, signs, and persists.
     */
    public void createNft(Block block, BlockStoreInterface store) throws Exception {
        log.info("Creating NFT, block hash={}", block.getHash());
        ServiceBaseCheck check = newServiceBaseCheck();
        if (check.checkFullTokenSolidity(block, 0, true, store) == SolidityState.getSuccessState()) {
            multiSignServiceCreate.saveMultiSign(block, store);
            multiSignServiceCreate.signTokenAndSaveBlock(block, store);
        } else {
            multiSignServiceCreate.saveMultiSign(block, store);
        }
    }
}
