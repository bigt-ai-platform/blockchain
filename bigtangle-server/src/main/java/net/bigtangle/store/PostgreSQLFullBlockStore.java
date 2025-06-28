/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.server.service.base.MinioService;
 

/**
 * <p>
 * A full block store using the PostgresSQL database engine.
 * </p>
 */

public class PostgreSQLFullBlockStore extends DatabaseFullBlockStore {

    private static final String DUPLICATE_KEY_ERROR_CODE = "23505";
 
    public static final String DATABASE_CONNECTION_URL_PREFIX = "jdbc:postgresql://"; // "jdbc:log4jdbc:mysql://";

    // create table SQL
    private static final String CREATE_SETTINGS_TABLE = "CREATE TABLE settings (\n" 
            + "    name varchar(32) NOT NULL,\n"
            + "    settingvalue BYTEA,\n" 
            + "    CONSTRAINT setting_pk PRIMARY KEY (name)  \n" 
            + ")\n";


    private static final String CREATE_BLOCKS_TABLE = "CREATE TABLE blocks (\n" 
            + "    hash BYTEA NOT NULL,\n"
            + "    height bigint NOT NULL,\n" 
            + "    block BYTEA NOT NULL,\n"
            + "    prevblockhash  BYTEA NOT NULL,\n"
            + "    prevbranchblockhash  BYTEA NOT NULL,\n" 
            + "    mineraddress BYTEA NOT NULL,\n"
            + "    blocktype bigint NOT NULL,\n" 
            //reward block chain length is here milestone
            + "    milestone bigint NOT NULL,\n"
            + "    milestonelastupdate bigint NOT NULL,\n"  
            + "    confirmed boolean NOT NULL,\n"
     
            //solid is result of validation of the block, 
            + "    solid bigint NOT NULL,\n"
            + "    inserttime bigint NOT NULL,\n"
            + "     PRIMARY KEY (hash) \n" 
            + ") ";

    
    private static final String CREATE_MCMC_TABLE = "CREATE TABLE mcmc (\n" 
            + "    hash BYTEA NOT NULL,\n" 
            //dynamic data
            //MCMC rating,depth,cumulativeweight
            + "    rating bigint NOT NULL,\n"
            + "    depth bigint NOT NULL,\n" 
            + "    cumulativeweight bigint NOT NULL,\n"
            + "    CONSTRAINT mcmc_pk PRIMARY KEY (hash) \n" 
            + ") ";
    
 
    private static final String CREATE_OUTPUT_TABLE = "CREATE TABLE outputs (\n" 
            + "    blockhash BYTEA NOT NULL,\n" 
            + "    hash BYTEA NOT NULL,\n"
            + "    outputindex bigint NOT NULL,\n" 
            + "    coinvalue BYTEA NOT NULL,\n"
            + "    scriptbytes BYTEA NOT NULL,\n" 
            + "    toaddress varchar(255),\n"
            + "    addresstargetable bigint,\n" 
            + "    coinbase boolean,\n" 
            + "    tokenid varchar(255),\n" 
            + "    fromaddress varchar(255),\n" 
            + "    memo TEXT,\n"
            + "    minimumsign bigint NOT NULL,\n"
            + "    time bigint,\n" 
            //begin the derived value of the output from block
            //this is for track the spent, spent = true means spenderblock is confirmed
            + "    spent boolean NOT NULL,\n" 
            + "    spenderblockhash  BYTEA,\n"
            //confirmed = the block of this output is confirmed
            + "    confirmed boolean NOT NULL,\n"
            //this is indicator for wallet to minimize conflict, is set for create at spender block
            + "    spendpending boolean NOT NULL,\n" 
            + "    spendpendingtime bigint,\n" 
            + "    CONSTRAINT outputs_pk PRIMARY KEY (blockhash, hash, outputindex) \n" 
            + "   ) \n";


