package net.bigtangle.layer1.contract;

import java.util.Set;

import net.bigtangle.core.Block;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.service.base.handler.ContractConnectSupport;
import net.bigtangle.store.BlockStoreInterface;

/**
 * Test stub — avoids pulling l1-contract-server (with Spring bean conflicts)
 * onto the test classpath. Only exists so AbstractIntegrationTest compiles.
 */
public class ContractEngine {
    public ContractExecutionResult executeContract(ContractConnectSupport support,
            NetworkParameters networkParameters, Block block, BlockStoreInterface store,
            String contractid, Contractresult prevHash, Set<Sha256Hash> referencedblocks)
            throws BlockStoreException {
        return new ContractExecutionResult();
    }

    public ContractExecutionResult executeContract(ContractConnectSupport support,
            NetworkParameters networkParameters, Block block, BlockStoreInterface store,
            Token contract, Contractresult prevHash, Set<Sha256Hash> referencedblocks)
            throws BlockStoreException {
        return new ContractExecutionResult();
    }
}
