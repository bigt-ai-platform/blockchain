/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

import jakarta.annotation.Nullable;

import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.BlockEvaluation;
import net.bigtangle.core.BlockEvaluationDisplay;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.SpentBlockData;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
import net.bigtangle.core.TokenKeyValues;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.server.core.BlockWrap;
/**
 * <p>
 * A generic full block store for a relational database. This generic class
 * requires certain table structures for the block store.
 * </p>
 * 
 */
public abstract class DatabaseFullBlockStoreBase implements BlockStoreInterface {

	private static final String LIMIT_500 = " limit 500 ";

	// 3 params per outpoint keeps a batched IN query far below PostgreSQL's
	// 65,535-parameter ceiling.
	private static final int BATCH_CHUNK_SIZE = 1500;

	protected static final Logger log = LoggerFactory.getLogger(DatabaseFullBlockStoreBase.class);

	public static final String VERSION_SETTING = "version";

	/** The layer domain this store is provisioned for. Defaults to all domains. */
	private BlockStoreInterface.StoreDomain storeDomain = BlockStoreInterface.StoreDomain.ALL;

	/** The layer domain this store is provisioned for. */
	@Override
	public BlockStoreInterface.StoreDomain getStoreDomain() {
		return storeDomain;
	}

	/** Set the layer domain; controls which tables are created and which reads run. */
	public void setStoreDomain(BlockStoreInterface.StoreDomain storeDomain) {
		this.storeDomain = storeDomain == null ? BlockStoreInterface.StoreDomain.ALL : storeDomain;
	}

	// Drop table SQL.
	private static final String DROP_SETTINGS_TABLE = "DROP TABLE IF EXISTS settings";
	private static final String DROP_OPEN_OUTPUT_TABLE = "DROP TABLE IF EXISTS outputs";
	private static final String DROP_OUTPUTSMULTI_TABLE = "DROP TABLE IF EXISTS outputsmulti";
	private static final String DROP_TOKENS_TABLE = "DROP TABLE IF EXISTS tokens";
	private static final String DROP_MATCHING_TABLE = "DROP TABLE IF EXISTS matching";
	private static final String DROP_MULTISIGNADDRESS_TABLE = "DROP TABLE IF EXISTS multisignaddress";
	private static final String DROP_MULTISIGNBY_TABLE = "DROP TABLE IF EXISTS multisignby";
	private static final String DROP_MULTISIGN_TABLE = "DROP TABLE IF EXISTS multisign";
	private static final String DROP_TX_REWARDS_TABLE = "DROP TABLE IF EXISTS txreward";
	private static final String DROP_USERDATA_TABLE = "DROP TABLE IF EXISTS userdata";
	private static final String DROP_PAYMULTISIGN_TABLE = "DROP TABLE IF EXISTS paymultisign";
	private static final String DROP_PAYMULTISIGNADDRESS_TABLE = "DROP TABLE IF EXISTS paymultisignaddress";

	private static final String DROP_ORDERCANCEL_TABLE = "DROP TABLE IF EXISTS ordercancel";
	private static final String DROP_STAKE_DEPOSITS_TABLE = "DROP TABLE IF EXISTS stake_deposits";
	private static final String 		DROP_BATCHBLOCK_TABLE = "DROP TABLE IF EXISTS batchblock";
	private static final String DROP_TRANSACTIONSTATUS_TABLE = "DROP TABLE IF EXISTS transactionstatus";
	private static final String DROP_SUBTANGLE_PERMISSION_TABLE = "DROP TABLE IF EXISTS subtangle_permission";
	private static final String DROP_ORDERS_TABLE = "DROP TABLE IF EXISTS orders";

	private static final String DROP_MYSERVERBLOCKS_TABLE = "DROP TABLE IF EXISTS myserverblocks";

	private static final String DROP_ACCESS_PERMISSION_TABLE = "DROP TABLE  IF EXISTS access_permission";
	private static final String DROP_ACCESS_GRANT_TABLE = "DROP TABLE  IF EXISTS access_grant";
	private static final String DROP_CONTRACT_EVENT_TABLE = "DROP TABLE  IF EXISTS contractevent";
	private static final String DROP_CONTRACT_EVENT_CANCEL_TABLE = "DROP TABLE  IF EXISTS contracteventcancel";
	private static final String DROP_CONTRACT_RESULT_TABLE = "DROP TABLE IF EXISTS contractresult";
	private static final String DROP_ORDER_RESULT_TABLE = "DROP TABLE IF EXISTS orderresult";
	private static final String DROP_CHAINBLOCKQUEUE_TABLE = "DROP TABLE  IF EXISTS chainblockqueue";

	private static final String DROP_LOCKOBJECT_TABLE = "DROP TABLE  IF EXISTS lockobject";
	private static final String DROP_TIPSQUEUE_TABLE = "DROP TABLE  IF EXISTS tipsqueue";
	private static final String DROP_MATCHING_LAST_TABLE = "DROP TABLE  IF EXISTS matchinglast";
	private static final String DROP_MATCHINGDAILY_TABLE = "DROP TABLE  IF EXISTS matchingdaily";
	private static final String DROP_MATCHINGLASTDAY_TABLE = "DROP TABLE  IF EXISTS matchinglastday";
	private static final String DROP_ACCOUNT_TABLE = "DROP TABLE  IF EXISTS accountBalance";
	// Queries SQL.
	protected final String SELECT_SETTINGS_SQL = "SELECT settingvalue FROM settings WHERE name = ?";
	protected final String INSERT_SETTINGS_SQL = getInsert() + "  INTO settings(name, settingvalue) VALUES(?, ?)"
			+ duplicateInsert();

	protected final String SELECT_BLOCKS_TEMPLATE = "  blocks.hash, block,  "
			+ "  height, chainlength, chainlengthlastupdate,  inserttime,   solid, confirmed";

	protected final String SELECT_BLOCKS_SQL = " select " + SELECT_BLOCKS_TEMPLATE + " FROM blocks WHERE hash = ?";

	protected final String SELECT_BLOCKS_CHAINLENGTH_SQL = "SELECT block, height FROM blocks WHERE height "
			+ " >= (select min(height) from blocks where  chainlength >= ? and  chainlength <=?)"
			+ " and height <= (select max(height) from blocks where  chainlength >= ? and  chainlength <=?) "
			+ " order by height asc ";

	protected final String SELECT_NOT_INVALID_APPROVER_BLOCKS_SQL = "SELECT " + SELECT_BLOCKS_TEMPLATE
			+ "  FROM blocks WHERE (prevblockhash = ? or prevbranchblockhash = ?) AND solid >= 0 ";

	protected final String SELECT_APPROVER_HASHES_SQL = "SELECT hash FROM blocks "
			+ "WHERE blocks.prevblockhash = ? or blocks.prevbranchblockhash = ?";

	protected final String INSERT_BLOCKS_SQL = getInsert() + "  INTO blocks(hash,  height, block,  prevblockhash,"
			+ "prevbranchblockhash,blocktype,  "
			+ "chainlength, chainlengthlastupdate,  inserttime,  solid, confirmed  )"
			+ " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ? ,  ?, ? )" + duplicateInsert();

	protected final String INSERT_OUTPUTS_SQL = getInsert()
			+ " INTO outputs (hash, outputindex, coinvalue, scriptbytes, toaddress, addresstargetable,"
			+ " coinbase, blockhash, tokenid, fromaddress, memo, spent, confirmed, spendpending,time, spendpendingtime, minimumsign)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?)" + duplicateInsert();

	protected final String OUTPUTS_CONFIRMED = "(SELECT b.confirmed FROM blocks b WHERE b.hash = outputs.blockhash)";

	protected final String SELECT_OUTPUTS_SQL = "SELECT coinvalue, scriptbytes, coinbase, toaddress,"
			+ " addresstargetable, blockhash, tokenid, fromaddress, memo, spent, " + OUTPUTS_CONFIRMED + " AS confirmed, "
			+ "spendpending , spendpendingtime, minimumsign, time, spenderblockhash FROM outputs WHERE hash = ? AND outputindex = ? AND blockhash = ? ";

	protected final String SELECT_OUTPUTS_SPENTBLOCK_SQL = "SELECT " + " spent, " + OUTPUTS_CONFIRMED + " AS confirmed, "
			+ " spenderblockhash FROM outputs WHERE hash = ? AND outputindex = ? AND blockhash = ? ";

	protected final String SELECT_TRANSACTION_OUTPUTS_SQL_BASE = "SELECT " + "outputs.hash, coinvalue, scriptbytes, "
			+ " outputs.outputindex, coinbase, " + "  outputs.toaddress  as  toaddress,"
			+ " outputsmulti.toaddress  as multitoaddress, " + "  addresstargetable, blockhash, tokenid, "
			+ " fromaddress, memo, spent, " + OUTPUTS_CONFIRMED + " AS confirmed, "
			+ "spendpending,spendpendingtime,  minimumsign, time , spenderblockhash "
			+ " FROM outputs LEFT JOIN outputsmulti " + " ON outputs.hash = outputsmulti.hash"
			+ " AND outputs.outputindex = outputsmulti.outputindex ";

	/**
	 * Lean base without the {@code outputsmulti} join: the multisig display
	 * address is only needed by user-facing output listing
	 * ({@link #getOpenTransactionOutputsWithMultiSig}); every balance/spend path
	 * matches on {@code outputs.toaddress} alone, so paying for the join there
	 * is wasted query time on the hot path.
	 */
	protected final String SELECT_TRANSACTION_OUTPUTS_SQL_BASE_NO_MULTI = "SELECT " + "outputs.hash, coinvalue, "
			+ " scriptbytes, outputs.outputindex, coinbase, outputs.toaddress as toaddress, addresstargetable,"
			+ " blockhash, tokenid, fromaddress, memo, spent, " + OUTPUTS_CONFIRMED + " AS confirmed, "
			+ "spendpending, spendpendingtime, minimumsign, time , spenderblockhash"
			+ " FROM outputs ";

	protected final String SELECT_OPEN_TRANSACTION_OUTPUTS_SQL = SELECT_TRANSACTION_OUTPUTS_SQL_BASE
			+ " WHERE  " + OUTPUTS_CONFIRMED + " = true and spent= false and outputs.toaddress = ? " + " OR outputsmulti.toaddress = ?";

	protected final String SELECT_OPEN_TRANSACTION_OUTPUTS_NO_MULTI_SQL = SELECT_TRANSACTION_OUTPUTS_SQL_BASE_NO_MULTI
			+ " WHERE  " + OUTPUTS_CONFIRMED + " = true and spent= false and outputs.toaddress = ?";

	protected final String SELECT_OPEN_TRANSACTION_OUTPUTS_TOKEN_SQL = "SELECT " + " outputs.hash, coinvalue, "
			+ " scriptbytes, outputs.outputindex, coinbase, outputs.toaddress as toaddress , addresstargetable,"
			+ " blockhash, tokenid, fromaddress, memo, spent, " + OUTPUTS_CONFIRMED + " AS confirmed, spendpending, spendpendingtime, minimumsign, time , spenderblockhash"
			+ " , outputsmulti.toaddress  as multitoaddress" + " FROM outputs LEFT JOIN outputsmulti "
			+ " ON outputs.hash = outputsmulti.hash AND outputs.outputindex = outputsmulti.outputindex "
			+ " WHERE   (outputs.toaddress = ? " + " OR outputsmulti.toaddress = ?) " + " AND tokenid = ?";
	public final String SELECT_ALL_OUTPUTS_TOKEN_SQL = "SELECT " + " outputs.hash, coinvalue, "
			+ " scriptbytes, outputs.outputindex, coinbase, outputs.toaddress, addresstargetable,"
			+ " blockhash, tokenid, fromaddress, memo, spent, " + OUTPUTS_CONFIRMED + " AS confirmed, spendpending, spendpendingtime , minimumsign, time , spenderblockhash"
			+ " FROM outputs  WHERE  " + OUTPUTS_CONFIRMED + " = true and spent= false and tokenid = ?";

