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
 * A full block store using the MySQL database engine.
 * </p>
 */

public class MySQLFullBlockStore extends DatabaseFullBlockStore {

    private static final String MYSQL_DUPLICATE_KEY_ERROR_CODE = "23000";
 //   private static final String DATABASE_DRIVER_CLASS = "com.mysql.jdbc.Driver";
    public static final String DATABASE_CONNECTION_URL_PREFIX = "jdbc:mysql://"; // "jdbc:log4jdbc:mysql://";

    // create table SQL
    private static final String CREATE_SETTINGS_TABLE = "CREATE TABLE settings (\n" 
            + "    name varchar(32) NOT NULL,\n"
            + "    settingvalue blob,\n" 
            + "    CONSTRAINT setting_pk PRIMARY KEY (name)  \n" 
            + ")\n";


    private static final String CREATE_BLOCKS_TABLE = "CREATE TABLE blocks (\n" 
            + "    hash binary(32) NOT NULL,\n"
            + "    height bigint NOT NULL,\n" 
            + "    block mediumblob NOT NULL,\n"
            + "    prevblockhash  binary(32) NOT NULL,\n"
            + "    prevbranchblockhash  binary(32) NOT NULL,\n" 
            + "    mineraddress binary(20) NOT NULL,\n"
            + "    blocktype bigint NOT NULL,\n" 
            //reward block chain length is here milestone
            + "    milestone bigint NOT NULL,\n"
            + "    milestonelastupdate bigint NOT NULL,\n"  
            + "    confirmed boolean NOT NULL,\n"
     
            //solid is result of validation of the block, 
            + "    solid bigint NOT NULL,\n"
            + "    inserttime bigint NOT NULL,\n"
            + "    CONSTRAINT blocks_pk PRIMARY KEY (hash) \n" 
            + ") ENGINE=InnoDB ";

    
    private static final String CREATE_MCMC_TABLE = "CREATE TABLE mcmc (\n" 
            + "    hash binary(32) NOT NULL,\n" 
            //dynamic data
            //MCMC rating,depth,cumulativeweight
            + "    rating bigint NOT NULL,\n"
            + "    depth bigint NOT NULL,\n" 
            + "    cumulativeweight bigint NOT NULL,\n"
            + "    CONSTRAINT mcmc_pk PRIMARY KEY (hash) \n" 
            + ") ENGINE=InnoDB ";
    
 
    private static final String CREATE_OUTPUT_TABLE = "CREATE TABLE outputs (\n" 
            + "    blockhash binary(32) NOT NULL,\n" 
            + "    hash binary(32) NOT NULL,\n"
            + "    outputindex bigint NOT NULL,\n" 
            + "    coinvalue mediumblob NOT NULL,\n"
            + "    scriptbytes mediumblob NOT NULL,\n" 
            + "    toaddress varchar(255),\n"
            + "    addresstargetable bigint,\n" 
            + "    coinbase boolean,\n" 
            + "    tokenid varchar(255),\n" 
            + "    fromaddress varchar(255),\n" 
            + "    memo MEDIUMTEXT,\n"
            + "    minimumsign bigint NOT NULL,\n"
            + "    time bigint,\n" 
            //begin the derived value of the output from block
            //this is for track the spent, spent = true means spenderblock is confirmed
            + "    spent boolean NOT NULL,\n" 
            + "    spenderblockhash  binary(32),\n"
            //confirmed = the block of this output is confirmed
            + "    confirmed boolean NOT NULL,\n"
            //this is indicator for wallet to minimize conflict, is set for create at spender block
            + "    spendpending boolean NOT NULL,\n" 
            + "    spendpendingtime bigint,\n" 
            + "    CONSTRAINT outputs_pk PRIMARY KEY (blockhash, hash, outputindex) \n" 
            + "   ) ENGINE=InnoDB \n";


    //This is table for output with possible multi sign address
    private static final String CREATE_OUTPUT_MULTI_TABLE = "CREATE TABLE outputsmulti (\n"
            + "    hash binary(32) NOT NULL,\n" 
            + "    outputindex bigint NOT NULL,\n"
            + "    toaddress varchar(255) NOT NULL,\n" 
            + "    CONSTRAINT outputsmulti_pk PRIMARY KEY (hash, outputindex, toaddress) \n" 
            + ") ENGINE=InnoDB \n";

    
    private static final String CREATE_TX_REWARD_TABLE = "CREATE TABLE txreward (\n"
            + "   blockhash binary(32) NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash binary(32),\n" 
            + "   prevblockhash binary(32) NOT NULL,\n" 
            + "   difficulty bigint NOT NULL,\n" 
            + "   chainlength bigint NOT NULL,\n" 
            + "   PRIMARY KEY (blockhash) ) ENGINE=InnoDB";

