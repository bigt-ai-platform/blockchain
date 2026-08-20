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
import net.bigtangle.server.config.ServerConfiguration;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.OrderPostgreSQLFullBlockStore;

@Component
public class BeforeStartup {

	private static final Logger logger = LoggerFactory.getLogger(BeforeStartup.class);

	@PostConstruct
	public void run() throws Exception {

		logger.debug("server config: {}", serverConfiguration.toString());

		// set false in test
		if (serverConfiguration.getCreatetable()) {
			BlockStoreInterface store = new OrderPostgreSQLFullBlockStore(networkParameters, dataSource.getConnection());
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
}
