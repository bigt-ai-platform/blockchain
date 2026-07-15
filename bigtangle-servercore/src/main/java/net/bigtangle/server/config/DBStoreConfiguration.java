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
import net.bigtangle.store.MySQLFullBlockStore;
import net.bigtangle.store.PostgreSQLFullBlockStore;

@Configuration
public class DBStoreConfiguration {

	private static final Logger logger = LoggerFactory.getLogger(DBStoreConfiguration.class);

	@Value("${db.dbtype:mysql}")
	private String dbtype;

	@Value("${db.hostname:localhost}")
	private String hostname;

	@Value("${db.dbName:info}")
	private String dbName;

	@Value("${db.username:root}")
	private String username = "root";

	@Value("${db.password:test1234}")
	private String password;

	@Value("${db.port:3306}")
	private String port;

	@Autowired
	NetworkParameters networkParameters;
	@Autowired
	ServerConfiguration serverConfiguration;

	@Bean
	public DataSource dataSource() throws BlockStoreException, IOException, InterruptedException, ExecutionException {
		if ("mysql".equals(dbtype)) {
			return dataSourceMysql();
		}
		if ("postgresql".equals(dbtype)) {
			return dataSourcePostgresql();
		}
		return dataSourceMysql();
	}

	public DataSource dataSourcePostgresql()
			throws BlockStoreException, IOException, InterruptedException, ExecutionException {

		HikariConfig config = new HikariConfig();

		config.setJdbcUrl(
				PostgreSQLFullBlockStore.DATABASE_CONNECTION_URL_PREFIX + hostname + ":" + port + "/" + dbName + "");
		config.setUsername(username);
		config.setPassword(password);
		config.setDriverClassName("org.postgresql.Driver"); // explicitly set driver
		config.setMaximumPoolSize(20); // Must stay well below PG max_connections=100 to allow overlapping pools across @DirtiesContext
		config.setMinimumIdle(5);
		config.setConnectionTimeout(30000);
		config.setIdleTimeout(60000);
		config.setMaxLifetime(1800000);
		config.addDataSourceProperty("cachePrepStmts", "true");
		config.addDataSourceProperty("prepStmtCacheSize", "250");
		config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

		config.setLeakDetectionThreshold(300000);
		logger.debug(config.getJdbcUrl());
		return new HikariDataSource(config);

	}

	public DataSource dataSourceMysql()
			throws BlockStoreException, IOException, InterruptedException, ExecutionException {
		// createDatabase();

		HikariConfig config = new HikariConfig();

		config.setJdbcUrl(MySQLFullBlockStore.DATABASE_CONNECTION_URL_PREFIX + hostname + ":" + port + "/" + dbName
				+ "?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC");
		config.setUsername(username);
		config.setPassword(password);
		config.addDataSourceProperty("cachePrepStmts", "true");
		config.addDataSourceProperty("prepStmtCacheSize", "250");
		config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
		config.addDataSourceProperty("useServerPrepStmts", "true");
		config.addDataSourceProperty("cacheResultSetMetadata", "true");
		config.addDataSourceProperty("cacheServerConfiguration", "true");
		config.setMaximumPoolSize(100);
		config.setLeakDetectionThreshold(300000);
		logger.debug(config.getJdbcUrl());
		return new HikariDataSource(config);

	}

	public String getDbtype() {
		return dbtype;
	}

	public void setDbtype(String dbtype) {
		this.dbtype = dbtype;
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
