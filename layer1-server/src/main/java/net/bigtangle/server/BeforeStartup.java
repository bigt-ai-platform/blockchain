/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.config.DBStoreConfiguration;
import net.bigtangle.server.config.MinioConfig;
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.server.service.base.MinioService;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.MySQLFullBlockStore;
import net.bigtangle.store.PostgreSQLFullBlockStore;

@Component
public class BeforeStartup {

	private static final Logger logger = LoggerFactory.getLogger(BeforeStartup.class);

	@PostConstruct
	public void run() throws Exception {

		logger.debug("server config: {}", serverConfiguration.toString());

		// set false in test
		if (serverConfiguration.getCreatetable()) {
			BlockStoreInterface store;
			if ("mysql".equals(dbStoreConfiguration.getDbtype())) {
				store = new MySQLFullBlockStore(networkParameters, dataSource.getConnection(),
						new MinioService(minioConfig, networkParameters));
			} else {
				store = new PostgreSQLFullBlockStore(networkParameters, dataSource.getConnection(),
						new MinioService(minioConfig, networkParameters));

			}
			try {
				store.create();
				// update tables to new version after initial setup
				store.updateDatabse();
			} finally {
				store.close();
			}
		}

	}

	@Autowired
	private ServerConfiguration serverConfiguration;
	@Autowired
	NetworkParameters networkParameters;
	@Autowired
	protected transient DataSource dataSource;
	@Autowired
	protected transient DBStoreConfiguration dbStoreConfiguration;

	@Autowired
	protected MinioConfig minioConfig;
}