    private static final String CREATE_ORDERS_TABLE = "CREATE TABLE orders (\n"
                // initial issuing block  hash
            + "    blockhash binary(32) NOT NULL,\n" 
                // ZEROHASH if confirmed by order blocks,
                // issuing ordermatch blockhash if issued by ordermatch block
            + "    collectinghash binary(32) NOT NULL,\n" 
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
            + "    beneficiarypubkey binary(33),\n"
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
            + "    spenderblockhash  binary(32),\n" 
            + "    CONSTRAINT orders_pk PRIMARY KEY (blockhash, collectinghash) "
            + " USING HASH \n" + ") ENGINE=InnoDB \n";

    private static final String CREATE_ORDER_CANCEL_TABLE = "CREATE TABLE ordercancel (\n"
            + "   blockhash binary(32) NOT NULL,\n" 
            + "   orderblockhash binary(32) NOT NULL,\n"
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash binary(32),\n" 
            + "   time bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) ) ENGINE=InnoDB";
    
    private static final String CREATE_MATCHING_TABLE = "CREATE TABLE matching (\n"
            + "    id bigint NOT NULL AUTO_INCREMENT,\n" 
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid varchar(255) NOT NULL,\n" 
            + "    basetokenid varchar(255) NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY (id) \n" 
            + ") ENGINE=InnoDB\n";

    private static final String CREATE_MATCHINGDAILY_TABLE = "CREATE TABLE matchingdaily (\n"
            + "    id bigint NOT NULL AUTO_INCREMENT,\n" 
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
            + ") ENGINE=InnoDB\n";
    
    private static final String CREATE_MATCHING_LAST_TABLE = "CREATE TABLE matchinglast (\n" 
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid varchar(255) NOT NULL,\n" 
            + "    basetokenid varchar(255) NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY ( tokenid,basetokenid) \n" 
            + ") ENGINE=InnoDB\n";
    private static final String CREATE_MATCHING_LAST_DAY_TABLE = "CREATE TABLE matchinglastday (\n" 
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid varchar(255) NOT NULL,\n" 
            + "    basetokenid varchar(255) NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY ( tokenid,basetokenid) \n" 
            + ") ENGINE=InnoDB\n";
    
    private static final String CREATE_TOKENS_TABLE = "CREATE TABLE tokens (\n"
            + "    blockhash binary(32) NOT NULL,\n" 
    		+ "    confirmed boolean NOT NULL,\n"
            + "    tokenid varchar(255) NOT NULL  ,\n" 
    		+ "    tokenindex bigint NOT NULL   ,\n"
            + "    amount mediumblob ,\n" 
            + "    tokenname varchar(100) ,\n" 
            + "    description varchar(5000) ,\n"
            + "    domainname varchar(100) ,\n" 
            + "    signnumber bigint NOT NULL   ,\n" 
            + "    tokentype int(11),\n"
            + "    tokenstop boolean,\n" 
            + "    prevblockhash binary(32),\n"
            + "    spent boolean NOT NULL,\n" 
            + "    spenderblockhash  binary(32),\n"
            + "    tokenkeyvalues  mediumblob,\n" 
            + "    revoked boolean   ,\n" 
            + "    language char(2)   ,\n"
            + "    classification varchar(255)   ,\n"
            + "    domainpredblockhash varchar(255) NOT NULL,\n"
            + "    decimals int ,\n" 
            + "    PRIMARY KEY (blockhash) \n) ENGINE=InnoDB";

    // Helpers
    private static final String CREATE_MULTISIGNADDRESS_TABLE = "CREATE TABLE multisignaddress (\n"
            + "    blockhash binary(32) NOT NULL,\n" 
            + "    tokenid varchar(255) NOT NULL  ,\n"
            + "    address varchar(255),\n" 
            + "    pubKeyHex varchar(255),\n" 
            + "    posIndex int(11),\n"
            + "    tokenHolder int(11) NOT NULL DEFAULT 0,\n"
            + "    PRIMARY KEY (blockhash, tokenid, pubKeyHex) \n) ENGINE=InnoDB";

 

