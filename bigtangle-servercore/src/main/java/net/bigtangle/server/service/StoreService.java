package net.bigtangle.server.service;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.DBStoreConfiguration;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreInterface.StoreDomain;
import net.bigtangle.store.DatabaseFullBlockStoreBase;
import net.bigtangle.store.MySQLFullBlockStore;
import net.bigtangle.store.PostgreSQLFullBlockStore;

@Service
public class StoreService {

    @Autowired
    protected DataSource dataSource;

    @Autowired
    protected NetworkParameters networkParameters;

    @Autowired
    protected transient DBStoreConfiguration dbStoreConfiguration;

    /** Layer domain this node is provisioned for (core / order / contract / all). */
    @Value("${store.domain:all}")
    private String storeDomain;

    public BlockStoreInterface getStore() throws BlockStoreException {
        try {
            StoreDomain domain = parseDomain();
            java.sql.Connection c = dataSource.getConnection();
            // A pooled connection can be returned mid-transaction if a batch
            // write was not properly finalized (autocommit=false + open
            // transaction). Queries on such a connection then see a STALE
            // snapshot — e.g. getStakeDeposit returns null for a record that
            // was committed after the stale transaction began, which in
            // multi-node operation makes validators reject their own
            // attestations. Reset the connection to a clean state so every
            // store starts from the latest committed view.
            if (!c.getAutoCommit()) {
                c.rollback();
                c.setAutoCommit(true);
            }
            BlockStoreInterface store;
            if ("mysql".equals(dbStoreConfiguration.getDbtype())) {
                store = new MySQLFullBlockStore(networkParameters, c);
            } else {
                store = new PostgreSQLFullBlockStore(networkParameters, c);
            }
            if (domain != StoreDomain.ALL && store instanceof DatabaseFullBlockStoreBase base) {
                base.setStoreDomain(domain);
            }
            return store;
        } catch (SQLException e) {
            throw new BlockStoreException(e);
        }
    }

    private StoreDomain parseDomain() {
        if (storeDomain == null) {
            return StoreDomain.ALL;
        }
        try {
            return StoreDomain.valueOf(storeDomain.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return StoreDomain.ALL;
        }
    }
}
