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
            BlockStoreInterface store;
            if ("mysql".equals(dbStoreConfiguration.getDbtype())) {
                store = new MySQLFullBlockStore(networkParameters, dataSource.getConnection());
            } else {
                store = new PostgreSQLFullBlockStore(networkParameters, dataSource.getConnection());
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
