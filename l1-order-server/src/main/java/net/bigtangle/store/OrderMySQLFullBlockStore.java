/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.store;

import java.sql.Connection;

import net.bigtangle.params.NetworkParameters;

/**
 * MySQL store provisioned for the <b>order</b> domain: the shared core tables
 * plus the order-matching tables (orders, ordercancel, matching, orderresult,
 * paymultisign, price/ticker). Contract/EVM tables are not created. Used by the
 * L1-order layer.
 */
public class OrderMySQLFullBlockStore extends MySQLFullBlockStore {

    public OrderMySQLFullBlockStore(NetworkParameters params, Connection conn) {
        super(params, conn);
        setStoreDomain(BlockStoreInterface.StoreDomain.ORDER);
    }
}