    //This is table for output with possible multi sign address
    private static final String CREATE_OUTPUT_MULTI_TABLE = "CREATE TABLE outputsmulti (\n"
            + "    hash BYTEA NOT NULL,\n" 
            + "    outputindex bigint NOT NULL,\n"
            + "    toaddress varchar(255) NOT NULL,\n" 
            + "    CONSTRAINT outputsmulti_pk PRIMARY KEY (hash, outputindex, toaddress) \n" 
            + ") \n";

    
    private static final String CREATE_TX_REWARD_TABLE = "CREATE TABLE txreward (\n"
            + "   blockhash BYTEA NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash BYTEA,\n" 
            + "   prevblockhash BYTEA NOT NULL,\n" 
            + "   difficulty bigint NOT NULL,\n" 
            + "   chainlength bigint NOT NULL,\n" 
            + "   PRIMARY KEY (blockhash) )";

    private static final String CREATE_ORDERS_TABLE = "CREATE TABLE orders (\n"
                // initial issuing block  hash
            + "    blockhash BYTEA NOT NULL,\n" 
                // ZEROHASH if confirmed by order blocks,
                // issuing ordermatch blockhash if issued by ordermatch block
            + "    collectinghash BYTEA NOT NULL,\n" 
            + "    offercoinvalue bigint NOT NULL,\n" 
            + "    offertokenid varchar(255),\n" 
             + "   targetcoinvalue bigint,\n" 
            + "    targettokenid varchar(255),\n" 
                // buy or sell
            + "    side varchar(255),\n" 
                // public address
            + "    beneficiaryaddress varchar(255),\n" 
                // the pubkey that will receive the targettokens
                // on completion or returned   tokens on cancels 
            + "    beneficiarypubkey BYTEA,\n"
               // order is valid untill this time
            + "    validToTime bigint,\n" 
                // a number used to track operations on the
                // order, e.g. increasing by one when refreshing
                // order is valid after this time
            + "    validFromTime bigint,\n"            
               // order base token
            + "    orderbasetoken varchar(255),\n" 
            + "    tokendecimals int ,\n" 
             + "   price bigint,\n" 
            // true iff a order block of this order is confirmed
            + "    confirmed boolean NOT NULL,\n" 
            // true if used by a confirmed  ordermatch block (either
            // returned or used for another orderoutput/output)
            + "    spent boolean NOT NULL,\n" 
            + "    spenderblockhash  BYTEA,\n" 
            + "    CONSTRAINT orders_pk PRIMARY KEY (blockhash, collectinghash) "
            + "  \n" + ") \n";

    private static final String CREATE_ORDER_CANCEL_TABLE = "CREATE TABLE ordercancel (\n"
            + "   blockhash BYTEA NOT NULL,\n" 
            + "   orderblockhash BYTEA NOT NULL,\n"
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash BYTEA,\n" 
            + "   time bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) )";
    
    private static final String CREATE_MATCHING_TABLE = "CREATE TABLE matching (\n"
            + "    id   SERIAL,\n" 
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid varchar(255) NOT NULL,\n" 
            + "    basetokenid varchar(255) NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY (id) \n" 
            + ")\n";

    private static final String CREATE_MATCHINGDAILY_TABLE = "CREATE TABLE matchingdaily (\n"
    		   + "    id SERIAL,\n" // Changed AUTO_INCREMENT to SERIAL
    		   + "    matchday varchar(255) NOT NULL,\n"
    		   + "    tokenid varchar(255) NOT NULL,\n"
    		   + "    basetokenid varchar(255) NOT NULL,\n"
    		   + "    avgprice bigint NOT NULL,\n"
    		   + "    totalQuantity bigint NOT NULL,\n"
    		   + "    highprice bigint NOT NULL,\n"
    		   + "    lowprice bigint NOT NULL,\n"
    		   + "    open bigint NOT NULL,\n"
    		   + "    close bigint NOT NULL,\n"
    		   + "    matchinterval varchar(255) NOT NULL,\n"
    		   + "    inserttime bigint NOT NULL,\n"
    		   + "    PRIMARY KEY (id) \n"
    		   + ")\n";
    
