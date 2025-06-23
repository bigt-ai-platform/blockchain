/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.web;

import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

@Component
public class BeforeStartup {

	//private static final Logger logger = LoggerFactory.getLogger(BeforeStartup.class);

	@PostConstruct
	public void run() throws Exception { }

	 

}
