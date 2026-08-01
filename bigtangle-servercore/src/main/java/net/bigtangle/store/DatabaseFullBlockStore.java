/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.Nullable;

import net.bigtangle.core.Block;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancel;
import net.bigtangle.core.ContractEventRecord;
import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.OrderCancel;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.PayMultiSign;
import net.bigtangle.core.PayMultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import java.util.HashMap;
import java.util.Map;

import net.bigtangle.core.AttestationData;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.StakeRecord;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UserData;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.ProtocolException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.ordermatch.AVGMatchResult;
import net.bigtangle.ordermatch.MatchLastdayResult;
import net.bigtangle.ordermatch.MatchResult;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.server.data.AnchorRecord;
import net.bigtangle.server.data.VaultRecord;
import net.bigtangle.server.data.BatchBlock;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.Contractresult;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.Orderresult;
import net.bigtangle.server.data.TipsQueue;
/**
 * <p>
 * A generic full block store for a relational database. This generic class
 * requires certain table structures for the block store.
 * </p>
 * 
 */
public abstract class DatabaseFullBlockStore extends DatabaseFullBlockStoreBase {

	/**
	 * <p>
	 * Create a new DatabaseFullBlockStore, using the full connection URL instead of
	 * a hostname and password, and optionally allowing a schema to be specified.
	 * </p>
	 */
	public DatabaseFullBlockStore(NetworkParameters params, Connection conn) {
		super(params, conn);
	}

