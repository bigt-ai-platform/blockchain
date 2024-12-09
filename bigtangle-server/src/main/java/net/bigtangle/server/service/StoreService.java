package net.bigtangle.server.service;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.MySQLFullBlockStore;

@Service
public class StoreService {

    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected NetworkParameters networkParameters;

    
     public BlockStoreInterface getStore() throws BlockStoreException      {
        
        MySQLFullBlockStore store;
        try {
            store = new MySQLFullBlockStore(networkParameters, dataSource.getConnection());
        } catch (SQLException e) {
            throw new BlockStoreException(e);
        }
      
        return store;
    }
     
}