    private static final String CREATE_MATCHING_LAST_TABLE = "CREATE TABLE matchinglast (\n" 
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid varchar(255) NOT NULL,\n" 
            + "    basetokenid varchar(255) NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY ( tokenid,basetokenid) \n" 
            + ")\n";
    private static final String CREATE_MATCHING_LAST_DAY_TABLE = "CREATE TABLE matchinglastday (\n" 
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid varchar(255) NOT NULL,\n" 
            + "    basetokenid varchar(255) NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY ( tokenid,basetokenid) \n" 
            + ")\n";
    
    private static final String CREATE_TOKENS_TABLE = "CREATE TABLE tokens (\n"
    	    + "    blockhash BYTEA NOT NULL,\n"
    		+ "    confirmed boolean NOT NULL,\n"
    	    + "    tokenid varchar(255) NOT NULL  ,\n"
    		+ "    tokenindex bigint NOT NULL   ,\n"
    	    + "    amount BYTEA ,\n"
    	    + "    tokenname varchar(100) ,\n"
    	    + "    description varchar(5000) ,\n"
    	    + "    domainname varchar(100) ,\n"
    	    + "    signnumber bigint NOT NULL   ,\n"
    	    + "    tokentype int,\n"   //Removed (11) from int
    	    + "    tokenstop boolean,\n"
    	    + "    prevblockhash BYTEA,\n"
    	    + "    spent boolean NOT NULL,\n"
    	    + "    spenderblockhash  BYTEA,\n"
    	    + "    tokenkeyvalues  BYTEA,\n"
    	    + "    revoked boolean   ,\n"
    	    + "    language char(2)   ,\n"
    	    + "    classification varchar(255)   ,\n"
    	    + "    domainpredblockhash varchar(255) NOT NULL,\n"
    	    + "    decimals int ,\n"
    	    + "    PRIMARY KEY (blockhash) \n)";
    // Helpers
    private static final String CREATE_MULTISIGNADDRESS_TABLE = "CREATE TABLE multisignaddress (\n"
    	    + "    blockhash BYTEA NOT NULL,\n"
    	    + "    tokenid varchar(255) NOT NULL  ,\n"
    	    + "    address varchar(255),\n"
    	    + "    pubKeyHex varchar(255),\n"
    	    + "    posIndex int,\n"   // Removed (11) from int
    	    + "    tokenHolder int NOT NULL DEFAULT 0,\n" //Removed (11) from int
    	    + "    PRIMARY KEY (blockhash, tokenid, pubKeyHex) \n)";

 
    private static final String CREATE_MULTISIGN_TABLE = "CREATE TABLE multisign (\n"
    	    + "    id varchar(255) NOT NULL  ,\n"
    	    + "    tokenid varchar(255) NOT NULL  ,\n"
    	    + "    tokenindex bigint NOT NULL   ,\n"
    	    + "    address varchar(255),\n"
    	    + "    blockhash  BYTEA NOT NULL,\n"
    	    + "    sign int NOT NULL,\n" // Removed (11) from int
    	    + "    PRIMARY KEY (id) \n)";

    private static final String CREATE_PAYMULTISIGN_TABLE = "CREATE TABLE paymultisign (\n"
            + "    orderid varchar(255) NOT NULL  ,\n" 
            + "    tokenid varchar(255) NOT NULL  ,\n"
            + "    toaddress varchar(255) NOT NULL,\n" 
            + "    blockhash BYTEA NOT NULL,\n"
            + "    amount BYTEA ,\n" 
            + "    minsignnumber bigint,\n" 
            + "    outputHashHex varchar(255) ,\n"
            + "    outputindex bigint ,\n" + "    PRIMARY KEY (orderid) \n)";

