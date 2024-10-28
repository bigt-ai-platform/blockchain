/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.io.IOException;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.BlockMCMC;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.ProtocolException;
import net.bigtangle.core.exception.UTXOProviderException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.script.Script;
import net.bigtangle.server.core.BlockWrap;
import net.bigtangle.server.data.DepthAndWeight;
import net.bigtangle.server.data.Rating;
import net.bigtangle.utils.Gzip;

/**
 * <p>
 * A generic full block store for a relational database. This generic class
 * requires certain table structures for the block store.
 * </p>
 * 
 */
public abstract class DatabaseFullBlockStoreBase implements FullBlockStore {

	private static final String LIMIT_500 = " limit 500 ";

	protected static final Logger log = LoggerFactory.getLogger(DatabaseFullBlockStoreBase.class);

	public static final String VERSION_SETTING = "version";

	// Drop table SQL.
	private static String DROP_SETTINGS_TABLE = "DROP TABLE IF EXISTS settings";
	private static String DROP_BLOCKS_TABLE = "DROP TABLE IF EXISTS blocks";
	private static String DROP_OPEN_OUTPUT_TABLE = "DROP TABLE IF EXISTS outputs";
	private static String DROP_OUTPUTSMULTI_TABLE = "DROP TABLE IF EXISTS outputsmulti";
	private static String DROP_TOKENS_TABLE = "DROP TABLE IF EXISTS tokens";
	private static String DROP_MATCHING_TABLE = "DROP TABLE IF EXISTS matching";
	private static String DROP_MULTISIGNADDRESS_TABLE = "DROP TABLE IF EXISTS multisignaddress";
	private static String DROP_MULTISIGNBY_TABLE = "DROP TABLE IF EXISTS multisignby";
	private static String DROP_MULTISIGN_TABLE = "DROP TABLE IF EXISTS multisign";
	private static String DROP_TX_REWARDS_TABLE = "DROP TABLE IF EXISTS txreward";
	private static String DROP_USERDATA_TABLE = "DROP TABLE IF EXISTS userdata";
	private static String DROP_PAYMULTISIGN_TABLE = "DROP TABLE IF EXISTS paymultisign";
	private static String DROP_PAYMULTISIGNADDRESS_TABLE = "DROP TABLE IF EXISTS paymultisignaddress";

	private static String DROP_ORDERCANCEL_TABLE = "DROP TABLE IF EXISTS ordercancel";
	private static String DROP_BATCHBLOCK_TABLE = "DROP TABLE IF EXISTS batchblock";
	private static String DROP_SUBTANGLE_PERMISSION_TABLE = "DROP TABLE IF EXISTS subtangle_permission";
	private static String DROP_ORDERS_TABLE = "DROP TABLE IF EXISTS orders";

	private static String DROP_MYSERVERBLOCKS_TABLE = "DROP TABLE IF EXISTS myserverblocks";

	private static String DROP_ACCESS_PERMISSION_TABLE = "DROP TABLE  IF EXISTS access_permission";
	private static String DROP_ACCESS_GRANT_TABLE = "DROP TABLE  IF EXISTS access_grant";
	private static String DROP_CONTRACT_EVENT_TABLE = "DROP TABLE  IF EXISTS contractevent";
	private static String DROP_CONTRACT_EVENT_CANCEL_TABLE = "DROP TABLE  IF EXISTS contracteventcancel";
	private static String DROP_CONTRACT_RESULT_TABLE = "DROP TABLE IF EXISTS contractresult";
	private static String DROP_ORDER_RESULT_TABLE = "DROP TABLE IF EXISTS orderresult";
	private static String DROP_CHAINBLOCKQUEUE_TABLE = "DROP TABLE  IF EXISTS chainblockqueue";

	private static String DROP_MCMC_TABLE = "DROP TABLE  IF EXISTS mcmc";
	private static String DROP_LOCKOBJECT_TABLE = "DROP TABLE  IF EXISTS lockobject";
	private static String DROP_MATCHING_LAST_TABLE = "DROP TABLE  IF EXISTS matchinglast";
	private static String DROP_MATCHINGDAILY_TABLE = "DROP TABLE  IF EXISTS matchingdaily";
	private static String DROP_MATCHINGLASTDAY_TABLE = "DROP TABLE  IF EXISTS matchinglastday";
	private static String DROP_ACCOUNT_TABLE = "DROP TABLE  IF EXISTS accountBalance";
	// Queries SQL.
	protected final String SELECT_SETTINGS_SQL = "SELECT settingvalue FROM settings WHERE name = ?";
	protected final String INSERT_SETTINGS_SQL = getInsert() + "  INTO settings(name, settingvalue) VALUES(?, ?)";

	protected final String SELECT_BLOCKS_TEMPLATE = "  blocks.hash, block,  "
			+ "  height, milestone, milestonelastupdate,  inserttime,   solid, confirmed";

	protected final String SELECT_BLOCKS_SQL = " select " + SELECT_BLOCKS_TEMPLATE + " FROM blocks WHERE hash = ?"
			+ afterSelect();

	protected final String SELECT_BLOCKS_MILESTONE_SQL = "SELECT block, height FROM blocks WHERE height "
			+ " >= (select min(height) from blocks where  milestone >= ? and  milestone <=?)"
			+ " and height <= (select max(height) from blocks where  milestone >= ? and  milestone <=?) "
			+ afterSelect() + " order by height asc ";

	protected final String SELECT_MCMC_TEMPLATE = "  hash, rating, depth, cumulativeweight ";

	protected final String SELECT_NOT_INVALID_APPROVER_BLOCKS_SQL = "SELECT " + SELECT_BLOCKS_TEMPLATE
			+ "  , rating, depth, cumulativeweight "
			+ "  FROM blocks, mcmc WHERE blocks.hash= mcmc.hash and (prevblockhash = ? or prevbranchblockhash = ?) AND solid >= 0 "
			+ afterSelect();

	protected final String SELECT_SOLID_APPROVER_BLOCKS_SQL = "SELECT" + SELECT_BLOCKS_TEMPLATE
			+ " ,  rating, depth, cumulativeweight "
			+ " FROM blocks, mcmc WHERE blocks.hash= mcmc.hash and (prevblockhash = ? or prevbranchblockhash = ?) AND solid = 2 "
			+ afterSelect();

	protected final String SELECT_SOLID_APPROVER_HASHES_SQL = "SELECT hash FROM blocks "
			+ "WHERE blocks.prevblockhash = ? or blocks.prevbranchblockhash = ?" + afterSelect();

	protected final String INSERT_BLOCKS_SQL = getInsert() + "  INTO blocks(hash,  height, block,  prevblockhash,"
			+ "prevbranchblockhash,mineraddress,blocktype,  "
			+ "milestone, milestonelastupdate,  inserttime,  solid, confirmed  )"
			+ " VALUES(?, ?, ?, ?, ?,?, ?, ?, ?, ? ,  ?, ? )";

	protected final String INSERT_OUTPUTS_SQL = getInsert()
			+ " INTO outputs (hash, outputindex, coinvalue, scriptbytes, toaddress, addresstargetable,"
			+ " coinbase, blockhash, tokenid, fromaddress, memo, spent, confirmed, spendpending,time, spendpendingtime, minimumsign)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?)";

	protected final String SELECT_OUTPUTS_SQL = "SELECT coinvalue, scriptbytes, coinbase, toaddress,"
			+ " addresstargetable, blockhash, tokenid, fromaddress, memo, spent, confirmed, "
			+ "spendpending , spendpendingtime, minimumsign, time, spenderblockhash FROM outputs WHERE hash = ? AND outputindex = ? AND blockhash = ? ";

	protected final String SELECT_TRANSACTION_OUTPUTS_SQL_BASE = "SELECT " + "outputs.hash, coinvalue, scriptbytes, "
			+ " outputs.outputindex, coinbase, " + "  outputs.toaddress  as  toaddress,"
			+ " outputsmulti.toaddress  as multitoaddress, " + "  addresstargetable, blockhash, tokenid, "
			+ " fromaddress, memo, spent, confirmed, "
			+ "spendpending,spendpendingtime,  minimumsign, time , spenderblockhash "
			+ " FROM outputs LEFT JOIN outputsmulti " + " ON outputs.hash = outputsmulti.hash"
			+ " AND outputs.outputindex = outputsmulti.outputindex ";

	protected final String SELECT_OPEN_TRANSACTION_OUTPUTS_SQL = SELECT_TRANSACTION_OUTPUTS_SQL_BASE
			+ " WHERE  confirmed=true and spent= false and outputs.toaddress = ? " + " OR outputsmulti.toaddress = ?";

	protected final String SELECT_OPEN_TRANSACTION_OUTPUTS_TOKEN_SQL = "SELECT " + " outputs.hash, coinvalue, "
			+ " scriptbytes, outputs.outputindex, coinbase, outputs.toaddress as toaddress , addresstargetable,"
			+ " blockhash, tokenid, fromaddress, memo, spent, confirmed, spendpending, spendpendingtime, minimumsign, time , spenderblockhash"
			+ " , outputsmulti.toaddress  as multitoaddress" + " FROM outputs LEFT JOIN outputsmulti "
			+ " ON outputs.hash = outputsmulti.hash AND outputs.outputindex = outputsmulti.outputindex "
			+ " WHERE   (outputs.toaddress = ? " + " OR outputsmulti.toaddress = ?) " + " AND tokenid = ?";
	public final String SELECT_ALL_OUTPUTS_TOKEN_SQL = "SELECT " + " outputs.hash, coinvalue, "
			+ " scriptbytes, outputs.outputindex, coinbase, outputs.toaddress, addresstargetable,"
			+ " blockhash, tokenid, fromaddress, memo, spent, confirmed, spendpending, spendpendingtime , minimumsign, time , spenderblockhash"
			+ " FROM outputs  WHERE  confirmed=true and spent= false and tokenid = ?";

	// Tables exist SQL.
	protected final String SELECT_CHECK_TABLES_EXIST_SQL = "SELECT * FROM settings WHERE 1 = 2";

	protected final String SELECT_BLOCKS_MCMC_CONFIRM = "SELECT" + SELECT_BLOCKS_TEMPLATE
			+ " FROM blocks, mcmc  WHERE blocks.hash=mcmc.hash and solid=2 AND milestone = -1 AND confirmed = false AND height > ?"
			+ " AND height <= ? AND mcmc.rating >= " + NetworkParameters.CONFIRMATION_UPPER_THRESHOLD + afterSelect();

	protected final String SELECT_BLOCKS_TO_UNCONFIRM_SQL = "SELECT" + SELECT_BLOCKS_TEMPLATE
			+ "  FROM blocks , mcmc WHERE blocks.hash=mcmc.hash and solid=2 AND milestone = -1 AND confirmed = true AND mcmc.rating < "
			+ NetworkParameters.CONFIRMATION_LOWER_THRESHOLD + afterSelect();

	protected final String SELECT_BLOCKS_IN_MILESTONE_INTERVAL_SQL = "SELECT hash "  
			+ "  FROM blocks WHERE milestone >= ? AND milestone <= ?" + afterSelect();

