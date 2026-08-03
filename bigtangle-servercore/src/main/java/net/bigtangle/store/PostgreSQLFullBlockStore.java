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
            + "    blocktype VARCHAR(50) NOT NULL,\n" 
            //reward block chain length is here
            + "    chainlength bigint NOT NULL,\n"
            + "    chainlengthlastupdate bigint NOT NULL,\n"  
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
            + "    tokenid TEXT,\n" 
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
            + "    spendpendingtime bigint\n" 
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
            + "   chainlength bigint NOT NULL,\n" 
            + "   PRIMARY KEY (blockhash) )";

    private static final String CREATE_ORDERS_TABLE = "CREATE TABLE orders (\n"
                // initial issuing block  hash
            + "    blockhash BYTEA NOT NULL,\n" 
                // ZEROHASH if confirmed by order blocks,
                // issuing ordermatch blockhash if issued by ordermatch block
            + "    collectinghash BYTEA NOT NULL,\n" 
            + "    offercoinvalue bigint NOT NULL,\n" 
            + "    offertokenid TEXT,\n" 
             + "   targetcoinvalue bigint,\n" 
            + "    targettokenid TEXT,\n" 
                // buy or sell
            + "    side varchar(255),\n" 
                // public address
            + "    beneficiaryaddress TEXT,\n" 
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
            + "    orderbasetoken TEXT,\n" 
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
            + "    tokenid TEXT NOT NULL,\n" 
            + "    basetokenid TEXT NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY (id) \n" 
            + ")\n";

    private static final String CREATE_MATCHINGDAILY_TABLE = "CREATE TABLE matchingdaily (\n"
    		   + "    id SERIAL,\n" // Changed AUTO_INCREMENT to SERIAL
    		   + "    matchday varchar(255) NOT NULL,\n"
    		   + "    tokenid TEXT NOT NULL,\n"
    		   + "    basetokenid TEXT NOT NULL,\n"
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
            + "    id SERIAL PRIMARY KEY,\n"
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid TEXT NOT NULL,\n" 
            + "    basetokenid TEXT NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    token_basetoken_md5 UUID NOT NULL,\n"
            + "    CONSTRAINT matchinglast_unique UNIQUE (token_basetoken_md5)\n"
            + ")\n";
    private static final String CREATE_MATCHING_LAST_DAY_TABLE = "CREATE TABLE matchinglastday (\n" 
            + "    txhash varchar(255) NOT NULL,\n"
            + "    tokenid TEXT NOT NULL,\n" 
            + "    basetokenid TEXT NOT NULL,\n" 
            + "    price bigint NOT NULL,\n"
            + "    executedQuantity bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "    PRIMARY KEY ( tokenid,basetokenid) \n" 
            + ")\n";
    
    private static final String CREATE_TOKENS_TABLE = "CREATE TABLE tokens (\n"
    	    + "    blockhash BYTEA NOT NULL,\n"
    		+ "    confirmed boolean NOT NULL,\n"
    	    + "    tokenid TEXT NOT NULL  ,\n"
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
    	    + "    id SERIAL PRIMARY KEY,\n"
    	    + "    blockhash BYTEA NOT NULL,\n"
    	    + "    tokenid TEXT NOT NULL  ,\n"
    	    + "    address varchar(255),\n"
    	    + "    pubKeyHex TEXT,\n"
    	    + "    posIndex int,\n"
    	    + "    tokenHolder int NOT NULL DEFAULT 0\n"
    	    + ")";
    private static final String CREATE_MULTISIGNADDRESS_UNIQ_INDEX = "CREATE UNIQUE INDEX multisignaddress_uniq ON multisignaddress (blockhash, md5(tokenid), md5(pubKeyHex))";
    private static final String CREATE_MULTISIGNADDRESS_TOKENID_INDEX = "CREATE INDEX multisignaddress_tokenid_idx ON multisignaddress (md5(tokenid))";

 
    private static final String CREATE_MULTISIGN_TABLE = "CREATE TABLE multisign (\n"
    	    + "    id varchar(255) NOT NULL  ,\n"
    	    + "    tokenid TEXT NOT NULL  ,\n"
    	    + "    tokenindex bigint NOT NULL   ,\n"
    	    + "    address varchar(255),\n"
    	    + "    blockhash  BYTEA NOT NULL,\n"
    	    + "    sign int NOT NULL,\n" // Removed (11) from int
    	    + "    PRIMARY KEY (id) \n)";

    private static final String CREATE_PAYMULTISIGN_TABLE = "CREATE TABLE paymultisign (\n"
            + "    orderid varchar(255) NOT NULL  ,\n" 
            + "    tokenid TEXT NOT NULL  ,\n"
            + "    toaddress varchar(255) NOT NULL,\n" 
            + "    blockhash BYTEA NOT NULL,\n"
            + "    amount BYTEA ,\n" 
            + "    minsignnumber bigint,\n" 
            + "    outputHashHex varchar(255) ,\n"
            + "    outputindex bigint ,\n" + "    PRIMARY KEY (orderid) \n)";

    private static final String CREATE_PAYMULTISIGNADDRESS_TABLE = "CREATE TABLE paymultisignaddress (\n"
            + "    id SERIAL PRIMARY KEY,\n"
            + "    orderid varchar(255) NOT NULL  ,\n" 
            + "    pubKey TEXT,\n" 
            + "    sign int NOT NULL,\n"
            + "    signIndex int NOT NULL,\n" 
            + "    signInputData BYTEA\n"
            + ")";
    //aggregate of utxo for view only
    private static final String CREATE_ACCOUNT_TABLE = "CREATE TABLE accountBalance (\n" 
            + "    id SERIAL PRIMARY KEY,\n"
            + "    address varchar(255),\n"
            + "    tokenid TEXT,\n" 
            + "    coinvalue BYTEA NOT NULL,\n" 
            + "    time bigint,\n" 
            + "    lastblockhash  BYTEA NOT NULL,\n" 
            + "    address_tokenid_md5 UUID NOT NULL,\n"
            + "    CONSTRAINT account_unique UNIQUE (address_tokenid_md5)\n"
            + "   ) \n";

    
    private static final String CREATE_USERDATA_TABLE = "CREATE TABLE userdata (\n"
            + "    id SERIAL PRIMARY KEY,\n"
            + "    blockhash BYTEA NOT NULL,\n" 
            + "    dataclassname varchar(255) NOT NULL,\n"
            + "    data BYTEA NOT NULL,\n" 
            + "    pubKey TEXT,\n" 
            + "    blocktype bigint\n"
            + ")";

 
    private static final String CREATE_POS_STATE_TABLE = "CREATE TABLE pos_state (\n"
            + "    id SERIAL PRIMARY KEY,\n"
            + "    service VARCHAR(64) NOT NULL,\n"
            + "    key TEXT NOT NULL,\n"
            + "    value BYTEA,\n"
            + "    service_key_md5 UUID NOT NULL,\n"
            + "    CONSTRAINT pos_state_unique UNIQUE (service_key_md5)\n"
            + ")";

    private static final String CREATE_ATTESTATION_VOTES_TABLE = "CREATE TABLE attestation_votes (\n"
            + "    id SERIAL PRIMARY KEY,\n"
            + "    blockhash BYTEA NOT NULL,\n"
            + "    pubkey BYTEA NOT NULL,\n"
            + "    weight BIGINT NOT NULL,\n"
            + "    slot BIGINT NOT NULL,\n"
            + "    inserted TIMESTAMP DEFAULT CURRENT_TIMESTAMP,\n"
            + "    pubkey_blockhash_md5 UUID NOT NULL,\n"
            + "    CONSTRAINT attestation_votes_unique UNIQUE (pubkey_blockhash_md5)\n"
            + ")";

    private static final String CREATE_STAKE_DEPOSITS_TABLE = "CREATE TABLE stake_deposits (\n"
    		+ "    id SERIAL PRIMARY KEY,\n"
    		+ "    pubkey BYTEA NOT NULL,\n"
    		+ "    amount BIGINT NOT NULL,\n"
    		+ "    withdrawal_credentials BYTEA,\n"
    		+ "    activated_epoch BIGINT DEFAULT -1,\n"
    		+ "    slashed BOOLEAN DEFAULT FALSE,\n"
    		+ "    withdrawable_epoch BIGINT DEFAULT -1,\n"
    		+ "    blockhash BYTEA,\n"
    		+ "    pubkey_md5 UUID NOT NULL,\n"
    		+ "    CONSTRAINT stake_deposits_unique UNIQUE (pubkey_md5)\n"
    		+ ")";

    private static final String CREATE_BATCHBLOCK_TABLE = "CREATE TABLE batchblock (\n"
    		+ "    hash BYTEA NOT NULL,\n"
    		+ "    block BYTEA NOT NULL,\n"
    		+ "    inserttime TIMESTAMP NOT NULL,\n"
    		+ "   CONSTRAINT batchblock_pk PRIMARY KEY (hash)  \n"
    		+ ")";
    private static final String CREATE_TRANSACTIONSTATUS_TABLE = "CREATE TABLE transactionstatus (\n"
            + "    txhash BYTEA NOT NULL,\n"
            + "    status varchar(20) NOT NULL,\n"
            + "    blockhash BYTEA,\n"
            + "    chainlength bigint,\n"
            + "    address varchar(64),\n"
            + "    createdtime bigint NOT NULL,\n"
            + "    updatedtime bigint NOT NULL,\n"
            + "    CONSTRAINT transactionstatus_pk PRIMARY KEY (txhash)\n"
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
          + "   pubKey TEXT,\n"
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
            + "    contracttokenid TEXT NOT NULL,\n" 
             + "   targetcoinvalue BYTEA,\n" 
            + "    targettokenid TEXT,\n" 
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
            + "   contracttokenid TEXT  NOT NULL,\n" 
            + "   contractresult BYTEA NOT NULL,\n" 
            + "   prevblockhash BYTEA NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash BYTEA,\n" 
            + "    rewardchainlength bigint NOT NULL,\n"
            + "   chainlength bigint NOT NULL,\n" 
            + "    inserttime bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) )";

    // one EVM receipt per EVM transaction block (EVM world state is persisted
    // in the contractresult blob as the engine's extraData)
    private static final String CREATE_EVM_RECEIPT_TABLE = "CREATE TABLE IF NOT EXISTS evm_receipt (\n"
            + "   blockhash BYTEA NOT NULL,\n"  
            + "   contracttokenid TEXT  NOT NULL,\n" 
            + "   receipt BYTEA NOT NULL,\n" 
            + "   inserttime bigint NOT NULL,\n"
            + "   PRIMARY KEY (blockhash) )";

    //  the order execution result
    private static final String CREATE_ORDER_RESULT_TABLE = "CREATE TABLE orderresult (\n"
            + "   blockhash BYTEA NOT NULL,\n"  
            + "   orderresult BYTEA NOT NULL,\n" 
            + "   prevblockhash BYTEA NOT NULL,\n" 
            + "   confirmed boolean NOT NULL,\n" 
            + "   spent boolean NOT NULL,\n"
            + "   spenderblockhash BYTEA,\n" 
            + "    rewardchainlength bigint NOT NULL,\n"
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
        private static final String CREATE_TIPSQUEUE_TABLE = "CREATE TABLE tipsqueue (\n" 
            + "    hash BYTEA NOT NULL,\n" 
            + "    block BYTEA NOT NULL,\n" 
            + "    height bigint NOT NULL,\n " 
            + "    inserttime bigint NOT NULL,\n"
            + "    CONSTRAINT tipsqueue_pk PRIMARY KEY (hash)  \n" + ") \n";

    private static final String CREATE_ANCHOR_TABLE = "CREATE TABLE IF NOT EXISTS anchor (\n"
            + "    chainId varchar(255) NOT NULL,\n"
            + "    eventId varchar(255),\n"
            + "    l1RewardHeadHash varchar(255) NOT NULL,\n"
            + "    l1Height bigint NOT NULL,\n"
            + "    confirmedRoot varchar(255),\n"
            + "    signatureHex TEXT,\n"
            + "    spvProofHex TEXT,\n"
            + "    burnJson TEXT,\n"
            + "    blockHash varchar(255) NOT NULL,\n"
            + "    confirmed boolean NOT NULL DEFAULT false,\n"
            + "    PRIMARY KEY (chainId, l1Height)\n)";

    private static final String CREATE_VAULT_TABLE = "CREATE TABLE IF NOT EXISTS vault (\n"
            + "    chainId varchar(255) NOT NULL,\n"
            + "    utxoBlockHash varchar(255) NOT NULL,\n"
            + "    utxoIndex bigint NOT NULL,\n"
            + "    amount bigint NOT NULL,\n"
            + "    tokenIdHex TEXT,\n"
            + "    ownerAddress varchar(255),\n"
            + "    spent boolean NOT NULL DEFAULT false,\n"
            + "    PRIMARY KEY (chainId, utxoBlockHash, utxoIndex)\n)";

    // Some indexes to speed up stuff
    private static final String CREATE_OUTPUTS_BRIN_INDEX = "CREATE INDEX outputs_blockhash_brin_idx ON outputs USING brin(blockhash) WITH (pages_per_range=32) ";
    private static final String CREATE_OUTPUTS_ADDRESS_MULTI_INDEX = "CREATE INDEX outputs_hash_index_toaddress_idx ON outputs (hash, outputindex, toaddress) ";
    private static final String CREATE_OUTPUTS_TOADDRESS_INDEX = "CREATE INDEX outputs_toaddress_idx ON outputs (toaddress) ";
    private static final String CREATE_OUTPUTS_FROMADDRESS_INDEX = "CREATE INDEX outputs_fromaddress_idx ON outputs (fromaddress) ";
    
    private static final String CREATE_PREVBRANCH_HASH_INDEX = "CREATE INDEX blocks_prevbranchblockhash_idx ON blocks (prevbranchblockhash) ";
    private static final String CREATE_PREVTRUNK_HASH_INDEX = "CREATE INDEX blocks_prevblockhash_idx ON blocks (prevblockhash) ";
    
    private static final String CREATE_ORDERS_COLLECTINGHASH_TABLE_INDEX = "CREATE INDEX orders_collectinghash_idx ON orders (collectinghash) ";
    private static final String CREATE_BLOCKS_CHAINLENGTH_INDEX = "CREATE INDEX blocks_chainlength_idx ON blocks (chainlength)   ";
    private static final String CREATE_BLOCKS_HEIGHT_INDEX = "CREATE INDEX blocks_height_idx ON blocks (height)   ";
    private static final String CREATE_BLOCKS_SOLID_HEIGHT_INDEX = "CREATE INDEX blocks_solid_height_idx ON blocks (solid, height)   ";
    private static final String CREATE_TXREARD_CHAINLENGTH_INDEX = "CREATE INDEX txreard_chainlength_idx ON txreward (chainlength, confirmed)  ";
    private static final String CREATE_CONTRACT_EVENT_CONTRACTTOKENID_TABLE_INDEX = "CREATE INDEX contractevent_contracttokenid_idx ON contractevent (contracttokenid) ";
    private static final String CREATE_CONTRACT_EVENT_COLLECTINGHASH_TABLE_INDEX = "CREATE INDEX contractevent_collectinghash_idx ON contractevent (collectinghash)";
    private static final String CREATE_CONTRACT_EXECUTION_CONTRACTTOKENID_TABLE_INDEX = "CREATE INDEX contractresult_contracttokenid_idx ON contractresult (contracttokenid) ";
    private static final String CREATE_ORDERS_SPENT_TABLE_INDEX = "CREATE INDEX orders_spent_idx ON orders (confirmed, spent) ";
    private static final String CREATE_MATCHING_TOKEN_TABLE_INDEX = "CREATE INDEX matching_inserttime_idx ON matching (inserttime) ";
      
    private static final String CREATE_TOKEN_TOKENID_TABLE_INDEX = "CREATE INDEX tokens_tokenid_idx ON tokens (md5(tokenid)) ";
    private static final String CREATE_BLOCKS_CHAINLENGTH_CONFIRMED_INDEX = "CREATE INDEX blocks_chainlength_confirmed_idx ON blocks (chainlength, confirmed)   ";
    
    
  
    public PostgreSQLFullBlockStore(NetworkParameters params, Connection conn )  {
        super(  params,   conn);
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
        sqlStatements.add(CREATE_STAKE_DEPOSITS_TABLE);
        sqlStatements.add(CREATE_ATTESTATION_VOTES_TABLE);
        sqlStatements.add(CREATE_POS_STATE_TABLE);
        sqlStatements.add(CREATE_BATCHBLOCK_TABLE);
        sqlStatements.add(CREATE_TRANSACTIONSTATUS_TABLE);
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
        sqlStatements.add(CREATE_EVM_RECEIPT_TABLE);
        sqlStatements.add(CREATE_CHAINBLOCKQUEUE_TABLE);
        sqlStatements.add(CREATE_LOCKOBJECT_TABLE);
        sqlStatements.add(CREATE_MATCHINGDAILY_TABLE);
        sqlStatements.add(CREATE_ACCOUNT_TABLE);
        sqlStatements.add(CREATE_TIPSQUEUE_TABLE);
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
        sqlStatements.add(CREATE_OUTPUTS_BRIN_INDEX);
        sqlStatements.add(CREATE_OUTPUTS_ADDRESS_MULTI_INDEX); 
        sqlStatements.add(CREATE_BLOCKS_HEIGHT_INDEX);
        sqlStatements.add(CREATE_BLOCKS_SOLID_HEIGHT_INDEX);
        sqlStatements.add(CREATE_OUTPUTS_TOADDRESS_INDEX);
        sqlStatements.add(CREATE_PREVBRANCH_HASH_INDEX);
        sqlStatements.add(CREATE_PREVTRUNK_HASH_INDEX);

        sqlStatements.add(CREATE_ORDERS_COLLECTINGHASH_TABLE_INDEX);
        sqlStatements.add(CREATE_BLOCKS_CHAINLENGTH_INDEX);
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
        sqlStatements.add(CREATE_MULTISIGNADDRESS_UNIQ_INDEX);
        sqlStatements.add(CREATE_MULTISIGNADDRESS_TOKENID_INDEX);
        sqlStatements.add(CREATE_BLOCKS_CHAINLENGTH_CONFIRMED_INDEX);
        return sqlStatements;
    }


}