    private static final String CREATE_PAYMULTISIGNADDRESS_TABLE = "CREATE TABLE paymultisignaddress (\n"
            + "    orderid varchar(255) NOT NULL  ,\n" 
            + "    pubKey varchar(255),\n" 
            + "    sign int NOT NULL,\n"
            + "    signIndex int NOT NULL,\n" 
            + "    signInputData BYTEA,\n"
            + "    PRIMARY KEY (orderid, pubKey) \n)";
    //aggregate of utxo for view only
    private static final String CREATE_ACCOUNT_TABLE = "CREATE TABLE accountBalance (\n" 
            + "    address varchar(255),\n"
            + "    tokenid varchar(255),\n" 
            + "    coinvalue BYTEA NOT NULL,\n" 
            + "    time bigint,\n" 
            + "    lastblockhash  BYTEA NOT NULL,\n" 
            + "    CONSTRAINT account_pk PRIMARY KEY (address, tokenid) \n" 
            + "   ) \n";

    
    private static final String CREATE_USERDATA_TABLE = "CREATE TABLE userdata (\n"
            + "    blockhash BYTEA NOT NULL,\n" 
            + "    dataclassname varchar(255) NOT NULL,\n"
            + "    data BYTEA NOT NULL,\n" 
            + "    pubKey varchar(255),\n" 
            + "    blocktype bigint,\n"
            + "   CONSTRAINT userdata_pk PRIMARY KEY (dataclassname, pubKey)  \n" 
            + ")";

 
    private static final String CREATE_BATCHBLOCK_TABLE = "CREATE TABLE batchblock (\n"
    	    + "    hash BYTEA NOT NULL,\n"
    	    + "    block BYTEA NOT NULL,\n"
    	    + "    inserttime TIMESTAMP NOT NULL,\n"  //Replaced datetime with timestamp
    	    + "   CONSTRAINT batchblock_pk PRIMARY KEY (hash)  \n"
    	    + ")";
    private static final String CREATE_SUBTANGLE_PERMISSION_TABLE = "CREATE TABLE subtangle_permission (\n"
            + "    pubkey varchar(255) NOT NULL,\n" 
            + "    userdataPubkey varchar(255) NOT NULL,\n"
            + "    status varchar(255) NOT NULL,\n"
            + "   CONSTRAINT subtangle_permission_pk PRIMARY KEY (pubkey)  \n" 
            + ")";

    /*
     * indicate of a server created block
     */
    private static final String CREATE_MYSERVERBLOCKS_TABLE = "CREATE TABLE myserverblocks (\n"
            + "    prevhash BYTEA NOT NULL,\n" 
            + "    hash BYTEA NOT NULL,\n" 
            + "    inserttime bigint,\n"
            + "    CONSTRAINT myserverblocks_pk PRIMARY KEY (prevhash, hash)  \n" 
            + ")";
    
 
    
    private static final String CREATE_ACCESS_PERMISSION_TABLE = 
            "CREATE TABLE access_permission (\n"
          + "   accessToken varchar(255) ,\n" 
          + "   pubKey varchar(255),\n"
          + "   refreshTime bigint,\n"
          + "   PRIMARY KEY (accessToken) )";
    
    private static final String CREATE_ACCESS_GRANT_TABLE = 
            "CREATE TABLE access_grant (\n"
          + "   address varchar(255),\n"
          + "   createTime bigint,\n"
          + "   PRIMARY KEY (address) )";

    

