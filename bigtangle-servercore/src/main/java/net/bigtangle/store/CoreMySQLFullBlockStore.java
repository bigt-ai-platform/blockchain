/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.store;

import java.sql.Connection;

import net.bigtangle.core.StoreDomain;
import net.bigtangle.params.NetworkParameters;

/**
 * MySQL store provisioned for the <b>core</b> domain only: creates and uses only
 * the shared chain/UTXO/token/stake tables, never the order-matching or
 * contract/EVM tables. Used by Layer 0.
 */
public class CoreMySQLFullBlockStore extends MySQLFullBlockStore {

    public CoreMySQLFullBlockStore(NetworkParameters params, Connection conn) {
        super(params, conn);
        setStoreDomain(StoreDomain.CORE);
    }
}
