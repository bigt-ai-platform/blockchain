package net.bigtangle.server.service;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.DBStoreConfiguration;
import net.bigtangle.server.service.base.MinioService;
import net.bigtangle.store.BlockStoreInterface;
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

    private static final MinioService MINIO_SERVICE = new MinioService();

    public BlockStoreInterface getStore() throws BlockStoreException {
        try {
            if ("mysql".equals(dbStoreConfiguration.getDbtype())) {
                return new MySQLFullBlockStore(networkParameters, dataSource.getConnection(), MINIO_SERVICE);
            } else {
                return new PostgreSQLFullBlockStore(networkParameters, dataSource.getConnection(), MINIO_SERVICE);
            }
        } catch (SQLException e) {
            throw new BlockStoreException(e);
        }
    }
}