	@Override
	public List<MultiSignAddress> getMultiSignAddressListByTokenidAndBlockHashHex(String tokenid,
			Sha256Hash prevblockhash) throws BlockStoreException {

		List<MultiSignAddress> list = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_MULTISIGNADDRESS_SQL)) {
			preparedStatement.setString(1, tokenid);
			preparedStatement.setBytes(2, prevblockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				String tokenid0 = resultSet.getString("tokenid");
				String address = resultSet.getString("address");
				String pubKeyHex = resultSet.getString("pubKeyHex");
				MultiSignAddress multiSignAddress = new MultiSignAddress(tokenid0, address, pubKeyHex);
				int posIndex = resultSet.getInt("posIndex");
				multiSignAddress.setPosIndex(posIndex);
				int tokenHolder = resultSet.getInt("tokenHolder");
				multiSignAddress.setTokenHolder(tokenHolder);
				// TODO if(multiSignAddress.getTokenHolder() > 0)
				list.add(multiSignAddress);
			}
			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void insertMultiSignAddress(MultiSignAddress multiSignAddress) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_MULTISIGNADDRESS_SQL)) {
			preparedStatement.setString(1, multiSignAddress.getTokenid());
			preparedStatement.setString(2, multiSignAddress.getAddress());
			preparedStatement.setString(3, multiSignAddress.getPubKeyHex());
			preparedStatement.setInt(4, multiSignAddress.getPosIndex());
			preparedStatement.setBytes(5, multiSignAddress.getBlockhash().getBytes());
			preparedStatement.setInt(6, multiSignAddress.getTokenHolder());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(getDuplicateKeyErrorCode().equals(e.getSQLState())))
				throw new BlockStoreException(e);
		}
		//
	}

	@Override
	public Token getCalMaxTokenIndex(String tokenid) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(COUNT_TOKENSINDEX_SQL)) {
			preparedStatement.setString(1, tokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			Token tokens = new Token();
			if (resultSet.next()) {
				tokens.setBlockHash(Sha256Hash.wrap(resultSet.getBytes("blockhash")));
				tokens.setTokenindex(resultSet.getInt("tokenindex"));
				return tokens;
			} else {
				// tokens.setBlockhash("");
				tokens.setTokenindex(-1);
			}
			return tokens;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public Token getTokenByBlockHash(Sha256Hash blockhash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_SQL)) {

			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			Token tokens = null;
			if (resultSet.next()) {
				tokens = new Token();
				setToken(resultSet, tokens);
			}
			return tokens;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<Token> getTokenID(String tokenid) throws BlockStoreException {
		List<Token> list = new ArrayList<>();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKENID_SQL)) {
			preparedStatement.setString(1, tokenid);
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

	@Override
	public List<MultiSign> getMultiSignListByAddress(String address) throws BlockStoreException {
		List<MultiSign> list = new ArrayList<>();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_MULTISIGN_ADDRESS_SQL)) {
			preparedStatement.setString(1, address);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				setMultisign(list, resultSet);
			}
			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	private void setMultisign(List<MultiSign> list, ResultSet resultSet) throws SQLException {
		String id = resultSet.getString("id");
		String tokenid = resultSet.getString("tokenid");
		long tokenindex = resultSet.getLong("tokenindex");
		String address0 = resultSet.getString("address");
		byte[] blockhash = resultSet.getBytes("blockhash");
		int sign = resultSet.getInt("sign");

		MultiSign multiSign = new MultiSign();
		multiSign.setId(id);
		multiSign.setTokenindex(tokenindex);
		multiSign.setTokenid(tokenid);
		multiSign.setAddress(address0);
		multiSign.setBlockbytes(blockhash);
		multiSign.setSign(sign);

		list.add(multiSign);
	}

	public List<MultiSign> getMultiSignListByTokenidAndAddress(final String tokenid, String address)
			throws BlockStoreException {
		List<MultiSign> list = new ArrayList<>();

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_MULTISIGN_TOKENID_ADDRESS_SQL)) {
			preparedStatement.setString(1, tokenid);
			preparedStatement.setString(2, address);

			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				setMultisign(list, resultSet);
			}
			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<MultiSign> getMultiSignListByTokenid(String tokenid, int tokenindex, Set<String> addresses,
			boolean isSign) throws BlockStoreException {
		List<MultiSign> list = new ArrayList<>();

		PreparedStatement preparedStatement = null;
		String sql = "SELECT id, tokenid, tokenindex, address, blockhash, sign FROM multisign WHERE 1 = 1 ";
		if (addresses != null && !addresses.isEmpty()) {
			sql += " AND address IN( " + buildINList(addresses) + " ) ";
		}
		if (tokenid != null && !tokenid.trim().isEmpty()) {
			sql += " AND tokenid=?  ";
			if (tokenindex != -1) {
				sql += "  AND tokenindex = ? ";
			}
		}

		if (!isSign) {
			sql += " AND sign = 0";
		}
		sql += " ORDER BY tokenid,tokenindex DESC";
		try {
			log.info("sql : {}", sql);
			preparedStatement = getConnection().prepareStatement(sql);
			if (tokenid != null && !tokenid.isEmpty()) {
				preparedStatement.setString(1, tokenid.trim());
				if (tokenindex != -1) {
					preparedStatement.setInt(2, tokenindex);
				}
			}
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				setMultisign(list, resultSet);
			}
			return list;
		} catch (SQLException ex) {
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
	public int getCountMultiSignAlready(String tokenid, long tokenindex, String address) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_COUNT_MULTISIGN_SQL)) {
			preparedStatement.setString(1, tokenid);
			preparedStatement.setLong(2, tokenindex);
			preparedStatement.setString(3, address);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	public int countMultiSign(String tokenid, long tokenindex, int sign) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_COUNT_ALL_MULTISIGN_SQL)) {
			preparedStatement.setString(1, tokenid);
			preparedStatement.setLong(2, tokenindex);
			preparedStatement.setInt(3, sign);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void saveMultiSign(MultiSign multiSign) throws BlockStoreException {

		if (multiSign.getTokenid() == null || "".equals(multiSign.getTokenid())) {
			return;
		}

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_MULTISIGN_SQL)) {
			preparedStatement.setString(1, multiSign.getTokenid());
			preparedStatement.setLong(2, multiSign.getTokenindex());
			preparedStatement.setString(3, multiSign.getAddress());
			preparedStatement.setBytes(4, multiSign.getBlockbytes());
			preparedStatement.setInt(5, multiSign.getSign());
			preparedStatement.setString(6, multiSign.getId());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateMultiSign(String tokenid, long tokenIndex, String address, byte[] blockhash, int sign)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_MULTISIGN_SQL)) {
			preparedStatement.setBytes(1, blockhash);
			preparedStatement.setInt(2, sign);
			preparedStatement.setString(3, tokenid);
			preparedStatement.setLong(4, tokenIndex);
			preparedStatement.setString(5, address);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteMultiSign(String tokenid) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(DELETE_MULTISIGN_SQL)) {
			preparedStatement.setString(1, tokenid);
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public boolean getRewardSpent(Sha256Hash hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_SPENT_SQL)) {
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public Sha256Hash getRewardSpender(Sha256Hash hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_SPENDER_SQL)) {
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return resultSet.getBytes(1) == null ? null : Sha256Hash.wrap(resultSet.getBytes(1));
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public Sha256Hash getRewardPrevBlockHash(Sha256Hash blockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_TX_REWARD_PREVBLOCKHASH_SQL)) {
			preparedStatement.setBytes(1, blockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return Sha256Hash.wrap(resultSet.getBytes(1));
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
		//
	}

	@Override
	public long getRewardChainLength(Sha256Hash blockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_CHAINLENGTH_SQL)) {
			preparedStatement.setBytes(1, blockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getLong(1);
			} else {
				return -1;
			}

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public boolean getRewardConfirmed(Sha256Hash hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_CONFIRMED_SQL)) {
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void insertReward(Sha256Hash hash, Sha256Hash prevBlockHash, long chainLength)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_TX_REWARD_SQL)) {
			preparedStatement.setBytes(1, hash.getBytes());
			preparedStatement.setBoolean(2, false);
			preparedStatement.setBoolean(3, false);
			preparedStatement.setBytes(4, null);
			preparedStatement.setBytes(5, prevBlockHash.getBytes());
			preparedStatement.setLong(6, chainLength);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(getDuplicateKeyErrorCode().equals(e.getSQLState())))
				throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateRewardConfirmed(Sha256Hash hash, boolean b) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_TX_REWARD_CONFIRMED_SQL)) {
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, hash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateRewardSpent(Sha256Hash hash, boolean b, @Nullable Sha256Hash spenderBlockHash)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_TX_REWARD_SPENT_SQL)) {
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, spenderBlockHash == null ? null : spenderBlockHash.getBytes());
			preparedStatement.setBytes(3, hash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public TXReward getRewardConfirmedAtHeight(long chainlength) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_TX_REWARD_CONFIRMED_AT_HEIGHT_REWARD_SQL)) {
			preparedStatement.setLong(1, chainlength);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return setReward(resultSet);
			} else
				return null;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public TXReward getMaxConfirmedReward() throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_TX_REWARD_MAX_CONFIRMED_REWARD_SQL)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {

				return setReward(resultSet);
			} else
				return null;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<TXReward> getAllConfirmedReward() throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_TX_REWARD_ALL_CONFIRMED_REWARD_SQL)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			List<TXReward> list = new ArrayList<>();
			while (resultSet.next()) {
				list.add(setReward(resultSet));
			}

			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	private TXReward setReward(ResultSet resultSet) throws SQLException {
		return new TXReward(Sha256Hash.wrap(resultSet.getBytes("blockhash")), resultSet.getBoolean("confirmed"),
				resultSet.getBoolean("spent"), Sha256Hash.wrap(resultSet.getBytes("prevblockhash")),
				Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")), resultSet.getLong("chainlength"));
	}

	@Override
	public void updateMultiSignBlockBitcoinSerialize(String tokenid, long tokenindex, byte[] bytes)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_MULTISIGN1_SQL)) {
			preparedStatement.setBytes(1, bytes);
			preparedStatement.setString(2, tokenid);
			preparedStatement.setLong(3, tokenindex);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertOutputsMulti(OutputsMulti outputsMulti) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_OUTPUTSMULTI_SQL)) {
			preparedStatement.setBytes(1, outputsMulti.getHash().getBytes());
			preparedStatement.setString(2, outputsMulti.getToAddress());
			preparedStatement.setLong(3, outputsMulti.getOutputIndex());

			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(getDuplicateKeyErrorCode().equals(e.getSQLState())))
				throw new BlockStoreException(e);
		}

	}

	public List<OutputsMulti> queryOutputsMultiByHashAndIndex(byte[] hash, long index) throws BlockStoreException {

		List<OutputsMulti> list = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_OUTPUTSMULTI_SQL)) {
			preparedStatement.setBytes(1, hash);
			preparedStatement.setLong(2, index);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Sha256Hash sha256Hash = Sha256Hash.of(resultSet.getBytes("hash"));
				String address = resultSet.getString("toaddress");
				long outputindex = resultSet.getLong("outputindex");

				OutputsMulti outputsMulti = new OutputsMulti(sha256Hash, address, outputindex);
				list.add(outputsMulti);
			}
			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public UserData queryUserDataWithPubKeyAndDataclassname(String dataclassname, String pubKey)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_USERDATA_SQL)) {
			preparedStatement.setString(1, dataclassname);
			preparedStatement.setString(2, pubKey);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			UserData userData = new UserData();
			Sha256Hash blockhash = resultSet.getBytes("blockhash") != null
					? Sha256Hash.wrap(resultSet.getBytes("blockhash"))
					: null;
			userData.setBlockhash(blockhash);
			userData.setData(resultSet.getBytes("data"));
			userData.setDataclassname(resultSet.getString("dataclassname"));
			userData.setPubKey(resultSet.getString("pubKey"));
			userData.setBlocktype(resultSet.getLong("blocktype"));
			return userData;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void insertUserData(UserData userData) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_USERDATA_SQL)) {
			preparedStatement.setBytes(1, userData.getBlockhash().getBytes());
			preparedStatement.setString(2, userData.getDataclassname());
			preparedStatement.setBytes(3, userData.getData());
			preparedStatement.setString(4, userData.getPubKey());
			preparedStatement.setLong(5, userData.getBlocktype());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<UserData> getUserDataListWithBlocktypePubKeyList(int blocktype, List<String> pubKeyList)
			throws BlockStoreException {
		if (pubKeyList.isEmpty()) {
			return new ArrayList<>();
		}

		PreparedStatement preparedStatement = null;
		try {
			String sql = "select blockhash, dataclassname, data, pubKey, blocktype from userdata where blocktype = ? and pubKey in ";
			StringBuilder stringBuffer = new StringBuilder();
			for (String str : pubKeyList)
				stringBuffer.append(",'").append(str).append("'");
			sql += "(" + stringBuffer.substring(1) + ")";

			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setLong(1, blocktype);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<UserData> list = new ArrayList<>();
			while (resultSet.next()) {
				UserData userData = new UserData();
				Sha256Hash blockhash = resultSet.getBytes("blockhash") != null
						? Sha256Hash.wrap(resultSet.getBytes("blockhash"))
						: null;
				userData.setBlockhash(blockhash);
				userData.setData(resultSet.getBytes("data"));
				userData.setDataclassname(resultSet.getString("dataclassname"));
				userData.setPubKey(resultSet.getString("pubKey"));
				userData.setBlocktype(resultSet.getLong("blocktype"));
				list.add(userData);
			}
			return list;
		} catch (SQLException ex) {
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
	public void updateUserData(UserData userData) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_USERDATA_SQL)) {
			preparedStatement.setBytes(1, userData.getBlockhash().getBytes());
			preparedStatement.setBytes(2, userData.getData());
			preparedStatement.setString(3, userData.getDataclassname());
			preparedStatement.setString(4, userData.getPubKey());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertPayPayMultiSign(PayMultiSign payMultiSign) throws BlockStoreException {
		String sql = "insert into paymultisign (orderid, tokenid, toaddress, blockhash, amount, minsignnumber,"
				+ " outputHashHex,  outputindex) values (?, ?, ?, ?, ?, ?, ?,?)";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, payMultiSign.getOrderid());
			preparedStatement.setString(2, payMultiSign.getTokenid());
			preparedStatement.setString(3, payMultiSign.getToaddress());
			preparedStatement.setBytes(4, payMultiSign.getBlockhash());
			preparedStatement.setBytes(5, payMultiSign.getAmount().toByteArray());
			preparedStatement.setLong(6, payMultiSign.getMinsignnumber());
			preparedStatement.setString(7, payMultiSign.getOutputHashHex());
			preparedStatement.setLong(8, payMultiSign.getOutputindex());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertPayMultiSignAddress(PayMultiSignAddress payMultiSignAddress) throws BlockStoreException {
		String sql = "insert into paymultisignaddress (orderid, pubKey, sign, signInputData, signIndex) values (?, ?, ?, ?, ?)";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, payMultiSignAddress.getOrderid());
			preparedStatement.setString(2, payMultiSignAddress.getPubKey());
			preparedStatement.setInt(3, payMultiSignAddress.getSign());
			preparedStatement.setBytes(4, payMultiSignAddress.getSignInputData());
			preparedStatement.setInt(5, payMultiSignAddress.getSignIndex());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updatePayMultiSignAddressSign(String orderid, String pubKey, int sign, byte[] signInputData)
			throws BlockStoreException {
		String sql = "update paymultisignaddress set sign = ?, signInputData = ? where orderid = ? and pubKey = ?";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setInt(1, sign);
			preparedStatement.setBytes(2, signInputData);
			preparedStatement.setString(3, orderid);
			preparedStatement.setString(4, pubKey);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public PayMultiSign getPayMultiSignWithOrderid(String orderid) throws BlockStoreException {
		String sql = "select orderid, tokenid, toaddress, blockhash, amount, minsignnumber, outputHashHex, outputindex from paymultisign where orderid = ?";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, orderid.trim());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			PayMultiSign payMultiSign = new PayMultiSign();
			payMultiSign.setAmount(new BigInteger(resultSet.getBytes("amount")));
			payMultiSign.setBlockhash(resultSet.getBytes("blockhash"));
			payMultiSign.setMinsignnumber(resultSet.getLong("minsignnumber"));
			payMultiSign.setOrderid(resultSet.getString("orderid"));
			payMultiSign.setToaddress(resultSet.getString("toaddress"));
			payMultiSign.setTokenid(resultSet.getString("tokenid"));
			payMultiSign.setBlockhashHex(Utils.HEX.encode(payMultiSign.getBlockhash()));
			payMultiSign.setOutputHashHex(resultSet.getString("outputHashHex"));
			payMultiSign.setOutputindex(resultSet.getLong("outputindex"));
			return payMultiSign;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<PayMultiSignAddress> getPayMultiSignAddressWithOrderid(String orderid) throws BlockStoreException {
		String sql = "select orderid, pubKey, sign, signInputData, signIndex from paymultisignaddress where orderid = ? ORDER BY signIndex ASC";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, orderid.trim());
			ResultSet resultSet = preparedStatement.executeQuery();
			List<PayMultiSignAddress> list = new ArrayList<>();
			while (resultSet.next()) {
				PayMultiSignAddress payMultiSignAddress = new PayMultiSignAddress();
				payMultiSignAddress.setOrderid(resultSet.getString("orderid"));
				payMultiSignAddress.setPubKey(resultSet.getString("pubKey"));
				payMultiSignAddress.setSign(resultSet.getInt("sign"));
				payMultiSignAddress.setSignInputData(resultSet.getBytes("signInputData"));
				payMultiSignAddress.setSignIndex(resultSet.getInt("signIndex"));
				list.add(payMultiSignAddress);
			}
			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void updatePayMultiSignBlockhash(String orderid, byte[] blockhash) throws BlockStoreException {
		String sql = "update paymultisign set blockhash = ? where orderid = ?";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setBytes(1, blockhash);
			preparedStatement.setString(2, orderid);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<PayMultiSign> getPayMultiSignList(List<String> pubKeys) throws BlockStoreException {
		if (pubKeys.isEmpty()) {
			return new ArrayList<>();
		}
		String sql = "SELECT paymultisign.orderid, tokenid, toaddress, blockhash, amount, minsignnumber, outputHashHex,"
				+ "outputindex, sign,(select count(1) from  paymultisignaddress t where t.orderid=paymultisign.orderid AND sign!=0) as signcount "
				+ " FROM paymultisign LEFT JOIN paymultisignaddress ON paymultisign.orderid = paymultisignaddress.orderid "
				+ " WHERE paymultisignaddress.pubKey ";
		StringBuilder stringBuffer = new StringBuilder();
		for (String pubKey : pubKeys)
			stringBuffer.append(",'").append(pubKey).append("'");
		sql += " in (" + stringBuffer.substring(1) + ")";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			List<PayMultiSign> list = new ArrayList<>();
			while (resultSet.next()) {
				PayMultiSign payMultiSign = new PayMultiSign();
				payMultiSign.setAmount(new BigInteger(resultSet.getBytes("amount")));
				payMultiSign.setBlockhash(resultSet.getBytes("blockhash"));
				payMultiSign.setMinsignnumber(resultSet.getLong("minsignnumber"));
				payMultiSign.setOrderid(resultSet.getString("orderid"));
				payMultiSign.setToaddress(resultSet.getString("toaddress"));
				payMultiSign.setTokenid(resultSet.getString("tokenid"));
				payMultiSign.setBlockhashHex(Utils.HEX.encode(payMultiSign.getBlockhash()));
				payMultiSign.setOutputHashHex(resultSet.getString("outputHashHex"));
				payMultiSign.setOutputindex(resultSet.getLong("outputindex"));
				payMultiSign.setSign(resultSet.getInt("sign"));
				payMultiSign.setSigncount(resultSet.getInt("signcount"));
				list.add(payMultiSign);
			}
			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public int getCountPayMultiSignAddressStatus(String orderid) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement("select count(*) as count from paymultisignaddress where orderid = ? and sign = 1")) {
			preparedStatement.setString(1, orderid);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public UTXO getOutputsWithHexStr(byte[] hash, long outputindex) throws BlockStoreException {
		String sql = "SELECT coinvalue, scriptbytes, coinbase, toaddress,"
				+ " addresstargetable, blockhash, tokenid, fromaddress, memo, minimumsign, time, spent, confirmed, "
				+ " spendpending, spendpendingtime FROM outputs WHERE hash = ? and outputindex = ?";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setBytes(1, hash);
			preparedStatement.setLong(2, outputindex);
			ResultSet results = preparedStatement.executeQuery();
			if (!results.next()) {
				return null;
			}

			Coin amount = new Coin(new BigInteger(results.getBytes("coinvalue")), results.getString("tokenid"));
			byte[] scriptBytes = results.getBytes("scriptbytes");
			boolean coinbase = results.getBoolean("coinbase");
			String address = results.getString("toaddress");
			Sha256Hash blockhash = Sha256Hash.wrap(results.getBytes("blockhash"));
			Sha256Hash spenderblockhash = Sha256Hash.wrap(results.getBytes("spenderblockhash"));
			String fromaddress = results.getString("fromaddress");
			String memo = results.getString("memo");
			boolean spent = results.getBoolean("spent");
			boolean confirmed = results.getBoolean("confirmed");
			boolean spendPending = results.getBoolean("spendpending");
			String tokenid = results.getString("tokenid");

			return new UTXO(Sha256Hash.wrap(hash), outputindex, amount, coinbase, new Script(scriptBytes), address,
					blockhash, fromaddress, memo, tokenid, spent, confirmed, spendPending, 0,
					results.getLong("spendpendingtime"), results.getLong("time"), spenderblockhash);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public byte[] getSettingValue(String name) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(getSelectSettingsSQL())) {
			preparedStatement.setString(1, name);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return resultSet.getBytes(1);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertBatchBlock(Block block) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_BATCHBLOCK_SQL)) {
			preparedStatement.setBytes(1, block.getHash().getBytes());
			preparedStatement.setBytes(2, block.bitcoinSerialize());
			preparedStatement.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis()));
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteBatchBlock(Sha256Hash hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(DELETE_BATCHBLOCK_SQL)) {
			preparedStatement.setBytes(1, hash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<BatchBlock> getBatchBlockList() throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_BATCHBLOCK_SQL)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			List<BatchBlock> list = new ArrayList<>();
			while (resultSet.next()) {
				BatchBlock batchBlock = new BatchBlock();
				batchBlock.setHash(Sha256Hash.wrap(resultSet.getBytes("hash")));
				batchBlock.setBlock(resultSet.getBytes("block"));
				batchBlock.setInsertTime(resultSet.getDate("inserttime"));
				list.add(batchBlock);
			}
			return list;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void insertTipsQueue(TipsQueue tipsQueue) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_TIPSQUEUE_SQL)) {
			preparedStatement.setBytes(1, tipsQueue.getHash());
			preparedStatement.setBytes(2, tipsQueue.getBlock());
			preparedStatement.setLong(3, tipsQueue.getHeight());
			preparedStatement.setLong(4, tipsQueue.getInserttime());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteTipsQueue(Sha256Hash hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(DELETE_TIPSQUEUE_SQL)) {
			preparedStatement.setBytes(1, hash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public TipsQueue getTipsQueue() throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TIPSQUEUE_SQL);
			 ResultSet resultSet = preparedStatement.executeQuery()) {
			if (resultSet.next()) {
				byte[] hash = resultSet.getBytes(1);
				byte[] block = resultSet.getBytes(2);
				long height = resultSet.getLong(3);
				long inserttime = resultSet.getLong(4);
				return new TipsQueue(hash, block, height, inserttime);
			}
			return null;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public List<TipsQueue> getAllTipsQueue() throws BlockStoreException {
		List<TipsQueue> result = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_ALL_TIPSQUEUE_SQL);
			 ResultSet rs = preparedStatement.executeQuery()) {
			while (rs.next()) {
				byte[] hash = rs.getBytes(1);
				byte[] block = rs.getBytes(2);
				long height = rs.getLong(3);
				long inserttime = rs.getLong(4);
				result.add(new TipsQueue(hash, block, height, inserttime));
			}
			return result;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void insertSubtanglePermission(String pubkey, String userdatapubkey, String status)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_SUBTANGLE_PERMISSION_SQL)) {
			preparedStatement.setString(1, pubkey);
			preparedStatement.setString(2, userdatapubkey);
			preparedStatement.setString(3, status);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteSubtanglePermission(String pubkey) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(DELETE_SUBTANGLE_PERMISSION_SQL)) {
			preparedStatement.setString(1, pubkey);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateSubtanglePermission(String pubkey, String userdataPubkey, String status)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(UPATE_ALL_SUBTANGLE_PERMISSION_SQL)) {
			preparedStatement.setString(1, status);
			preparedStatement.setString(2, userdataPubkey);
			preparedStatement.setString(3, pubkey);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Map<String, String>> getAllSubtanglePermissionList() throws BlockStoreException {

		List<Map<String, String>> list = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_ALL_SUBTANGLE_PERMISSION_SQL)) {

			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Map<String, String> map = new HashMap<>();
				map.put("pubkey", resultSet.getString("pubkey"));
				map.put("userdataPubkey", resultSet.getString("userdataPubkey"));
				map.put("status", resultSet.getString("status"));
				list.add(map);
			}
			return list;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Map<String, String>> getSubtanglePermissionListByPubkey(String pubkey) throws BlockStoreException {

		List<Map<String, String>> list = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_ALL_SUBTANGLE_PERMISSION_SQL)) {
			preparedStatement.setString(1, pubkey);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Map<String, String> map = new HashMap<>();
				map.put("pubkey", resultSet.getString("pubkey"));
				map.put("userdataPubkey", resultSet.getString("userdataPubkey"));
				map.put("status", resultSet.getString("status"));
				list.add(map);
			}
			return list;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Map<String, String>> getSubtanglePermissionListByPubkeys(List<String> pubkeys)
			throws BlockStoreException {
		String sql = SELECT_SUBTANGLE_PERMISSION_BY_PUBKEYS_SQL + " AND pubkey ";
		StringBuilder stringBuffer = new StringBuilder();
		for (String pubKey : pubkeys)
			stringBuffer.append(",'").append(pubKey).append("'");
		sql += " in (" + stringBuffer.substring(1) + ")";

		List<Map<String, String>> list = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Map<String, String> map = new HashMap<>();
				map.put("pubkey", resultSet.getString("pubkey"));
				map.put("userdataPubkey", resultSet.getString("userdataPubkey"));
				map.put("status", resultSet.getString("status"));
				list.add(map);
			}
			return list;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public HashMap<Sha256Hash, OrderRecord> getOrderMatchingIssuedOrders(Sha256Hash issuingMatcherBlockHash)
			throws BlockStoreException {

		HashMap<Sha256Hash, OrderRecord> result = new HashMap<>();
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_ORDERS_BY_ISSUER_SQL)) {
			preparedStatement.setBytes(1, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				result.put(Sha256Hash.wrap(resultSet.getBytes(1)), setOrder(resultSet));
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public OrderRecord getOrder(Sha256Hash txHash, Sha256Hash issuingMatcherBlockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_ORDER_SQL)) {
			preparedStatement.setBytes(1, txHash.getBytes());
			preparedStatement.setBytes(2, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next())
				return null;

			return setOrder(resultSet);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void insertCancelOrder(OrderCancel orderCancel) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_OrderCancel_SQL)) {
			// "blockhash, orderblockhash, confirmed, spent, spenderblockhash,time";
			preparedStatement.setBytes(1, orderCancel.getBlockHash().getBytes());
			preparedStatement.setBytes(2, orderCancel.getOrderBlockHash().getBytes());
			preparedStatement.setBoolean(3, true);
			preparedStatement.setBoolean(4, orderCancel.isSpent());
			preparedStatement.setBytes(5,
					orderCancel.getSpenderBlockHash() != null ? orderCancel.getSpenderBlockHash().getBytes() : null);
			preparedStatement.setLong(6, orderCancel.getTime());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		}

	}

	@Override
	public void updateContractEventCancelSpent(Set<Sha256Hash> cancels, Sha256Hash blockhash, Boolean spent)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(CONTRACTEVENTCANCEL_UPDATE_SPENT_SQL)) {
			for (Sha256Hash o : cancels) {
				preparedStatement.setBoolean(1, spent);
				preparedStatement.setBytes(2, blockhash != null ? blockhash.getBytes() : null);
				preparedStatement.setBytes(3, o.getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertOrder(Collection<OrderRecord> records) throws BlockStoreException {
		if (records == null)
			return;

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_ORDER_SQL)) {
			for (OrderRecord record : records) {
				preparedStatement.setBytes(1, record.getBlockHash().getBytes());
				preparedStatement.setBytes(2, record.getIssuingMatcherBlockHash().getBytes());
				preparedStatement.setLong(3, record.getOfferValue());
				preparedStatement.setString(4, record.getOfferTokenid());
				preparedStatement.setBoolean(5, record.isConfirmed());
				preparedStatement.setBoolean(6, record.isSpent());
				preparedStatement.setBytes(7,
						record.getSpenderBlockHash() != null ? record.getSpenderBlockHash().getBytes() : null);
				preparedStatement.setLong(8, record.getTargetValue());
				preparedStatement.setString(9, record.getTargetTokenid());
				preparedStatement.setBytes(10, record.getBeneficiaryPubKey());
				preparedStatement.setLong(11, record.getValidToTime());
				preparedStatement.setLong(12, record.getValidFromTime());
				preparedStatement.setString(13, record.getSide() == null ? null : record.getSide().name());
				preparedStatement.setString(14, record.getBeneficiaryAddress());
				preparedStatement.setString(15, record.getOrderBaseToken());
				preparedStatement.setLong(16, record.getPrice());
				preparedStatement.setInt(17, record.getTokenDecimals());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();

		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		}

	}

	@Override
	public void updateOrderConfirmed(Collection<OrderRecord> orderRecords, boolean confirm) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_ORDER_CONFIRMED_SQL)) {
			for (OrderRecord o : orderRecords) {
				preparedStatement.setBoolean(1, confirm);
				preparedStatement.setBytes(2, o.getBlockHash().getBytes());
				preparedStatement.setBytes(3, o.getIssuingMatcherBlockHash().getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateOrderSpent(Collection<OrderRecord> orderRecords) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_ORDER_SPENT_SQL)) {
			for (OrderRecord o : orderRecords) {
				preparedStatement.setBoolean(1, o.isSpent());
				preparedStatement.setBytes(2,
						o.getSpenderBlockHash() != null ? o.getSpenderBlockHash().getBytes() : null);
				preparedStatement.setBytes(3, o.getBlockHash().getBytes());
				preparedStatement.setBytes(4, o.getIssuingMatcherBlockHash().getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertContractEvent(Collection<ContractEventRecord> records) throws BlockStoreException {
		if (records == null)
			return;

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_CONTRACT_EVENT_SQL)) {
			for (ContractEventRecord record : records) {
				preparedStatement.setBytes(1, record.getBlockHash().getBytes());

				preparedStatement.setString(2, record.getContractTokenid());
				preparedStatement.setBoolean(3, record.isConfirmed());
				preparedStatement.setBoolean(4, record.isSpent());
				preparedStatement.setBytes(5,
						record.getSpenderBlockHash() != null ? record.getSpenderBlockHash().getBytes() : null);
				preparedStatement.setBytes(6, record.getTargetValue().toByteArray());
				preparedStatement.setString(7, record.getTargetTokenid());
				preparedStatement.setString(8, record.getBeneficiaryAddress());
				preparedStatement.setBytes(9, record.getCollectinghash().getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();

		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		}

	}

	@Override
	public void updateOrderBlockhash(Sha256Hash blockhash, Sha256Hash collectinghash, boolean confirm, boolean spent,
			Sha256Hash spenderBlockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(getUpdate() + " orders SET spent = ?, spenderblockhash = ?, confirmed =? "
						+ " WHERE collectinghash=? and blockhash=? ")) {

			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spenderBlockHash != null ? spenderBlockHash.getBytes() : null);
			preparedStatement.setBoolean(3, confirm);
			preparedStatement.setBytes(4, collectinghash.getBytes());
			preparedStatement.setBytes(5, blockhash.getBytes());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateOrderPrevhash(Sha256Hash collectinghash, boolean confirm, boolean spent,
			Sha256Hash spenderBlockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(getUpdate()
				+ " orders SET spent = ?, spenderblockhash = ?, confirmed =? " + " WHERE collectinghash=?   ")) {

			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spenderBlockHash != null ? spenderBlockHash.getBytes() : null);
			preparedStatement.setBoolean(3, confirm);
			preparedStatement.setBytes(4, collectinghash.getBytes());

			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateContractEventBlockhash(Sha256Hash blockhash, Sha256Hash collectinghash, boolean confirm,
			boolean spent, Sha256Hash spenderBlockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(getUpdate() + " contractevent SET spent = ?, spenderblockhash = ?, confirmed =? "
						+ " WHERE collectinghash=? and blockhash=? ")) {

			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spenderBlockHash != null ? spenderBlockHash.getBytes() : null);
			preparedStatement.setBoolean(3, confirm);
			preparedStatement.setBytes(4, collectinghash.getBytes());
			preparedStatement.setBytes(5, blockhash.getBytes());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateContractEventPrevhash(Sha256Hash collectinghash, boolean confirm, boolean spent,
			Sha256Hash spenderBlockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(getUpdate()
				+ " contractevent SET spent = ?, spenderblockhash = ?, confirmed =? " + " WHERE collectinghash=?   ")) {
			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spenderBlockHash != null ? spenderBlockHash.getBytes() : null);
			preparedStatement.setBoolean(3, confirm);
			preparedStatement.setBytes(4, collectinghash.getBytes());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateContractEventSpent(Collection<ContractEventRecord> records) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(getUpdate() + " contractevent SET spent = ?, spenderblockhash = ? , confirmed=?"
						+ " WHERE blockhash = ? and collectinghash=? ")) {

			for (ContractEventRecord o : records) {
				preparedStatement.setBoolean(1, o.isSpent());
				preparedStatement.setBytes(2,
						o.getSpenderBlockHash() != null ? o.getSpenderBlockHash().getBytes() : null);
				preparedStatement.setBoolean(3, o.isConfirmed());
				preparedStatement.setBytes(4, o.getBlockHash().getBytes());
				preparedStatement.setBytes(5, o.getCollectinghash().getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public Map<Sha256Hash, ContractEventRecord> getContractEventPrev(String contractid, Sha256Hash prevHash)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_PREV_CONTRACT_SQL)) {
			preparedStatement.setString(1, contractid);
			preparedStatement.setBytes(2, prevHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			Map<Sha256Hash, ContractEventRecord> list = new HashMap<>();
			while (resultSet.next()) {
				ContractEventRecord contractEventRecord = setContractEventRecord(resultSet);
				list.put(contractEventRecord.getBlockHash(), contractEventRecord);
			}
			return list;

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<ContractEventRecord> getContractEvents(Sha256Hash hash) throws BlockStoreException {
		List<ContractEventRecord> list = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement("SELECT " + CONTRACT_TEMPLATE + " FROM contractevent WHERE  blockhash = ?   ")) {
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				list.add(setContractEventRecord(resultSet));
			}
			return list;

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public ContractEventRecord getContractEvent(Sha256Hash blockhash, Sha256Hash collectinghash)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_CONTRACT_SQL)) {
			preparedStatement.setBytes(1, blockhash.getBytes());
			preparedStatement.setBytes(2, collectinghash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();

			if (resultSet.next()) {
				return setContractEventRecord(resultSet);
			}
			return null;

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertContractResult(ContractExecutionResult record, Sha256Hash blockhash) throws BlockStoreException {
		if (record == null)
			return;

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_CONTRACT_RESULT_SQL)) {
			preparedStatement.setBytes(1, blockhash.getBytes());
			preparedStatement.setString(2, record.getContracttokenid());
			preparedStatement.setBoolean(3, record.isConfirmed());
			preparedStatement.setBoolean(4, record.isSpent());
			preparedStatement.setBytes(5,
					record.getSpenderBlockHash() != null ? record.getSpenderBlockHash().getBytes() : null);
			preparedStatement.setBytes(6, record.toByteArray());
			preparedStatement.setBytes(7,
					record.getPrevblockhash() != null ? record.getPrevblockhash().getBytes() : null);
			preparedStatement.setLong(8, record.getTime());
			preparedStatement.setLong(9, -1);
			preparedStatement.setLong(10, record.getChainlength());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		}

	}

	@Override
	public void updateContractResultSpent(Sha256Hash contractResult, Sha256Hash spentBlock, boolean spent)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(
				getUpdate() + " contractresult SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ? ")) {

			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spentBlock != null ? spentBlock.getBytes() : null);
			preparedStatement.setBytes(3, contractResult.getBytes());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Orderresult> getOrderresultWithPrev(Sha256Hash prevhash) throws BlockStoreException {
		// one of the block is spent then, return Sha256Hash, otherwise null
		List<Orderresult> re = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_ORDERRESULT_PREV_HASH_SQL)) {

			preparedStatement.setBytes(1, prevhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				re.add(setOrderresult(resultSet));
			}
			return re;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateContractResultConfirmed(Sha256Hash record, boolean confirm) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(UPDATE_CONTRACTRESULT_CONFIRMED_SQL)) {

			preparedStatement.setBoolean(1, confirm);
			preparedStatement.setBytes(2, record.getBytes());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public Contractresult getContractresult(Sha256Hash blockhash) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_CONTRACTRESULT_HASH_SQL)) {
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return setContractresult(resultSet);

			}
			return Contractresult.firstContractresult();

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Contractresult> getContractresultWithPrev(Sha256Hash prev) throws BlockStoreException {

		List<Contractresult> re = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_CONTRACTRESULT_PREV_HASH_SQL)) {
			preparedStatement.setBytes(1, prev.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				re.add(setContractresult(resultSet));
			}
			return re;

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public Orderresult getOrderResult(Sha256Hash blockhash) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_ORDERRESULT_HASH_SQL)) {
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return setOrderresult(resultSet);

			}
			return Orderresult.zeroOrderresult();

		} catch (Exception e) {
			throw new BlockStoreException(e);
		}

	}

	private Orderresult setOrderresult(ResultSet resultSet) throws SQLException {
		return new Orderresult(Sha256Hash.wrap(resultSet.getBytes("blockhash")), resultSet.getBoolean("confirmed"),
				resultSet.getBoolean("spent"), Sha256Hash.wrap(resultSet.getBytes("prevblockhash")),
				Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")), resultSet.getBytes("orderresult"),
				resultSet.getLong("rewardchainlength"), resultSet.getLong("chainlength"), resultSet.getLong("inserttime"));

	}

	private Contractresult setContractresult(ResultSet resultSet) throws SQLException {
		return new Contractresult(Sha256Hash.wrap(resultSet.getBytes("blockhash")), resultSet.getBoolean("confirmed"),
				resultSet.getBoolean("spent"), Sha256Hash.wrap(resultSet.getBytes("prevblockhash")),
				Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")), resultSet.getBytes("contractresult"),
				resultSet.getString("contracttokenid"), resultSet.getLong("rewardchainlength"),
				resultSet.getLong("chainlength"), resultSet.getLong("inserttime"));

	}

	@Override
	public void insertOrderResult(OrderExecutionResult record, Sha256Hash blockhash) throws BlockStoreException {
		if (record == null)
			return;

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_ORDER_RESULT_SQL)) {
			preparedStatement.setBytes(1, blockhash.getBytes());

			preparedStatement.setBoolean(2, record.isConfirmed());
			preparedStatement.setBoolean(3, record.isSpent());
			preparedStatement.setBytes(4,
					record.getSpenderBlockHash() != null ? record.getSpenderBlockHash().getBytes() : null);
			preparedStatement.setBytes(5, record.toByteArray());
			preparedStatement.setBytes(6,
					record.getPrevblockhash() != null ? record.getPrevblockhash().getBytes() : null);
			preparedStatement.setLong(7, record.getTime());
			preparedStatement.setLong(8, -1);
			preparedStatement.setLong(9, record.getChainlength());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		}

	}

	@Override
	public void updateOrderResultSpent(Sha256Hash blockhash, Sha256Hash spentBlock, boolean spent)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(
				getUpdate() + " orderresult SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ? ")) {

			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spentBlock != null ? spentBlock.getBytes() : null);
			preparedStatement.setBytes(3, blockhash.getBytes());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void updateOrderResultConfirmed(Sha256Hash record, boolean confirm) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(UPDATE_ORDERRESULT_CONFIRMED_SQL)) {

			preparedStatement.setBoolean(1, confirm);
			preparedStatement.setBytes(2, record.getBytes());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	/*
	 * all spent order and older than a month will be deleted from order table.
	 */
	@Override
	public void prunedClosedOrders(Long beforetime) throws BlockStoreException {

		try (PreparedStatement deleteStatement = getConnection().prepareStatement("DELETE FROM orders\n"
				+ "    WHERE blockhash IN (\n" + "        SELECT blockhash\n" + "        FROM orders\n"
				+ "       WHERE spent = true\n" + "       AND validToTime < ?\n" + "       LIMIT 1000\n" + "    ); ")) {

			deleteStatement.setLong(1, beforetime - 100 * NetworkParameters.ORDER_TIMEOUT_MAX);
			deleteStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	/*
	 * remove the blocks, only if : 1) there is no unspent transaction related to
	 * the block 2) this block is outside the cutoff height, reorg is possible 3)
	 * the spenderblock is outside the cutoff height, reorg is possible
	 */
	@Override
	public void prunedBlocks(Long height, Long chain) throws BlockStoreException {

		PreparedStatement preparedStatement;
		try (PreparedStatement deleteStatement = getConnection()
				.prepareStatement(" delete FROM blocks WHERE" + "   hash  = ? ")) {

			preparedStatement = getConnection()
					.prepareStatement("  select distinct( blocks.hash) from  blocks  , outputs "
							+ " where spenderblockhash = blocks.hash    "
							+ "  and blocks.chainlength < ? and blocks.chainlength > 0  " + " and ( blocks.blocktype = '"
							+ BlockType.BLOCKTYPE_TRANSFER.name() + "' or blocks.blocktype = '"
							+ BlockType.BLOCKTYPE_ORDER_OPEN.name() + "' or blocks.blocktype = '"
							+ BlockType.BLOCKTYPE_BEACON.name() + "'  ) limit 1000 ");
			// preparedStatement.setLong(1, height);
			preparedStatement.setLong(1, chain);

			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				deleteStatement.setBytes(1, resultSet.getBytes(1));
				deleteStatement.addBatch();
			}

			// log.debug(deleteStatement.toString());
			int[] r = deleteStatement.executeBatch();
			log.debug(" deleteStatement.executeBatch() count = {}", r.length);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	/*
	 * all spent UTXO History and older than the maxRewardblock can be pruned.
	 */
	@Override
	public void prunedHistoryUTXO(Long maxRewardblock) throws BlockStoreException {

		try (PreparedStatement deleteStatement = getConnection()
				.prepareStatement(" delete FROM outputs WHERE  spent=true AND "
						+ "spenderblockhash in (select hash from blocks where chainlength < ? and chainlength > 0  limit 1000 ) ")) {
			deleteStatement.setLong(1, maxRewardblock);
			deleteStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	/*
	 * all spent UTXO History and older than the before time, minimum 60 days
	 */
	@Override
	public void prunedPriceTicker(Long beforetime) throws BlockStoreException {

		PreparedStatement deleteStatement = null;
		try {

			long minTime = Math.min(beforetime, System.currentTimeMillis() / 1000 - 60 * 24 * 60 * 60);
			deleteStatement = getConnection()
					.prepareStatement(" delete FROM matching WHERE inserttime < ?  limit 1000 ");
			deleteStatement.setLong(1, minTime);
			// System.currentTimeMillis() / 1000 - 10 *
			// NetworkParameters.ORDER_TIMEOUT_MAX);
			deleteStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {

			if (deleteStatement != null) {
				try {
					deleteStatement.close();
				} catch (SQLException e) {

				}
			}
		}

	}

	@Override
	public List<OrderRecord> getAllOpenOrdersSorted(List<String> addresses, String tokenid) throws BlockStoreException {
		List<OrderRecord> result = new ArrayList<>();

		String sql = SELECT_OPEN_ORDERS_SORTED_SQL;
		String orderby = " ORDER BY blockhash, collectinghash";

		if (tokenid != null && !tokenid.trim().isEmpty()) {
			sql += " AND (offertokenid=? or targettokenid=?)";
		}
		if (addresses != null && !addresses.isEmpty()) {
			sql += " AND beneficiaryaddress in (";

			sql += buildINList(addresses) + ")";
		}
		sql += orderby;
		try (PreparedStatement s = getConnection().prepareStatement(sql)) {
			// log.debug(sql);
			int i = 1;

			if (tokenid != null && !tokenid.trim().isEmpty()) {
				s.setString(i++, tokenid);
				s.setString(i, tokenid);
			}
			ResultSet resultSet = s.executeQuery();
			while (resultSet.next()) {
				OrderRecord order = setOrder(resultSet);
				result.add(order);
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} catch (ProtocolException e) {
			// Corrupted database.
			throw new BlockStoreException(e);
		} catch (VerificationException e) {
			// Should not be able to happen unless the database contains bad
			// blocks.
			throw new BlockStoreException(e);
		}

	}

	private OrderRecord setOrder(ResultSet resultSet) throws SQLException {
		return new OrderRecord(Sha256Hash.wrap(resultSet.getBytes("blockhash")),
				Sha256Hash.wrap(resultSet.getBytes("collectinghash")), resultSet.getLong("offercoinvalue"),
				resultSet.getString("offertokenid"), resultSet.getBoolean("confirmed"), resultSet.getBoolean("spent"),
				resultSet.getBytes("spenderblockhash") == null ? null
						: Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")),
				resultSet.getLong("targetcoinvalue"), resultSet.getString("targetTokenid"),
				resultSet.getBytes("beneficiarypubkey"), resultSet.getLong("validToTime"),
				resultSet.getLong("validFromTime"), resultSet.getString("side"),
				resultSet.getString("beneficiaryaddress"), resultSet.getString("orderbasetoken"),
				resultSet.getLong("price"), resultSet.getInt("tokendecimals"));

	}

	private ContractEventRecord setContractEventRecord(ResultSet resultSet) throws SQLException {
		return new ContractEventRecord(Sha256Hash.wrap(resultSet.getBytes("blockhash")),
				Sha256Hash.wrap(resultSet.getBytes("collectinghash")), resultSet.getString("contractTokenid"),
				resultSet.getBoolean("confirmed"), resultSet.getBoolean("spent"),
				resultSet.getBytes("spenderblockhash") == null ? null
						: Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")),
				new BigInteger(resultSet.getBytes("targetcoinvalue")), resultSet.getString("targetTokenid"),
				resultSet.getString("beneficiaryaddress"));

	}

	@Override
	public List<UTXO> getAllAvailableUTXOsSorted() throws BlockStoreException {
		List<UTXO> result = new ArrayList<>();

		try (PreparedStatement s = getConnection().prepareStatement(SELECT_AVAILABLE_UTXOS_SORTED_SQL)) {
			ResultSet resultSet = s.executeQuery();
			while (resultSet.next()) {
				// Parse it.
				Coin amount = new Coin(new BigInteger(resultSet.getBytes("coinvalue")), resultSet.getString("tokenid"));

				byte[] scriptBytes = resultSet.getBytes(2);
				boolean coinbase = resultSet.getBoolean(3);
				String address = resultSet.getString(4);
				Sha256Hash blockhash = resultSet.getBytes(6) != null ? Sha256Hash.wrap(resultSet.getBytes(6)) : null;

				String fromaddress = resultSet.getString(8);
				String memo = resultSet.getString(9);
				boolean spent = resultSet.getBoolean(10);
				boolean confirmed = resultSet.getBoolean(11);
				boolean spendPending = resultSet.getBoolean(12);
				String tokenid = resultSet.getString("tokenid");
				byte[] hash = resultSet.getBytes("hash");
				long index = resultSet.getLong("outputindex");
				Sha256Hash spenderblockhash = Sha256Hash.wrap(resultSet.getBytes("spenderblockhash"));
				UTXO txout = new UTXO(Sha256Hash.wrap(hash), index, amount, coinbase, new Script(scriptBytes), address,
						blockhash, fromaddress, memo, tokenid, spent, confirmed, spendPending, 0,
						resultSet.getLong("spendpendingtime"), resultSet.getLong("time"), spenderblockhash);
				result.add(txout);
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} catch (ProtocolException e) {
			// Corrupted database.
			throw new BlockStoreException(e);
		} catch (VerificationException e) {
			// Should not be able to happen unless the database contains bad
			// blocks.
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertMyserverblocks(Sha256Hash prevhash, Sha256Hash hash, Long inserttime) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" insert into myserverblocks (prevhash, hash, inserttime) values (?,?,?) ")) {
			preparedStatement.setBytes(1, prevhash.getBytes());
			preparedStatement.setBytes(2, hash.getBytes());
			preparedStatement.setLong(3, inserttime);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteMyserverblocks(Sha256Hash prevhash) throws BlockStoreException {
		// delete only one, but anyone

		PreparedStatement preparedStatement = null;
		PreparedStatement p2 = null;
		try {
			preparedStatement = getConnection().prepareStatement(" select hash from myserverblocks where prevhash = ?");
			preparedStatement.setBytes(1, prevhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				byte[] hash = resultSet.getBytes(1);
				p2 = getConnection().prepareStatement(" delete  from  myserverblocks  where prevhash = ?  and hash =?");
				p2.setBytes(1, prevhash.getBytes());
				p2.setBytes(2, hash);
				p2.executeUpdate();
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
			if (p2 != null) {
				try {
					p2.close();
				} catch (SQLException e) {

				}
			}
		}

	}

	@Override
	public boolean existBlock(Sha256Hash hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" select hash from blocks where hash = ?")) {
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			return resultSet.next();
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public boolean existMyserverblocks(Sha256Hash prevhash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" select hash from myserverblocks where prevhash = ?")) {
			preparedStatement.setBytes(1, prevhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			return resultSet.next();
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void insertMatchingEvent(List<MatchResult> matchs) throws BlockStoreException {

		// log.debug("insertMatchingEvent: " + matchs.size());
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_MATCHING_EVENT_SQL)) {

			for (MatchResult match : matchs) {
				preparedStatement.setString(1, match.getTxhash());
				preparedStatement.setString(2, match.getTokenid());
				preparedStatement.setString(3, match.getBasetokenid());
				preparedStatement.setLong(4, match.getPrice());
				preparedStatement.setLong(5, match.getExecutedQuantity());
				preparedStatement.setLong(6, match.getInserttime());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
			insertMatchingEventLast(filterMatch(matchs));
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	public void insertMatchingEventLast(List<MatchResult> matchs) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		PreparedStatement deleteStatement = null;
		try {
			deleteStatement = getConnection().prepareStatement(DELETE_MATCHING_EVENT_LAST_BY_KEY);
			for (MatchResult match : matchs) {

				deleteStatement.setString(1, match.getTokenid());
				deleteStatement.setString(2, match.getBasetokenid());
				deleteStatement.addBatch();
			}
			assert deleteStatement != null;
			deleteStatement.executeBatch();

			preparedStatement = getConnection().prepareStatement(INSERT_MATCHING_EVENT_LAST_SQL);
			for (MatchResult match : matchs) {
				preparedStatement.setString(1, match.getTxhash());
				preparedStatement.setString(2, match.getTokenid());
				preparedStatement.setString(3, match.getBasetokenid());
				preparedStatement.setLong(4, match.getPrice());
				preparedStatement.setLong(5, match.getExecutedQuantity());
				preparedStatement.setLong(6, match.getInserttime());
				String combined = match.getTokenid() + "\0" + match.getBasetokenid();
				preparedStatement.setObject(7, java.util.UUID.nameUUIDFromBytes(
						combined.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (deleteStatement != null) {
				try {
					deleteStatement.close();
				} catch (SQLException e) {

				}
			}

			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {

				}
			}
		}
	}

	public List<MatchResult> filterMatch(List<MatchResult> matchs) {
		List<MatchResult> re = new ArrayList<>();
		for (MatchResult match : matchs) {
			if (re.stream().noneMatch(element -> element.getBasetokenid().equals(match.getBasetokenid())
					&& element.getTokenid().equals(match.getTokenid()))) {
				re.add(match);
			}
		}

		return re;
	}

	@Override
	public List<MatchLastdayResult> getLastMatchingEvents(Set<String> tokenIds, String basetoken)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			String sql = "SELECT  ml.txhash txhash,ml.tokenid tokenid ,ml.basetokenid basetokenid,  ml.price price, ml.executedQuantity executedQuantity,ml.inserttime inserttime, "
					+ "mld.price lastdayprice,mld.executedQuantity lastdayQuantity "
					+ "FROM matchinglast ml LEFT JOIN matchinglastday mld ON ml.tokenid=mld.tokenid AND  ml.basetokenid=mld.basetokenid";
			sql += " where ml.basetokenid=?";
			if (tokenIds != null && !tokenIds.isEmpty()) {
				sql += "  and ml.tokenid IN ( " + buildINList(tokenIds) + " )";
			}
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, basetoken);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<MatchLastdayResult> list = new ArrayList<>();
			while (resultSet.next()) {
				list.add(new MatchLastdayResult(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3),
						resultSet.getLong(4), resultSet.getLong(5), resultSet.getLong(6), resultSet.getLong(7),
						resultSet.getLong(8)));
			}
			return list;
		} catch (SQLException ex) {
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
	public void deleteMatchingEvents(String hash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(DELETE_MATCHING_EVENT_BY_HASH)) {
			preparedStatement.setString(1, Utils.HEX.encode(hash.getBytes()));
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public Token queryDomainnameToken(Sha256Hash domainNameBlockHash) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_BY_DOMAINNAME_SQL)) {
			preparedStatement.setBytes(1, domainNameBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				Token tokens = new Token();
				tokens.setBlockHash(Sha256Hash.wrap(resultSet.getBytes("blockhash")));
				tokens.setTokenid(resultSet.getString("tokenid"));
				return tokens;
			}
			return null;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public Token getTokensByDomainname(String domainname) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_BY_DOMAINNAME_SQL0)) {
			preparedStatement.setString(1, domainname);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				Token tokens = new Token();
				tokens.setBlockHash(Sha256Hash.wrap(resultSet.getBytes("blockhash")));
				tokens.setTokenid(resultSet.getString("tokenid"));
				return tokens;
			}
			return null;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Sha256Hash> blocksNotChainlengthFromHeigth(long cutoffHeight) throws BlockStoreException {
		List<Sha256Hash> storedBlockHashes = new ArrayList<>();

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_BLOCKS_FROM_AND_NOT_CHAINLENGTH_SQL)) {
			preparedStatement.setLong(1, cutoffHeight);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				storedBlockHashes.add(Sha256Hash.wrap(resultSet.getBytes(1)));
			}
			return storedBlockHashes;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<OrderCancel> getOrderCancelByOrderBlockHash(HashSet<String> orderBlockHashs)
			throws BlockStoreException {
		if (orderBlockHashs.isEmpty()) {
			return new ArrayList<>();
		}
		List<OrderCancel> orderCancels = new ArrayList<>();

		PreparedStatement preparedStatement = null;
		try {
			StringBuilder sql = new StringBuilder();
			for (String s : orderBlockHashs) {
				sql.append(",'").append(s).append("'");
			}
			preparedStatement = getConnection()
					.prepareStatement(SELECT_ORDERCANCEL_SQL + " AND orderblockhash IN (" + sql.substring(1) + ")");
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				OrderCancel orderCancel = new OrderCancel();
				orderCancel.setBlockHash(Sha256Hash.wrap(resultSet.getBytes("blockhash")));
				orderCancel.setOrderBlockHash(Sha256Hash.wrap(resultSet.getBytes("orderblockhash")));
				orderCancel.setConfirmed(resultSet.getBoolean("confirmed"));
				orderCancel.setSpent(resultSet.getBoolean("spent"));
				orderCancel.setSpenderBlockHash(Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")));
				orderCancel.setTime(resultSet.getLong("time"));
				orderCancels.add(orderCancel);
			}
			return orderCancels;
		} catch (SQLException ex) {
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
	public void insertContractEventCancel(ContractEventCancel orderCancel) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_OrderCancel_SQL)) {
			// "blockhash, orderblockhash, confirmed, spent, spenderblockhash,time";
			preparedStatement.setBytes(1, orderCancel.getBlockHash().getBytes());
			preparedStatement.setBytes(2, orderCancel.getEventBlockHash().getBytes());
			preparedStatement.setBoolean(3, true);
			preparedStatement.setBoolean(4, orderCancel.isSpent());
			preparedStatement.setBytes(5,
					orderCancel.getSpenderBlockHash() != null ? orderCancel.getSpenderBlockHash().getBytes() : null);
			preparedStatement.setLong(6, orderCancel.getTime());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		}
		//
	}

	@Override
	public void updateOrderCancelSpent(Set<Sha256Hash> cancels, Sha256Hash blockhash, Boolean spent)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(ORDERCANCEL_UPDATE_SPENT_SQL)) {
			for (Sha256Hash o : cancels) {
				preparedStatement.setBoolean(1, spent);
				preparedStatement.setBytes(2, blockhash != null ? blockhash.getBytes() : null);
				preparedStatement.setBytes(3, o.getBytes());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<MatchLastdayResult> getTimeBetweenMatchingEvents(String tokenid, String basetoken, Long startDate,
			Long endDate, int count) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_MATCHING_EVENT + " where  basetokenid = ? and  tokenid = ? ";

			if (startDate != null)
				sql += " AND inserttime >= " + startDate;
			sql += "  ORDER BY inserttime DESC " + "LIMIT   " + count;
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, basetoken);
			preparedStatement.setString(2, tokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<MatchLastdayResult> list = new ArrayList<>();
			while (resultSet.next()) {
				list.add(new MatchLastdayResult(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3),
						resultSet.getLong(4), resultSet.getLong(5), resultSet.getLong(6)));
			}
			return list;
		} catch (SQLException ex) {
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
	public List<MatchLastdayResult> getTimeAVGBetweenMatchingEvents(String tokenid, String basetoken, Long startDate,
			Long endDate, int count) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			String SELECT_AVG = "select tokenid,basetokenid,  avgprice, totalQuantity,matchday "
					+ "from matchingdaily where datediff(curdate(),str_to_date(matchday,'%Y-%m-%d'))<=30";
			String sql = SELECT_AVG + " AND  basetokenid = ? AND  tokenid = ? ";

			sql += "  ORDER BY inserttime DESC " + "LIMIT   " + count;
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, basetoken);
			preparedStatement.setString(2, tokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<MatchLastdayResult> list = new ArrayList<>();
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
			while (resultSet.next()) {
				list.add(
						new MatchLastdayResult("", resultSet.getString(1), resultSet.getString(2), resultSet.getLong(3),
								resultSet.getLong(4), dateFormat.parse(resultSet.getString(5)).getTime() / 1000));
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

	public void insertAccessPermission(String pubKey, String accessToken) throws BlockStoreException {
		String sql = "insert into access_permission (pubKey, accessToken, refreshTime) values (?,?,?)";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, pubKey);
			preparedStatement.setString(2, accessToken);
			preparedStatement.setLong(3, System.currentTimeMillis());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public int getCountAccessPermissionByPubKey(String pubKey, String accessToken) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(
				"select count(1) as count from access_permission where pubKey = ? and accessToken = ?")) {
			preparedStatement.setString(1, pubKey);
			preparedStatement.setString(2, accessToken);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void insertAccessGrant(String address) throws BlockStoreException {
		String sql = "insert into access_grant (address, createTime) VALUES (?,?)" + duplicateInsert();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, address);
			preparedStatement.setLong(2, System.currentTimeMillis());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteAccessGrant(String address) throws BlockStoreException {
		String sql = "delete from access_grant where address = ?";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			preparedStatement.setString(1, address);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public int getCountAccessGrantByAddress(String address) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement("select count(1) as count from access_grant where address = ?")) {
			preparedStatement.setString(1, address);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	public List<Block> findRetryBlocks(long minHeigth) throws BlockStoreException {

		String sql = "SELECT hash,  "
				+ " height, chainlength, chainlengthlastupdate,  inserttime,  blocktype, solid, confirmed , block"
				+ "  FROM   blocks ";
		sql += " where solid >=0 and confirmed=false and height >= " + minHeigth;
		sql += " ORDER BY insertTime desc ";
		List<Block> result = new ArrayList<>();

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Block block = params.getDefaultSerializer().makeZippedBlock(resultSet.getBytes("block"));

				result.add(block);
			}
			return result;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public void insertChainBlockQueue(ChainBlockQueue chainBlockQueue) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(INSERT_CHAINBLOCKQUEUE)) {
			preparedStatement.setBytes(1, chainBlockQueue.getHash());
			preparedStatement.setBytes(2, chainBlockQueue.getBlock());
			preparedStatement.setLong(3, chainBlockQueue.getChainlength());
			preparedStatement.setBoolean(4, chainBlockQueue.isOrphan());
			preparedStatement.setLong(5, chainBlockQueue.getInserttime());
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
			// It is possible we try to add a duplicate Block if we
			// upgraded
			if (!(e.getSQLState().equals(getDuplicateKeyErrorCode())))
				throw new BlockStoreException(e);

		}

	}

	@Override
	public void deleteAllChainBlockQueue() throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(" delete from chainblockqueue ")) {
			preparedStatement.execute();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteChainBlockQueue(List<ChainBlockQueue> chainBlockQueues) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" delete from chainblockqueue  where hash = ?")) {

			for (ChainBlockQueue chainBlockQueue : chainBlockQueues) {
				preparedStatement.setBytes(1, chainBlockQueue.getHash());
				preparedStatement.addBatch();
			}
			preparedStatement.executeBatch();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<ChainBlockQueue> selectChainblockqueue(boolean orphan, int limit) throws BlockStoreException {

		PreparedStatement s = null;
		List<ChainBlockQueue> list = new ArrayList<>();
		try {
			s = getConnection().prepareStatement(
					SELECT_CHAINBLOCKQUEUE + " where orphan =? " + " order by chainlength asc" + " limit " + limit);
			s.setBoolean(1, orphan);
			ResultSet resultSet = s.executeQuery();
			while (resultSet.next()) {
				list.add(setChainBlockQueue(resultSet));
			}
			return list;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (s != null)
				try {
					s.close();
				} catch (SQLException e) {

				}
		}
	}

	private ChainBlockQueue setChainBlockQueue(ResultSet resultSet) throws SQLException {
		return new ChainBlockQueue(resultSet.getBytes("hash"), resultSet.getBytes("block"),
				resultSet.getLong("chainlength"), resultSet.getBoolean("orphan"), resultSet.getLong("inserttime"));
	}

	@Override
	public void insertLockobject(LockObject lockObject) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" insert into lockobject (lockobjectid, locktime) values (?, ?)  ")) {
			preparedStatement.setString(1, lockObject.getLockobjectid());
			preparedStatement.setLong(2, lockObject.getLocktime());
			preparedStatement.execute();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteLockobject(String lockobjectid) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" delete from lockobject  where lockobjectid = ?")) {
			preparedStatement.setString(1, lockobjectid);
			preparedStatement.execute();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void deleteAllLockobject() throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(" delete from lockobject ")) {
			preparedStatement.execute();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void saveAvgPrice(AVGMatchResult matchResult) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(
				" insert into matchingdaily(matchday,tokenid,basetokenid,avgprice,totalQuantity,highprice,lowprice,inserttime) values(?,?,?,?,?,?,?,?) ")) {
			preparedStatement.setString(1, matchResult.getMatchday());
			preparedStatement.setString(2, matchResult.getTokenid());
			preparedStatement.setString(3, matchResult.getBasetokenid());
			preparedStatement.setLong(4, matchResult.getPrice());
			preparedStatement.setLong(5, matchResult.getExecutedQuantity());
			preparedStatement.setLong(6, matchResult.getHignprice());
			preparedStatement.setLong(7, matchResult.getLowprice());
			preparedStatement.setLong(8, new Date().getTime());
			preparedStatement.execute();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void batchAddAvgPrice() throws Exception {
		List<Long> times = selectTimesUntilNow();
		for (Long time : times) {
			Date date = new Date(time * 1000);
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
			String day = dateFormat.format(date);
			if (getCountMatching(day) == 0) {
				DateFormat dateFormat0 = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS");
				Date startDate = dateFormat0.parse(day + "-00:00:00:000");
				Date endDate = dateFormat0.parse(day + "-23:59:59:999");
				List<AVGMatchResult> list = queryTickerByTime(startDate.getTime(), endDate.getTime());
				if (list != null && !list.isEmpty()) {
					List<String> tokenids = new ArrayList<>();
					for (AVGMatchResult matchResult : list) {
						tokenids.add(matchResult.getTokenid() + "-" + matchResult.getBasetokenid());
						saveAvgPrice(matchResult);

					}
					addLastdayPrice(tokenids);
				}
			}
		}

	}

	public void addLastdayPrice(List<String> tokenids) throws Exception {
		if (tokenids != null && !tokenids.isEmpty()) {
			Date yesterdayDate = new Date(System.currentTimeMillis() - 86400000L);
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
			String yesterday = dateFormat.format(yesterdayDate);
			DateFormat dateFormat0 = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS");
			long starttime = dateFormat0.parse(yesterday + "-00:00:00:000").getTime();
			long endtime = dateFormat0.parse(yesterday + "-23:59:59:999").getTime();
			for (String tokenid : tokenids) {
				MatchResult tempMatchResult = queryTickerLast(starttime, endtime, tokenid.split("-")[0],
						tokenid.split("-")[1]);
				if (tempMatchResult != null) {
					deleteLastdayPrice(tempMatchResult);
					saveLastdayPrice(tempMatchResult);
				}

			}
		}

	}

	public void saveLastdayPrice(MatchResult matchResult) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection().prepareStatement(
				" insert into matchinglastday(tokenid,basetokenid,price,executedQuantity ,inserttime) values(?,?,?,?,? ) ")) {

			preparedStatement.setString(1, matchResult.getTokenid());
			preparedStatement.setString(2, matchResult.getBasetokenid());
			preparedStatement.setLong(3, matchResult.getPrice());
			preparedStatement.setLong(4, matchResult.getExecutedQuantity());

			preparedStatement.setLong(5, new Date().getTime());
			preparedStatement.execute();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	public void deleteLastdayPrice(MatchResult matchResult) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement("delete from  matchinglastday where tokenid=? and basetokenid=? ")) {

			preparedStatement.setString(1, matchResult.getTokenid());
			preparedStatement.setString(2, matchResult.getBasetokenid());
			preparedStatement.execute();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	public List<Long> selectTimesUntilNow() throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		Date yesterdayDate = new Date(System.currentTimeMillis() - 86400000L);
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		String yesterday = dateFormat.format(yesterdayDate);
		DateFormat dateFormat0 = new SimpleDateFormat("yyyy-MM-dd-HH:mm:ss:SSS");

		try {
			long time = dateFormat0.parse(yesterday + "-23:59:59:999").getTime();
			preparedStatement = getConnection()
					.prepareStatement(" select inserttime from matching where inserttime<=? order by  inserttime asc");
			preparedStatement.setLong(1, time / 1000);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<Long> times = new ArrayList<>();
			while (resultSet.next()) {
				times.add(resultSet.getLong(1));

			}
			return times;
		} catch (Exception e) {
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

	public int getCountMatching(String matchday) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" select count(1) from matchingdaily where matchday=?  ")) {
			preparedStatement.setString(1, matchday);
			ResultSet resultSet = preparedStatement.executeQuery();
			int count = 0;
			if (resultSet.next()) {
				count = resultSet.getInt(1);
			}
			return count;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<AVGMatchResult> queryTickerByTime(long starttime, long endtime) throws BlockStoreException {
		PreparedStatement preparedStatement = null;

		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

		String matchday = dateFormat.format(starttime);
		try {
			preparedStatement = getConnection().prepareStatement(" select tokenid,basetokenid,sum(price),count(price),"
					+ "max(price),min(price),sum(executedQuantity)"
					+ " from matching where inserttime>=? and inserttime<=? group by tokenid,basetokenid  ");
			preparedStatement.setLong(1, starttime / 1000);
			preparedStatement.setLong(2, endtime / 1000);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<AVGMatchResult> orderTickers = new ArrayList<>();
			while (resultSet.next()) {
				AVGMatchResult matchResult = new AVGMatchResult();
				matchResult.setTokenid(resultSet.getString(1));
				matchResult.setBasetokenid(resultSet.getString(2));
				matchResult.setPrice(resultSet.getLong(3) / resultSet.getLong(4));
				BigDecimal avgprice;
				avgprice = new BigDecimal(resultSet.getLong(3)).divide(new BigDecimal(resultSet.getLong(4)),
						RoundingMode.HALF_DOWN);
				matchResult.setAvgprice(avgprice);
				matchResult.setMatchday(matchday);
				matchResult.setHignprice(resultSet.getLong(5));
				matchResult.setLowprice(resultSet.getLong(6));
				matchResult.setExecutedQuantity(resultSet.getLong(7));
				orderTickers.add(matchResult);

			}
			return orderTickers;
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

	public MatchResult queryTickerLast(long starttime, long endtime, String tokenid, String basetokenid)
			throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" select tokenid,basetokenid,  price,  executedQuantity "
						+ " from matching where inserttime>=? and inserttime<=?   and  tokenid=? and basetokenid=?  ")) {
			preparedStatement.setLong(1, starttime / 1000);
			preparedStatement.setLong(2, endtime / 1000);
			preparedStatement.setString(3, tokenid);
			preparedStatement.setString(4, basetokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			MatchResult matchResult = null;
			if (resultSet.next()) {
				matchResult = new MatchResult();
				matchResult.setTokenid(resultSet.getString(1));
				matchResult.setBasetokenid(resultSet.getString(2));
				matchResult.setPrice(resultSet.getLong(3));
				matchResult.setExecutedQuantity(resultSet.getLong(4));
			}
			return matchResult;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public LockObject selectLockobject(String lockobjectid) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(" select lockobjectid, locktime from lockobject  where lockobjectid = ?")) {
			preparedStatement.setString(1, lockobjectid);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return new LockObject(lockobjectid, resultSet.getLong("locktime"));
			}
			return null;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}

	}

	@Override
	public List<Coin> getAccountBalance(String address, String tokenid) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		List<Coin> list = new ArrayList<>();
		try {
			String sql = " select address, tokenid,coinvalue from accountBalance  where 1=1 ";

			if (address != null && !address.trim().isEmpty()) {
				sql += " and address = ?";
			}
			if (tokenid != null && !tokenid.trim().isEmpty()) {
				sql += " and tokenid = ?";
			}
			preparedStatement = getConnection().prepareStatement(sql);
			int i = 1;

			if (address != null && !address.trim().isEmpty()) {
				preparedStatement.setString(i++, address);
			}
			if (tokenid != null && !tokenid.trim().isEmpty()) {
				preparedStatement.setString(i, tokenid);
			}

			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Coin coin = new Coin(new BigInteger(resultSet.getBytes("coinvalue")), resultSet.getString("tokenid"));
				list.add(coin);
			}
			return list;
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

	public Map<String, Map<String, Coin>> queryOutputsMap(String address, String tokenid) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		Map<String, Map<String, Coin>> map = new HashMap<>();
		try {
			String sql = " select toaddress, tokenid,coinvalue,fromaddress from outputs  where spent = false";
			if (address != null && !address.trim().isEmpty()) {
				sql += " and toaddress = ?";
			}
			if (tokenid != null && !tokenid.trim().isEmpty()) {
				sql += " and tokenid = ?";
			}
			preparedStatement = getConnection().prepareStatement(sql);
			int i = 1;

			if (address != null && !address.trim().isEmpty()) {
				preparedStatement.setString(i++, address);
			}
			if (tokenid != null && !tokenid.trim().isEmpty()) {
				preparedStatement.setString(i, tokenid);
			}
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {

				String tempTokenid = resultSet.getString("tokenid");
				String tempAddress = resultSet.getString("toaddress");
				if (!map.containsKey(tempAddress)) {
					Map<String, Coin> tokenMap = new HashMap<>();
					map.put(tempAddress, tokenMap);
				}
				Map<String, Coin> tempMap = map.get(tempAddress);
				Coin amount = new Coin(new BigInteger(resultSet.getBytes("coinvalue")), tempTokenid);
				if (tempMap.containsKey(tempTokenid)) {
					amount = amount.add(tempMap.get(tempTokenid));
				}
				tempMap.put(tempTokenid, amount);
				map.put(tempAddress, tempMap);
			}
			return map;
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

	public void addAccountCoinBatch(Map<String, Map<String, Coin>> toAddressMap) throws BlockStoreException {

		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(
					"insert into accountBalance (address, tokenid,coinvalue,lastblockhash,address_tokenid_md5) values(?,?,?,?,?)");

			for (String toaddress : toAddressMap.keySet()) {
				for (String tokenid : toAddressMap.get(toaddress).keySet()) {
					s.setString(1, toaddress);
					s.setString(2, tokenid);
					s.setBytes(3, toAddressMap.get(toaddress).get(tokenid).getValue().toByteArray());
					s.setBytes(4, Sha256Hash.ZERO_HASH.getBytes());
					s.setObject(5, java.util.UUID.nameUUIDFromBytes(
							(toaddress + "\0" + tokenid).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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

	public void deleteAccountCoin(String address, String tokenid) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			String sql = " delete  from accountBalance  where 1=1 ";
			if (address != null && !address.trim().isEmpty()) {
				sql += " and  address = ?";
			}
			if (tokenid != null && !tokenid.trim().isEmpty()) {
				sql += " and tokenid = ?";
			}
			preparedStatement = getConnection().prepareStatement(sql);
			int i = 1;

			if (address != null && !address.trim().isEmpty()) {
				preparedStatement.setString(i++, address);
			}
			if (tokenid != null && !tokenid.trim().isEmpty()) {
				preparedStatement.setString(i, tokenid);
			}

			preparedStatement.executeUpdate();
			preparedStatement.close();
		} catch (SQLException e) {
			if (!(getDuplicateKeyErrorCode().equals(e.getSQLState())))
				throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					if (preparedStatement.getConnection() != null)
						preparedStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException(e);
				}
			}
		}
	}

	/*
	 * UTXO 100 output record -> pay 30 -> update 100, address1 spent, add new 30
	 * address2 and 70 address1 from =address1 List
	 */
	@Override
	public void calculateAccount(String address, String tokenid) throws BlockStoreException {
		// collect all different address and tokenid as list and calculate the account

		deleteAccountCoin(address, tokenid);
		Map<String, Map<String, Coin>> toAddressMap = queryOutputsMap(address, tokenid);
		addAccountCoinBatch(toAddressMap);
	}

	@Override
	public Contractresult getMaxRewardChainlengthContractresult(String contracttokenid) throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_CONTRACTRESULT_MAX_CHAINLENGTH_SQL)) {
			preparedStatement.setString(1, contracttokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				return setContractresult(resultSet);
			}
			return Contractresult.firstContractresult();

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public Contractresult getMaxConfirmedContractresult(String contracttokenid) throws BlockStoreException {
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_CONTRACTRESULT_MAX_CONFIRMED_SQL)) {
			preparedStatement.setString(1, contracttokenid);
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				return setContractresult(resultSet);
			}
			return Contractresult.firstContractresult();

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public List<Contractresult> getLowerConfirmedContractresult(String contracttokenid, long chainlenght)
			throws BlockStoreException {
		List<Contractresult> result = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_CONTRACTRESULT_LOWER_CONFIRMED_SQL)) {
			preparedStatement.setString(1, contracttokenid);
			preparedStatement.setLong(2, chainlenght);
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				result.add(setContractresult(resultSet));
			}
			return result;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public Orderresult getMaxRewardChainlengthOrderresult() throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_ORDER_RESULT_MAX_CHAINLENGTH_SQL)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return setOrderresult(resultSet);
			} else
				return Orderresult.zeroOrderresult();

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}

	}

	@Override
	public Orderresult getMaxConfirmedOrderresult() throws BlockStoreException {

		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_ORDERRESULT_MAX_CONFIRMED_SQL)) {
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				return setOrderresult(resultSet);
			}
			return Orderresult.zeroOrderresult();

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
		//
	}

	@Override
	public List<Orderresult> getLowerConfirmedOrderresult(long chainlength) throws BlockStoreException {
		List<Orderresult> result = new ArrayList<>();
		try (PreparedStatement preparedStatement = getConnection()
				.prepareStatement(SELECT_ORDERRESULT_LOWER_CONFIRMED_SQL)) {
			preparedStatement.setLong(1, chainlength);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				result.add(setOrderresult(resultSet));
			}
			return result;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		}
		//
	}

	@Override
	public List<ContractEventRecord> getContractEventRecordOpen(String tokenid) throws BlockStoreException {
		List<ContractEventRecord> result = new ArrayList<>();

		String sql = SELECT_OPEN_CONTRACT_EVENT_SQL + " ORDER BY blockhash, collectinghash ";

		try (PreparedStatement preparedStatement = getConnection().prepareStatement(sql)) {
			// log.debug(sql);
			preparedStatement.setString(1, tokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				ContractEventRecord c = setContractEventRecord(resultSet);
				result.add(c);
			}
			return result;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} catch (ProtocolException e) {
			// Corrupted database.
			throw new BlockStoreException(e);
		} catch (VerificationException e) {
			// Should not be able to happen unless the database contains bad
			// blocks.
			throw new BlockStoreException(e);
		}

	}

	@Override
	public void saveAnchor(AnchorRecord anchor) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(INSERT_ANCHOR_SQL)) {
			s.setString(1, anchor.getChainId());
			s.setString(2, anchor.getL1RewardHeadHash().toString());
			s.setLong(3, anchor.getL1Height());
			s.setString(4, anchor.getConfirmedRoot() != null ? anchor.getConfirmedRoot().toString() : null);
			s.setString(5, anchor.getSignatureHex());
			s.setString(6, anchor.getBlockHash().toString());
			s.setBoolean(7, anchor.isConfirmed());
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public AnchorRecord getAnchorByChainIdAndHeight(String chainId, long l1Height) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_ANCHOR_BY_CHAINID_HEIGHT_SQL)) {
			s.setString(1, chainId);
			s.setLong(2, l1Height);
			try (ResultSet rs = s.executeQuery()) {
				if (rs.next()) {
					return setAnchorRecord(rs);
				}
			}
			return null;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public List<AnchorRecord> getAnchorsByChainId(String chainId, long sinceHeight, int limit)
			throws BlockStoreException {
		List<AnchorRecord> result = new ArrayList<>();
		String sql = SELECT_ANCHORS_BY_CHAINID_SQL + " LIMIT ?";
		try (PreparedStatement s = getConnection().prepareStatement(sql)) {
			s.setString(1, chainId);
			s.setLong(2, sinceHeight);
			s.setInt(3, limit);
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) {
					result.add(setAnchorRecord(rs));
				}
			}
			return result;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public AnchorRecord getLatestAnchorByChainId(String chainId) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_LATEST_ANCHOR_BY_CHAINID_SQL)) {
			s.setString(1, chainId);
			try (ResultSet rs = s.executeQuery()) {
				if (rs.next()) {
					return setAnchorRecord(rs);
				}
			}
			return null;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public AnchorRecord getAnchorByBlockHash(Sha256Hash blockHash) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_ANCHOR_BY_BLOCKHASH_SQL)) {
			s.setString(1, blockHash.toString());
			try (ResultSet rs = s.executeQuery()) {
				if (rs.next()) {
					return setAnchorRecord(rs);
				}
			}
			return null;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void updateAnchorConfirmed(String chainId, long l1Height, boolean confirmed) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(UPDATE_ANCHOR_CONFIRMED_SQL)) {
			s.setBoolean(1, confirmed);
			s.setString(2, chainId);
			s.setLong(3, l1Height);
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void saveVaultUTXO(VaultRecord vault) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(INSERT_VAULT_SQL)) {
			s.setString(1, vault.getChainId());
			s.setString(2, vault.getUtxoBlockHash().toString());
			s.setLong(3, vault.getUtxoIndex());
			s.setLong(4, vault.getAmount());
			s.setString(5, vault.getTokenIdHex());
			s.setString(6, vault.getOwnerAddress());
			s.setBoolean(7, vault.isSpent());
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public List<VaultRecord> getVaultUTXOsByChainId(String chainId, boolean spent) throws BlockStoreException {
		List<VaultRecord> result = new ArrayList<>();
		try (PreparedStatement s = getConnection().prepareStatement(SELECT_VAULT_BY_CHAINID_SQL)) {
			s.setString(1, chainId);
			s.setBoolean(2, spent);
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) {
					result.add(setVaultRecord(rs));
				}
			}
			return result;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void markVaultUTXOSpent(String chainId, Sha256Hash utxoBlockHash, long utxoIndex) throws BlockStoreException {
		try (PreparedStatement s = getConnection().prepareStatement(UPDATE_VAULT_SPENT_SQL)) {
			s.setBoolean(1, true);
			s.setString(2, chainId);
			s.setString(3, utxoBlockHash.toString());
			s.setLong(4, utxoIndex);
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	private VaultRecord setVaultRecord(ResultSet rs) throws SQLException {
		VaultRecord v = new VaultRecord();
		v.setChainId(rs.getString("chainId"));
		String hash = rs.getString("utxoBlockHash");
		if (hash != null) v.setUtxoBlockHash(Sha256Hash.wrap(hash));
		v.setUtxoIndex(rs.getLong("utxoIndex"));
		v.setAmount(rs.getLong("amount"));
		v.setTokenIdHex(rs.getString("tokenIdHex"));
		v.setOwnerAddress(rs.getString("ownerAddress"));
		v.setSpent(rs.getBoolean("spent"));
		return v;
	}

	@Override
	public void saveStakeDeposit(StakeRecord stake) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("INSERT INTO stake_deposits "
					+ "(pubkey, amount, withdrawal_credentials, activated_epoch, slashed, withdrawable_epoch, blockhash, pubkey_md5) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (pubkey_md5) DO NOTHING")) {
			s.setBytes(1, stake.getPubkey());
			s.setLong(2, stake.getAmount().longValue());
			s.setBytes(3, stake.getWithdrawalCredentials());
			s.setLong(4, stake.getActivatedEpoch());
			s.setBoolean(5, stake.isSlashed());
			s.setLong(6, stake.getWithdrawableEpoch());
			s.setBytes(7, stake.getBlockHash() != null ? stake.getBlockHash().getBytes() : null);
			s.setObject(8, java.util.UUID.nameUUIDFromBytes(stake.getPubkey()));
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public StakeRecord getStakeDeposit(byte[] pubkey) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT * FROM stake_deposits WHERE pubkey = ?")) {
			s.setBytes(1, pubkey);
			try (ResultSet rs = s.executeQuery()) {
				if (rs.next()) return setStakeRecord(rs);
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return null;
	}

	@Override
	public List<StakeRecord> getActiveStakeDeposits() throws BlockStoreException {
		List<StakeRecord> list = new ArrayList<>();
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT * FROM stake_deposits WHERE activated_epoch >= 0 "
					+ "AND slashed = FALSE AND (withdrawable_epoch < 0 OR withdrawable_epoch > 0)")) {
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) list.add(setStakeRecord(rs));
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return list;
	}

	@Override
	public void updateStakeActivation(byte[] pubkey, long epoch) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("UPDATE stake_deposits SET activated_epoch = ? WHERE pubkey = ?")) {
			s.setLong(1, epoch);
			s.setBytes(2, pubkey);
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void updateStakeSlashing(byte[] pubkey, long withdrawableEpoch) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("UPDATE stake_deposits SET slashed = TRUE, withdrawable_epoch = ? WHERE pubkey = ?")) {
			s.setLong(1, withdrawableEpoch);
			s.setBytes(2, pubkey);
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public List<StakeRecord> getAllStakeDeposits() throws BlockStoreException {
		List<StakeRecord> list = new ArrayList<>();
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT * FROM stake_deposits")) {
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) list.add(setStakeRecord(rs));
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return list;
	}

	@Override
	public void releaseStakeDeposit(byte[] pubkey) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("UPDATE stake_deposits SET slashed = FALSE, "
					+ "withdrawable_epoch = -1, activated_epoch = -1 WHERE pubkey = ?")) {
			s.setBytes(1, pubkey);
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public void saveAttestationVote(Sha256Hash blockHash, byte[] pubkey, long weight) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("INSERT INTO attestation_votes (blockhash, pubkey, weight, slot, pubkey_blockhash_md5) "
					+ "VALUES (?, ?, ?, ?, ?) ON CONFLICT (pubkey_blockhash_md5) DO UPDATE SET weight = ?")) {
			s.setBytes(1, blockHash.getBytes());
			s.setBytes(2, pubkey);
			s.setLong(3, weight);
			long slot = System.currentTimeMillis() / 12_000L;
			s.setLong(4, slot);
			byte[] combined = new byte[pubkey.length + 32];
			System.arraycopy(pubkey, 0, combined, 0, pubkey.length);
			System.arraycopy(blockHash.getBytes(), 0, combined, pubkey.length, 32);
			s.setObject(5, java.util.UUID.nameUUIDFromBytes(combined));
			s.setLong(6, weight);
			s.executeUpdate();
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public Map<Sha256Hash, Long> getSummedAttestationVotes() throws BlockStoreException {
		Map<Sha256Hash, Long> result = new HashMap<>();
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT blockhash, SUM(weight) as total FROM attestation_votes GROUP BY blockhash")) {
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) {
					result.put(Sha256Hash.wrap(rs.getBytes("blockhash")), rs.getLong("total"));
				}
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return result;
	}

	@Override
	public List<AttestationData> getAttestationsForSlot(long slot) throws BlockStoreException {
		List<AttestationData> list = new ArrayList<>();
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT * FROM attestation_votes WHERE slot = ?")) {
			s.setLong(1, slot);
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) {
					AttestationData att = new AttestationData();
					att.setBeaconBlockHash(Sha256Hash.wrap(rs.getBytes("blockhash")));
					att.setValidatorPubkey(rs.getBytes("pubkey"));
					att.setSlot(slot);
					list.add(att);
				}
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return list;
	}

	private StakeRecord setStakeRecord(ResultSet rs) throws SQLException {
		StakeRecord r = new StakeRecord();
		r.setPubkey(rs.getBytes("pubkey"));
		r.setAmount(BigInteger.valueOf(rs.getLong("amount")));
		r.setWithdrawalCredentials(rs.getBytes("withdrawal_credentials"));
		r.setActivatedEpoch(rs.getLong("activated_epoch"));
		r.setSlashed(rs.getBoolean("slashed"));
		r.setWithdrawableEpoch(rs.getLong("withdrawable_epoch"));
		String hash = rs.getString("blockhash");
		if (hash != null) r.setBlockHash(Sha256Hash.wrap(hash));
		return r;
	}

	private AnchorRecord setAnchorRecord(ResultSet rs) throws SQLException {
		AnchorRecord anchor = new AnchorRecord();
		anchor.setChainId(rs.getString("chainId"));
		String l1RewardHeadHash = rs.getString("l1RewardHeadHash");
		if (l1RewardHeadHash != null) {
			anchor.setL1RewardHeadHash(Sha256Hash.wrap(l1RewardHeadHash));
		}
		anchor.setL1Height(rs.getLong("l1Height"));
		String confirmedRoot = rs.getString("confirmedRoot");
		if (confirmedRoot != null) {
			anchor.setConfirmedRoot(Sha256Hash.wrap(confirmedRoot));
		}
		anchor.setSignatureHex(rs.getString("signatureHex"));
		String blockHash = rs.getString("blockHash");
		if (blockHash != null) {
			anchor.setBlockHash(Sha256Hash.wrap(blockHash));
		}
		anchor.setConfirmed(rs.getBoolean("confirmed"));
		return anchor;
	}

	@Override
	public void savePosState(String service, String key, byte[] value) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("INSERT INTO pos_state (service, key, value, service_key_md5) VALUES (?, ?, ?, ?) "
					+ "ON CONFLICT (service_key_md5) DO UPDATE SET value = EXCLUDED.value")) {
			s.setString(1, service);
			s.setString(2, key);
			s.setBytes(3, value);
			s.setObject(4, java.util.UUID.nameUUIDFromBytes((service + "\0" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
			s.executeUpdate();
		} catch (Exception e) {
			throw new BlockStoreException(e);
		}
	}

	@Override
	public byte[] getPosState(String service, String key) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT value FROM pos_state WHERE service = ? AND key = ?")) {
			s.setString(1, service);
			s.setString(2, key);
			try (ResultSet rs = s.executeQuery()) {
				if (rs.next()) return rs.getBytes("value");
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return null;
	}

	@Override
	public Map<String, byte[]> getPosStateByService(String service) throws BlockStoreException {
		Map<String, byte[]> result = new HashMap<>();
		try (PreparedStatement s = getConnection()
				.prepareStatement("SELECT key, value FROM pos_state WHERE service = ?")) {
			s.setString(1, service);
			try (ResultSet rs = s.executeQuery()) {
				while (rs.next()) {
					result.put(rs.getString("key"), rs.getBytes("value"));
				}
			}
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
		return result;
	}

	@Override
	public void deletePosState(String service, String key) throws BlockStoreException {
		try (PreparedStatement s = getConnection()
				.prepareStatement("DELETE FROM pos_state WHERE service = ? AND key = ?")) {
			s.setString(1, service);
			s.setString(2, key);
			s.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		}
	}

}
