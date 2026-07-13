package net.bigtangle.server.service;

import java.sql.SQLException;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.DBStoreConfiguration;
import net.bigtangle.server.config.MinioConfig;
import net.bigtangle.server.service.base.MinioService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.MySQLFullBlockStore;
import net.bigtangle.store.PostgreSQLFullBlockStore;

/**
 * Service class responsible for managing database store implementations.
 * Provides abstraction for different database types (MySQL, PostgreSQL) and
 * creates appropriate store implementations based on configuration.
 * <p>
 * MinioService is cached because its constructor creates a heavyweight
 * OkHttpClient (connection pool, thread pool). Recreating it per request
 * causes unnecessary GC pressure and connection churn.
 */
@Service
public class StoreService {

    // Database connection pool for managing database connections
    @Autowired
    protected DataSource dataSource;

    // Network parameters defining blockchain configuration and rules
    @Autowired
    protected NetworkParameters networkParameters;

    // Configuration for database store settings including database type
    @Autowired
    protected transient DBStoreConfiguration dbStoreConfiguration;

    @Autowired
    protected MinioConfig minioConfig;

    private volatile MinioService cachedMinioService;

    /**
     * Returns a cached singleton MinioService. The MinioClient internally
     * manages an OkHttpClient with connection pooling — recreating it per
     * request is expensive and unnecessary.
     */
    private MinioService getMinioService() {
        MinioService service = cachedMinioService;
        if (service == null) {
            synchronized (this) {
                service = cachedMinioService;
                if (service == null) {
                    service = new MinioService(minioConfig, networkParameters);
                    cachedMinioService = service;
                }
            }
        }
        return service;
    }

    /**
     * Creates and returns the appropriate BlockStoreInterface implementation
     * based on configured database type.
     *
     * @return BlockStoreInterface implementation for the configured database
     * @throws BlockStoreException if there is an error creating the store
     */
    public BlockStoreInterface getStore() throws BlockStoreException {
        try {
            MinioService minioService = getMinioService();
            if ("mysql".equals(dbStoreConfiguration.getDbtype())) {
                return new MySQLFullBlockStore(networkParameters, dataSource.getConnection(), minioService);
            } else {
                return new PostgreSQLFullBlockStore(networkParameters, dataSource.getConnection(), minioService);
            }
        } catch (SQLException e) {
            throw new BlockStoreException(e);
        }
    }
}