    private static final String CREATE_CONTRACT_EVENT_TABLE = "CREATE TABLE contractevent (\n"
            + "    blockhash BYTEA NOT NULL,\n" 
            + "    collectinghash BYTEA NOT NULL,\n" 
            + "    contracttokenid varchar(255) NOT NULL,\n" 
             + "   targetcoinvalue BYTEA,\n" 
            + "    targettokenid varchar(255),\n" 
                // public address  will receive the targettokens
                // on completion or returned   tokens on cancels 
            + "    beneficiaryaddress varchar(255) NOT NULL,\n" 
            // true if a order block of this order is confirmed
            + "    confirmed boolean NOT NULL,\n" 
            // true if used by a confirmed  block (either
            // returned or used for another  )
            + "    spent boolean NOT NULL,\n" 
            + "    spenderblockhash  BYTEA,\n" 
            + "    CONSTRAINT contractevent_pk PRIMARY KEY (blockhash, collectinghash) "
            + "  \n" + ") \n";

    private static final String CREATE_CONTRACTEVENT_CANCEL_TABLE = "CREATE TABLE contracteventcancel (\n"
            + "   blockhash BYTEA NOT NULL,\n" 
            + "   eventblockhash BYTEA NOT NULL,\n"
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash BYTEA,\n" 
            + "   time bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) )";

    // the contract execution result
    private static final String CREATE_CONTRACT_RESULT_TABLE = "CREATE TABLE contractresult (\n"
            + "   blockhash BYTEA NOT NULL,\n"  
            + "   contracttokenid varchar(255)  NOT NULL,\n" 
            + "   contractresult BYTEA NOT NULL,\n" 
            + "   prevblockhash BYTEA NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash BYTEA,\n" 
            + "    milestone bigint NOT NULL,\n"
            + "   chainlength bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) )";
   
    //  the order execution result
    private static final String CREATE_ORDER_RESULT_TABLE = "CREATE TABLE orderresult (\n"
            + "   blockhash BYTEA NOT NULL,\n"  
            + "   orderresult BYTEA NOT NULL,\n" 
            + "   prevblockhash BYTEA NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash BYTEA,\n" 
            + "    milestone bigint NOT NULL,\n"
            + "   chainlength bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) )";
    private static final String CREATE_CHAINBLOCKQUEUE_TABLE = "CREATE TABLE chainblockqueue (\n" 
            + "    hash BYTEA NOT NULL,\n" 
            + "    block BYTEA NOT NULL,\n" 
            + "    chainlength bigint NOT NULL,\n " 
            + "    orphan boolean,\n " 
            + "    inserttime bigint NOT NULL,\n"
            + "    CONSTRAINT chainblockqueue_pk PRIMARY KEY (hash)  \n" + ") \n";
    private static final String CREATE_LOCKOBJECT_TABLE = "CREATE TABLE lockobject (\n" 
            + "    lockobjectid varchar(255) NOT NULL,\n"  
            + "    locktime bigint NOT NULL,\n"
            + "    CONSTRAINT lockobject_pk PRIMARY KEY (lockobjectid)  \n" + ") \n";
    
    // Some indexes to speed up stuff
    private static final String CREATE_OUTPUTS_ADDRESS_MULTI_INDEX = "CREATE INDEX outputs_hash_index_toaddress_idx ON outputs (hash, outputindex, toaddress) ";
    private static final String CREATE_OUTPUTS_TOADDRESS_INDEX = "CREATE INDEX outputs_toaddress_idx ON outputs (toaddress) ";
    private static final String CREATE_OUTPUTS_FROMADDRESS_INDEX = "CREATE INDEX outputs_fromaddress_idx ON outputs (fromaddress) ";
    
    private static final String CREATE_PREVBRANCH_HASH_INDEX = "CREATE INDEX blocks_prevbranchblockhash_idx ON blocks (prevbranchblockhash) ";
    private static final String CREATE_PREVTRUNK_HASH_INDEX = "CREATE INDEX blocks_prevblockhash_idx ON blocks (prevblockhash) ";
    