    private static final String CREATE_MULTISIGN_TABLE = "CREATE TABLE multisign (\n"
            + "    id varchar(255) NOT NULL  ,\n" 
            + "    tokenid varchar(255) NOT NULL  ,\n"
            + "    tokenindex bigint NOT NULL   ,\n" 
            + "    address varchar(255),\n"
            + "    blockhash  mediumblob NOT NULL,\n" 
            + "    sign int(11) NOT NULL,\n" 
            + "    PRIMARY KEY (id) \n) ENGINE=InnoDB";

    private static final String CREATE_PAYMULTISIGN_TABLE = "CREATE TABLE paymultisign (\n"
            + "    orderid varchar(255) NOT NULL  ,\n" 
            + "    tokenid varchar(255) NOT NULL  ,\n"
            + "    toaddress varchar(255) NOT NULL,\n" 
            + "    blockhash mediumblob NOT NULL,\n"
            + "    amount mediumblob ,\n" 
            + "    minsignnumber bigint(20) ,\n" 
            + "    outputHashHex varchar(255) ,\n"
            + "    outputindex bigint ,\n" + "    PRIMARY KEY (orderid) \n) ENGINE=InnoDB";

    private static final String CREATE_PAYMULTISIGNADDRESS_TABLE = "CREATE TABLE paymultisignaddress (\n"
            + "    orderid varchar(255) NOT NULL  ,\n" 
            + "    pubKey varchar(255),\n" 
            + "    sign int(11) NOT NULL,\n"
            + "    signIndex int(11) NOT NULL,\n" 
            + "    signInputData mediumblob,\n"
            + "    PRIMARY KEY (orderid, pubKey) \n) ENGINE=InnoDB";
    //aggregate of utxo for view only
    private static final String CREATE_ACCOUNT_TABLE = "CREATE TABLE accountBalance (\n" 
            + "    address varchar(255),\n"
            + "    tokenid varchar(255),\n" 
            + "    coinvalue mediumblob NOT NULL,\n" 
            + "    time bigint,\n" 
            + "    lastblockhash  mediumblob NOT NULL,\n" 
            + "    CONSTRAINT account_pk PRIMARY KEY (address, tokenid) \n" 
            + "   ) ENGINE=InnoDB \n";

    
    private static final String CREATE_USERDATA_TABLE = "CREATE TABLE userdata (\n"
            + "    blockhash binary(32) NOT NULL,\n" 
            + "    dataclassname varchar(255) NOT NULL,\n"
            + "    data mediumblob NOT NULL,\n" 
            + "    pubKey varchar(255),\n" 
            + "    blocktype bigint,\n"
            + "   CONSTRAINT userdata_pk PRIMARY KEY (dataclassname, pubKey) USING BTREE \n" 
            + ") ENGINE=InnoDB";

 
    private static final String CREATE_BATCHBLOCK_TABLE = "CREATE TABLE batchblock (\n"
            + "    hash binary(32) NOT NULL,\n" 
            + "    block mediumblob NOT NULL,\n"
            + "    inserttime datetime NOT NULL,\n" 
            + "   CONSTRAINT batchblock_pk PRIMARY KEY (hash)  \n"
            + ") ENGINE=InnoDB";

    private static final String CREATE_SUBTANGLE_PERMISSION_TABLE = "CREATE TABLE subtangle_permission (\n"
            + "    pubkey varchar(255) NOT NULL,\n" 
            + "    userdataPubkey varchar(255) NOT NULL,\n"
            + "    status varchar(255) NOT NULL,\n"
            + "   CONSTRAINT subtangle_permission_pk PRIMARY KEY (pubkey) USING BTREE \n" 
            + ") ENGINE=InnoDB";

    /*
     * indicate of a server created block
     */
    private static final String CREATE_MYSERVERBLOCKS_TABLE = "CREATE TABLE myserverblocks (\n"
            + "    prevhash binary(32) NOT NULL,\n" 
            + "    hash binary(32) NOT NULL,\n" 
            + "    inserttime bigint,\n"
            + "    CONSTRAINT myserverblocks_pk PRIMARY KEY (prevhash, hash) USING BTREE \n" 
            + ") ENGINE=InnoDB";
    
 
    
    private static final String CREATE_ACCESS_PERMISSION_TABLE = 
            "CREATE TABLE access_permission (\n"
          + "   accessToken varchar(255) ,\n" 
          + "   pubKey varchar(255),\n"
          + "   refreshTime bigint,\n"
          + "   PRIMARY KEY (accessToken) ) ENGINE=InnoDB";
    
