/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.config;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.store.PostgreSQLFullBlockStore;

@Configuration
public class DBStoreConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(DBStoreConfiguration.class);

	@Value("${db.hostname:localhost}")
	private String hostname;

	@Value("${db.dbName:info}")
	private String dbName;

	@Value("${db.username:root}")
	private String username = "root";

	@Value("${db.password:test1234}")
	private String password;

	@Value("${db.port:5432}")
	private String port;

	@Autowired
	NetworkParameters networkParameters;
	@Autowired
	ServerConfiguration serverConfiguration;

	@Bean
	public DataSource dataSource() throws BlockStoreException, IOException, InterruptedException, ExecutionException {
		return dataSourcePostgresql(
				Integer.getInteger("db.pool.mainMaxSize", 50),
				Integer.getInteger("db.pool.mainMinIdle", 5));
	}

	/**
	 * Dedicated, smaller connection pool for the CONSENSUS path (slot tick,
	 * beacon proposal/attestation, block connection, confirmation). The main
	 * {@link #dataSource()} pool is shared by the submit/API burst and can be
	 * exhausted under load, which would stall the slot tick and let the node
	 * propose late on a stale head — forking the chain. Giving the consensus
	 * path its own pool guarantees it never waits for a connection behind the
	 * burst.
	 *
	 * <p>Sized from {@code db.pool.posMaxSize} (default 16): under a heavy
	 * submit burst the connect/confirm path nests several store borrows per
	 * cycle; with the old default of 8 the pool pinned at 3 leaked-or-active
	 * connections and the epoch tick timed out after 30s ("HikariPool-2 -
	 * Connection is not available"), which froze Casper finality evaluation.
	 */
	@Bean
	public DataSource posDataSource() throws BlockStoreException, IOException, InterruptedException, ExecutionException {
		return dataSourcePostgresql(
				Integer.getInteger("db.pool.posMaxSize", 16),
				Integer.getInteger("db.pool.posMinIdle", 4));
	}

	public DataSource dataSourcePostgresql(int maximumPoolSize, int minimumIdle)
			throws BlockStoreException, IOException, InterruptedException, ExecutionException {

		HikariConfig config = new HikariConfig();

		config.setJdbcUrl(
				PostgreSQLFullBlockStore.DATABASE_CONNECTION_URL_PREFIX + hostname + ":" + port + "/" + dbName + "");
		config.setUsername(username);
		config.setPassword(password);
		config.setDriverClassName("org.postgresql.Driver"); // explicitly set driver
		config.setMaximumPoolSize(maximumPoolSize);
		config.setMinimumIdle(minimumIdle);
		config.setConnectionTimeout(30000);
		config.setIdleTimeout(60000);
		config.setMaxLifetime(1800000);
		config.addDataSourceProperty("cachePrepStmts", "true");
		config.addDataSourceProperty("prepStmtCacheSize", "250");
		config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
		config.addDataSourceProperty("reWriteBatchedInserts", "true");

		config.setLeakDetectionThreshold(300000);
		logger.debug(config.getJdbcUrl());
		return new HikariDataSource(config);

	}

	public String getHostname() {
		return hostname;
	}

	public void setHostname(String hostname) {
		this.hostname = hostname;
	}

	public String getDbName() {
		return dbName;
	}

	public void setDbName(String dbName) {
		this.dbName = dbName;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getPort() {
		return port;
	}

	public void setPort(String port) {
		this.port = port;
	}

}
