package net.bigtangle.layer0.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.bigtangle.core.Block;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.data.SolidityState;
import net.bigtangle.server.service.CacheBlockService;
import net.bigtangle.server.service.base.ServiceBaseCheck;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Layer-0 service for validating and creating new tokens. Delegates token
 * solidity checks to {@link ServiceBaseCheck} while keeping token-specific
 * workflows (domain checks, uniqueness, multisig sign-and-save) here.
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

    private ServiceBaseCheck newServiceBaseCheck() {
        return new ServiceBaseCheck(serverConfiguration, networkParameters, cacheBlockService, jsonmapper);
    }

    /**
     * Full token creation solidity check: formal fields, amounts, previous
     * issuance chain, multisig signatures, domain permission. Returns
     * {@link SolidityState#getSuccessState()} on success.
     */
    public SolidityState validateToken(Block block, BlockStoreInterface store) throws BlockStoreException {
        ServiceBaseCheck check = newServiceBaseCheck();
        return check.checkFullTokenSolidity(block, 0, true, store);
    }

    /**
     * Formal token validation without dependency checks (coinbase, data
     * fields, signatures).
     */
    public SolidityState validateTokenFormal(Block block) throws BlockStoreException {
        ServiceBaseCheck check = newServiceBaseCheck();
        return check.checkFormalTokenSolidity(block, false);
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