	// Tables exist SQL.
	protected final String SELECT_CHECK_TABLES_EXIST_SQL = "SELECT * FROM settings WHERE 1 = 2";

	protected final String SELECT_BLOCKS_SOLID_INTERVAL_SQL = "SELECT" + SELECT_BLOCKS_TEMPLATE
			+ " FROM blocks WHERE solid=2 AND chainlength = -1 AND confirmed = false AND height > ?"
			+ " AND height <= ?";

	protected final String SELECT_BLOCKS_TO_UNCONFIRM_SQL = "SELECT" + SELECT_BLOCKS_TEMPLATE
			+ "  FROM blocks WHERE solid=2 AND chainlength = -1 AND confirmed = true";

	protected final String SELECT_BLOCKS_IN_CHAINLENGTH_INTERVAL_SQL = "SELECT hash "
			+ "  FROM blocks WHERE chainlength >= ? AND chainlength <= ?";

	protected final String SELECT_SOLID_BLOCKS_IN_INTERVAL_SQL = "SELECT   " + SELECT_BLOCKS_TEMPLATE
			+ " FROM blocks WHERE   height > ? AND height <= ? AND solid = 2 ";

	protected final String SELECT_SOLID_BLOCK_TOPOLOGY_INTERVAL_SQL = "SELECT hash, prevblockhash, prevbranchblockhash, "
			+ " height, chainlength, chainlengthlastupdate, inserttime, solid, confirmed "
			+ " FROM blocks WHERE height > ? AND height <= ? AND solid = 2 ";

	protected final String SELECT_BLOCKS_FROM_AND_NOT_CHAINLENGTH_SQL = "SELECT hash, block "
			+ "FROM blocks WHERE chainlength = -1 AND height >= ? AND solid > -1 order by height desc ";

	/** Bulk repair sweep: all non-beacon blocks above the cutoff, connected or not. */
	protected final String SELECT_NONCHAIN_BLOCKS_FROM_HEIGHT_SQL = "SELECT hash, block "
			+ "FROM blocks WHERE height >= ? AND solid > -1 "
			+ "AND blocktype <> 'BLOCKTYPE_BEACON' order by height desc ";

	protected final String SELECT_HASHES_FROM_AND_NOT_CHAINLENGTH_SQL = "SELECT hash "
			+ "FROM blocks WHERE chainlength = -1 AND height >= ? AND solid > -1 order by height desc ";

	/**
	 * Orphaned (non-chain) blocks currently marked invalid. The reference sweep
	 * excludes these via {@code solid > -1}; this query feeds the bounded-retry
	 * rehabilitation so a block that failed one re-validation during a reorg
	 * gets another chance instead of starving forever.
	 */
	protected final String SELECT_INVALID_NONCHAIN_HASHES_SQL = "SELECT hash "
			+ "FROM blocks WHERE chainlength = -1 AND confirmed = false AND solid = -1 AND height >= ? "
			+ "ORDER BY height DESC LIMIT ?";

	protected final String UPDATE_ORDER_SPENT_SQL = getUpdate() + " orders SET spent = ?, spenderblockhash = ? "
			+ " WHERE blockhash = ? AND collectinghash = ?";

	protected final String UPDATE_ORDER_CONFIRMED_SQL = getUpdate() + " orders SET confirmed = ? "
			+ " WHERE blockhash = ? AND collectinghash = ?";

	protected final String ORDER_TEMPLATE = "  blockhash, collectinghash, offercoinvalue, offertokenid, "
			+ "confirmed, spent, spenderblockhash, targetcoinvalue, targettokenid, "
			+ "beneficiarypubkey, validToTime, validFromTime, side , beneficiaryaddress, orderbasetoken, price, tokendecimals ";
	protected final String SELECT_ORDERS_BY_ISSUER_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE collectinghash = ?";

	protected final String SELECT_ORDERS_NotSpent_BY_ISSUER_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE collectinghash = ? and spent=false";