    private static final String CREATE_ORDERS_COLLECTINGHASH_TABLE_INDEX = "CREATE INDEX orders_collectinghash_idx ON orders (collectinghash) ";
    private static final String CREATE_BLOCKS_MILESTONE_INDEX = "CREATE INDEX blocks_milestone_idx ON blocks (milestone)   ";
    private static final String CREATE_BLOCKS_HEIGHT_INDEX = "CREATE INDEX blocks_height_idx ON blocks (height)   ";
    private static final String CREATE_TXREARD_CHAINLENGTH_INDEX = "CREATE INDEX txreard_chainlength_idx ON txreward (chainlength, confirmed)  ";
    private static final String CREATE_CONTRACT_EVENT_CONTRACTTOKENID_TABLE_INDEX = "CREATE INDEX contractevent_contracttokenid_idx ON contractevent (contracttokenid) ";
    private static final String CREATE_CONTRACT_EVENT_COLLECTINGHASH_TABLE_INDEX = "CREATE INDEX contractevent_collectinghash_idx ON contractevent (collectinghash)";
    private static final String CREATE_CONTRACT_EXECUTION_CONTRACTTOKENID_TABLE_INDEX = "CREATE INDEX contractresult_contracttokenid_idx ON contractresult (contracttokenid) ";
    private static final String CREATE_ORDERS_SPENT_TABLE_INDEX = "CREATE INDEX orders_spent_idx ON orders (confirmed, spent) ";
    private static final String CREATE_MATCHING_TOKEN_TABLE_INDEX = "CREATE INDEX matching_inserttime_idx ON matching (inserttime) ";
      
    private static final String CREATE_TOKEN_TOKENID_TABLE_INDEX = "CREATE INDEX tokens_tokenid_idx ON tokens (tokenid) ";
    private static final String CREATE_BLOCKS_MILESTONE_CONFIRMED_INDEX = "CREATE INDEX blocks_milestone_confirmed_idx ON blocks (milestone, confirmed)   ";
    
    
  
    public PostgreSQLFullBlockStore(NetworkParameters params, Connection conn, MinioService minioService )  {
        super(  params,   conn, minioService);
    }

    @Override
    protected String getDuplicateKeyErrorCode() {
        return DUPLICATE_KEY_ERROR_CODE;
    }

    protected String	duplicateInsert() {
		return " ON CONFLICT DO NOTHING ";
	}
	
    @Override
    protected List<String> getCreateTablesSQL() {
        List<String> sqlStatements = new ArrayList<String>();
        sqlStatements.addAll( getCreateTablesSQL1());
        sqlStatements.addAll( getCreateTablesSQL2());
        return sqlStatements;
    }

    protected List<String> getCreateTablesSQL1() {
        List<String> sqlStatements = new ArrayList<String>();
        sqlStatements.add(CREATE_BLOCKS_TABLE); 
        sqlStatements.add(CREATE_OUTPUT_TABLE);
        sqlStatements.add(CREATE_OUTPUT_MULTI_TABLE);
        sqlStatements.add(CREATE_TOKENS_TABLE);
        sqlStatements.add(CREATE_MATCHING_TABLE);
        sqlStatements.add(CREATE_MULTISIGNADDRESS_TABLE); 
        sqlStatements.add(CREATE_MULTISIGN_TABLE);
        sqlStatements.add(CREATE_TX_REWARD_TABLE);
        sqlStatements.add(CREATE_USERDATA_TABLE);
        sqlStatements.add(CREATE_PAYMULTISIGN_TABLE);
        sqlStatements.add(CREATE_PAYMULTISIGNADDRESS_TABLE);
        sqlStatements.add(CREATE_ORDER_CANCEL_TABLE);
        sqlStatements.add(CREATE_BATCHBLOCK_TABLE);
        sqlStatements.add(CREATE_SUBTANGLE_PERMISSION_TABLE);
        sqlStatements.add(CREATE_ORDERS_TABLE);
        sqlStatements.add(CREATE_MYSERVERBLOCKS_TABLE);
        sqlStatements.add(CREATE_SETTINGS_TABLE);
 
        sqlStatements.add(CREATE_MCMC_TABLE); 
        sqlStatements.add(CREATE_MATCHING_LAST_TABLE);
        sqlStatements.add(CREATE_MATCHING_LAST_DAY_TABLE);
        return sqlStatements;
    }

