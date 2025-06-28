/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.server.test;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import net.bigtangle.params.NetworkParameters;

 
public class DeleteTablesTest extends AbstractIntegrationTest {
    @Autowired
    private NetworkParameters networkParameters;

    @BeforeEach
    public void setUp() throws Exception {
        store= storeService.getStore();
    }

    @Test
    // init
    public void deleteStore() throws Exception {
        store.deleteStore();
    }

     
}