	protected final String SELECT_ORDER_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE blockhash = ? AND collectinghash = ?";
	protected final String INSERT_ORDER_SQL = getInsert()
			+ "  INTO orders (blockhash, collectinghash, offercoinvalue, offertokenid, confirmed, spent, spenderblockhash, "
			+ "targetcoinvalue, targettokenid, beneficiarypubkey, validToTime, validFromTime, side, beneficiaryaddress, orderbasetoken, price, tokendecimals) "
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,  ?,?,?,?,?,?,?)" + duplicateInsert();

	protected final String INSERT_OrderCancel_SQL = getInsert()
			+ " INTO ordercancel (blockhash, orderblockhash, confirmed, spent, spenderblockhash,time) "
			+ " VALUES (?, ?, ?, ?, ?,?)" + duplicateInsert();

	protected final String INSERT_CONTRACT_EVENT_SQL = getInsert()
			+ "  INTO contractevent (blockhash, contracttokenid, confirmed, spent, spenderblockhash, "
			+ "targetcoinvalue, targettokenid,    beneficiaryaddress, collectinghash) "
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?,?)" + duplicateInsert();
	protected final String CONTRACT_TEMPLATE = " blockhash, collectinghash, contracttokenid, confirmed, spent, spenderblockhash,  "
			+ "targetcoinvalue, targettokenid,    beneficiaryaddress";

	protected final String SELECT_PREV_CONTRACT_SQL = "SELECT " + CONTRACT_TEMPLATE
			+ " FROM contractevent WHERE contracttokenid = ? AND  collectinghash=? ";

	protected final String SELECT_CONTRACT_SQL = "SELECT " + CONTRACT_TEMPLATE
			+ " FROM contractevent WHERE blockhash = ? AND  collectinghash=? ";
	protected final String SELECT_OPEN_CONTRACT_EVENT_SQL = "SELECT " + CONTRACT_TEMPLATE
			+ " FROM contractevent WHERE confirmed=true AND spent=false AND targettokenid= ? ";

	protected final String UPDATE_CONTRACTRESULT_CONFIRMED_SQL = getUpdate() + " contractresult SET confirmed = ? "
			+ " WHERE blockhash = ?";

	protected final String INSERT_CONTRACT_RESULT_SQL = getInsert()
			+ "  INTO contractresult (blockhash,  contracttokenid, confirmed, spent, spenderblockhash, "
			+ " contractresult, prevblockhash, inserttime, rewardchainlength,chainlength) "
			+ " VALUES (?, ?, ?, ?, ?, ?,?,?,?,?)" + duplicateInsert();
	protected final String SELECT_CONTRACTRESULT = "SELECT  blockhash,  contracttokenid, confirmed, spent, spenderblockhash,  "
			+ " contractresult, prevblockhash, inserttime, rewardchainlength, chainlength" + " FROM contractresult ";
	protected final String SELECT_CONTRACTRESULT_HASH_SQL = SELECT_CONTRACTRESULT + "  WHERE blockhash=?   ";
	protected final String SELECT_CONTRACTRESULT_PREV_HASH_SQL = SELECT_CONTRACTRESULT + "  WHERE prevblockhash=?   ";

	protected final String SELECT_CONTRACTRESULT_MAX_CHAINLENGTH_SQL = SELECT_CONTRACTRESULT
			+ " WHERE confirmed = true and contracttokenid=?  and spent=false  and rewardchainlength >0 order by chainlength desc, blockhash asc limit 1 ";
	protected final String SELECT_CONTRACTRESULT_MAX_CONFIRMED_SQL = SELECT_CONTRACTRESULT
			+ " WHERE confirmed = true and contracttokenid=?   order by chainlength desc, blockhash asc limit 1   ";
	protected final String SELECT_CONTRACTRESULT_LOWER_CONFIRMED_SQL = SELECT_CONTRACTRESULT
			+ " WHERE rewardchainlength < 0 and  confirmed = true and contracttokenid=? and chainlength <=?   ";

	protected final String UPDATE_CONTRACTRESULT_CHAINLENGTH_SQL = getUpdate()
			+ " contractresult SET rewardchainlength = ?   WHERE blockhash = ?";

	protected final String INSERT_EVM_RECEIPT_SQL = getInsert() + "  INTO evm_receipt (blockhash, contracttokenid, "
			+ " receipt, inserttime) " + " VALUES (?, ?, ?, ?)" + duplicateInsert();
	protected final String SELECT_EVM_RECEIPT_SQL = "SELECT contracttokenid, receipt FROM evm_receipt WHERE blockhash = ?";
	protected final String SELECT_EVM_RECEIPTS_BY_TOKEN_SQL = "SELECT receipt FROM evm_receipt WHERE contracttokenid = ? ORDER BY inserttime DESC";


	protected final String UPDATE_ORDERRESULT_CONFIRMED_SQL = getUpdate() + " orderresult SET confirmed = ? "
			+ " WHERE blockhash = ?";
	protected final String INSERT_ORDER_RESULT_SQL = getInsert()
			+ "  INTO orderresult (blockhash, confirmed, spent, spenderblockhash, "
			+ " orderresult, prevblockhash, inserttime,  rewardchainlength,chainlength) " + " VALUES (?, ?, ?, ?, ?, ?,?,?,?)"
			+ duplicateInsert();
	protected final String SELECT_ORDERRESULT = "  select blockhash, confirmed, spent, spenderblockhash, "
			+ " orderresult, prevblockhash, inserttime ,  rewardchainlength, chainlength" + " FROM orderresult ";
	protected final String SELECT_ORDERRESULT_MAX_CONFIRMED_SQL = SELECT_ORDERRESULT
			+ " WHERE confirmed = true   order by chainlength desc, blockhash asc limit 1  ";
	protected final String SELECT_ORDERRESULT_LOWER_CONFIRMED_SQL = SELECT_ORDERRESULT
			+ " WHERE confirmed = true and rewardchainlength < 0  and  chainlength  <=?  ";
	protected final String SELECT_ORDERRESULT_HASH_SQL = SELECT_ORDERRESULT + " WHERE blockhash=?";
	protected final String SELECT_ORDERRESULT_PREV_HASH_SQL = SELECT_ORDERRESULT + " WHERE prevblockhash=?";
	protected final String SELECT_ORDER_RESULT_MAX_CHAINLENGTH_SQL = SELECT_ORDERRESULT
			+ " WHERE confirmed = true and chainlength >0 and spent=false order by chainlength desc, blockhash asc limit 1";
	protected final String UPDATE_ORDERRESULT_CHAINLENGTH_SQL = getUpdate()
			+ " orderresult SET rewardchainlength = ?   WHERE blockhash = ?";

	protected final String INSERT_TOKENS_SQL = getInsert()
			+ " INTO tokens (blockhash, confirmed, tokenid, tokenindex, amount, "
			+ "tokenname, description, domainname, signnumber,tokentype, tokenstop,"
			+ " prevblockhash, spent, spenderblockhash, tokenkeyvalues, revoked,language,classification, decimals, domainpredblockhash) "
			+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?,?,?)" + duplicateInsert();

	protected String SELECT_TOKENS_SQL_TEMPLATE = "SELECT blockhash, confirmed, tokenid, tokenindex, amount, tokenname, description, domainname, signnumber,tokentype, tokenstop ,"
			+ "tokenkeyvalues, revoked,language,classification,decimals, domainpredblockhash ";

	protected final String SELECT_TOKEN_SPENT_BY_BLOCKHASH_SQL = "SELECT confirmed, spent,spenderblockhash FROM tokens WHERE blockhash = ?";

	protected final String SELECT_TOKEN_ANY_CONFIRMED_SQL = "SELECT confirmed FROM tokens WHERE tokenid = ? AND tokenindex = ? AND confirmed = true";

	protected final String SELECT_TOKEN_ISSUING_CONFIRMED_BLOCK_SQL = "SELECT blockhash FROM tokens WHERE tokenid = ? AND tokenindex = ? AND confirmed = true";

	protected final String SELECT_DOMAIN_ISSUING_CONFIRMED_BLOCK_SQL = "SELECT blockhash FROM tokens WHERE tokenname = ? AND domainpredblockhash = ? AND tokenindex = ? AND confirmed = true";

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
			+ " outputs SET spent = ?, spenderblockhash = ?, spendpending = ?, spendpendingtime = ? WHERE hash = ? AND outputindex= ? AND blockhash = ?";

	protected final String UPDATE_OUTPUTS_CONFIRMED_SQL = getUpdate()
			+ " outputs SET confirmed = ? WHERE hash = ? AND outputindex= ? AND blockhash = ?";

	protected final String UPDATE_ALL_OUTPUTS_CONFIRMED_SQL = getUpdate()
			+ " outputs SET confirmed = ? WHERE blockhash = ?";

	protected final String UPDATE_OUTPUTS_SPENDPENDING_SQL = getUpdate()
			+ " outputs SET spendpending = ?, spendpendingtime=? WHERE hash = ? AND outputindex= ? AND blockhash = ?";

	protected final String UPDATE_BLOCKEVALUATION_CHAINLENGTH_SQL = getUpdate()
			+ " blocks SET chainlength = ?, chainlengthlastupdate= ?  WHERE hash = ?";

	protected final String UPDATE_BLOCKEVALUATION_CONFIRMED_SQL = getUpdate()
			+ " blocks SET confirmed = ? WHERE hash = ?";

	protected final String UPDATE_BLOCKEVALUATION_CONFIRMED_CHAINLENGTH_SQL = getUpdate()
			+ " blocks SET confirmed = ?, chainlength = ?, chainlengthlastupdate = ? WHERE hash = ?";

	protected final String UPDATE_BLOCKEVALUATION_SOLID_SQL = getUpdate() + " blocks SET solid = ? WHERE hash = ?";
	protected final String RESET_CHAINLENGTH_SOLID_SQL = getUpdate() + " blocks SET solid = 0 WHERE chainlength = ?";

	protected final String SELECT_MULTISIGNADDRESS_SQL = "SELECT blockhash, tokenid, address, pubKeyHex, posIndex, tokenHolder FROM multisignaddress WHERE tokenid = ? AND blockhash = ?";
	protected final String INSERT_MULTISIGNADDRESS_SQL = "INSERT INTO multisignaddress (tokenid, address, pubKeyHex, posIndex,blockhash,tokenHolder) VALUES (?, ?, ?, ?,?,?)"
			+ duplicateInsert();

	protected final String SELECT_MULTISIGN_ADDRESS_SQL = "SELECT id, tokenid, tokenindex, address, blockhash, sign FROM multisign WHERE address = ? ORDER BY tokenindex ASC";
	protected final String SELECT_MULTISIGN_TOKENID_ADDRESS_SQL = "SELECT id, tokenid, tokenindex, address, blockhash, sign FROM multisign WHERE tokenid = ? and address = ? ORDER BY tokenindex ASC";

	protected final String INSERT_MULTISIGN_SQL = "INSERT INTO multisign (tokenid, tokenindex, address, blockhash, sign, id) VALUES (?, ?, ?, ?, ?, ?)"
			+ duplicateInsert();
	protected final String UPDATE_MULTISIGN_SQL = "UPDATE multisign SET blockhash = ?, sign = ? WHERE tokenid = ? AND tokenindex = ? AND address = ?";
	protected final String UPDATE_MULTISIGN1_SQL = "UPDATE multisign SET blockhash = ? WHERE tokenid = ? AND tokenindex = ?";
	protected final String SELECT_COUNT_MULTISIGN_SQL = "SELECT COUNT(*) as count FROM multisign WHERE tokenid = ? AND tokenindex = ? AND address = ? ";
	protected final String SELECT_COUNT_ALL_MULTISIGN_SQL = "SELECT COUNT(*) as count FROM multisign WHERE tokenid = ? AND tokenindex = ?  AND sign=?";

	protected final String DELETE_MULTISIGN_SQL = "DELETE FROM multisign WHERE tokenid = ?";

	/* REWARD */
	protected final String INSERT_TX_REWARD_SQL = getInsert()
			+ "  INTO txreward (blockhash, confirmed, spent, spenderblockhash, prevblockhash, chainlength) VALUES (?, ?, ?, ?, ?, ?)"
			+ duplicateInsert();
	protected final String SELECT_TX_REWARD_MAX_CONFIRMED_REWARD_SQL = "SELECT blockhash, confirmed, spent, spenderblockhash, prevblockhash, chainlength FROM txreward"
			+ " WHERE confirmed = true  order by chainlength desc, blockhash asc limit 1";
	protected final String SELECT_TX_REWARD_CONFIRMED_AT_HEIGHT_REWARD_SQL = "SELECT blockhash, confirmed, spent, spenderblockhash, prevblockhash, chainlength FROM txreward"
			+ " WHERE confirmed = true AND chainlength=?  order by blockhash asc limit 1";
	protected final String SELECT_TX_REWARD_ALL_CONFIRMED_REWARD_SQL = "SELECT blockhash, confirmed, "
			+ "spent, spenderblockhash, prevblockhash, chainlength FROM txreward "
			+ "WHERE confirmed = true order by chainlength, blockhash";

	protected final String SELECT_TX_REWARD_CONFIRMED_SQL = "SELECT confirmed " + "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_CHAINLENGTH_SQL = "SELECT chainlength "
			+ "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_SPENT_SQL = "SELECT spent " + "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_SPENDER_SQL = "SELECT spenderblockhash "
			+ "FROM txreward WHERE blockhash = ?";
	protected final String SELECT_TX_REWARD_PREVBLOCKHASH_SQL = "SELECT prevblockhash "
			+ "FROM txreward WHERE blockhash = ?";
	protected final String UPDATE_TX_REWARD_CONFIRMED_SQL = "UPDATE txreward SET confirmed = ? WHERE blockhash = ?";
	protected final String UPDATE_TX_REWARD_SPENT_SQL = "UPDATE txreward SET spent = ?, spenderblockhash = ? WHERE blockhash = ?";

	/* TRANSACTION STATUS */
	protected final String INSERT_TRANSACTIONSTATUS_SQL = getInsert()
			+ " INTO transactionstatus (txhash, status, blockhash, chainlength, address, createdtime, updatedtime)"
			+ " VALUES (?, ?, ?, ?, ?, ?, ?)";
	protected final String UPDATE_TRANSACTIONSTATUS_SQL = getUpdate()
			+ " transactionstatus SET status = ?, blockhash = ?, chainlength = ?, address = ?, updatedtime = ? WHERE txhash = ?";
	protected final String SELECT_TRANSACTIONSTATUS_SQL = "SELECT txhash, status, blockhash, chainlength, address, createdtime, updatedtime"
			+ " FROM transactionstatus WHERE txhash = ?";
	protected final String SELECT_TRANSACTIONSTATUS_BY_STATUS_SQL = "SELECT txhash, status, blockhash, chainlength, address, createdtime, updatedtime"
			+ " FROM transactionstatus WHERE status = ?";
	protected final String SELECT_TRANSACTIONSTATUS_BY_ADDRESS_SQL = "SELECT txhash, status, blockhash, chainlength, address, createdtime, updatedtime"
			+ " FROM transactionstatus WHERE address = ?";

	/* MATCHING EVENTS */
	protected final String INSERT_MATCHING_EVENT_SQL = getInsert()
			+ " INTO matching (txhash, tokenid, basetokenid, price, executedQuantity, inserttime) VALUES (?, ?, ?, ?, ?, ?)"
			+ duplicateInsert();
	protected final String SELECT_MATCHING_EVENT = "SELECT txhash, tokenid,basetokenid,  price, executedQuantity, inserttime "
			+ "FROM matching ";
	protected final String DELETE_MATCHING_EVENT_BY_HASH = "DELETE FROM matching WHERE txhash = ?";
	// lastest MATCHING EVENTS
	protected final String INSERT_MATCHING_EVENT_LAST_SQL = getInsert()
			+ " INTO matchinglast (txhash, tokenid, basetokenid, price, executedQuantity, inserttime, token_basetoken_md5) VALUES (?, ?, ?, ?, ?, ?, ?)"
			+ duplicateInsert();
	protected final String DELETE_MATCHING_EVENT_LAST_BY_KEY = "DELETE FROM matchinglast WHERE tokenid = ? and basetokenid=?";

	/* OTHER */
	protected final String INSERT_OUTPUTSMULTI_SQL = "insert into outputsmulti (hash, toaddress, outputindex) values (?, ?, ?)"
			+ duplicateInsert();
	protected final String SELECT_OUTPUTSMULTI_SQL = "select hash, toaddress, outputindex from outputsmulti where hash=? and outputindex=?";

	protected final String SELECT_USERDATA_SQL = "SELECT blockhash, dataclassname, data, pubKey, blocktype FROM userdata WHERE dataclassname = ? and pubKey = ?";
	protected final String INSERT_USERDATA_SQL = "INSERT INTO userdata (blockhash, dataclassname, data, pubKey, blocktype) VALUES (?, ?, ?, ?, ?)"
			+ duplicateInsert();
	protected final String UPDATE_USERDATA_SQL = "UPDATE userdata SET blockhash = ?, data = ? WHERE dataclassname = ? and pubKey = ?";

	protected final String INSERT_BATCHBLOCK_SQL = "INSERT INTO batchblock (hash, block, inserttime) VALUES (?, ?, ?)"
			+ duplicateInsert();
	protected final String DELETE_BATCHBLOCK_SQL = "DELETE FROM batchblock WHERE hash = ?";
	protected final String SELECT_BATCHBLOCK_SQL = "SELECT hash, block, inserttime FROM batchblock order by inserttime ASC";

	protected final String INSERT_SUBTANGLE_PERMISSION_SQL = "INSERT INTO  subtangle_permission (pubkey, userdataPubkey , status) VALUES (?, ?, ?)"
			+ duplicateInsert();

	protected final String DELETE_SUBTANGLE_PERMISSION_SQL = "DELETE FROM  subtangle_permission WHERE pubkey=?";
	protected final String UPATE_ALL_SUBTANGLE_PERMISSION_SQL = "UPDATE   subtangle_permission set status=? ,userdataPubkey=? WHERE  pubkey=? ";

	protected final String SELECT_ALL_SUBTANGLE_PERMISSION_SQL = "SELECT   pubkey, userdataPubkey , status FROM subtangle_permission ";

	protected final String SELECT_SUBTANGLE_PERMISSION_BY_PUBKEYS_SQL = "SELECT   pubkey, userdataPubkey , status FROM subtangle_permission WHERE 1=1 ";

	protected final String SELECT_OPEN_ORDERS_SORTED_SQL = "SELECT " + ORDER_TEMPLATE
			+ " FROM orders WHERE confirmed=true AND spent=false ";

	protected final String SELECT_AVAILABLE_UTXOS_SORTED_SQL = "SELECT coinvalue, scriptbytes, coinbase, toaddress, "
			+ "addresstargetable, blockhash, tokenid, fromaddress, memo, spent, " + OUTPUTS_CONFIRMED + " AS confirmed, spendpending,spendpendingtime, minimumsign, time, hash, outputindex, spenderblockhash "
			+ " FROM outputs WHERE " + OUTPUTS_CONFIRMED + " = true AND spent=false ORDER BY hash, outputindex";

	protected final String SELECT_ORDERCANCEL_SQL = "SELECT blockhash, orderblockhash, confirmed, spent, spenderblockhash,time"
			+ " FROM ordercancel WHERE 1 = 1";
	protected final String ORDERCANCEL_UPDATE_SPENT_SQL = "UPDATE ordercancel SET spent = ?, spenderblockhash=?  WHERE blockhash = ? ";

	protected final String ChainBlockQueueColumn = " hash, block, chainlength, orphan, inserttime";
	protected final String INSERT_CHAINBLOCKQUEUE = getInsert() + "  INTO chainblockqueue (" + ChainBlockQueueColumn
			+ ") " + " VALUES (?, ?, ?,?,?)" + duplicateInsert();
	protected final String SELECT_CHAINBLOCKQUEUE = " select " + ChainBlockQueueColumn + " from chainblockqueue  ";

	protected final String CONTRACTEVENTCANCEL_UPDATE_SPENT_SQL = "UPDATE contracteventcancel SET spent = ?, spenderblockhash=?  WHERE blockhash = ? ";

	protected final String INSERT_ANCHOR_SQL = "INSERT INTO anchor (chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, signatureHex, signatureHexList, spvProofHex, burnJson, blockHash, confirmed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			+ duplicateInsert();
	protected final String SELECT_ANCHOR_BY_CHAINID_HEIGHT_SQL = "SELECT chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, signatureHex, signatureHexList, spvProofHex, burnJson, blockHash, confirmed FROM anchor WHERE chainId = ? AND l1Height = ?";
	protected final String SELECT_ANCHORS_BY_CHAINID_SQL = "SELECT chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, signatureHex, signatureHexList, spvProofHex, burnJson, blockHash, confirmed FROM anchor WHERE chainId = ? AND l1Height >= ? ORDER BY l1Height ASC";
	protected final String SELECT_LATEST_ANCHOR_BY_CHAINID_SQL = "SELECT chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, signatureHex, signatureHexList, spvProofHex, burnJson, blockHash, confirmed FROM anchor WHERE chainId = ? ORDER BY l1Height DESC LIMIT 1";
	protected final String SELECT_ANCHOR_BY_BLOCKHASH_SQL = "SELECT chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, signatureHex, signatureHexList, spvProofHex, burnJson, blockHash, confirmed FROM anchor WHERE blockHash = ?";
	protected final String SELECT_ALL_ANCHORS_SQL = "SELECT chainId, eventId, l1RewardHeadHash, l1Height, confirmedRoot, signatureHex, signatureHexList, spvProofHex, burnJson, blockHash, confirmed FROM anchor";
	protected final String UPDATE_ANCHOR_CONFIRMED_SQL = "UPDATE anchor SET confirmed = ? WHERE chainId = ? AND l1Height = ?";
	protected final String INSERT_VAULT_SQL = "INSERT INTO vault (chainId, utxoBlockHash, utxoIndex, pegInBlockHash, amount, tokenIdHex, ownerAddress, spent) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
	protected final String SELECT_VAULT_BY_CHAINID_SQL = "SELECT chainId, utxoBlockHash, utxoIndex, pegInBlockHash, amount, tokenIdHex, ownerAddress, spent FROM vault WHERE chainId = ? AND spent = ?";
	protected final String UPDATE_VAULT_SPENT_SQL = "UPDATE vault SET spent = ? WHERE chainId = ? AND utxoBlockHash = ? AND utxoIndex = ?";

	protected NetworkParameters params;
	protected Connection conn;

	public Connection getConnection() throws SQLException {

		return conn;
	}

	@Override
	public void setBatchDurability(boolean asyncCommit) throws BlockStoreException {
		// synchronous_commit is PostgreSQL-only; other drivers are left as-is (no-op).
		if (!(conn instanceof org.postgresql.PGConnection)) {
			return;
		}
		try {
			try (PreparedStatement s = getConnection()
					.prepareStatement("SET synchronous_commit = " + (asyncCommit ? "off" : "on"))) {
				s.execute();
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
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

	protected String duplicateInsert() {
		return "";
	}

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
		List<String> sqlStatements = new ArrayList<>();
		sqlStatements.add(DROP_SETTINGS_TABLE);
		String DROP_BLOCKS_TABLE = "DROP TABLE IF EXISTS blocks";
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
		sqlStatements.add("DROP TABLE IF EXISTS pos_state");
		sqlStatements.add("DROP TABLE IF EXISTS attestation_votes");
		sqlStatements.add(DROP_STAKE_DEPOSITS_TABLE);
		sqlStatements.add(DROP_BATCHBLOCK_TABLE);
		sqlStatements.add(DROP_TRANSACTIONSTATUS_TABLE);
		sqlStatements.add(DROP_SUBTANGLE_PERMISSION_TABLE);
		sqlStatements.add(DROP_ORDERS_TABLE);
		sqlStatements.add(DROP_MYSERVERBLOCKS_TABLE);
		sqlStatements.add(DROP_ACCESS_PERMISSION_TABLE);
		sqlStatements.add(DROP_ACCESS_GRANT_TABLE);
		sqlStatements.add(DROP_CONTRACT_EVENT_TABLE);
		sqlStatements.add(DROP_CONTRACT_EVENT_CANCEL_TABLE);
		sqlStatements.add(DROP_CHAINBLOCKQUEUE_TABLE);
		sqlStatements.add(DROP_LOCKOBJECT_TABLE);
		sqlStatements.add(DROP_TIPSQUEUE_TABLE);
		sqlStatements.add(DROP_MATCHING_LAST_TABLE);
		sqlStatements.add(DROP_MATCHINGDAILY_TABLE);
		sqlStatements.add(DROP_MATCHINGLASTDAY_TABLE);
		sqlStatements.add(DROP_ACCOUNT_TABLE);
		sqlStatements.add("DROP TABLE IF EXISTS anchor");
		sqlStatements.add("DROP TABLE IF EXISTS vault");
		sqlStatements.add("DROP TABLE IF EXISTS evm_receipt");
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

	@Override
	public void close() {
		if (conn == null) return;
		try {
			if (!conn.getAutoCommit()) {
				conn.rollback();
			}
		} catch (Exception e) {
			// Ignore rollback failures but still close
		}
		try {
			conn.close();
		} catch (Exception e) {
			// Ignore
		}
		conn = null;
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
	 */
	private synchronized void createTables() throws SQLException, BlockStoreException {
		// beginDatabaseBatchWrite();
		// create all the database tables
		updateTables(getCreateTablesSQL());
		// create all the database indexes
		updateTables(getCreateIndexesSQL());
		// insert the initial settings for this store
		dbversion();
		createNewStore(params);
	}

	/*
	 * initial ps.setBytes(2, "03".getBytes());
	 */
	private void dbversion() throws SQLException {
		PreparedStatement ps = getConnection().prepareStatement(getInsertSettingsSQL());
		ps.setString(1, VERSION_SETTING);
		ps.setBytes(2, "05".getBytes());
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
	protected synchronized void updateTables(List<String> sqls) throws SQLException {

		try (Statement s = getConnection().createStatement()) {
			for (String sql : sqls) {
				s.addBatch(sql);
			}
			s.executeBatch();
		}

	}

	/**
	 * Create a new store for the given
	 * {@link net.bigtangle.params.NetworkParameters}.
	 * 
	 * @param params The network.
	 * @throws BlockStoreException If the store couldn't be created.
	 */
	private void createNewStore(NetworkParameters params) throws BlockStoreException {
		try {

			Block genesisBlock = UtilGeneseBlock.createGenesis( params );
			saveNewStore(genesisBlock);
			if (params.genesisMintsBIG()) {
			    saveGenesisTransactionOutput(genesisBlock);
			}

			// Just fill the tables with some valid data
			// Reward output table
			insertReward(genesisBlock.getHash(), Sha256Hash.ZERO_HASH, 0);
			updateRewardConfirmed(genesisBlock.getHash(), true);

			// create bigtangle Token output table
			Token bigtangle = Token.genesisToken(params);
			insertToken(bigtangle.getBlockHash(), bigtangle);
			updateTokenConfirmed(genesisBlock.getHash(), true);

		} catch (VerificationException e) {
			throw new RuntimeException(e); // Cannot happen.
		}
	}

	private void saveNewStore(Block b) throws BlockStoreException {
		put(b);

		updateBlockEvaluationChainlength(b.getHash(), 0);

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
			List<UTXO> a = new ArrayList<>();
			a.add(newOut);
			addUnspentTransactionOutput(a);
			if (script.isSentToMultiSig()) {

				for (PQKey ecKey : script.getPubKeys()) {
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
			byte[] rawBlock = block.unsafeBitcoinSerialize();
			s.setBytes(3, rawBlock);

			s.setBytes(4, block.getPrevBlockHash().getBytes());
			s.setBytes(5, block.getPrevBranchBlockHash().getBytes());
			s.setString(6, block.getBlockType().name());

			int j = 1;
			s.setLong(j + 6, blockEvaluation.getChainlength());
			s.setLong(j + 7, blockEvaluation.getChainlengthLastUpdateTime());
			s.setLong(j + 8, blockEvaluation.getInsertTime());
			s.setLong(j + 9, blockEvaluation.getSolid());
			s.setBoolean(j + 10, blockEvaluation.isConfirmed());

			s.executeUpdate();
			s.close();
		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw e;
		}
	}

	/** Convert a blocktype DB string value to ordinal. */
	protected static int blockTypeFromDB(ResultSet rs) throws SQLException {
		String bt = rs.getString("blocktype");
		if (bt == null) return 0;
		return net.bigtangle.core.BlockType.valueOf(bt).ordinal();
	}

	/** Thread-local flag to skip cache operations (put/evict) during batch.
	 *  Batch blocks are transient mempool dumps — caching is unnecessary. */
	private static final ThreadLocal<Boolean> SKIP_CACHE = ThreadLocal.withInitial(() -> false);

	public static AutoCloseable skipCacheForBatch() {
		SKIP_CACHE.set(true);
		return () -> SKIP_CACHE.remove();
	}

	public static boolean isCacheSkipped() {
		return SKIP_CACHE.get();
	}

	/** Thread-local flag to use PostgreSQL COPY instead of batch INSERT
	 *  for UTXO bulk loading.  COPY streams raw data directly to PG
	 *  without SQL parsing, 2-5x faster for large batches. */
	private static final ThreadLocal<Boolean> USE_PG_COPY = ThreadLocal.withInitial(() -> false);

	public static AutoCloseable usePgCopyForBatch() {
		USE_PG_COPY.set(true);
		return () -> USE_PG_COPY.remove();
	}

	public void put(Block block) throws BlockStoreException {
		try {
			BlockEvaluation blockEval = BlockEvaluation.buildInitial(block);
			putUpdateStoredBlock(block, blockEval);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public Block get(Sha256Hash hash) throws BlockStoreException {
		// log.info("find block hexStr : " + hash.toString());
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_BLOCKS_SQL)) {
			s.setBytes(1, hash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return null;
			}
			return params.getDefaultSerializer().makeZippedBlock(results.getBytes("block"));

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public byte[] getByte(Sha256Hash hash) throws BlockStoreException {

		// log.info("find block hexStr : " + hash.toString());
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_BLOCKS_SQL)) {
			s.setBytes(1, hash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return null;
			}
			// Parse it.

			return results.getBytes(2);

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<byte[]> blocksFromChainLength(long start, long end) {
		// Optimize for chain head
		List<byte[]> re = new ArrayList<>();

		// log.info("find block hexStr : " + hash.toString());
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_BLOCKS_CHAINLENGTH_SQL)) {
			s.setLong(1, start);
			s.setLong(2, end);
			s.setLong(3, start);
			s.setLong(4, end);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				re.add(results.getBytes("block"));
			}
			return re;
		} catch (Exception ex) {
			log.warn("", ex);
		}

		return re;
	}

	@Override
	public List<byte[]> blocksFromNonChainHeigth(long heigth) {
		List<byte[]> re = new ArrayList<>();

		// Bulk repair for a lagging node: serve every non-beacon block above
		// its cutoff, CONNECTED or not. The previous chainlength = -1 filter
		// made this endpoint useless on healthy peers (they have no unconnected
		// blocks left), so a node behind the mesh could never pull the
		// referenced transfer blocks its queued beacons depend on.
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_NONCHAIN_BLOCKS_FROM_HEIGHT_SQL)) {
			s.setLong(1, heigth);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				re.add(results.getBytes("block"));
			}
			return re;
		} catch (Exception ex) {
			log.warn("", ex);
		}

		return re;
	}

	@Override
	public List<Sha256Hash> hashesFromNonChainHeigth(long heigth) throws BlockStoreException {
		List<Sha256Hash> re = new ArrayList<>();
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_HASHES_FROM_AND_NOT_CHAINLENGTH_SQL)) {
			s.setLong(1, heigth);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				re.add(Sha256Hash.wrap(results.getBytes("hash")));
			}
			return re;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
	}

	@Override
	public List<Sha256Hash> invalidNonChainHashes(long cutoffHeight, int limit) throws BlockStoreException {
		List<Sha256Hash> re = new ArrayList<>();
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_INVALID_NONCHAIN_HASHES_SQL)) {
			s.setLong(1, cutoffHeight);
			s.setInt(2, limit);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				re.add(Sha256Hash.wrap(results.getBytes("hash")));
			}
			return re;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
	}

	private boolean verifyHeader(Block block) {
		try {
			block.verifyHeader();
			return true;
		} catch (VerificationException e) {
			return false;
		}
	}

	@Override
	public List<BlockWrap> getNotInvalidApproverBlocks(Sha256Hash hash) throws BlockStoreException {
		List<BlockWrap> storedBlocks = new ArrayList<>();

		try (PreparedStatement s = getConnection().prepareStatement(SELECT_NOT_INVALID_APPROVER_BLOCKS_SQL)) {
			s.setBytes(1, hash.getBytes());
			s.setBytes(2, hash.getBytes());
			ResultSet resultSet = s.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluationNumber(resultSet);
				Block block = params.getDefaultSerializer().makeZippedBlockStream(resultSet.getBinaryStream("block"));
				if (verifyHeader(block)) {
					storedBlocks.add(new BlockWrap(block, blockEvaluation, params));
				}
			}
			return storedBlocks;
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Sha256Hash> getApproverBlockHashes(Sha256Hash hash) throws BlockStoreException {
		List<Sha256Hash> storedBlockHash = new ArrayList<>();

		try (PreparedStatement s = getConnection().prepareStatement(SELECT_APPROVER_HASHES_SQL)) {
			s.setBytes(1, hash.getBytes());
			s.setBytes(2, hash.getBytes());
			ResultSet results = s.executeQuery();
			while (results.next()) {
				storedBlockHash.add(Sha256Hash.wrap(results.getBytes(1)));
			}
			return storedBlockHash;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
	}

	@Override
	public boolean getOutputConfirmation(Sha256Hash blockHash, Sha256Hash hash, long index) throws BlockStoreException {

		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT  confirmed " + "FROM blocks WHERE hash = ? ")) {
			s.setBytes(1, blockHash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return false;
			}
			return results.getBoolean("confirmed");

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
	}

	@Override
	public boolean isBlockConfirmed(Sha256Hash blockHash) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT confirmed " + "FROM blocks WHERE hash = ? ")) {
			s.setBytes(1, blockHash.getBytes());
			ResultSet results = s.executeQuery();
			return results.next() && results.getBoolean("confirmed");
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
	}

	@Override
	public long countSpentOutputs(Sha256Hash txHash) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT count(*) FROM outputs WHERE hash = ? AND spent = true")) {
			s.setBytes(1, txHash.getBytes());
			ResultSet results = s.executeQuery();
			results.next();
			return results.getLong(1);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
	}

	@Override
	public SpentBlockData getTransactionSpentBlock(Sha256Hash blockHash, Sha256Hash hash, long index)
			throws BlockStoreException {

		try (PreparedStatement s = getConnection().prepareStatement(SELECT_OUTPUTS_SPENTBLOCK_SQL)) {
			s.setBytes(1, hash.getBytes());
			// index is actually an unsigned int
			s.setLong(2, index);
			s.setBytes(3, blockHash.getBytes());
			ResultSet results = s.executeQuery();
			if (results.next()) {
				return setSpentBlock(blockHash, results);
			}
			return null;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public UTXO getTransactionOutput(Sha256Hash blockHash, Sha256Hash hash, long index) throws BlockStoreException {

		try (PreparedStatement s = getConnection().prepareStatement(SELECT_OUTPUTS_SQL)) {
			s.setBytes(1, hash.getBytes());
			s.setLong(2, index);
			s.setBytes(3, blockHash.getBytes());
			ResultSet results = s.executeQuery();
			if (!results.next()) {
				return null;
			}
			return setUTXO(hash, index, results);

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public Map<Long, UTXO> getTransactionOutputs(Sha256Hash blockHash, Sha256Hash hash, Collection<Long> indices)
			throws BlockStoreException {
		Map<Long, UTXO> result = new HashMap<>();
		if (indices.isEmpty()) return result;
		StringBuilder sql = new StringBuilder(
				"SELECT outputindex, coinvalue, scriptbytes, coinbase, toaddress, addresstargetable, blockhash, tokenid, fromaddress, memo, spent, " + OUTPUTS_CONFIRMED + " AS confirmed, spendpending, spendpendingtime, minimumsign, time, spenderblockhash FROM outputs WHERE hash = ? AND blockhash = ? AND outputindex IN (");
		Iterator<Long> iter = indices.iterator();
		while (iter.hasNext()) {
			iter.next();
			sql.append("?");
			if (iter.hasNext()) sql.append(",");
		}
		sql.append(")");
		try (PreparedStatement s = getConnection().prepareStatement(sql.toString())) {
			int param = 1;
			s.setBytes(param++, hash.getBytes());
			s.setBytes(param++, blockHash.getBytes());
			for (long idx : indices) {
				s.setLong(param++, idx);
			}
			ResultSet rs = s.executeQuery();
			while (rs.next()) {
				long idx = rs.getLong("outputindex");
				result.put(idx, setUTXO(hash, idx, rs));
			}
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
		return result;
	}

	@Override
	public Map<TransactionOutPoint, UTXO> getTransactionOutputs(Collection<TransactionOutPoint> outpoints)
			throws BlockStoreException {
		Map<TransactionOutPoint, UTXO> result = new HashMap<>();
		if (outpoints.isEmpty())
			return result;
		// De-duplicate: the same outpoint may be spent by several blocks.
		LinkedHashSet<TransactionOutPoint> distinct = new LinkedHashSet<>(outpoints);
		// Chunk to stay far below PostgreSQL's 65,535-parameter limit: 3 params
		// per outpoint -> 1500 outpoints per chunk.
		List<TransactionOutPoint> chunk = new ArrayList<>(BATCH_CHUNK_SIZE);
		for (TransactionOutPoint op : distinct) {
			chunk.add(op);
			if (chunk.size() == BATCH_CHUNK_SIZE) {
				batchGetTransactionOutputs(chunk, result);
				chunk = new ArrayList<>(BATCH_CHUNK_SIZE);
			}
		}
		if (!chunk.isEmpty()) {
			batchGetTransactionOutputs(chunk, result);
		}
		return result;
	}

	@Override
	public Map<TransactionOutPoint, OutputSpentStatus> getOutputSpentStatus(Collection<TransactionOutPoint> outpoints)
			throws BlockStoreException {
		Map<TransactionOutPoint, OutputSpentStatus> result = new HashMap<>();
		if (outpoints.isEmpty()) {
			return result;
		}
		LinkedHashSet<TransactionOutPoint> distinct = new LinkedHashSet<>(outpoints);
		List<TransactionOutPoint> chunk = new ArrayList<>(BATCH_CHUNK_SIZE);
		for (TransactionOutPoint op : distinct) {
			chunk.add(op);
			if (chunk.size() == BATCH_CHUNK_SIZE) {
				batchGetOutputSpentStatus(chunk, result);
				chunk = new ArrayList<>(BATCH_CHUNK_SIZE);
			}
		}
		if (!chunk.isEmpty()) {
			batchGetOutputSpentStatus(chunk, result);
		}
		return result;
	}

	private void batchGetOutputSpentStatus(List<TransactionOutPoint> distinct,
			Map<TransactionOutPoint, OutputSpentStatus> result) throws BlockStoreException {
		Map<String, TransactionOutPoint> byKey = new HashMap<>();
		int n = distinct.size();
		String[] txHex = new String[n];
		Object[] idxArr = new Object[n];
		String[] blkHex = new String[n];
		int i = 0;
		for (TransactionOutPoint op : distinct) {
			byKey.put(outpointKey(op), op);
			txHex[i] = Utils.HEX.encode(op.getTxHash().getBytes());
			idxArr[i] = op.getIndex();
			blkHex[i] = Utils.HEX.encode(op.getBlockHash().getBytes());
			i++;
		}
		// Outpoint probe via unnest-array join. A row-value "(a,b,c) IN ((..),..)"
		// with thousands of tuples costs the planner ~600ms per statement even
		// on an idle database (planning dominates); the array join plans in ms
		// and executes ~10x faster overall.
		String sql = "SELECT o.hash AS hash, o.outputindex AS outputindex, o.blockhash AS blockhash, "
				+ "o.spent AS spent, b.confirmed AS confirmed, o.spenderblockhash AS spenderblockhash "
				+ "FROM outputs o JOIN (SELECT decode(t.h,'hex') h, t.i i, decode(t.b,'hex') b "
				+ "FROM unnest(?::text[], ?::bigint[], ?::text[]) AS t(h,i,b)) u "
				+ "ON o.hash=u.h AND o.outputindex=u.i AND o.blockhash=u.b "
				+ "LEFT JOIN blocks b ON b.hash=o.blockhash";
		try (PreparedStatement s = getConnection().prepareStatement(sql)) {
			s.setArray(1, getConnection().createArrayOf("text", txHex));
			s.setArray(2, getConnection().createArrayOf("bigint", idxArr));
			s.setArray(3, getConnection().createArrayOf("text", blkHex));
			long execStart = System.currentTimeMillis();
			ResultSet rs = s.executeQuery();
			int rows = 0;
			while (rs.next()) {
				rows++;
				long idx = rs.getLong("outputindex");
				Sha256Hash blockHash = Sha256Hash.wrap(rs.getBytes("blockhash"));
				Sha256Hash txHash = Sha256Hash.wrap(rs.getBytes("hash"));
				TransactionOutPoint op = byKey.get(outpointKey(txHash, blockHash, idx));
				if (op != null) {
					Sha256Hash spender = rs.getBytes("spenderblockhash") == null ? null
							: Sha256Hash.wrap(rs.getBytes("spenderblockhash"));
					result.put(op, new OutputSpentStatus(rs.getBoolean("confirmed"), spender));
				}
			}
			long ms = System.currentTimeMillis() - execStart;
			if (ms > 500 || n >= 1000) {
				log.info("batchGetOutputSpentStatus: n={} rows={} execute={}ms", n, rows, ms);
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	private void batchGetTransactionOutputs(List<TransactionOutPoint> distinct, Map<TransactionOutPoint, UTXO> result)
			throws BlockStoreException {
		Map<String, TransactionOutPoint> byKey = new HashMap<>();
		int n = distinct.size();
		String[] txHex = new String[n];
		Object[] idxArr = new Object[n];
		String[] blkHex = new String[n];
		int i = 0;
		for (TransactionOutPoint op : distinct) {
			byKey.put(outpointKey(op), op);
			txHex[i] = Utils.HEX.encode(op.getTxHash().getBytes());
			idxArr[i] = op.getIndex();
			blkHex[i] = Utils.HEX.encode(op.getBlockHash().getBytes());
			i++;
		}
		// unnest-array join — see batchGetOutputSpentStatus for why not row-value IN.
		String sql = "SELECT o.hash AS hash, o.outputindex AS outputindex, coinvalue, scriptbytes, coinbase, "
				+ "o.toaddress AS toaddress, addresstargetable, o.blockhash AS blockhash, tokenid, fromaddress, memo, spent, "
				+ "b.confirmed AS confirmed, spendpending, spendpendingtime, minimumsign, time, spenderblockhash, NULL as multitoaddress "
				+ "FROM outputs o JOIN (SELECT decode(t.h,'hex') h, t.i i, decode(t.b,'hex') b "
				+ "FROM unnest(?::text[], ?::bigint[], ?::text[]) AS t(h,i,b)) u "
				+ "ON o.hash=u.h AND o.outputindex=u.i AND o.blockhash=u.b "
				+ "LEFT JOIN blocks b ON b.hash=o.blockhash";
		try (PreparedStatement s = getConnection().prepareStatement(sql)) {
			s.setArray(1, getConnection().createArrayOf("text", txHex));
			s.setArray(2, getConnection().createArrayOf("bigint", idxArr));
			s.setArray(3, getConnection().createArrayOf("text", blkHex));
			ResultSet rs = s.executeQuery();
			while (rs.next()) {
				long idx = rs.getLong("outputindex");
				Sha256Hash blockHash = Sha256Hash.wrap(rs.getBytes("blockhash"));
				Sha256Hash txHash = Sha256Hash.wrap(rs.getBytes("hash"));
				TransactionOutPoint op = byKey.get(outpointKey(txHash, blockHash, idx));
				if (op != null) {
					result.put(op, setUTXO(txHash, idx, rs));
				}
			}
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
	}

	private static String outpointKey(TransactionOutPoint op) {
		return outpointKey(op.getTxHash(), op.getBlockHash(), op.getIndex());
	}

	private static String outpointKey(Sha256Hash txHash, Sha256Hash blockHash, long index) {
		return Utils.HEX.encode(txHash.getBytes()) + ":" + Utils.HEX.encode(blockHash.getBytes()) + ":" + index;
	}

	protected SpentBlockData setSpentBlock(Sha256Hash blockHash, ResultSet results) throws SQLException {

		boolean spent = results.getBoolean("spent");
		Sha256Hash spenderblockhash = Sha256Hash.wrap(results.getBytes("spenderblockhash"));
		boolean confirmed = results.getBoolean("confirmed");

		return new SpentBlockData(blockHash, spent, confirmed, spenderblockhash);
	}

	private UTXO setUTXO(Sha256Hash hash, long index, ResultSet results) throws SQLException {
		return setUTXO(hash, index, results, true);
	}

	/**
	 * @param withMultiAddress when false the result set has no
	 *        {@code multitoaddress} column (lean no-join queries); a
	 *        {@code minimumsign > 1} output keeps its raw toaddress and callers
	 *        needing the multisig display address must use the
	 *        {@code ...WithMultiSig} queries.
	 */
	private UTXO setUTXO(Sha256Hash hash, long index, ResultSet results, boolean withMultiAddress) throws SQLException {
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
		if (withMultiAddress && minimumsign > 1) {
			address = results.getString("multitoaddress");
		}
		return new UTXO(hash, index, coinvalue, coinbase, new Script(scriptBytes), address, blockhash, fromaddress,
				memo, tokenid, spent, confirmed, spendPending, minimumsign, spendPendingTime, time, spenderblockhash);
	}

	@Override
	public void addUnspentTransactionOutput(List<UTXO> utxos) throws BlockStoreException {
		if (USE_PG_COPY.get() && conn instanceof PGConnection) {
			addUnspentTransactionOutputCopy(utxos);
			return;
		}
		addUnspentTransactionOutputBatch(utxos);
	}

	private void addUnspentTransactionOutputBatch(List<UTXO> utxos) throws BlockStoreException {
		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(INSERT_OUTPUTS_SQL);
			for (UTXO out : utxos) {
				if (out.getValue().isPositive()) {
					s.setBytes(1, out.getTxHash().getBytes());
					s.setLong(2, out.getIndex());
					s.setBytes(3, out.getValue().getValue().toByteArray());
					s.setBytes(4, out.getScript().getProgram());
					s.setString(5, out.getAddress());
					s.setNull(6, java.sql.Types.BIGINT);
					s.setBoolean(7, out.isCoinbase());
					s.setBytes(8, out.getBlockHash() != null ? out.getBlockHash().getBytes() : null);
					s.setString(9, Utils.HEX.encode(out.getValue().getTokenid()));
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
				}
			}
		}
	}

	/** COPY-based bulk load — streams UTXO rows directly to PostgreSQL
	 *  without SQL parsing overhead (~3-5x faster than batch INSERT). */
	private void addUnspentTransactionOutputCopy(List<UTXO> utxos) throws BlockStoreException {
		StringBuilder sb = new StringBuilder(65536);
		try {
			CopyManager cm = ((PGConnection) conn).getCopyAPI();
			CopyIn copyIn = cm.copyIn(
					"COPY outputs (hash, outputindex, coinvalue, scriptbytes, toaddress, addresstargetable,"
					+ " coinbase, blockhash, tokenid, fromaddress, memo, spent, confirmed, spendpending,"
					+ " time, spendpendingtime, minimumsign) FROM STDIN");
			for (UTXO out : utxos) {
				if (!out.getValue().isPositive()) continue;
				sb.setLength(0);
				copyHexBytes(sb, out.getTxHash().getBytes()); sb.append('\t');
				sb.append(out.getIndex()); sb.append('\t');
				copyHexBytes(sb, out.getValue().getValue().toByteArray()); sb.append('\t');
				copyHexBytes(sb, out.getScript().getProgram()); sb.append('\t');
				copyTextString(sb, out.getAddress()); sb.append('\t');
				sb.append("\\N"); sb.append('\t');  // addresstargetable — unused
				sb.append(out.isCoinbase() ? 't' : 'f'); sb.append('\t');
				if (out.getBlockHash() != null) {
					copyHexBytes(sb, out.getBlockHash().getBytes());
				}
				sb.append('\t');
				sb.append(Utils.HEX.encode(out.getValue().getTokenid())); sb.append('\t');
				copyTextString(sb, out.getFromaddress()); sb.append('\t');
				copyTextString(sb, out.getMemo()); sb.append('\t');
				sb.append(out.isSpent() ? 't' : 'f'); sb.append('\t');
				sb.append(out.isConfirmed() ? 't' : 'f'); sb.append('\t');
				sb.append(out.isSpendPending() ? 't' : 'f'); sb.append('\t');
				sb.append(out.getTime()); sb.append('\t');
				sb.append(out.getSpendPendingTime()); sb.append('\t');
				sb.append(out.getMinimumsign());
				sb.append('\n');
				copyIn.writeToCopy(sb.toString().getBytes(StandardCharsets.UTF_8),
						0, sb.length());
			}
			copyIn.endCopy();
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	/** Append a bytea value in PostgreSQL hex format (\x...) for COPY. */
	private static void copyHexBytes(StringBuilder sb, byte[] data) {
		sb.append("\\x");
		sb.append(Utils.HEX.encode(data));
	}

	/** Append a text value for COPY, escaping special characters and handling null. */
	private static void copyTextString(StringBuilder sb, String s) {
		if (s == null) {
			sb.append("\\N");
			return;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '\\': sb.append("\\\\"); break;
			case '\t': sb.append("\\t"); break;
			case '\n': sb.append("\\n"); break;
			case '\r': sb.append("\\r"); break;
			default:   sb.append(c);
			}
		}
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
				log.debug("Rollback database batch write with connection: {}", getConnection().toString());

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
		} catch (SQLException | BlockStoreException ex) {
			log.warn("Warning: deleteStore", ex);
			throw new RuntimeException(ex);
		}
	}

	/**
	 * Deletes the store by deleting the tables within the database.
	 *
	 */
	public void deleteStore() {
		Statement s = null;
		try {
			s = getConnection().createStatement();
			for (String sql : getDropTablesSQL()) {
			//	log.info("drop table : {}", sql);
				s.addBatch(sql);
			}
			s.executeBatch();
			s.close();
		} catch (Exception e) {
			log.info("drop table : ", e);
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
	public List<UTXO> getOpenAllOutputs(String tokenid) throws UTXOProviderException {

		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<>();
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
	public List<UTXO> getOpenTransactionOutputs(List<Address> addresses) throws UTXOProviderException {
		if (addresses == null || addresses.isEmpty()) {
			return new ArrayList<>();
		}
		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<>();
		try {
			// Single query for all addresses instead of one round-trip per
			// address (a 5k-address getOutputs previously issued 5k queries on
			// the shared connection and took minutes). No outputsmulti join:
			// spend-candidate resolution matches outputs.toaddress only —
			// multisig display needs getOpenTransactionOutputsWithMultiSig.
			StringBuilder sql = new StringBuilder(SELECT_TRANSACTION_OUTPUTS_SQL_BASE_NO_MULTI);
			sql.append(" WHERE ").append(OUTPUTS_CONFIRMED).append(" = true and spent = false and outputs.toaddress IN (");
			for (int i = 0; i < addresses.size(); i++) {
				sql.append("?,");
			}
			sql.setLength(sql.length() - 1);
			sql.append(")");

			s = getConnection().prepareStatement(sql.toString());
			int idx = 1;
			for (Address address : addresses) {
				s.setString(idx++, address.toString());
			}
			ResultSet results = s.executeQuery();
			while (results.next()) {
				outputs.add(setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"),
						results, false));
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

	/**
	 * Multisig display variant: joins {@code outputsmulti} so a
	 * {@code minimumsign > 1} output reports its multi address, matching an
	 * output owned via either its raw or its multisig toaddress. Slower than
	 * {@link #getOpenTransactionOutputs(List)} — user-facing output display only.
	 */
	@Override
	public List<UTXO> getOpenTransactionOutputsWithMultiSig(List<Address> addresses) throws UTXOProviderException {
		if (addresses == null || addresses.isEmpty()) {
			return new ArrayList<>();
		}
		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<>();
		try {
			// Single query for all addresses; the multitoaddress branch must ALSO
			// require confirmed/spent (the old "? OR ?" form ignored both).
			StringBuilder sql = new StringBuilder(SELECT_TRANSACTION_OUTPUTS_SQL_BASE);
			sql.append(" WHERE ").append(OUTPUTS_CONFIRMED).append(" = true and spent = false and ( outputs.toaddress IN (");
			for (int i = 0; i < addresses.size(); i++) {
				sql.append("?,");
			}
			sql.setLength(sql.length() - 1);
			sql.append(") OR outputsmulti.toaddress IN (");
			for (int i = 0; i < addresses.size(); i++) {
				sql.append("?,");
			}
			sql.setLength(sql.length() - 1);
			sql.append(") )");

			s = getConnection().prepareStatement(sql.toString());
			int idx = 1;
			for (Address address : addresses) {
				s.setString(idx++, address.toString());
			}
			for (Address address : addresses) {
				s.setString(idx++, address.toString());
			}
			ResultSet results = s.executeQuery();
			while (results.next()) {
				outputs.add(setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"),
						results));
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
	public List<UTXO> getOpenTransactionOutputs(String address) throws UTXOProviderException {
		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<>();
		try {

			s = getConnection().prepareStatement(SELECT_OPEN_TRANSACTION_OUTPUTS_NO_MULTI_SQL);

			s.setString(1, address);
			ResultSet results = s.executeQuery();
			int cnt = 0;
			while (results.next()) {
				cnt++;
				outputs.add(setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"),
						results, false));
			}
			if (cnt == 0) {
				log.warn("getOpenTransactionOutputs: 0 rows for address={}", address);
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

	/**
	 * Multisig display variant of {@link #getOpenTransactionOutputs(String)}
	 * (see {@link #getOpenTransactionOutputsWithMultiSig(List)}).
	 */
	@Override
	public List<UTXO> getOpenTransactionOutputsWithMultiSig(String address) throws UTXOProviderException {
		PreparedStatement s = null;
		List<UTXO> outputs = new ArrayList<>();
		try {

			s = getConnection().prepareStatement(SELECT_OPEN_TRANSACTION_OUTPUTS_SQL);

			s.setString(1, address);
			s.setString(2, address);
			ResultSet results = s.executeQuery();
			while (results.next()) {
				outputs.add(setUTXO(Sha256Hash.wrap(results.getBytes("hash")), results.getLong("outputindex"),
						results));
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

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_SQL)) {
			preparedStatement.setBytes(1, hash.getBytes());

			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

			Block block = params.getDefaultSerializer().makeZippedBlockStream(resultSet.getBinaryStream("block"));
			return new BlockWrap(block, blockEvaluation, params);
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<BlockWrap> getBlockWraps(Collection<Sha256Hash> hashes) throws BlockStoreException {
		if (hashes == null || hashes.isEmpty()) {
			return new ArrayList<>();
		}
		try {
			StringBuilder sql = new StringBuilder("SELECT " + SELECT_BLOCKS_TEMPLATE + " FROM blocks WHERE hash IN (");
			for (int i = 0; i < hashes.size(); i++) {
				if (i > 0) sql.append(",");
				sql.append("?");
			}
			sql.append(")");
			try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql.toString())) {
				int idx = 1;
				for (Sha256Hash hash : hashes) {
					preparedStatement.setBytes(idx++, hash.getBytes());
				}
				ResultSet resultSet = preparedStatement.executeQuery();
				List<BlockWrap> result = new ArrayList<>(hashes.size());
				while (resultSet.next()) {
					BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);
					Block block = params.getDefaultSerializer().makeZippedBlockStream(resultSet.getBinaryStream("block"));
					result.add(new BlockWrap(block, blockEvaluation, params));
				}
				return result;
			}
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}
	}

	@Override
	public List<UTXO> getOutputsHistory(String fromaddress, String toaddress, Long starttime, Long endtime)
			throws BlockStoreException {
		List<UTXO> outputs = new ArrayList<>();

		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_TRANSACTION_OUTPUTS_SQL_BASE + "WHERE  confirmed=true ";

			if (fromaddress != null && !fromaddress.trim().isEmpty()) {
				sql += " AND outputs.fromaddress=?";
			}
			if (toaddress != null && !toaddress.trim().isEmpty()) {
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
			if (fromaddress != null && !fromaddress.trim().isEmpty()) {
				preparedStatement.setString(i++, fromaddress);
			}
			if (toaddress != null && !toaddress.trim().isEmpty()) {
				preparedStatement.setString(i++, toaddress);
			}
			if (starttime != null) {
				preparedStatement.setLong(i++, starttime);
			}
			if (endtime != null) {
				preparedStatement.setLong(i, endtime);
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
					//
				}
			}
		}
	}

	@Override
	public TreeSet<BlockWrap> getBlocksToConfirm(long cutoffHeight, long maxHeight) throws BlockStoreException {
		Comparator<BlockWrap> comparator = Comparator.comparingLong((BlockWrap b) -> b.getBlock().getHeight())
				.thenComparing((BlockWrap b) -> b.getBlock().getHash());
		TreeSet<BlockWrap> storedBlockHashes = new TreeSet<>(comparator);

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_SOLID_INTERVAL_SQL)) {
			preparedStatement.setLong(1, cutoffHeight);
			preparedStatement.setLong(2, maxHeight);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

				Block block = params.getDefaultSerializer().makeZippedBlockStream(resultSet.getBinaryStream("block"));
				if (verifyHeader(block))
					storedBlockHashes.add(new BlockWrap(block, blockEvaluation, params));
			}
			return storedBlockHashes;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public HashSet<BlockEvaluation> getBlocksToUnconfirm() throws BlockStoreException {
		HashSet<BlockEvaluation> storedBlockHashes = new HashSet<>();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_TO_UNCONFIRM_SQL)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

				storedBlockHashes.add(blockEvaluation);
			}
			return storedBlockHashes;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public PriorityQueue<BlockWrap> getSolidBlocksInIntervalDescending(long cutoffHeight, long maxHeight)
			throws BlockStoreException {
		PriorityQueue<BlockWrap> blocksByDescendingHeight = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_SOLID_BLOCKS_IN_INTERVAL_SQL)) {
			preparedStatement.setLong(1, cutoffHeight);
			preparedStatement.setLong(2, maxHeight);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = setBlockEvaluation(resultSet);

				Block block = params.getDefaultSerializer().makeZippedBlockStream(resultSet.getBinaryStream("block"));
				if (verifyHeader(block))
					blocksByDescendingHeight.add(new BlockWrap(block, blockEvaluation, params));
			}
			return blocksByDescendingHeight;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public PriorityQueue<BlockWrap> getSolidBlockTopologyInInterval(long cutoffHeight, long maxHeight)
			throws BlockStoreException {
		// Lightweight version: no block bytes deserialization, only topology fields
		PriorityQueue<BlockWrap> blocksByDescendingHeight = new PriorityQueue<>(
				Comparator.comparingLong((BlockWrap b) -> b.getBlockEvaluation().getHeight()).reversed());

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_SOLID_BLOCK_TOPOLOGY_INTERVAL_SQL)) {
			preparedStatement.setLong(1, cutoffHeight);
			preparedStatement.setLong(2, maxHeight);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluation blockEvaluation = BlockEvaluation.build(
						Sha256Hash.wrap(resultSet.getBytes("hash")), resultSet.getLong("height"),
						resultSet.getLong("chainlength"), resultSet.getLong("chainlengthlastupdate"),
						resultSet.getLong("inserttime"), resultSet.getLong("solid"),
						resultSet.getBoolean("confirmed"));

				// Create a minimal Block with only topology fields — no block bytes loaded
				long blockHeight = resultSet.getLong("height");
				Block block = new Block(params);
				block.setHeight(blockHeight);
				block.setPrevBlockHash(Sha256Hash.wrap(resultSet.getBytes("prevblockhash")));
				byte[] prevBranchBytes = resultSet.getBytes("prevbranchblockhash");
				if (prevBranchBytes != null) {
					block.setPrevBranchBlockHash(Sha256Hash.wrap(prevBranchBytes));
				}
				// Set minimal time to avoid time-reversion validation errors
				long insertTime = resultSet.getLong("inserttime");
				block.setTime(Math.max(insertTime / 1000, 1));

				blocksByDescendingHeight.add(new BlockWrap(block, blockEvaluation, params));
			}
			return blocksByDescendingHeight;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}
	}

	@Override
	public List<Sha256Hash> getBlocksInChainlengthInterval(long minChainLength, long currChainLength)
			throws BlockStoreException {
		List<Sha256Hash> resultQueue = new ArrayList<>();

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_BLOCKS_IN_CHAINLENGTH_INTERVAL_SQL)) {
			preparedStatement.setLong(1, minChainLength);
			preparedStatement.setLong(2, currChainLength);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				resultQueue.add(Sha256Hash.wrap(resultSet.getBytes(1)));
			}
			return resultQueue;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}

	}

	public List<Sha256Hash> getBlocksByPrevHash(Sha256Hash prev) throws BlockStoreException {
		List<Sha256Hash> result = new ArrayList<>();
		String sql = "SELECT hash FROM blocks WHERE prevblockhash = ? OR prevbranchblockhash = ?";
		try (PreparedStatement s = getConnection().prepareStatement(sql)) {
			s.setBytes(1, prev.getBytes());
			s.setBytes(2, prev.getBytes());
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) result.add(Sha256Hash.wrap(rs.getBytes(1)));
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return result;
	}

	private BlockEvaluation setBlockEvaluationNumber(ResultSet resultSet) throws SQLException {

		return BlockEvaluation.build(Sha256Hash.wrap(resultSet.getBytes(1)), resultSet.getLong(3), resultSet.getLong(4),
				resultSet.getLong(5), resultSet.getLong(6), resultSet.getLong(7), resultSet.getBoolean(8));
	}

	private BlockEvaluation setBlockEvaluation(ResultSet resultSet) throws SQLException {
		return BlockEvaluation.build(Sha256Hash.wrap(resultSet.getBytes("hash")), resultSet.getLong("height"),
				resultSet.getLong("chainlength"), resultSet.getLong("chainlengthlastupdate"),
				resultSet.getLong("inserttime"), resultSet.getLong("solid"), resultSet.getBoolean("confirmed"));
	}

	@Override
	public void updateBlockEvaluationChainlength(Sha256Hash blockhash, long b) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(UPDATE_BLOCKEVALUATION_CHAINLENGTH_SQL)) {
			preparedStatement.setLong(1, b);
			preparedStatement.setLong(2, System.currentTimeMillis());
			preparedStatement.setBytes(3, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateBlockEvaluationConfirmed(Sha256Hash blockhash, boolean b) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(UPDATE_BLOCKEVALUATION_CONFIRMED_SQL)) {
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateBlockEvaluationConfirmedBatch(List<Sha256Hash> blockHashes, List<Long> chainlengths)
			throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(UPDATE_BLOCKEVALUATION_CONFIRMED_CHAINLENGTH_SQL)) {
			long now = System.currentTimeMillis();
			for (int i = 0; i < blockHashes.size(); i++) {
				s.setBoolean(1, true);
				s.setLong(2, chainlengths.get(i));
				s.setLong(3, now);
				s.setBytes(4, blockHashes.get(i).getBytes());
				s.addBatch();
			}
			s.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void updateBlockEvaluationSolid(Sha256Hash blockhash, long solid) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_BLOCKEVALUATION_SOLID_SQL)) {
			preparedStatement.setLong(1, solid);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void resetChainlengthSolid(long chainlength) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(RESET_CHAINLENGTH_SOLID_SQL)) {
			preparedStatement.setLong(1, chainlength);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
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
			return setBlockEvaluation(resultSet);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {

				}
			}
		}
	}

	@Override
	public void updateTransactionOutputSpent(Sha256Hash prevBlockHash, Sha256Hash prevTxHash, long index, boolean b,
			@Nullable Sha256Hash spenderBlockHash) throws BlockStoreException {

		// Keep the spend-pending claim in sync with the spent flag: confirming
		// a spender supersedes the pending claim, and UNconfirming it (reorg)
		// must RELEASE the claim — otherwise the output stays hidden from
		// getOutputs forever even though its spending block is gone.
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_OUTPUTS_SPENT_SQL)) {
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, spenderBlockHash != null ? spenderBlockHash.getBytes() : null);
			preparedStatement.setBoolean(3, !b);
			preparedStatement.setLong(4, b ? 0 : System.currentTimeMillis());
			preparedStatement.setBytes(5, prevTxHash.getBytes());
			preparedStatement.setLong(6, index);
			preparedStatement.setBytes(7, prevBlockHash.getBytes());
			// log.debug(preparedStatement.toString());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateTransactionOutputSpentBatch(List<Sha256Hash> prevBlockHashes, List<Sha256Hash> prevTxHashes,
			List<Long> indexes, Sha256Hash spenderBlockHash) throws BlockStoreException {
		List<Sha256Hash> spenderBlockHashes = new ArrayList<>(prevBlockHashes.size());
		for (int i = 0; i < prevBlockHashes.size(); i++) {
			spenderBlockHashes.add(spenderBlockHash);
		}
		updateTransactionOutputSpentBatch(prevBlockHashes, prevTxHashes, indexes, spenderBlockHashes);
	}

	@Override
	public void updateTransactionOutputSpentBatch(List<Sha256Hash> prevBlockHashes, List<Sha256Hash> prevTxHashes,
			List<Long> indexes, List<Sha256Hash> spenderBlockHashes) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(UPDATE_OUTPUTS_SPENT_SQL)) {
			for (int i = 0; i < prevBlockHashes.size(); i++) {
				s.setBoolean(1, true);
				s.setBytes(2, spenderBlockHashes.get(i).getBytes());
				// confirming supersedes any pending claim
				s.setBoolean(3, false);
				s.setLong(4, 0);
				s.setBytes(5, prevTxHashes.get(i).getBytes());
				s.setLong(6, indexes.get(i));
				s.setBytes(7, prevBlockHashes.get(i).getBytes());
				s.addBatch();
			}
			s.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void updateTransactionOutputConfirmed(Sha256Hash prevBlockHash, Sha256Hash prevTxHash, long index, boolean b)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_OUTPUTS_CONFIRMED_SQL)) {
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, prevTxHash.getBytes());
			preparedStatement.setLong(3, index);
			preparedStatement.setBytes(4, prevBlockHash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateAllTransactionOutputsConfirmed(Sha256Hash prevBlockHash, boolean b) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_ALL_OUTPUTS_CONFIRMED_SQL)) {
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, prevBlockHash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateAllTransactionOutputsConfirmedBatch(List<Sha256Hash> blockHashes, boolean b)
			throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(UPDATE_ALL_OUTPUTS_CONFIRMED_SQL)) {
			for (Sha256Hash h : blockHashes) {
				s.setBoolean(1, b);
				s.setBytes(2, h.getBytes());
				s.addBatch();
			}
			s.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void updateTransactionOutputSpendPending(List<UTXO> utxos) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_OUTPUTS_SPENDPENDING_SQL)) {
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
		}

	}

	@Override
	public List<Token> getTokensList(Set<String> tokenids) throws BlockStoreException {
		List<Token> list = new ArrayList<>();
		if (tokenids.isEmpty())
			return list;

		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_CONFIRMED_TOKENS_SQL;
			if (!tokenids.isEmpty()) {
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

				}
			}
		}
	}

	@Override
	public List<Token> getTokenTypeList(int type) throws BlockStoreException {
		List<Token> list = new ArrayList<>();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_TYPE_SQL)) {
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
		}

	}

	public Map<String, BigInteger> getTokenAmountMap() throws BlockStoreException {
		Map<String, BigInteger> map = new HashMap<>();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_ACOUNT_MAP_SQL)) {

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
		}

	}

	@Override
	public List<Token> getTokensList(String name) throws BlockStoreException {
		List<Token> list = new ArrayList<>();

		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_CONFIRMED_TOKENS_SQL;
			if (name != null && !name.trim().isEmpty()) {
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

				}
			}
		}
	}

	@Override
	public List<Token> getTokensByNameOrId(String keyword) throws BlockStoreException {
		List<Token> list = new ArrayList<>();
		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_CONFIRMED_TOKENS_SQL;
			if (keyword != null && !keyword.trim().isEmpty()) {
				sql += " AND (tokenname ILIKE ? OR tokenid ILIKE ? OR description ILIKE ? OR domainname ILIKE ?)";
			}
			sql += LIMIT_500;
			preparedStatement = getConnection().prepareStatement(sql);
			if (keyword != null && !keyword.trim().isEmpty()) {
				String like = "%" + keyword.trim() + "%";
				preparedStatement.setString(1, like);
				preparedStatement.setString(2, like);
				preparedStatement.setString(3, like);
				preparedStatement.setString(4, like);
			}
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
				}
			}
		}
	}

	protected void setToken(ResultSet resultSet, Token tokens) throws SQLException, IOException {
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
				log.warn("Token {}", tokens, e);
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

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_TOKENS_SQL)) {

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
			preparedStatement.setBytes(14, null);
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
		}

	}

	@Override
	public Sha256Hash getTokenPrevblockhash(Sha256Hash blockhash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_PREVBLOCKHASH_SQL)) {
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return Sha256Hash.wrap(resultSet.getBytes(1));
			} else {
				return null;
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public SpentBlockData getTokenSpent(Sha256Hash blockhash) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_TOKEN_SPENT_BY_BLOCKHASH_SQL)) {
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return setSpentBlock(blockhash, resultSet);
			}

			return null;

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public boolean getTokenAnyConfirmed(String tokenid, long tokenIndex) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_ANY_CONFIRMED_SQL)) {
			preparedStatement.setString(1, tokenid);
			preparedStatement.setLong(2, tokenIndex);
			ResultSet resultSet = preparedStatement.executeQuery();
			return resultSet.next();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
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

				}
			}
		}
	}

	@Override
	public BlockWrap getTokenIssuingConfirmedBlock(String tokenid, long tokenIndex) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_TOKEN_ISSUING_CONFIRMED_BLOCK_SQL)) {
			preparedStatement.setString(1, tokenid);
			preparedStatement.setLong(2, tokenIndex);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return getBlockWrap(Sha256Hash.wrap(resultSet.getBytes(1)));
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public BlockWrap getDomainIssuingConfirmedBlock(String tokenName, String domainPred, long index)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_DOMAIN_ISSUING_CONFIRMED_BLOCK_SQL)) {
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
		}

	}

	@Override
	public void updateTokenSpent(Sha256Hash blockhash, boolean b, Sha256Hash spenderBlockHash)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_TOKEN_SPENT_SQL)) {

			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, spenderBlockHash == null ? null : spenderBlockHash.getBytes());
			preparedStatement.setBytes(3, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateTokenConfirmed(Sha256Hash blockHash, boolean confirmed) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_TOKEN_CONFIRMED_SQL)) {

			preparedStatement.setBoolean(1, confirmed);
			preparedStatement.setBytes(2, blockHash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<BlockEvaluationDisplay> getSearchBlockEvaluations(List<String> address, String lastestAmount,
			long height, long maxblocks) throws BlockStoreException {

		String sql = "";
		StringBuilder stringBuffer = new StringBuilder();
		if (!"0".equalsIgnoreCase(lastestAmount) && !"".equalsIgnoreCase(lastestAmount)) {
			sql += "SELECT hash,  "
					+ " height, chainlength, chainlengthlastupdate,  inserttime,  blocktype, solid, confirmed "
					+ "  FROM  blocks ";
			sql += " where height >= " + height;
			sql += " ORDER BY insertTime desc ";
			long a = Long.parseLong(lastestAmount);
			if (a > maxblocks) {
				a = maxblocks;
			}
			sql += " LIMIT " + a;
		} else {
			sql += "SELECT blocks.hash, "
					+ " blocks.height, chainlength, chainlengthlastupdate,  inserttime,  blocktype, solid, blocks.confirmed"
					+ " FROM outputs JOIN blocks " + "ON outputs.blockhash = blocks.hash  ";
			sql += " where height >= " + height;
			sql += " and  outputs.toaddress in ";
			for (String str : address)
				stringBuffer.append(",").append("'").append(str).append("'");
			sql += "(" + stringBuffer.substring(1) + ")";

			sql += " ORDER BY insertTime desc ";
		}
		List<BlockEvaluationDisplay> result = new ArrayList<>();
		TXReward maxConfirmedReward = getMaxConfirmedReward();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				BlockEvaluationDisplay blockEvaluation = BlockEvaluationDisplay.build(
						Sha256Hash.wrap(resultSet.getBytes("hash")), resultSet.getLong("height"),
						resultSet.getLong("chainlength"), resultSet.getLong("chainlengthlastupdate"),
						resultSet.getLong("inserttime"), blockTypeFromDB(resultSet), resultSet.getLong("solid"),
						resultSet.getBoolean("confirmed"), maxConfirmedReward.getChainLength());
				blockEvaluation.setRatingWithDefault();
				result.add(blockEvaluation);
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public BlockEvaluation getBlockEvaluationsByhashs(Sha256Hash hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(
				"SELECT hash,  " + " height, chainlength, chainlengthlastupdate,  inserttime,  blocktype, solid, confirmed "
						+ "  FROM  blocks WHERE hash = ? ")) {

			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return BlockEvaluation.build(hash, resultSet.getLong("height"), resultSet.getLong("chainlength"),
						resultSet.getLong("chainlengthlastupdate"), resultSet.getLong("inserttime"),
						resultSet.getLong("solid"), resultSet.getBoolean("confirmed"));

			}

			return null;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<BlockEvaluationDisplay> getSearchBlockEvaluationsByhashs(List<String> blockhashs)
			throws BlockStoreException {

		List<BlockEvaluationDisplay> result = new ArrayList<>();
		if (blockhashs == null || blockhashs.isEmpty()) {
			return result;
		}
		String sql = "";

		sql += "SELECT hash,  " + " height, chainlength, chainlengthlastupdate,  inserttime,  blocktype, solid, confirmed "
				+ "  FROM  blocks WHERE hash = ? ";

		TXReward maxConfirmedReward = getMaxConfirmedReward();
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			for (String hash : blockhashs) {

				preparedStatement.setBytes(1, Utils.HEX.decode(hash));
				ResultSet resultSet = preparedStatement.executeQuery();
				while (resultSet.next()) {
					BlockEvaluationDisplay blockEvaluation = BlockEvaluationDisplay.build(
							Sha256Hash.wrap(resultSet.getBytes("hash")), resultSet.getLong("height"),
							resultSet.getLong("chainlength"), resultSet.getLong("chainlengthlastupdate"),
						resultSet.getLong("inserttime"), blockTypeFromDB(resultSet), resultSet.getLong("solid"),
							resultSet.getBoolean("confirmed"), maxConfirmedReward.getChainLength());
					blockEvaluation.setRatingWithDefault();
					result.add(blockEvaluation);
				}
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	protected String buildINList(Collection<String> datalist) {
		if (datalist == null || datalist.isEmpty())
			return "";
		StringBuilder stringBuffer = new StringBuilder();
		for (String str : datalist)
			stringBuffer.append(",").append("'").append(str).append("'");
		return stringBuffer.substring(1);
	}

	@Override
	public void updateOrderresultChainlength(Sha256Hash blockhash, long rewardchainlength) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_ORDERRESULT_CHAINLENGTH_SQL)) {
			preparedStatement.setLong(1, rewardchainlength);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateContractresultChainlength(Sha256Hash blockhash, long rewardchainlength) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(UPDATE_CONTRACTRESULT_CHAINLENGTH_SQL)) {
			preparedStatement.setLong(1, rewardchainlength);
			preparedStatement.setBytes(2, blockhash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}
}