    private static final String CREATE_ACCESS_GRANT_TABLE = 
            "CREATE TABLE access_grant (\n"
          + "   address varchar(255),\n"
          + "   createTime bigint,\n"
          + "   PRIMARY KEY (address) ) ENGINE=InnoDB";

    

    private static final String CREATE_CONTRACT_EVENT_TABLE = "CREATE TABLE contractevent (\n"
            + "    blockhash binary(32) NOT NULL,\n" 
            + "    collectinghash binary(32) NOT NULL,\n" 
            + "    contracttokenid varchar(255) NOT NULL,\n" 
             + "   targetcoinvalue mediumblob,\n" 
            + "    targettokenid varchar(255),\n" 
                // public address  will receive the targettokens
                // on completion or returned   tokens on cancels 
            + "    beneficiaryaddress varchar(255) NOT NULL,\n" 
            // true if a order block of this order is confirmed
            + "    confirmed boolean NOT NULL,\n" 
            // true if used by a confirmed  block (either
            // returned or used for another  )
            + "    spent boolean NOT NULL,\n" 
            + "    spenderblockhash  binary(32),\n" 
            + "    CONSTRAINT contractevent_pk PRIMARY KEY (blockhash, collectinghash) "
            + " USING HASH \n" + ") ENGINE=InnoDB \n";

    private static final String CREATE_CONTRACTEVENT_CANCEL_TABLE = "CREATE TABLE contracteventcancel (\n"
            + "   blockhash binary(32) NOT NULL,\n" 
            + "   eventblockhash binary(32) NOT NULL,\n"
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash binary(32),\n" 
            + "   time bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) ) ENGINE=InnoDB";

    // the contract execution result
    private static final String CREATE_CONTRACT_RESULT_TABLE = "CREATE TABLE contractresult (\n"
            + "   blockhash binary(32) NOT NULL,\n"  
            + "   contracttokenid varchar(255)  NOT NULL,\n" 
            + "   contractexecutionresult mediumblob NOT NULL,\n" 
            + "   prevblockhash binary(32) NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "   milestone bigint NOT NULL,\n"
            + "   inserttime bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) ) ENGINE=InnoDB";
   
    //  the order execution result
    private static final String CREATE_ORDER_RESULT_TABLE = "CREATE TABLE orderresult (\n"
            + "   blockhash binary(32) NOT NULL,\n"  
            + "   orderexecutionresult mediumblob NOT NULL,\n" 
            + "   prevblockhash binary(32) NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "    milestone bigint NOT NULL,\n"
            + "    inserttime bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) ) ENGINE=InnoDB";
    private static final String CREATE_CHAINBLOCKQUEUE_TABLE = "CREATE TABLE chainblockqueue (\n" 
            + "    hash binary(32) NOT NULL,\n" 
            + "    block mediumblob NOT NULL,\n" 
            + "    chainlength bigint,\n " 
            + "    orphan boolean,\n " 
            + "    inserttime bigint NOT NULL,\n"
            + "    CONSTRAINT chainblockqueue_pk PRIMARY KEY (hash)  \n" + ") ENGINE=InnoDB \n";
    private static final String CREATE_LOCKOBJECT_TABLE = "CREATE TABLE lockobject (\n" 
            + "    lockobjectid varchar(255) NOT NULL,\n"  
            + "    locktime bigint NOT NULL,\n"
            + "    CONSTRAINT lockobject_pk PRIMARY KEY (lockobjectid)  \n" + ") ENGINE=InnoDB \n";
    
    private static final String CREATE_ANCHOR_TABLE = "CREATE TABLE anchor (\n"
            + "    chainId varchar(255) NOT NULL,\n"
            + "    l1RewardHeadHash varchar(255) NOT NULL,\n"
            + "    l1Height bigint NOT NULL,\n"
            + "    confirmedRoot varchar(255),\n"
            + "    signatureHex varchar(1024),\n"
            + "    blockHash varchar(255) NOT NULL,\n"
            + "    confirmed tinyint(1) NOT NULL DEFAULT 0,\n"
            + "    PRIMARY KEY (chainId, l1Height)\n) ENGINE=InnoDB";