	protected final String SELECT_SOLID_BLOCKS_IN_INTERVAL_SQL = "SELECT   " + SELECT_BLOCKS_TEMPLATE
			+ " FROM blocks WHERE   height > ? AND height <= ? AND solid = 2 " + afterSelect();

	protected final String SELECT_BLOCKS_CONFIRMED_AND_NOT_MILESTONE_SQL = "SELECT hash "
			+ "FROM blocks WHERE milestone = -1 AND confirmed = 1 " + afterSelect();

	protected final String SELECT_BLOCKS_NON_CHAIN_HEIGTH_SQL = "SELECT block "
			+ "FROM blocks WHERE milestone = -1 AND height >= ? " + afterSelect();

	protected final String UPDATE_ORDER_SPENT_SQL = getUpdate() + " orders SET spent = ?, spenderblockhash = ?, confirmed =true "
			+ " WHERE blockhash = ? AND collectinghash = ?";
	
	protected final String UPDATE_ORDER_UNSPENT_SQL = getUpdate() + " orders SET spent = false, spenderblockhash = null "
			+ " WHERE spenderblockhash = ?";
	
	protected final String UPDATE_ORDER_CONFIRMED_SQL = getUpdate() + " orders SET confirmed = ? "
			+ " WHERE blockhash = ? AND collectinghash = ?";
 
	protected final String ORDER_TEMPLATE = "  blockhash, collectinghash, offercoinvalue, offertokenid, "
			+ "confirmed, spent, spenderblockhash, targetcoinvalue, targettokenid, "
			+ "beneficiarypubkey, validToTime, validFromTime, side , beneficiaryaddress, orderbasetoken, price, tokendecimals ";
	protected final String SELECT_ORDERS_BY_ISSUER_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE collectinghash = ?";

	protected final String SELECT_ORDERS_NotSpent_BY_ISSUER_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE collectinghash = ? and spent=false";

