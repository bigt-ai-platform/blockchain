package net.bigtangle.layer0.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.server.service.base.handler.SolidityContext;
import net.bigtangle.server.layer0.handler.TokenCreationHandler;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Layer-0 service for validating and creating new tokens. This is the
 * workflow/API facade (Pattern A): it wires the {@link TokenCreationHandler}
 * (Pattern B) into a {@link ServiceBaseCheck} instance so token validation
 * flows through the single, strategy-dispatched path used by the consensus
 * engine. See LAYERING-PLAN.md ("Keep both, unified").
 */
@Service
public class TokenCreationService {

    private static final Logger log = LoggerFactory.getLogger(TokenCreationService.class);

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

    /**
     * Build a {@link ServiceBaseCheck} with the Layer-0
     * {@link TokenCreationHandler} registered, so token-type validation is
     * routed through the strategy seam (the same path the consensus switches
     * use) rather than calling the type-specific methods directly.
     */
    private ServiceBaseCheck newServiceBaseCheck() {
        ServiceBaseCheck check = new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService,
                jsonmapper);
        check.handlerRegistry().register(BlockType.BLOCKTYPE_TOKEN_CREATION, new TokenCreationHandler());
        return check;
    }

    /**
     * Full token creation solidity check via the registered handler: formal
     * fields, amounts, previous issuance chain, multisig signatures, domain
     * permission. Returns {@link SolidityState#getSuccessState()} on success.
     */
    public SolidityState validateToken(Block block, BlockStoreInterface store) throws BlockStoreException {
        ServiceBaseCheck check = newServiceBaseCheck();
        SolidityContext ctx = SolidityContext.builder().block(block).store(store).height(0).throwExceptions(true)
                .base(check).build();
        return check.handlerFor(BlockType.BLOCKTYPE_TOKEN_CREATION).get().checkFull(ctx);
    }

    /**
     * Formal token validation without dependency checks (coinbase, data
     * fields, signatures), via the registered handler.
     */
    public SolidityState validateTokenFormal(Block block) throws BlockStoreException {
        ServiceBaseCheck check = newServiceBaseCheck();
        SolidityContext ctx = SolidityContext.builder().block(block).throwExceptions(false).base(check).build();
        return check.handlerFor(BlockType.BLOCKTYPE_TOKEN_CREATION).get().checkFormal(ctx);
    }

    /**
     * Checks that the token name + domain combination is unique in the store.
     */
    public void checkUnique(Block block, BlockStoreInterface store)
            throws BlockStoreException, VerificationException, JsonParseException, JsonMappingException,
            java.io.IOException {
        ServiceBaseCheck check = newServiceBaseCheck();
        check.checkTokenUnique(block, store);
    }

    /**
     * Validates the domain name inside a token creation block.
     */
    public void checkDomain(Block block) {
        ServiceBaseCheck check = newServiceBaseCheck();
        check.checkDomainname(block);
    }

    /**
     * Full token creation flow: validate, sign multisig, save block. This is
     * the primary entry point for new token issuance.
     */
    public void createToken(Block block, BlockStoreInterface store) throws Exception {
        log.info("Creating token, block hash={}", block.getHash());
        ServiceBaseCheck check = newServiceBaseCheck();
        check.checkTokenUnique(block, store);

        if (check.checkFullTokenSolidity(block, 0, true, store) == SolidityState.getSuccessState()) {
            multiSignServiceCreate.saveMultiSign(block, store);
            multiSignServiceCreate.signTokenAndSaveBlock(block, store);
        } else {
            multiSignServiceCreate.saveMultiSign(block, store);
        }
    }
}