    private static final String CREATE_VAULT_TABLE = "CREATE TABLE vault (\n"
            + "    chainId varchar(255) NOT NULL,\n"
            + "    utxoBlockHash varchar(255) NOT NULL,\n"
            + "    utxoIndex bigint NOT NULL,\n"
            + "    amount bigint NOT NULL,\n"
            + "    tokenIdHex varchar(255),\n"
            + "    ownerAddress varchar(255),\n"
            + "    spent boolean NOT NULL DEFAULT false,\n"
            + "    PRIMARY KEY (chainId, utxoBlockHash, utxoIndex)\n) ENGINE=InnoDB";
    
    // Some indexes to speed up stuff
    private static final String CREATE_OUTPUTS_ADDRESS_MULTI_INDEX = "CREATE INDEX outputs_hash_index_toaddress_idx ON outputs (hash, outputindex, toaddress) USING HASH";
    private static final String CREATE_OUTPUTS_TOADDRESS_INDEX = "CREATE INDEX outputs_toaddress_idx ON outputs (toaddress) USING HASH";
    private static final String CREATE_OUTPUTS_FROMADDRESS_INDEX = "CREATE INDEX outputs_fromaddress_idx ON outputs (fromaddress) USING HASH";
    
    private static final String CREATE_PREVBRANCH_HASH_INDEX = "CREATE INDEX blocks_prevbranchblockhash_idx ON blocks (prevbranchblockhash) USING HASH";
    private static final String CREATE_PREVTRUNK_HASH_INDEX = "CREATE INDEX blocks_prevblockhash_idx ON blocks (prevblockhash) USING HASH";
    
    private static final String CREATE_ORDERS_COLLECTINGHASH_TABLE_INDEX = "CREATE INDEX orders_collectinghash_idx ON orders (collectinghash) USING btree";
    private static final String CREATE_BLOCKS_MILESTONE_INDEX = "CREATE INDEX blocks_milestone_idx ON blocks (milestone)  USING btree ";
    private static final String CREATE_BLOCKS_HEIGHT_INDEX = "CREATE INDEX blocks_height_idx ON blocks (height)  USING btree ";
    private static final String CREATE_TXREARD_CHAINLENGTH_INDEX = "CREATE INDEX txreard_chainlength_idx ON txreward (chainlength, confirmed)  ";
    private static final String CREATE_CONTRACT_EVENT_CONTRACTTOKENID_TABLE_INDEX = "CREATE INDEX contractevent_contracttokenid_idx ON contractevent (contracttokenid) USING btree";
    private static final String CREATE_CONTRACT_EVENT_COLLECTINGHASH_TABLE_INDEX = "CREATE INDEX contractevent_collectinghash_idx ON contractevent (collectinghash)";
    private static final String CREATE_CONTRACT_EXECUTION_CONTRACTTOKENID_TABLE_INDEX = "CREATE INDEX contractresult_contracttokenid_idx ON contractresult (contracttokenid) USING btree";
    private static final String CREATE_ORDERS_SPENT_TABLE_INDEX = "CREATE INDEX orders_spent_idx ON orders (confirmed, spent) ";
    private static final String CREATE_MATCHING_TOKEN_TABLE_INDEX = "CREATE INDEX matching_inserttime_idx ON matching (inserttime) ";
      
    private static final String CREATE_TOKEN_TOKENID_TABLE_INDEX = "CREATE INDEX tokens_tokenid_idx ON tokens (tokenid) ";
    private static final String CREATE_BLOCKS_MILESTONE_CONFIRMED_INDEX = "CREATE INDEX blocks_milestone_confirmed_idx ON blocks (milestone, confirmed)   ";
    
    
  
    public MySQLFullBlockStore(NetworkParameters params, Connection conn, MinioService minioService )  {
            super(  params,   conn, minioService);
        }

    @Override
    protected String getDuplicateKeyErrorCode() {
        return MYSQL_DUPLICATE_KEY_ERROR_CODE;
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
        sqlStatements.add(CREATE_ANCHOR_TABLE);
        sqlStatements.add(CREATE_VAULT_TABLE);
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
       if("05".equals(ver)) {
           List<String> anchorTable = new ArrayList<String>();
           anchorTable.add(CREATE_ANCHOR_TABLE);
           updateTables(anchorTable);
           dbupdateversion("06");
       }
       if("06".equals(ver)) {
           List<String> vaultTable = new ArrayList<String>();
           vaultTable.add(CREATE_VAULT_TABLE);
           updateTables(vaultTable);
           dbupdateversion("07");
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