	protected final String SELECT_ORDER_SPENT_SQL = "SELECT spent FROM orders WHERE blockhash = ? AND collectinghash = ?";
	protected final String SELECT_ORDER_CONFIRMED_SQL = "SELECT confirmed FROM orders WHERE blockhash = ? AND collectinghash = ?";
	protected final String SELECT_ORDER_SPENDER_SQL = "SELECT spenderblockhash FROM orders WHERE blockhash = ? AND collectinghash = ?";
	protected final String SELECT_ORDER_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE blockhash = ? AND collectinghash = ?";
	protected final String INSERT_ORDER_SQL = getInsert()
			+ "  INTO orders (blockhash, collectinghash, offercoinvalue, offertokenid, confirmed, spent, spenderblockhash, "
			+ "targetcoinvalue, targettokenid, beneficiarypubkey, validToTime, validFromTime, side, beneficiaryaddress, orderbasetoken, price, tokendecimals) "
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,  ?,?,?,?,?,?,?)";

	protected final String INSERT_OrderCancel_SQL = getInsert()
			+ " INTO ordercancel (blockhash, orderblockhash, confirmed, spent, spenderblockhash,time) "
			+ " VALUES (?, ?, ?, ?, ?,?)";

	protected final String INSERT_CONTRACT_EVENT_SQL = getInsert()
			+ "  INTO contractevent (blockhash, contracttokenid, confirmed, spent, spenderblockhash, "
			+ "targetcoinvalue, targettokenid,    beneficiaryaddress, collectinghash) "
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?,?)";
	protected final String CONTRACT_TEMPLATE = " blockhash, collectinghash, contracttokenid, confirmed, spent, spenderblockhash,  "
			+ "targetcoinvalue, targettokenid,    beneficiaryaddress";

	protected final String UPDATE_CONTRACT_EVENT_CONFIRMED_SQL = getUpdate() + " contractevent SET confirmed = ? "
			+ " WHERE blockhash = ?";
	protected final String UPDATE_CONTRACT_EVENT_SPENT_SQL = getUpdate()
			+ " contractevent SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ?";
	protected final String SELECT_PREV_CONTRACT_SQL = "SELECT " + CONTRACT_TEMPLATE
			+ " FROM contractevent WHERE contracttokenid = ? AND  collectinghash=? ";

	protected final String SELECT_CONTRACT_SQL = "SELECT " + CONTRACT_TEMPLATE
			+ " FROM contractevent WHERE blockhash = ? AND  collectinghash=? ";

	protected final String UPDATE_CONTRACTRESULT_SPENT_SQL = getUpdate()
			+ " contractresult SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ?";

	protected final String UPDATE_CONTRACTRESULT_CONFIRMED_SQL = getUpdate() + " contractresult SET confirmed = ? "
			+ " WHERE blockhash = ?";

	protected final String SELECT_OPEN_CONTRACTID_CONTRACT_SQL = "SELECT distinct (contracttokenid)"
			+ " FROM contractresult WHERE confirmed = true AND spent=false";

	protected final String INSERT_CONTRACT_RESULT_SQL = getInsert()
			+ "  INTO contractresult (blockhash,  contracttokenid, confirmed, spent, spenderblockhash, "
			+ " contractresult, prevblockhash, inserttime, milestone) " + " VALUES (?, ?, ?, ?, ?, ?,?,?,?)";
	protected final String SELECT_CONTRACTRESULT  = "SELECT  blockhash,  contracttokenid, confirmed, spent, spenderblockhash,  "
			+ " contractresult, prevblockhash, inserttime, milestone "
			+ " FROM contractresult ";
	protected final String SELECT_CONTRACTRESULT_HASH_SQL =SELECT_CONTRACTRESULT +"  WHERE blockhash=?   ";
	protected final String SELECT_CONTRACTRESULT_MAX_MILESTONE_SQL = SELECT_CONTRACTRESULT
			+ " WHERE confirmed = 1 and contracttokenid=? and milestone >0 and spent=false order by milestone desc limit 1";
	protected final String SELECT_CONTRACTRESULT_CONFIRMED_NOTMILESTONE_SQL = SELECT_CONTRACTRESULT
			+ " WHERE confirmed = 1 and contracttokenid=? and milestone < 0  order by inserttime desc   ";
	protected final String UPDATE_CONTRACTRESULT_MILESTONE_SQL = getUpdate()
			+ " contractresult SET milestone = ?   WHERE blockhash = ?";
	
	protected final String UPDATE_ORDERRESULT_SPENT_SQL = getUpdate()
			+ " orderresult SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ?";
	protected final String UPDATE_ORDERRESULT_CONFIRMED_SQL = getUpdate() + " orderresult SET confirmed = ? "
			+ " WHERE blockhash = ?";
	protected final String INSERT_ORDER_RESULT_SQL = getInsert()
			+ "  INTO orderresult (blockhash, confirmed, spent, spenderblockhash, "
			+ " orderresult, prevblockhash, inserttime,  milestone) " + " VALUES (?, ?, ?, ?, ?, ?,?,?)";
	protected final String SELECT_ORDERRESULT =   "  select blockhash, confirmed, spent, spenderblockhash, "
			+ " orderresult, prevblockhash, inserttime ,  milestone" 
			+ " FROM orderresult ";
	protected final String SELECT_ORDERRESULT_CONFIRMED_NOTMILESTONE_SQL = SELECT_ORDERRESULT
			+ " WHERE confirmed = 1  and milestone < 0  order by inserttime desc   ";
	
	protected final String SELECT_ORDERRESULT_HASH_SQL =   SELECT_ORDERRESULT +" WHERE blockhash=?";
	protected final String SELECT_ORDER_RESULT_MAX_MILESTONE_SQL = SELECT_ORDERRESULT
			+ " WHERE confirmed = 1 and milestone >0 and spent=false order by milestone desc limit 1";
	protected final String UPDATE_ORDERRESULT_MILESTONE_SQL = getUpdate()
			+ " orderresult SET milestone = ?   WHERE blockhash = ?";
	
	protected final String INSERT_TOKENS_SQL = getInsert()
			+ " INTO tokens (blockhash, confirmed, tokenid, tokenindex, amount, "
			+ "tokenname, description, domainname, signnumber,tokentype, tokenstop,"
			+ " prevblockhash, spent, spenderblockhash, tokenkeyvalues, revoked,language,classification, decimals, domainpredblockhash) "
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?,?,?)";

	protected String SELECT_TOKENS_SQL_TEMPLATE = "SELECT blockhash, confirmed, tokenid, tokenindex, amount, tokenname, description, domainname, signnumber,tokentype, tokenstop ,"
			+ "tokenkeyvalues, revoked,language,classification,decimals, domainpredblockhash ";

	protected final String SELECT_TOKEN_SPENT_BY_BLOCKHASH_SQL = "SELECT spent FROM tokens WHERE blockhash = ?";

	protected final String SELECT_TOKEN_CONFIRMED_SQL = "SELECT confirmed FROM tokens WHERE blockhash = ?";

	protected final String SELECT_TOKEN_ANY_CONFIRMED_SQL = "SELECT confirmed FROM tokens WHERE tokenid = ? AND tokenindex = ? AND confirmed = true";

	protected final String SELECT_TOKEN_ISSUING_CONFIRMED_BLOCK_SQL = "SELECT blockhash FROM tokens WHERE tokenid = ? AND tokenindex = ? AND confirmed = true";

	protected final String SELECT_DOMAIN_ISSUING_CONFIRMED_BLOCK_SQL = "SELECT blockhash FROM tokens WHERE tokenname = ? AND domainpredblockhash = ? AND tokenindex = ? AND confirmed = true";

	protected final String SELECT_DOMAIN_DESCENDANT_CONFIRMED_BLOCKS_SQL = "SELECT blockhash FROM tokens WHERE domainpredblockhash = ? AND confirmed = true";

	protected final String SELECT_TOKEN_SPENDER_SQL = "SELECT spenderblockhash FROM tokens WHERE blockhash = ?";

	protected final String SELECT_TOKEN_PREVBLOCKHASH_SQL = "SELECT prevblockhash FROM tokens WHERE blockhash = ?";

	protected final String SELECT_TOKEN_SQL = SELECT_TOKENS_SQL_TEMPLATE + " FROM tokens WHERE blockhash = ?";

	protected final String SELECT_TOKENID_SQL = SELECT_TOKENS_SQL_TEMPLATE + " FROM tokens WHERE tokenid = ?";

	protected final String UPDATE_TOKEN_SPENT_SQL = getUpdate() + " tokens SET spent = ?, spenderblockhash = ? "
			+ " WHERE blockhash = ?";

	protected final String UPDATE_TOKEN_CONFIRMED_SQL = getUpdate() + " tokens SET confirmed = ? "
			+ " WHERE blockhash = ?";

	protected final String SELECT_CONFIRMED_TOKENS_SQL = SELECT_TOKENS_SQL_TEMPLATE
			+ " FROM tokens WHERE confirmed = true";

	protected final String SELECT_TOKENS_TYPE_SQL = SELECT_TOKENS_SQL_TEMPLATE
			+ " FROM tokens WHERE tokentype = ? and confirmed = true";

	protected final String SELECT_TOKENS_ACOUNT_MAP_SQL = "SELECT tokenid, amount  as amount "
			+ "FROM tokens WHERE confirmed = true ";

	protected final String COUNT_TOKENSINDEX_SQL = "SELECT blockhash, tokenindex FROM tokens"
			+ " WHERE tokenid = ? AND confirmed = true ORDER BY tokenindex DESC limit 1";

	protected final String SELECT_TOKENS_BY_DOMAINNAME_SQL = "SELECT blockhash, tokenid FROM tokens WHERE blockhash = ? limit 1";

	protected final String SELECT_TOKENS_BY_DOMAINNAME_SQL0 = "SELECT blockhash, tokenid "
			+ "FROM tokens WHERE tokenname = ?  AND confirmed = true limit 1";

	protected final String UPDATE_SETTINGS_SQL = getUpdate() + " settings SET settingvalue = ? WHERE name = ?";

	protected final String UPDATE_OUTPUTS_SPENT_SQL = getUpdate()
			+ " outputs SET spent = ?, spenderblockhash = ? WHERE hash = ? AND outputindex= ? AND blockhash = ?";

	protected final String UPDATE_OUTPUTS_CONFIRMED_SQL = getUpdate()
			+ " outputs SET confirmed = ? WHERE hash = ? AND outputindex= ? AND blockhash = ?";

	protected final String UPDATE_ALL_OUTPUTS_CONFIRMED_SQL = getUpdate()
			+ " outputs SET confirmed = ? WHERE blockhash = ?";

	protected final String UPDATE_OUTPUTS_SPENDPENDING_SQL = getUpdate()
			+ " outputs SET spendpending = ?, spendpendingtime=? WHERE hash = ? AND outputindex= ? AND blockhash = ?";

	protected final String UPDATE_BLOCKEVALUATION_WEIGHT_AND_DEPTH_SQL = getUpdate()
			+ " mcmc SET cumulativeweight = ?, depth = ? WHERE hash = ?";
	protected final String INSERT_BLOCKEVALUATION_WEIGHT_AND_DEPTH_SQL = getInsert()
			+ " into mcmc ( cumulativeweight  , depth   , hash, rating  ) VALUES (?,?,?, ?)  ";

	protected final String SELECT_MCMC_CHAINLENGHT_SQL = "  select mcmc.hash "
			+ " from blocks, mcmc where mcmc.hash=blocks.hash and milestone < ?  and milestone > 0  ";

	protected final String UPDATE_BLOCKEVALUATION_MILESTONE_SQL = getUpdate()
			+ " blocks SET milestone = ?, milestonelastupdate= ?  WHERE hash = ?";

	protected final String UPDATE_BLOCKEVALUATION_CONFIRMED_SQL = getUpdate()
			+ " blocks SET confirmed = ? WHERE hash = ?";

	protected final String UPDATE_BLOCKEVALUATION_RATING_SQL = getUpdate() + " mcmc SET rating = ? WHERE hash = ?";

	protected final String UPDATE_BLOCKEVALUATION_SOLID_SQL = getUpdate() + " blocks SET solid = ? WHERE hash = ?";

	protected final String SELECT_MULTISIGNADDRESS_SQL = "SELECT blockhash, tokenid, address, pubKeyHex, posIndex, tokenHolder FROM multisignaddress WHERE tokenid = ? AND blockhash = ?";
	protected final String INSERT_MULTISIGNADDRESS_SQL = "INSERT INTO multisignaddress (tokenid, address, pubKeyHex, posIndex,blockhash,tokenHolder) VALUES (?, ?, ?, ?,?,?)";
	protected final String DELETE_MULTISIGNADDRESS_SQL = "DELETE FROM multisignaddress WHERE tokenid = ? AND address = ?";
	protected final String COUNT_MULTISIGNADDRESS_SQL = "SELECT COUNT(*) as count FROM multisignaddress WHERE tokenid = ?";

	protected final String INSERT_MULTISIGNBY_SQL = "INSERT INTO multisignby (tokenid, tokenindex, address) VALUES (?, ?, ?)";
	protected final String SELECT_MULTISIGNBY_SQL = "SELECT COUNT(*) as count FROM multisignby WHERE tokenid = ? AND tokenindex = ? AND address = ?";
	protected final String SELECT_MULTISIGNBY0_SQL = "SELECT COUNT(*) as count FROM multisignby WHERE tokenid = ? AND tokenindex = ?";

	protected final String SELECT_MULTISIGN_ADDRESS_ALL_SQL = "SELECT id, tokenid, tokenindex, address, blockhash, sign,(select count(ms1.sign) from multisign ms1 where ms1.tokenid=tokenid and tokenindex=ms1.tokenindex and ms1.sign!=0 ) as count FROM multisign  WHERE 1=1 ";
	protected final String SELECT_MULTISIGN_ADDRESS_SQL = "SELECT id, tokenid, tokenindex, address, blockhash, sign FROM multisign WHERE address = ? ORDER BY tokenindex ASC";
	protected final String SELECT_MULTISIGN_TOKENID_ADDRESS_SQL = "SELECT id, tokenid, tokenindex, address, blockhash, sign FROM multisign WHERE tokenid = ? and address = ? ORDER BY tokenindex ASC";

	protected final String INSERT_MULTISIGN_SQL = "INSERT INTO multisign (tokenid, tokenindex, address, blockhash, sign, id) VALUES (?, ?, ?, ?, ?, ?)";
	protected final String UPDATE_MULTISIGN_SQL = "UPDATE multisign SET blockhash = ?, sign = ? WHERE tokenid = ? AND tokenindex = ? AND address = ?";
	protected final String UPDATE_MULTISIGN1_SQL = "UPDATE multisign SET blockhash = ? WHERE tokenid = ? AND tokenindex = ?";
	protected final String SELECT_COUNT_MULTISIGN_SQL = "SELECT COUNT(*) as count FROM multisign WHERE tokenid = ? AND tokenindex = ? AND address = ? ";
	protected final String SELECT_COUNT_ALL_MULTISIGN_SQL = "SELECT COUNT(*) as count FROM multisign WHERE tokenid = ? AND tokenindex = ?  AND sign=?";

	protected final String DELETE_MULTISIGN_SQL = "DELETE FROM multisign WHERE tokenid = ?";

	protected final String SELECT_COUNT_MULTISIGN_SIGN_SQL = "SELECT COUNT(*) as count FROM multisign WHERE tokenid = ? AND tokenindex = ? AND sign = ?";

	/* REWARD */
	protected final String INSERT_TX_REWARD_SQL = getInsert()
			+ "  INTO txreward (blockhash, confirmed, spent, spenderblockhash, prevblockhash, difficulty, chainlength) VALUES (?, ?, ?, ?, ?, ?, ?)";
	protected final String SELECT_TX_REWARD_MAX_CONFIRMED_REWARD_SQL = "SELECT blockhash, confirmed, spent, spenderblockhash, prevblockhash, difficulty, chainlength FROM txreward"
			+ " WHERE confirmed = 1  order by chainlength desc limit 1";
	protected final String SELECT_TX_REWARD_CONFIRMED_AT_HEIGHT_REWARD_SQL = "SELECT blockhash, confirmed, spent, spenderblockhash, prevblockhash, difficulty, chainlength FROM txreward"
			+ " WHERE confirmed = 1 AND chainlength=?";
	protected final String SELECT_TX_REWARD_ALL_CONFIRMED_REWARD_SQL = "SELECT blockhash, confirmed, "
			+ "spent, spenderblockhash, prevblockhash, difficulty, chainlength FROM txreward "
			+ "WHERE confirmed = 1 order by chainlength ";

	protected final String SELECT_TX_REWARD_CONFIRMED_SQL = "SELECT confirmed " + "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_CHAINLENGTH_SQL = "SELECT chainlength "
			+ "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_DIFFICULTY_SQL = "SELECT difficulty " + "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_SPENT_SQL = "SELECT spent " + "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_SPENDER_SQL = "SELECT spenderblockhash "
			+ "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_PREVBLOCKHASH_SQL = "SELECT prevblockhash "
			+ "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_REWARD_WHERE_PREV_HASH_SQL = "SELECT blockhash "
			+ "FROM txreward WHERE prevblockhash = ?";
	protected final String UPDATE_TX_REWARD_CONFIRMED_SQL = "UPDATE txreward SET confirmed = ? WHERE blockhash = ?";
	protected final String UPDATE_TX_REWARD_SPENT_SQL = "UPDATE txreward SET spent = ?, spenderblockhash = ? WHERE blockhash = ?";

	/* MATCHING EVENTS */
	protected final String INSERT_MATCHING_EVENT_SQL = getInsert()
			+ " INTO matching (txhash, tokenid, basetokenid, price, executedQuantity, inserttime) VALUES (?, ?, ?, ?, ?, ?)";
	protected final String SELECT_MATCHING_EVENT = "SELECT txhash, tokenid,basetokenid,  price, executedQuantity, inserttime "
			+ "FROM matching ";
	protected final String DELETE_MATCHING_EVENT_BY_HASH = "DELETE FROM matching WHERE txhash = ?";
	// lastest MATCHING EVENTS
	protected final String INSERT_MATCHING_EVENT_LAST_SQL = getInsert()
			+ " INTO matchinglast (txhash, tokenid, basetokenid, price, executedQuantity, inserttime) VALUES (?, ?, ?, ?, ?, ?)";
	protected final String SELECT_MATCHING_EVENT_LAST = "SELECT txhash, tokenid,basetokenid,  price, executedQuantity, inserttime "
			+ "FROM matchinglast ";
	protected final String DELETE_MATCHING_EVENT_LAST_BY_KEY = "DELETE FROM matchinglast WHERE tokenid = ? and basetokenid=?";

	/* OTHER */
	protected final String INSERT_OUTPUTSMULTI_SQL = "insert into outputsmulti (hash, toaddress, outputindex) values (?, ?, ?)";
	protected final String SELECT_OUTPUTSMULTI_SQL = "select hash, toaddress, outputindex from outputsmulti where hash=? and outputindex=?";

	protected final String SELECT_USERDATA_SQL = "SELECT blockhash, dataclassname, data, pubKey, blocktype FROM userdata WHERE dataclassname = ? and pubKey = ?";
	protected final String INSERT_USERDATA_SQL = "INSERT INTO userdata (blockhash, dataclassname, data, pubKey, blocktype) VALUES (?, ?, ?, ?, ?)";
	protected final String UPDATE_USERDATA_SQL = "UPDATE userdata SET blockhash = ?, data = ? WHERE dataclassname = ? and pubKey = ?";

	protected final String INSERT_BATCHBLOCK_SQL = "INSERT INTO batchblock (hash, block, inserttime) VALUE (?, ?, ?)";
	protected final String DELETE_BATCHBLOCK_SQL = "DELETE FROM batchblock WHERE hash = ?";
	protected final String SELECT_BATCHBLOCK_SQL = "SELECT hash, block, inserttime FROM batchblock order by inserttime ASC";
	protected final String INSERT_SUBTANGLE_PERMISSION_SQL = "INSERT INTO  subtangle_permission (pubkey, userdataPubkey , status) VALUE (?, ?, ?)";

	protected final String DELETE_SUBTANGLE_PERMISSION_SQL = "DELETE FROM  subtangle_permission WHERE pubkey=?";
	protected final String UPATE_ALL_SUBTANGLE_PERMISSION_SQL = "UPDATE   subtangle_permission set status=? ,userdataPubkey=? WHERE  pubkey=? ";

	protected final String SELECT_ALL_SUBTANGLE_PERMISSION_SQL = "SELECT   pubkey, userdataPubkey , status FROM subtangle_permission ";

	protected final String SELECT_SUBTANGLE_PERMISSION_BY_PUBKEYS_SQL = "SELECT   pubkey, userdataPubkey , status FROM subtangle_permission WHERE 1=1 ";

	protected final String SELECT_ORDERS_SORTED_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders ORDER BY blockhash, collectinghash";

	protected final String SELECT_OPEN_ORDERS_SORTED_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE confirmed=1 AND spent=0 ";

	// TODO remove test
	protected final String SELECT_AVAILABLE_UTXOS_SORTED_SQL = "SELECT coinvalue, scriptbytes, coinbase, toaddress, "
			+ "addresstargetable, blockhash, tokenid, fromaddress, memo, spent, confirmed, spendpending,spendpendingtime, minimumsign, time, hash, outputindex, spenderblockhash "
			+ " FROM outputs WHERE confirmed=1 AND spent=0 ORDER BY hash, outputindex";

  
	protected final String SELECT_ORDERCANCEL_SQL = "SELECT blockhash, orderblockhash, confirmed, spent, spenderblockhash,time"
			+ " FROM ordercancel WHERE 1 = 1";
	protected final String ORDERCANCEL_CONFIRMED_SQL = "SELECT blockhash, orderblockhash, confirmed, spent, spenderblockhash,time"
			+ " FROM ordercancel WHERE confirmed = true and spent=false";
	protected final String ORDERCANCEL_UPDATE_SPENT_SQL = "UPDATE ordercancel SET spent = ?, spenderblockhash=?  WHERE blockhash = ? ";

 

	protected final String BlockPrototype_SELECT_SQL = "   select prevblockhash, prevbranchblockhash, "
			+ " inserttime from blockprototype   ";
	protected final String BlockPrototype_INSERT_SQL = getInsert()
			+ "  INTO blockprototype (prevblockhash, prevbranchblockhash, inserttime) " + "VALUES (?, ?, ?)";
	protected final String BlockPrototype_DELETE_SQL = "   delete from blockprototype  where  prevblockhash =? and prevbranchblockhash=?  ";

	protected final String ChainBlockQueueColumn = " hash, block, chainlength, orphan, inserttime";
	protected final String INSERT_CHAINBLOCKQUEUE = getInsert() + "  INTO chainblockqueue (" + ChainBlockQueueColumn
			+ ") " + " VALUES (?, ?, ?,?,?)";
	protected final String SELECT_CHAINBLOCKQUEUE = " select " + ChainBlockQueueColumn + " from chainblockqueue  ";

	protected final String INSERT_CONTRACTEVENTCANCELSQL = getInsert()
			+ " INTO ordercancel (blockhash, eventblockhash, confirmed, spent, spenderblockhash,time) "
			+ " VALUES (?, ?, ?, ?, ?,?)";

	protected final String SELECT_CONTRACTEVENTCANCEL_SQL = "SELECT blockhash, eventblockhash, confirmed, spent, spenderblockhash,time"
			+ " FROM contracteventcancel WHERE 1 = 1";
	protected final String CONTRACTEVENTCANCEL_CONFIRMED_SQL = "SELECT blockhash, orderblockhash, confirmed, spent, spenderblockhash,time"
			+ " FROM contracteventcancel WHERE confirmed = true and spent=false";
	protected final String CONTRACTEVENTCANCEL_UPDATE_SPENT_SQL = "UPDATE contracteventcancel SET spent = ?, spenderblockhash=?  WHERE blockhash = ? ";

	
	protected NetworkParameters params;
	protected Connection conn;

	public Connection getConnection() throws SQLException {

		return conn;
	}

	/**
	 * <p>
	 * Create a new DatabaseFullBlockStore, using the full connection URL instead of
	 * a hostname and password, and optionally allowing a schema to be specified.
	 * </p>
	 */
	public DatabaseFullBlockStoreBase(NetworkParameters params, Connection conn) {
		this.params = params;
		this.conn = conn;
	}

	public void create() throws BlockStoreException {

		

		try {
			// Create tables if needed
			if (!tablesExists()) {
				createTables();
			} else {
				log.info("setting table   Exists");
			}
		} catch (Exception e) {
			log.warn("create table error", e);
			throw new BlockStoreException(e);
		}
	}

	protected String afterSelect() {
		return "";
	}

	protected String getInsert() {
		return "insert ";
	}

	protected String getUpdate() {
		return "update ";
	}

	/**
	 * Get the database driver class,
	 * <p>
	 * i.e org.postgresql.Driver.
	 * </p>
	 * 
	 * @return The fully qualified database driver class.
	 */
	protected abstract String getDatabaseDriverClass();

	 
	/**
	 * Get the SQL statements that create the tables (DDL).
	 * 
	 * @return The list of SQL statements.
	 */
	protected abstract List<String> getCreateTablesSQL();

	/**
	 * Get the SQL statements that create the indexes (DDL).
	 * 
	 * @return The list of SQL statements.
	 */
	protected abstract List<String> getCreateIndexesSQL();

	/**
	 * Get the database specific error code that indicated a duplicate key error
	 * when inserting a record.
	 * <p>
	 * This is the code returned by {@link java.sql.SQLException#getSQLState()}
	 * </p>
	 * 
	 * @return The database duplicate error code.
	 */
	protected abstract String getDuplicateKeyErrorCode();

	/**
	 * Get the SQL statement that checks if tables exist.
	 * 
	 * @return The SQL prepared statement.
	 */
	protected String getTablesExistSQL() {
		return SELECT_CHECK_TABLES_EXIST_SQL;
	}

	/**
	 * Get the SQL to drop all the tables (DDL).
	 * 
	 * @return The SQL drop statements.
	 */
	protected List<String> getDropTablesSQL() {
		List<String> sqlStatements = new ArrayList<String>();
		sqlStatements.add(DROP_SETTINGS_TABLE);
		sqlStatements.add(DROP_BLOCKS_TABLE);
		sqlStatements.add(DROP_OPEN_OUTPUT_TABLE);
		sqlStatements.add(DROP_OUTPUTSMULTI_TABLE);
		sqlStatements.add(DROP_TOKENS_TABLE);
		sqlStatements.add(DROP_MATCHING_TABLE);
		sqlStatements.add(DROP_MULTISIGNADDRESS_TABLE);
		sqlStatements.add(DROP_MULTISIGNBY_TABLE);
		sqlStatements.add(DROP_MULTISIGN_TABLE);
		sqlStatements.add(DROP_TX_REWARDS_TABLE);
		sqlStatements.add(DROP_USERDATA_TABLE);
		sqlStatements.add(DROP_PAYMULTISIGN_TABLE);
		sqlStatements.add(DROP_PAYMULTISIGNADDRESS_TABLE);
		sqlStatements.add(DROP_CONTRACT_RESULT_TABLE);
		sqlStatements.add(DROP_ORDER_RESULT_TABLE);
		sqlStatements.add(DROP_ORDERCANCEL_TABLE);
		sqlStatements.add(DROP_BATCHBLOCK_TABLE);
		sqlStatements.add(DROP_SUBTANGLE_PERMISSION_TABLE);
		sqlStatements.add(DROP_ORDERS_TABLE);
		sqlStatements.add(DROP_MYSERVERBLOCKS_TABLE);
		sqlStatements.add(DROP_ACCESS_PERMISSION_TABLE);
		sqlStatements.add(DROP_ACCESS_GRANT_TABLE);
		sqlStatements.add(DROP_CONTRACT_EVENT_TABLE);
		sqlStatements.add(DROP_CONTRACT_EVENT_CANCEL_TABLE);
		sqlStatements.add(DROP_CHAINBLOCKQUEUE_TABLE);
		sqlStatements.add(DROP_MCMC_TABLE);
		sqlStatements.add(DROP_LOCKOBJECT_TABLE);
		sqlStatements.add(DROP_MATCHING_LAST_TABLE);
		sqlStatements.add(DROP_MATCHINGDAILY_TABLE);
		sqlStatements.add(DROP_MATCHINGLASTDAY_TABLE);
		sqlStatements.add(DROP_ACCOUNT_TABLE);
		return sqlStatements;
	}

	/**
	 * Get the SQL to select a setting coinvalue.
	 * 
	 * @return The SQL select statement.
	 */
	protected String getSelectSettingsSQL() {
		return SELECT_SETTINGS_SQL;
	}

	/**
	 * Get the SQL to insert a settings record.
	 * 
	 * @return The SQL insert statement.
	 */
	protected String getInsertSettingsSQL() {
		return INSERT_SETTINGS_SQL;
	}

	/**
	 * Get the SQL to update a setting coinvalue.
	 * 
	 * @return The SQL update statement.
	 */
	protected abstract String getUpdateSettingsSLQ();

 
	@Override
	public void close() {
		try {
			if (!conn.getAutoCommit()) {
				conn.rollback();
			}

			conn.close();
			conn = null;
		} catch (Exception e) {
			// Ignore

		}

	}

	/**
	 * <p>
	 * Check if a tables exists within the database.
	 * </p>
	 *
	 * <p>
	 * This specifically checks for the 'settings' table and if it exists makes an
	 * assumption that the rest of the data structures are present.
	 * </p>
	 *
	 * @return If the tables exists.
	 * @throws java.sql.SQLException
	 */
	private boolean tablesExists() throws SQLException {
		PreparedStatement ps = null;
		try {
			ps = getConnection().prepareStatement(getTablesExistSQL());
			ResultSet results = ps.executeQuery();
			results.close();
			return true;
		} catch (SQLException ex) {
			return false;
		} finally {
			if (ps != null && !ps.isClosed()) {
				ps.close();
			}
		}
	}

	/**
	 * Create the tables/block store in the database and
	 * 
	 * @throws java.sql.SQLException If there is a database error.
	 * @throws BlockStoreException   If the block store could not be created.
	 */
	private synchronized void createTables() throws SQLException, BlockStoreException {
		try {
			// beginDatabaseBatchWrite();
			// create all the database tables
			updateTables(getCreateTablesSQL());
			// create all the database indexes
			updateTables(getCreateIndexesSQL());
			// insert the initial settings for this store
			dbversion("05");
			createNewStore(params);

		} catch (Exception e) {
			log.error("", e);
			// this.abortDatabaseBatchWrite();
		}
	}

	/*
	 * initial ps.setBytes(2, "03".getBytes());
	 */
	private void dbversion(String version) throws SQLException {
		PreparedStatement ps = getConnection().prepareStatement(getInsertSettingsSQL());
		ps.setString(1, VERSION_SETTING);
		ps.setBytes(2, version.getBytes());
		ps.execute();
		ps.close();
	}

	protected void dbupdateversion(String version) throws SQLException {
		PreparedStatement ps = getConnection().prepareStatement(UPDATE_SETTINGS_SQL);
		ps.setString(2, VERSION_SETTING);
		ps.setBytes(1, version.getBytes());
		ps.execute();
		ps.close();
	}

	/*
	 * check version and update the tables
	 */
	protected synchronized void updateTables(List<String> sqls) throws SQLException, BlockStoreException {

		Statement s = getConnection().createStatement();
		try {
			for (String sql : sqls) {
				if (log.isDebugEnabled()) {
					log.debug("DatabaseFullBlockStore :     " + sql);
				}
				s.addBatch(sql);
			}
			s.executeBatch();
		} catch (Exception e) {
			log.debug("DatabaseFullBlockStore :     ", e);

		} finally {
			s.close();
		}

	}

	/**
	 * Create a new store for the given
	 * {@link net.bigtangle.core.NetworkParameters}.
	 * 
	 * @param params The network.
	 * @throws BlockStoreException If the store couldn't be created.
	 */
	private void createNewStore(NetworkParameters params) throws BlockStoreException {
		try {

			saveNewStore(params.getGenesisBlock());
			saveGenesisTransactionOutput(params.getGenesisBlock());

			// Just fill the tables with some valid data
			// Reward output table
			insertReward(params.getGenesisBlock().getHash(), Sha256Hash.ZERO_HASH,
					Utils.encodeCompactBits(params.getMaxTargetReward()), 0);
			updateRewardConfirmed(params.getGenesisBlock().getHash(), true);

			// create bigtangle Token output table
			Token bigtangle = Token.genesisToken(params);
			insertToken(bigtangle.getBlockHash(), bigtangle);
			updateTokenConfirmed(params.getGenesisBlock().getHash(), true);

			// insert MCMC table
			ArrayList<DepthAndWeight> depthAndWeight = new ArrayList<DepthAndWeight>();
			depthAndWeight.add(new DepthAndWeight(params.getGenesisBlock().getHash(), 1, 0));
			updateBlockEvaluationWeightAndDepth(depthAndWeight);

		} catch (VerificationException e) {
			throw new RuntimeException(e); // Cannot happen.
		}
	}

	private void saveNewStore(Block b) throws BlockStoreException {
		put(b);

		updateBlockEvaluationMilestone(b.getHash(), 0);

		updateBlockEvaluationSolid(b.getHash(), 2);
		updateBlockEvaluationConfirmed(b.getHash(), true);

	}

	public void saveGenesisTransactionOutput(Block block) throws BlockStoreException {

		for (TransactionOutput out : block.getTransactions().get(0).getOutputs()) {
			// For each output, add it to the set of unspent outputs so
			// it can be consumed
			// in future.
			Script script = new Script(out.getScriptBytes());
			int minsignnumber = 1;
			if (script.isSentToMultiSig()) {
				minsignnumber = script.getNumberOfSignaturesRequiredToSpend();
			}

			UTXO newOut = new UTXO(block.getTransactions().get(0).getHash(), out.getIndex(), out.getValue(), true,
					script, script.getToAddress(params, true).toString(), block.getHash(), "",
					block.getTransactions().get(0).getMemo(), Utils.HEX.encode(out.getValue().getTokenid()), false,
					true, false, minsignnumber, 0, block.getTimeSeconds(), null);
			addUnspentTransactionOutput(newOut);
			List<UTXO> gen = new ArrayList<>();
			gen.add(newOut);
			if (script.isSentToMultiSig()) {

				for (ECKey ecKey : script.getPubKeys()) {
					String toaddress = ecKey.toAddress(params).toBase58();
					OutputsMulti outputsMulti = new OutputsMulti(newOut.getTxHash(), toaddress, newOut.getIndex());
					this.insertOutputsMulti(outputsMulti);
				}
			}

		}
	}

	protected void putUpdateStoredBlock(Block block, BlockEvaluation blockEvaluation) throws SQLException {
		try {

			PreparedStatement s = getConnection().prepareStatement(INSERT_BLOCKS_SQL);
			s.setBytes(1, block.getHash().getBytes());
			s.setLong(2, block.getHeight());
			s.setBytes(3, Gzip.compress(block.unsafeBitcoinSerialize()));

			s.setBytes(4, block.getPrevBlockHash().getBytes());
			s.setBytes(5, block.getPrevBranchBlockHash().getBytes());
			s.setBytes(6, block.getMinerAddress());
			s.setLong(7, block.getBlockType().ordinal());

			int j = 1;
			s.setLong(j + 7, blockEvaluation.getMilestone());
			s.setLong(j + 8, blockEvaluation.getMilestoneLastUpdateTime());

			s.setLong(j + 9, blockEvaluation.getInsertTime());

			s.setLong(j + 10, blockEvaluation.getSolid());
			s.setBoolean(j + 11, blockEvaluation.isConfirmed());

			s.executeUpdate();
			s.close();
			// log.info("add block hexStr : " + block.getHash().toString());
		} catch (SQLException e) {
			// It is possible we try to add a duplicate Block if we
			// upgraded
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw e;
		}
	}

	@Override
	public void put(Block block) throws BlockStoreException {
		

		try {

			BlockEvaluation blockEval = BlockEvaluation.buildInitial(block);

			putUpdateStoredBlock(block, blockEval);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	public Block get(Sha256Hash hash) throws BlockStoreException {

		try {

			byte[] re = getByte(hash);
			if (re == null)
				return null;
			return params.getDefaultSerializer().makeZippedBlock(re);
		} catch (IOException ex) {
			throw new BlockStoreException(ex);
		}

	}

	public byte[] getByte(Sha256Hash hash) throws BlockStoreException {

		
		PreparedStatement s = null;
		// log.info("find block hexStr : " + hash.toString());
		try {
			s = getConnection().prepareStatement(SELECT_BLOCKS_SQL);
			s.setBytes(1, hash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return null;
			}
			// Parse it.

			return results.getBytes(2);

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} catch (Exception e) {
			throw new BlockStoreException(e);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	public List<byte[]> blocksFromChainLength(long start, long end) throws BlockStoreException {
		// Optimize for chain head
		List<byte[]> re = new ArrayList<byte[]>();
		
		PreparedStatement s = null;
		// log.info("find block hexStr : " + hash.toString());
		try {
			s = getConnection().prepareStatement(SELECT_BLOCKS_MILESTONE_SQL);
			s.setLong(1, start);
			s.setLong(2, end);
			s.setLong(3, start);
			s.setLong(4, end);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				re.add(Gzip.decompressOut(results.getBytes("block")));
			}
			return re;
		} catch (Exception ex) {
			log.warn("", ex);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
		return re;
	}

	public List<byte[]> blocksFromNonChainHeigth(long heigth) throws BlockStoreException {
		// Optimize for chain head
		List<byte[]> re = new ArrayList<byte[]>();
		
		PreparedStatement s = null;
		// log.info("find block hexStr : " + hash.toString());
		try {
			s = getConnection().prepareStatement(SELECT_BLOCKS_NON_CHAIN_HEIGTH_SQL);
			s.setLong(1, heigth);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				re.add(Gzip.decompressOut(results.getBytes("block")));
			}
			return re;
		} catch (Exception ex) {
			log.warn("", ex);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
		return re;
	}

	private boolean verifyHeader(Block block) {
		try {
			block.verifyHeader();
			return true;
		} catch (VerificationException e) {
			return false;
		}
	}

	public BlockMCMC getMCMC(Sha256Hash hash) throws BlockStoreException {
		PreparedStatement s = null;

		try {
			s = getConnection().prepareStatement("SELECT " + SELECT_MCMC_TEMPLATE + " from mcmc where hash = ?");
			s.setBytes(1, hash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return null;
			} else {
				return setBlockMCMC(results);
			}
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} catch (ProtocolException e) {
			// Corrupted database.

			throw new BlockStoreException(e);
		} catch (VerificationException e) {
			// Should not be able to happen unless the database contains bad
			// blocks.
			throw new BlockStoreException(e);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	public List<BlockWrap> getNotInvalidApproverBlocks(Sha256Hash hash) throws BlockStoreException {
		List<BlockWrap> storedBlocks = new ArrayList<BlockWrap>();
		
		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(SELECT_NOT_INVALID_APPROVER_BLOCKS_SQL);
			s.setBytes(1, hash.getBytes());
			s.setBytes(2, hash.getBytes());
			ResultSet resultSet = s.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluationNumber(resultSet);
				BlockMCMC mcmc = setBlockMCMC(resultSet);
				Block block = params.getDefaultSerializer().makeZippedBlock(resultSet.getBytes("block"));
				if (verifyHeader(block)) {
					storedBlocks.add(new BlockWrap(block, blockEvaluation, mcmc, params));
				}
			}
			return storedBlocks;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} catch (Exception e) {
			// Corrupted database.
			throw new BlockStoreException(e);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
 
	public List<Sha256Hash> getSolidApproverBlockHashes(Sha256Hash hash) throws BlockStoreException {
		List<Sha256Hash> storedBlockHash = new ArrayList<Sha256Hash>();
		
		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(SELECT_SOLID_APPROVER_HASHES_SQL);
			s.setBytes(1, hash.getBytes());
			s.setBytes(2, hash.getBytes());
			ResultSet results = s.executeQuery();
			while (results.next()) {
				storedBlockHash.add(Sha256Hash.wrap(results.getBytes(1)));
			}
			return storedBlockHash;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} catch (ProtocolException e) {
			// Corrupted database.
			throw new BlockStoreException(e);
		} catch (VerificationException e) {
			// Should not be able to happen unless the database contains bad
			// blocks.
			throw new BlockStoreException(e);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getOutputConfirmation(Sha256Hash blockHash, Sha256Hash hash, long index) throws BlockStoreException {
		
		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(
					"SELECT  confirmed " + "FROM outputs WHERE hash = ? AND outputindex = ? AND blockhash = ? ");
			s.setBytes(1, hash.getBytes());
			// index is actually an unsigned int
			s.setLong(2, index);
			s.setBytes(3, blockHash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return false;
			}
			return results.getBoolean("confirmed");

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public UTXO getTransactionOutput(Sha256Hash blockHash, Sha256Hash hash, long index) throws BlockStoreException {
		
		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(SELECT_OUTPUTS_SQL);
			s.setBytes(1, hash.getBytes());
			// index is actually an unsigned int
			s.setLong(2, index);
			s.setBytes(3, blockHash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return null;
			}
			return setUTXO(hash, index, results);

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (s != null) {
				try {
					s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	private UTXO setUTXO(Sha256Hash hash, long index, ResultSet results) throws SQLException {
		// Parse it.
		Coin coinvalue = new Coin(new BigInteger(results.getBytes("coinvalue")), results.getString("tokenid"));
		byte[] scriptBytes = results.getBytes("scriptbytes");
		boolean coinbase = results.getBoolean("coinbase");
		String address = results.getString("toaddress");
		Sha256Hash blockhash = Sha256Hash.wrap(results.getBytes("blockhash"));

		String fromaddress = results.getString("fromaddress");
		String memo = results.getString("memo");
		boolean spent = results.getBoolean("spent");

		Sha256Hash spenderblockhash = Sha256Hash.wrap(results.getBytes("spenderblockhash"));
		boolean confirmed = results.getBoolean("confirmed");
		boolean spendPending = results.getBoolean("spendpending");
		long spendPendingTime = results.getLong("spendpendingtime");
		String tokenid = results.getString("tokenid");
		long minimumsign = results.getLong("minimumsign");
		long time = results.getLong("time");
		if (minimumsign > 1) {
			address = results.getString("multitoaddress");
		}
		return new UTXO(hash, index, coinvalue, coinbase, new Script(scriptBytes), address, blockhash, fromaddress,
				memo, tokenid, spent, confirmed, spendPending, minimumsign, spendPendingTime, time, spenderblockhash);
	}

	@Override
	public void addUnspentTransactionOutput(List<UTXO> utxos) throws BlockStoreException {
		
		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(INSERT_OUTPUTS_SQL);
			for (UTXO out : utxos) {
				if (out.getValue().isPositive()) {
					s.setBytes(1, out.getTxHash().getBytes());
					// index is actually an unsigned int
					s.setLong(2, out.getIndex());
					s.setBytes(3, out.getValue().getValue().toByteArray());
					s.setBytes(4, out.getScript().getProgram());
					s.setString(5, out.getAddress());
					s.setLong(6, out.getScript().getScriptType().ordinal());
					s.setBoolean(7, out.isCoinbase());
					s.setBytes(8, out.getBlockHash() != null ? out.getBlockHash().getBytes() : null);
					s.setString(9, Utils.HEX.encode(out.getValue().getTokenid()));
					// if ((out.getFromaddress() == null ||
					// "".equals(out.getFromaddress())) && !out.isCoinbase()) {
					// log.debug(" no Fromaddress " + out.toString());
					// }
					s.setString(10, out.getFromaddress());
					s.setString(11, out.getMemo());
					s.setBoolean(12, out.isSpent());
					s.setBoolean(13, out.isConfirmed());
					s.setBoolean(14, out.isSpendPending());
					s.setLong(15, out.getTime());
					s.setLong(16, out.getSpendPendingTime());
					s.setLong(17, out.getMinimumsign());
					s.addBatch();
				}
			}
			s.executeBatch();
			s.close();
		} catch (SQLException e) {
			if (!(getDuplicateKeyErrorCode().equals(e.getSQLState())))
				throw new BlockStoreException(e);
		} finally {
			if (s != null) {
				try {
					if (s.getConnection() != null)
						s.close();
				} catch (SQLException e) {
					// throw new BlockStoreException(e);
				}
			}
		}
	}

	

	@Override
	public void addUnspentTransactionOutput(UTXO out) throws BlockStoreException {
		List<UTXO> a = new ArrayList<UTXO>();
		a.add(out);
		addUnspentTransactionOutput(a);
	}

	@Override
	public void beginDatabaseBatchWrite() throws BlockStoreException {
		

		try {
			getConnection().setAutoCommit(false);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void commitDatabaseBatchWrite() throws BlockStoreException {
		try {
			if (!getConnection().getAutoCommit())
				getConnection().commit();
			getConnection().setAutoCommit(true);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void abortDatabaseBatchWrite() throws BlockStoreException {
		try {
			if (log.isDebugEnabled())
				log.debug("Rollback database batch write with connection: " + getConnection().toString());

			if (!getConnection().getAutoCommit()) {
				getConnection().rollback();
				getConnection().setAutoCommit(true);
			} else {
				log.warn("Warning: Rollback attempt without transaction");
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void defaultDatabaseBatchWrite() throws BlockStoreException {
		try {
			if (!getConnection().getAutoCommit()) {
				getConnection().setAutoCommit(true);
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public NetworkParameters getParams() {
		return params;
	}

	/**
	 * Resets the store by deleting the contents of the tables and reinitialising
	 * them.
	 * 
	 * @throws BlockStoreException If the tables couldn't be cleared and
	 *                             initialised.
	 */
	public void resetStore() throws BlockStoreException {
		
		defaultDatabaseBatchWrite();
		try {
			deleteStore();
			createTables();
		} catch (SQLException ex) {
			log.warn("Warning: deleteStore", ex);
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Deletes the store by deleting the tables within the database.
	 * 
	 * @throws BlockStoreException If tables couldn't be deleted.
	 */
	public void deleteStore() throws BlockStoreException {
		

		try {
			Statement s = getConnection().createStatement();
			for (String sql : getDropTablesSQL()) {
				log.info("drop table : " + sql);
				s.addBatch(sql);
			}
			s.executeBatch();
			s.close();
		} catch (Exception e) {
			log.info("drop table : ", e);
		} finally {

		}
	}
 

	@Override
	public List<UTXO> getOpenAllOutputs(String tokenid) throws UTXOProviderException {

		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<UTXO>();
		try {
			
			// Must be sorted for hash checkpoint
			s = getConnection().prepareStatement(SELECT_ALL_OUTPUTS_TOKEN_SQL + " order by hash, outputindex ");
			s.setString(1, tokenid);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				outputs.add(
						setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"), results));
			}
			return outputs;
		} catch (SQLException ex) {
			throw new UTXOProviderException(ex);
		} finally {
			if (s != null)
				try {
					s.close();
				} catch (SQLException e) {
					//
				}
		}

	}

	@Override
	public List<UTXO> getOpenOutputsByBlockhash(Sha256Hash blockhash) throws UTXOProviderException {

		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<UTXO>();
		try {
			
			// Must be sorted for hash checkpoint
			s = getConnection().prepareStatement(SELECT_TRANSACTION_OUTPUTS_SQL_BASE + "  where blockhash =?");
			s.setBytes(1, blockhash.getBytes());
			ResultSet results = s.executeQuery();
			while (results.next()) {
				outputs.add(
						setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"), results));
			}
			return outputs;
		} catch (SQLException ex) {
			throw new UTXOProviderException(ex);
		} finally {
			if (s != null)
				try {
					s.close();
				} catch (SQLException e) {
					//
				}
		}

	}

	@Override
	public List<UTXO> getOpenTransactionOutputs(List<Address> addresses) throws UTXOProviderException {
		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<UTXO>();
		try {
			
			s = getConnection().prepareStatement(SELECT_OPEN_TRANSACTION_OUTPUTS_SQL);
			for (Address address : addresses) {
				s.setString(1, address.toString());
				s.setString(2, address.toString());
				ResultSet results = s.executeQuery();
				while (results.next()) {
					outputs.add(setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"),
							results));
				}
			}
			return outputs;
		} catch (SQLException ex) {
			throw new UTXOProviderException(ex);
		}   finally {
			if (s != null)
				try {
					s.close();
				} catch (SQLException e) {
					//
				}
		}
	}

	@Override
	public List<UTXO> getOpenTransactionOutputs(String address) throws UTXOProviderException {
		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<UTXO>();
		try {
			
			s = getConnection().prepareStatement(SELECT_OPEN_TRANSACTION_OUTPUTS_SQL);

			s.setString(1, address.toString());
			s.setString(2, address.toString());
			ResultSet results = s.executeQuery();
			while (results.next()) {
				outputs.add(
						setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"), results));
			}
			return outputs;
		} catch (SQLException ex) {
			throw new UTXOProviderException(ex);
		} finally {
			if (s != null)
				try {
					s.close();
				} catch (SQLException e) {
					//
				}
		}
	}

	@Override
	public List<UTXO> getOpenTransactionOutputs(List<Address> addresses, byte[] tokenid00)
			throws UTXOProviderException {
		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<UTXO>();
		try {
			
			s = getConnection().prepareStatement(SELECT_OPEN_TRANSACTION_OUTPUTS_TOKEN_SQL);
			for (Address address : addresses) {
				s.setString(1, address.toString());
				s.setString(2, address.toString());
				s.setBytes(3, tokenid00);
				ResultSet results = s.executeQuery();
				while (results.next()) {
					outputs.add(setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"),
							results));

				}
			}
			return outputs;
		} catch (SQLException ex) {
			throw new UTXOProviderException(ex);
		} finally {
			if (s != null)
				try {
					s.close();
				} catch (SQLException e) {
					//
				}
		}
	}

	@Override
	public BlockWrap getBlockWrap(Sha256Hash hash) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_SQL);
			preparedStatement.setBytes(1, hash.getBytes());

			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

			Block block = params.getDefaultSerializer().makeZippedBlock(resultSet.getBytes("block"));
			return new BlockWrap(block, blockEvaluation, getMCMC(hash), params);
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<UTXO> getOutputsHistory(String fromaddress, String toaddress, Long starttime, Long endtime)
			throws BlockStoreException {
		List<UTXO> outputs = new ArrayList<UTXO>();
		
		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_TRANSACTION_OUTPUTS_SQL_BASE + "WHERE  confirmed=true ";

			if (fromaddress != null && !"".equals(fromaddress.trim())) {
				sql += " AND outputs.fromaddress=?";
			}
			if (toaddress != null && !"".equals(toaddress.trim())) {
				sql += " AND outputs.toaddress=?";
			}
			if (starttime != null) {
				sql += " AND time>=?";
			}
			if (endtime != null) {
				sql += " AND time<=?";
			}
			preparedStatement = getConnection().prepareStatement(sql);
			int i = 1;
			if (fromaddress != null && !"".equals(fromaddress.trim())) {
				preparedStatement.setString(i++, fromaddress);
			}
			if (toaddress != null && !"".equals(toaddress.trim())) {
				preparedStatement.setString(i++, toaddress);
			}
			if (starttime != null) {
				preparedStatement.setLong(i++, starttime);
			}
			if (endtime != null) {
				preparedStatement.setLong(i++, endtime);
			}
			ResultSet results = preparedStatement.executeQuery();
			while (results.next()) {
				outputs.add(
						setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"), results));

			}
			return outputs;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					//// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public TreeSet<BlockWrap> getBlocksToConfirm(long cutoffHeight, long maxHeight) throws BlockStoreException {
		Comparator<BlockWrap> comparator = Comparator.comparingLong((BlockWrap b) -> b.getBlock().getHeight())
				.thenComparing((BlockWrap b) -> b.getBlock().getHash());
		TreeSet<BlockWrap> storedBlockHashes = new TreeSet<>(comparator);
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_MCMC_CONFIRM);
			preparedStatement.setLong(1, cutoffHeight);
			preparedStatement.setLong(2, maxHeight);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

				Block block = params.getDefaultSerializer().makeZippedBlock(resultSet.getBytes("block"));
				if (verifyHeader(block))
					storedBlockHashes.add(
							new BlockWrap(block, blockEvaluation, getMCMC(blockEvaluation.getBlockHash()), params));
			}
			return storedBlockHashes;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public HashSet<BlockEvaluation> getBlocksToUnconfirm() throws BlockStoreException {
		HashSet<BlockEvaluation> storedBlockHashes = new HashSet<BlockEvaluation>();
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_TO_UNCONFIRM_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

				storedBlockHashes.add(blockEvaluation);
			}
			return storedBlockHashes;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public PriorityQueue<BlockWrap> getSolidBlocksInIntervalDescending(long cutoffHeight, long maxHeight)
			throws BlockStoreException {
		PriorityQueue<BlockWrap> blocksByDescendingHeight = new PriorityQueue<BlockWrap>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_SOLID_BLOCKS_IN_INTERVAL_SQL);
			preparedStatement.setLong(1, cutoffHeight);
			preparedStatement.setLong(2, maxHeight);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

				Block block = params.getDefaultSerializer().makeZippedBlock(resultSet.getBytes("block"));
				if (verifyHeader(block))
					blocksByDescendingHeight.add(
							new BlockWrap(block, blockEvaluation, getMCMC(blockEvaluation.getBlockHash()), params));
			}
			return blocksByDescendingHeight;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
 
	@Override
	public List<Sha256Hash> getBlocksInMilestoneInterval(long minChainLength, long currChainLength) throws BlockStoreException {
		// long currChainLength = getMaxConfirmedReward().getChainLength();
	//	long minChainLength = Math.max(0, currChainLength - NetworkParameters.MILESTONE_CUTOFF);
		List<Sha256Hash> resultQueue = new ArrayList<>();
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_IN_MILESTONE_INTERVAL_SQL);
			preparedStatement.setLong(1, minChainLength);
			preparedStatement.setLong(2, currChainLength);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				resultQueue.add(Sha256Hash.wrap(resultSet.getBytes(1))); 
			 		}
			return resultQueue;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	private BlockEvaluation setBlockEvaluationNumber(ResultSet resultSet) throws SQLException {

		BlockEvaluation blockEvaluation = BlockEvaluation.build(Sha256Hash.wrap(resultSet.getBytes(1)),
				resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5), resultSet.getLong(6),
				resultSet.getLong(7), resultSet.getBoolean(8));
		return blockEvaluation;
	}

	private BlockEvaluation setBlockEvaluation(ResultSet resultSet) throws SQLException {
		BlockEvaluation blockEvaluation = BlockEvaluation.build(Sha256Hash.wrap(resultSet.getBytes("hash")),
				resultSet.getLong("height"), resultSet.getLong("milestone"), resultSet.getLong("milestonelastupdate"),
				resultSet.getLong("inserttime"), resultSet.getLong("solid"), resultSet.getBoolean("confirmed"));
		return blockEvaluation;
	}

	private BlockMCMC setBlockMCMC(ResultSet resultSet) throws SQLException {
		return new BlockMCMC(Sha256Hash.wrap(resultSet.getBytes("hash")), resultSet.getLong("rating"),
				resultSet.getLong("depth"), resultSet.getLong("cumulativeweight"));

	}

	@Override
	public void deleteMCMC(long chainlength) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		PreparedStatement deleteStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_MCMC_CHAINLENGHT_SQL);
			deleteStatement = getConnection().prepareStatement(" delete from mcmc where hash = ?");
			preparedStatement.setLong(1, chainlength);
			ResultSet results = preparedStatement.executeQuery();
			if (results.next()) {
				deleteStatement.setBytes(1, results.getBytes("hash"));
				deleteStatement.addBatch();
			}
			deleteStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
			if (deleteStatement != null) {
				try {
					deleteStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	@Override
	public void updateBlockEvaluationWeightAndDepth(List<DepthAndWeight> depthAndWeight) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		PreparedStatement insertStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_BLOCKEVALUATION_WEIGHT_AND_DEPTH_SQL);
			insertStatement = getConnection().prepareStatement(INSERT_BLOCKEVALUATION_WEIGHT_AND_DEPTH_SQL);

			for (DepthAndWeight d : depthAndWeight) {
				if (getMCMC(d.getBlockHash()) == null) {
					insertStatement.setLong(1, d.getWeight());
					insertStatement.setLong(2, d.getDepth());
					insertStatement.setBytes(3, d.getBlockHash().getBytes());
					insertStatement.setLong(4, 0);
					insertStatement.addBatch();
				} else {
					preparedStatement.setLong(1, d.getWeight());
					preparedStatement.setLong(2, d.getDepth());
					preparedStatement.setBytes(3, d.getBlockHash().getBytes());
					preparedStatement.addBatch();
				}
			}
			preparedStatement.executeBatch();
			insertStatement.executeBatch();
		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		} finally {
			if (insertStatement != null) {
				try {
					insertStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

 

	@Override
	public void updateBlockEvaluationMilestone(Sha256Hash blockhash, long b) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		

		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_BLOCKEVALUATION_MILESTONE_SQL);
			preparedStatement.setLong(1, b);
			preparedStatement.setLong(2, System.currentTimeMillis());
			preparedStatement.setBytes(3, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	@Override
	public void updateBlockEvaluationConfirmed(Sha256Hash blockhash, boolean b) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		

		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_BLOCKEVALUATION_CONFIRMED_SQL);
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}
 

	@Override
	public void updateBlockEvaluationRating(List<Rating> ratings) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		
		try {

			preparedStatement = getConnection().prepareStatement(UPDATE_BLOCKEVALUATION_RATING_SQL);

			for (Rating r : ratings) {
				preparedStatement.setLong(1, r.getRating());
				preparedStatement.setBytes(2, r.getBlockhash().getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateBlockEvaluationSolid(Sha256Hash blockhash, long solid) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_BLOCKEVALUATION_SOLID_SQL);
			preparedStatement.setLong(1, solid);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
 

	@Override
	public BlockEvaluation getTransactionOutputSpender(Sha256Hash blockHash, Sha256Hash hash, long index)
			throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			UTXO u = getTransactionOutput(blockHash, hash, index);
			if (u == null || u.getSpenderBlockHash() == null)
				return null;
			preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_SQL);
			preparedStatement.setBytes(1, u.getSpenderBlockHash().getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);
			return blockEvaluation;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
 
	@Override
	public void updateTransactionOutputSpent(Sha256Hash prevBlockHash, Sha256Hash prevTxHash, long index, boolean b,
			@Nullable Sha256Hash spenderBlockHash) throws BlockStoreException {
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_OUTPUTS_SPENT_SQL);
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, spenderBlockHash != null ? spenderBlockHash.getBytes() : null);
			preparedStatement.setBytes(3, prevTxHash.getBytes());
			preparedStatement.setLong(4, index);
			preparedStatement.setBytes(5, prevBlockHash.getBytes());
			// log.debug(preparedStatement.toString());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}
 

	@Override
	public void updateTransactionOutputConfirmed(Sha256Hash prevBlockHash, Sha256Hash prevTxHash, long index, boolean b)
			throws BlockStoreException {
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_OUTPUTS_CONFIRMED_SQL);
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, prevTxHash.getBytes());
			preparedStatement.setLong(3, index);
			preparedStatement.setBytes(4, prevBlockHash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateAllTransactionOutputsConfirmed(Sha256Hash prevBlockHash, boolean b) throws BlockStoreException {
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ALL_OUTPUTS_CONFIRMED_SQL);
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, prevBlockHash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
 

	@Override
	public void updateTransactionOutputSpendPending(List<UTXO> utxos) throws BlockStoreException {
		

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_OUTPUTS_SPENDPENDING_SQL);
			for (UTXO u : utxos) {
				preparedStatement.setBoolean(1, true);
				preparedStatement.setLong(2, System.currentTimeMillis());
				preparedStatement.setBytes(3, u.getTxHash().getBytes());
				preparedStatement.setLong(4, u.getIndex());
				preparedStatement.setBytes(5, u.getBlockHash().getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Token> getTokensList(Set<String> tokenids) throws BlockStoreException {
		List<Token> list = new ArrayList<Token>();
		if (tokenids.isEmpty())
			return list;
		
		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_CONFIRMED_TOKENS_SQL;
			if (tokenids != null && !tokenids.isEmpty()) {
				sql += "  and tokenid in ( " + buildINList(tokenids) + " )";
			}
			sql += LIMIT_500;
			preparedStatement = getConnection().prepareStatement(sql);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {

				Token tokens = new Token();
				setToken(resultSet, tokens);
				list.add(tokens);
			}
			return list;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Token> getTokenTypeList(int type) throws BlockStoreException {
		List<Token> list = new ArrayList<Token>();
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_TYPE_SQL);
			preparedStatement.setInt(1, type);
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				Token tokens = new Token();
				setToken(resultSet, tokens);
				list.add(tokens);
			}
			return list;
		} catch (Exception ex) {

			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	public Map<String, BigInteger> getTokenAmountMap() throws BlockStoreException {
		Map<String, BigInteger> map = new HashMap<String, BigInteger>();
		
		PreparedStatement preparedStatement = null;
		try {

			preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_ACOUNT_MAP_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				BigInteger id = map.get(resultSet.getString("tokenid"));
				if (id == null) {
					map.put(resultSet.getString("tokenid"), new BigInteger(resultSet.getBytes("amount")));
				} else {
					map.put(resultSet.getString("tokenid"), id.add(new BigInteger(resultSet.getBytes("amount"))));

				}
			}
			return map;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Token> getTokensListFromDomain(String domainname) throws BlockStoreException {
		List<Token> list = new ArrayList<Token>();
		
		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_CONFIRMED_TOKENS_SQL;
			if (domainname != null && !"".equals(domainname.trim())) {
				sql += " AND (domainname = '" + domainname + "' )";
			}
			sql += LIMIT_500;
			preparedStatement = getConnection().prepareStatement(sql);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Token tokens = new Token();
				setToken(resultSet, tokens);
				list.add(tokens);
			}
			return list;
		} catch (Exception ex) {

			throw new BlockStoreException(ex);

		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Token> getTokensList(String name) throws BlockStoreException {
		List<Token> list = new ArrayList<Token>();
		
		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_CONFIRMED_TOKENS_SQL;
			if (name != null && !"".equals(name.trim())) {
				sql += " AND (tokenname LIKE '%" + name + "%' OR description LIKE '%" + name
						+ "%' OR domainname LIKE '%" + name + "%')";
			}
			sql += LIMIT_500;
			preparedStatement = getConnection().prepareStatement(sql);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Token tokens = new Token();
				setToken(resultSet, tokens);
				list.add(tokens);
			}
			return list;
		} catch (Exception ex) {

			throw new BlockStoreException(ex);

		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	protected void setToken(ResultSet resultSet, Token tokens)
			throws SQLException, JsonParseException, JsonMappingException, IOException {
		tokens.setBlockHash(Sha256Hash.wrap(resultSet.getBytes("blockhash")));
		tokens.setConfirmed(resultSet.getBoolean("confirmed"));
		tokens.setTokenid(resultSet.getString("tokenid"));
		tokens.setTokenindex(resultSet.getInt("tokenindex"));
		tokens.setAmount(new BigInteger(resultSet.getBytes("amount")));
		tokens.setTokenname(resultSet.getString("tokenname"));
		tokens.setDescription(resultSet.getString("description"));

		tokens.setSignnumber(resultSet.getInt("signnumber"));

		tokens.setTokentype(resultSet.getInt("tokentype"));
		tokens.setTokenstop(resultSet.getBoolean("tokenstop"));
		tokens.setDomainName(resultSet.getString("domainname"));
		tokens.setDecimals(resultSet.getInt("decimals"));
		tokens.setRevoked(resultSet.getBoolean("revoked"));
		tokens.setLanguage(resultSet.getString("language"));
		tokens.setClassification(resultSet.getString("classification"));
		tokens.setDomainNameBlockHash(resultSet.getString("domainpredblockhash"));
		byte[] buf = resultSet.getBytes("tokenkeyvalues");
		if (buf != null) {
			try {
				tokens.setTokenKeyValues(TokenKeyValues.parse(buf));
			} catch (Exception e) {
				log.warn("Token " + tokens, e);
			}
		}
	}

	@Override
	public void insertToken(Sha256Hash blockhash, Token token) throws BlockStoreException {
		boolean confirmed = false;
		String tokenid = token.getTokenid();
		long tokenindex = token.getTokenindex();
		String tokenname = token.getTokenname();
		String description = token.getDescription();

		int signnumber = token.getSignnumber();

		int tokentype = token.getTokentype();
		boolean tokenstop = token.isTokenstop();
		Sha256Hash prevblockhash = token.getPrevblockhash();
		byte[] tokenkeyvalues = null;
		if (token.getTokenKeyValues() != null) {
			tokenkeyvalues = token.getTokenKeyValues().toByteArray();
		}
		this.insertToken(blockhash, confirmed, tokenid, tokenindex, token.getAmount(), tokenname, description,
				token.getDomainName(), signnumber, tokentype, tokenstop, prevblockhash, tokenkeyvalues,
				token.getRevoked(), token.getLanguage(), token.getClassification(), token.getDecimals(),
				token.getDomainNameBlockHash());
	}

	public void insertToken(Sha256Hash blockhash, boolean confirmed, String tokenid, long tokenindex, BigInteger amount,
			String tokenname, String description, String domainname, int signnumber, int tokentype, boolean tokenstop,
			Sha256Hash prevblockhash, byte[] tokenkeyvalues, Boolean revoked, String language, String classification,
			int decimals, String domainNameBlockHash) throws BlockStoreException {
		
		PreparedStatement preparedStatement = null;
		try {

			preparedStatement = getConnection().prepareStatement(INSERT_TOKENS_SQL);
			preparedStatement.setBytes(1, blockhash.getBytes());
			preparedStatement.setBoolean(2, confirmed);
			preparedStatement.setString(3, tokenid);
			preparedStatement.setLong(4, tokenindex);
			preparedStatement.setBytes(5, amount.toByteArray());
			preparedStatement.setString(6, tokenname);
			preparedStatement.setString(7, description);
			preparedStatement.setString(8, domainname);
			preparedStatement.setInt(9, signnumber);

			preparedStatement.setInt(10, tokentype);
			preparedStatement.setBoolean(11, tokenstop);
			preparedStatement.setBytes(12, prevblockhash == null ? null : prevblockhash.getBytes());
			preparedStatement.setBoolean(13, false);
			preparedStatement.setString(14, null);
			preparedStatement.setBytes(15, tokenkeyvalues);
			preparedStatement.setBoolean(16, revoked);
			preparedStatement.setString(17, language);
			preparedStatement.setString(18, classification);
			preparedStatement.setLong(19, decimals);
			preparedStatement.setString(20, domainNameBlockHash);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			// It is possible we try to add a duplicate Block  
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode()))) 
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public Sha256Hash getTokenPrevblockhash(Sha256Hash blockhash) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_PREVBLOCKHASH_SQL);
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return Sha256Hash.wrap(resultSet.getBytes(1));
			} else {
				return null;
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public Sha256Hash getTokenSpender(String blockhash) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_SPENDER_SQL);
			preparedStatement.setString(1, blockhash);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return resultSet.getBytes(1) == null ? null : Sha256Hash.wrap(resultSet.getBytes(1));
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getTokenSpent(Sha256Hash blockhash) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_SPENT_BY_BLOCKHASH_SQL);
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getTokenConfirmed(Sha256Hash blockHash) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_CONFIRMED_SQL);
			preparedStatement.setBytes(1, blockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getTokenAnyConfirmed(String tokenid, long tokenIndex) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_ANY_CONFIRMED_SQL);
			preparedStatement.setString(1, tokenid);
			preparedStatement.setLong(2, tokenIndex);
			ResultSet resultSet = preparedStatement.executeQuery();
			return resultSet.next();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getTokennameAndDomain(String tokenname, String domainpre) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			String sql = "SELECT confirmed FROM tokens WHERE tokenname = ? AND domainpredblockhash = ?  ";
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, tokenname);
			preparedStatement.setString(2, domainpre);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getBoolean("confirmed");
			} else {
				return false;
			}

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public BlockWrap getTokenIssuingConfirmedBlock(String tokenid, long tokenIndex) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_ISSUING_CONFIRMED_BLOCK_SQL);
			preparedStatement.setString(1, tokenid);
			preparedStatement.setLong(2, tokenIndex);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return getBlockWrap(Sha256Hash.wrap(resultSet.getBytes(1)));
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public BlockWrap getDomainIssuingConfirmedBlock(String tokenName, String domainPred, long index)
			throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_DOMAIN_ISSUING_CONFIRMED_BLOCK_SQL);
			preparedStatement.setString(1, tokenName);
			preparedStatement.setString(2, domainPred);
			preparedStatement.setLong(3, index);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return getBlockWrap(Sha256Hash.wrap(resultSet.getBytes(1)));
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<String> getDomainDescendantConfirmedBlocks(String domainPred) throws BlockStoreException {
		List<String> storedBlocks = new ArrayList<String>();
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_DOMAIN_DESCENDANT_CONFIRMED_BLOCKS_SQL);
			preparedStatement.setString(1, domainPred);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				storedBlocks.add(Utils.HEX.encode(resultSet.getBytes("blockhash")));
			}
			return storedBlocks;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateTokenSpent(Sha256Hash blockhash, boolean b, Sha256Hash spenderBlockHash)
			throws BlockStoreException {
		
		PreparedStatement preparedStatement = null;
		try {

			preparedStatement = getConnection().prepareStatement(UPDATE_TOKEN_SPENT_SQL);
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, spenderBlockHash == null ? null : spenderBlockHash.getBytes());
			preparedStatement.setBytes(3, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateTokenConfirmed(Sha256Hash blockHash, boolean confirmed) throws BlockStoreException {
		
		PreparedStatement preparedStatement = null;
		try {

			preparedStatement = getConnection().prepareStatement(UPDATE_TOKEN_CONFIRMED_SQL);
			preparedStatement.setBoolean(1, confirmed);
			preparedStatement.setBytes(2, blockHash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<BlockEvaluationDisplay> getSearchBlockEvaluations(List<String> address, String lastestAmount,
			long height, long maxblocks) throws BlockStoreException {

		String sql = "";
		StringBuffer stringBuffer = new StringBuffer();
		if (!"0".equalsIgnoreCase(lastestAmount) && !"".equalsIgnoreCase(lastestAmount)) {
			sql += "SELECT hash,  "
					+ " height, milestone, milestonelastupdate,  inserttime,  blocktype, solid, confirmed "
					+ "  FROM  blocks ";
			sql += " where height >= " + height;
			sql += " ORDER BY insertTime desc ";
			Long a = Long.valueOf(lastestAmount);
			if (a > maxblocks) {
				a = maxblocks;
			}
			sql += " LIMIT " + a;
		} else {
			sql += "SELECT blocks.hash, "
					+ " blocks.height, milestone, milestonelastupdate,  inserttime,  blocktype, solid, blocks.confirmed"
					+ " FROM outputs JOIN blocks " + "ON outputs.blockhash = blocks.hash  ";
			sql += " where height >= " + height;
			sql += " and  outputs.toaddress in ";
			for (String str : address)
				stringBuffer.append(",").append("'" + str + "'");
			sql += "(" + stringBuffer.substring(1).toString() + ")";

			sql += " ORDER BY insertTime desc ";
		}
		List<BlockEvaluationDisplay> result = new ArrayList<BlockEvaluationDisplay>();
		TXReward maxConfirmedReward = getMaxConfirmedReward();
		
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluationDisplay blockEvaluation = BlockEvaluationDisplay.build(
						Sha256Hash.wrap(resultSet.getBytes("hash")), resultSet.getLong("height"),
						resultSet.getLong("milestone"), resultSet.getLong("milestonelastupdate"),
						resultSet.getLong("inserttime"), resultSet.getInt("blocktype"), resultSet.getLong("solid"),
						resultSet.getBoolean("confirmed"), maxConfirmedReward.getChainLength());
				blockEvaluation.setMcmcWithDefault(getMCMC(blockEvaluation.getBlockHash()));
				result.add(blockEvaluation);
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public  BlockEvaluation getBlockEvaluationsByhashs(Sha256Hash hash)
			throws BlockStoreException {
 
		PreparedStatement preparedStatement = null;
		try {

		 
				preparedStatement = getConnection().prepareStatement( "SELECT hash,  " + " height, milestone, milestonelastupdate,  inserttime,  blocktype, solid, confirmed "
						+ "  FROM  blocks WHERE hash = ? ");
				preparedStatement.setBytes(1,  hash.getBytes());
				ResultSet resultSet = preparedStatement.executeQuery();
				if (resultSet.next()) {
					 return BlockEvaluation.build(
							hash, resultSet.getLong("height"),
							resultSet.getLong("milestone"), resultSet.getLong("milestonelastupdate"),
							resultSet.getLong("inserttime"),  resultSet.getLong("solid"),
							resultSet.getBoolean("confirmed"));
				
				}
	 
			return null;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
	@Override
	public List<BlockEvaluationDisplay> getSearchBlockEvaluationsByhashs(List<String> blockhashs)
			throws BlockStoreException {

		List<BlockEvaluationDisplay> result = new ArrayList<BlockEvaluationDisplay>();
		if (blockhashs == null || blockhashs.isEmpty()) {
			return result;
		}
		String sql = "";

		sql += "SELECT hash,  " + " height, milestone, milestonelastupdate,  inserttime,  blocktype, solid, confirmed "
				+ "  FROM  blocks WHERE hash = ? ";

		
		TXReward maxConfirmedReward = getMaxConfirmedReward();
		PreparedStatement preparedStatement = null;
		try {

			for (String hash : blockhashs) {
				preparedStatement = getConnection().prepareStatement(sql);
				preparedStatement.setBytes(1, Utils.HEX.decode(hash));
				ResultSet resultSet = preparedStatement.executeQuery();
				while (resultSet.next()) {
					BlockEvaluationDisplay blockEvaluation = BlockEvaluationDisplay.build(
							Sha256Hash.wrap(resultSet.getBytes("hash")), resultSet.getLong("height"),
							resultSet.getLong("milestone"), resultSet.getLong("milestonelastupdate"),
							resultSet.getLong("inserttime"), resultSet.getInt("blocktype"), resultSet.getLong("solid"),
							resultSet.getBoolean("confirmed"), maxConfirmedReward.getChainLength());
					blockEvaluation.setMcmcWithDefault(getMCMC(blockEvaluation.getBlockHash()));
					result.add(blockEvaluation);
				}
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
	protected String buildINList(Collection<String> datalist) {
		if (datalist == null || datalist.isEmpty())
			return "";
		StringBuffer stringBuffer = new StringBuffer();
		for (String str : datalist)
			stringBuffer.append(",").append("'" + str + "'");
		return stringBuffer.substring(1).toString();
	}


	@Override
	public void updateOrderresultMilestone(Sha256Hash blockhash, long milestone) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		

		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ORDERRESULT_MILESTONE_SQL);
			preparedStatement.setLong(1, milestone);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}
	@Override
	public void updateContractresultMilestone(Sha256Hash blockhash, long milestone) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		

		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_CONTRACTRESULT_MILESTONE_SQL);
			preparedStatement.setLong(1, milestone);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}
}