    protected List<String> getCreateTablesSQL2() {
        List<String> sqlStatements = new ArrayList<String>(); 
        sqlStatements.add(CREATE_ACCESS_PERMISSION_TABLE);
        sqlStatements.add(CREATE_ACCESS_GRANT_TABLE);
        sqlStatements.add(CREATE_CONTRACT_EVENT_TABLE);
        sqlStatements.add(CREATE_CONTRACTEVENT_CANCEL_TABLE);
        sqlStatements.add(CREATE_CONTRACT_RESULT_TABLE); 
        sqlStatements.add(CREATE_ORDER_RESULT_TABLE); 
        sqlStatements.add(CREATE_CHAINBLOCKQUEUE_TABLE);
        sqlStatements.add(CREATE_LOCKOBJECT_TABLE); 
        sqlStatements.add(CREATE_MATCHINGDAILY_TABLE); 
        sqlStatements.add(CREATE_ACCOUNT_TABLE); 
        return sqlStatements;
    }

 
    public  void updateDatabse() throws BlockStoreException, SQLException  {
    
       byte[] settingValue = getSettingValue("version");
       String ver = "";
       if(settingValue!=null) ver= new String(settingValue);
       
       if("03".equals(ver)) {
           updateTables(getCreateTablesSQL2());
           updateTables(getCreateIndexesSQL2());
           dbupdateversion("05");
       }
      
    }
    @Override
    protected List<String> getCreateIndexesSQL() {
        List<String> sqlStatements = new ArrayList<String>();
        sqlStatements.addAll(getCreateIndexesSQL1());
        sqlStatements.addAll(getCreateIndexesSQL2());
        return sqlStatements;
    }
    
    protected List<String> getCreateIndexesSQL1() {
        List<String> sqlStatements = new ArrayList<String>();
        sqlStatements.add(CREATE_OUTPUTS_ADDRESS_MULTI_INDEX); 
        sqlStatements.add(CREATE_BLOCKS_HEIGHT_INDEX);
        sqlStatements.add(CREATE_OUTPUTS_TOADDRESS_INDEX);
        sqlStatements.add(CREATE_PREVBRANCH_HASH_INDEX);
        sqlStatements.add(CREATE_PREVTRUNK_HASH_INDEX);

        sqlStatements.add(CREATE_ORDERS_COLLECTINGHASH_TABLE_INDEX);
        sqlStatements.add(CREATE_BLOCKS_MILESTONE_INDEX);
        sqlStatements.add(CREATE_TXREARD_CHAINLENGTH_INDEX);

        return sqlStatements;
    }
    protected List<String> getCreateIndexesSQL2() {
        List<String> sqlStatements = new ArrayList<String>(); 
        sqlStatements.add(CREATE_OUTPUTS_FROMADDRESS_INDEX); 
        sqlStatements.add(CREATE_CONTRACT_EVENT_CONTRACTTOKENID_TABLE_INDEX); 
        sqlStatements.add(CREATE_CONTRACT_EVENT_COLLECTINGHASH_TABLE_INDEX); 
        sqlStatements.add(CREATE_CONTRACT_EXECUTION_CONTRACTTOKENID_TABLE_INDEX);
        sqlStatements.add(CREATE_ORDERS_SPENT_TABLE_INDEX);
        sqlStatements.add(CREATE_MATCHING_TOKEN_TABLE_INDEX);
        sqlStatements.add(CREATE_TOKEN_TOKENID_TABLE_INDEX);
        sqlStatements.add(CREATE_BLOCKS_MILESTONE_CONFIRMED_INDEX);
        return sqlStatements;
    }


}
