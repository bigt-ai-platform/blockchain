/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.store;

import java.sql.Connection;

import net.bigtangle.core.StoreDomain;
import net.bigtangle.params.NetworkParameters;

/**
 * MySQL store provisioned for the <b>contract</b> domain: the shared core tables
 * plus the contract/EVM tables (contractevent, contracteventcancel,
 * contractresult, evm_receipt). Order-matching tables are not created. Used by
 * the L1-contract layer.
 */
public class ContractMySQLFullBlockStore extends MySQLFullBlockStore {

    public ContractMySQLFullBlockStore(NetworkParameters params, Connection conn) {
        super(params, conn);
        setStoreDomain(StoreDomain.CONTRACT);
    }
}
