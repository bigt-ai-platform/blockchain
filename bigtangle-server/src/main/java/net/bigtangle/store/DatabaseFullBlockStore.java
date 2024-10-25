/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.store;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
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

import javax.annotation.Nullable;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ContractEventCancel;
import net.bigtangle.core.Contractresult;
import net.bigtangle.core.MultiSign;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderCancel;
import net.bigtangle.core.OrderRecord;
import net.bigtangle.core.Orderresult;
import net.bigtangle.core.OutputsMulti;
import net.bigtangle.core.PayMultiSign;
import net.bigtangle.core.PayMultiSignAddress;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TXReward;
import net.bigtangle.core.Token;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UserData;
import net.bigtangle.core.Utils;
import net.bigtangle.core.exception.BlockStoreException;
import net.bigtangle.core.exception.ProtocolException;
import net.bigtangle.core.exception.VerificationException;
import net.bigtangle.core.ordermatch.AVGMatchResult;
import net.bigtangle.core.ordermatch.MatchLastdayResult;
import net.bigtangle.core.ordermatch.MatchResult;
import net.bigtangle.script.Script;
import net.bigtangle.server.data.BatchBlock;
import net.bigtangle.server.data.ChainBlockQueue;
import net.bigtangle.server.data.ContractEventRecord;
import net.bigtangle.server.data.ContractExecutionResult;
import net.bigtangle.server.data.LockObject;
import net.bigtangle.server.data.OrderExecutionResult;
import net.bigtangle.utils.Gzip;

/**
 * <p>
 * A generic full block store for a relational database. This generic class
 * requires certain table structures for the block store.
 * </p>
 * 
 */
public abstract class DatabaseFullBlockStore extends DatabaseFullBlockStoreBase implements FullBlockStore {

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

		PreparedStatement preparedStatement = null;
		List<MultiSignAddress> list = new ArrayList<MultiSignAddress>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_MULTISIGNADDRESS_SQL);
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
	public void insertMultiSignAddress(MultiSignAddress multiSignAddress) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_MULTISIGNADDRESS_SQL);
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
	public void deleteMultiSignAddress(String tokenid, String address) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(DELETE_MULTISIGNADDRESS_SQL);
			preparedStatement.setString(1, tokenid);
			preparedStatement.setString(2, address);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public int getCountMultiSignAddress(String tokenid) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(COUNT_MULTISIGNADDRESS_SQL);
			preparedStatement.setString(1, tokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public Token getCalMaxTokenIndex(String tokenid) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(COUNT_TOKENSINDEX_SQL);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public Token getTokenByBlockHash(Sha256Hash blockhash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {

			preparedStatement = getConnection().prepareStatement(SELECT_TOKEN_SQL);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Token> getTokenID(Set<String> tokenids) throws BlockStoreException {
		List<Token> list = new ArrayList<Token>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					SELECT_TOKENS_SQL_TEMPLATE + " FROM tokens WHERE tokenid IN ( " + buildINList(tokenids) + " ) ");

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
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	@Override
	public List<Token> getTokenID(String tokenid) throws BlockStoreException {
		List<Token> list = new ArrayList<Token>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKENID_SQL);
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

		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	@Override
	public int getCountMultiSignByTokenIndexAndAddress(String tokenid, long tokenindex, String address)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_MULTISIGNBY_SQL);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<MultiSign> getMultiSignListByAddress(String address) throws BlockStoreException {
		List<MultiSign> list = new ArrayList<MultiSign>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_MULTISIGN_ADDRESS_SQL);
			preparedStatement.setString(1, address);
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
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	private void setMultisign(List<MultiSign> list, ResultSet resultSet) throws SQLException {
		String id = resultSet.getString("id");
		String tokenid = resultSet.getString("tokenid");
		Long tokenindex = resultSet.getLong("tokenindex");
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
		List<MultiSign> list = new ArrayList<MultiSign>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_MULTISIGN_TOKENID_ADDRESS_SQL);
			preparedStatement.setString(1, tokenid);
			preparedStatement.setString(2, address);

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
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<MultiSign> getMultiSignListByTokenid(String tokenid, int tokenindex, Set<String> addresses,
			boolean isSign) throws BlockStoreException {
		List<MultiSign> list = new ArrayList<MultiSign>();

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
			log.info("sql : " + sql);
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
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public int getCountMultiSignAlready(String tokenid, long tokenindex, String address) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_COUNT_MULTISIGN_SQL);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	public int countMultiSign(String tokenid, long tokenindex, int sign) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_COUNT_ALL_MULTISIGN_SQL);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void saveMultiSign(MultiSign multiSign) throws BlockStoreException {

		if (multiSign.getTokenid() == null || "".equals(multiSign.getTokenid())) {
			return;
		}

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_MULTISIGN_SQL);
			preparedStatement.setString(1, multiSign.getTokenid());
			preparedStatement.setLong(2, multiSign.getTokenindex());
			preparedStatement.setString(3, multiSign.getAddress());
			preparedStatement.setBytes(4, multiSign.getBlockbytes());
			preparedStatement.setInt(5, multiSign.getSign());
			preparedStatement.setString(6, multiSign.getId());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateMultiSign(String tokenid, long tokenIndex, String address, byte[] blockhash, int sign)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_MULTISIGN_SQL);
			preparedStatement.setBytes(1, blockhash);
			preparedStatement.setInt(2, sign);
			preparedStatement.setString(3, tokenid);
			preparedStatement.setLong(4, tokenIndex);
			preparedStatement.setString(5, address);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<MultiSign> getMultiSignListByTokenid(String tokenid, long tokenindex) throws BlockStoreException {
		List<MultiSign> list = new ArrayList<MultiSign>();

		PreparedStatement preparedStatement = null;
		String sql = SELECT_MULTISIGN_ADDRESS_ALL_SQL + " AND tokenid=? AND tokenindex = ?";
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, tokenid.trim());
			preparedStatement.setLong(2, tokenindex);
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
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void deleteMultiSign(String tokenid) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(DELETE_MULTISIGN_SQL);
			preparedStatement.setString(1, tokenid);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getRewardSpent(Sha256Hash hash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_SPENT_SQL);
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public Sha256Hash getRewardSpender(Sha256Hash hash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_SPENDER_SQL);
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return resultSet.getBytes(1) == null ? null : Sha256Hash.wrap(resultSet.getBytes(1));
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public Sha256Hash getRewardPrevBlockHash(Sha256Hash blockHash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_PREVBLOCKHASH_SQL);
			preparedStatement.setBytes(1, blockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return Sha256Hash.wrap(resultSet.getBytes(1));
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
	public long getRewardDifficulty(Sha256Hash blockHash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_DIFFICULTY_SQL);
			preparedStatement.setBytes(1, blockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getLong(1);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public long getRewardChainLength(Sha256Hash blockHash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_CHAINLENGTH_SQL);
			preparedStatement.setBytes(1, blockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getLong(1);
			} else {
				return -1;
			}

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getRewardConfirmed(Sha256Hash hash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_CONFIRMED_SQL);
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertReward(Sha256Hash hash, Sha256Hash prevBlockHash, long difficulty, long chainLength)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_TX_REWARD_SQL);
			preparedStatement.setBytes(1, hash.getBytes());
			preparedStatement.setBoolean(2, false);
			preparedStatement.setBoolean(3, false);
			preparedStatement.setBytes(4, null);
			preparedStatement.setBytes(5, prevBlockHash.getBytes());
			preparedStatement.setLong(6, difficulty);
			preparedStatement.setLong(7, chainLength);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(getDuplicateKeyErrorCode().equals(e.getSQLState())))
				throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateRewardConfirmed(Sha256Hash hash, boolean b) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_TX_REWARD_CONFIRMED_SQL);
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, hash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateRewardSpent(Sha256Hash hash, boolean b, @Nullable Sha256Hash spenderBlockHash)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_TX_REWARD_SPENT_SQL);
			preparedStatement.setBoolean(1, b);
			preparedStatement.setBytes(2, spenderBlockHash == null ? null : spenderBlockHash.getBytes());
			preparedStatement.setBytes(3, hash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public TXReward getRewardConfirmedAtHeight(long chainlength) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_CONFIRMED_AT_HEIGHT_REWARD_SQL);
			preparedStatement.setLong(1, chainlength);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return setReward(resultSet);
			} else
				return null;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public TXReward getMaxConfirmedReward() throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_MAX_CONFIRMED_REWARD_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {

				return setReward(resultSet);
			} else
				return null;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<TXReward> getAllConfirmedReward() throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TX_REWARD_ALL_CONFIRMED_REWARD_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<TXReward> list = new ArrayList<TXReward>();
			while (resultSet.next()) {
				list.add(setReward(resultSet));
			}

			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	private TXReward setReward(ResultSet resultSet) throws SQLException {
		return new TXReward(Sha256Hash.wrap(resultSet.getBytes("blockhash")), resultSet.getBoolean("confirmed"),
				resultSet.getBoolean("spent"), Sha256Hash.wrap(resultSet.getBytes("prevblockhash")),
				Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")), resultSet.getLong("difficulty"),
				resultSet.getLong("chainlength"));
	}

	@Override
	public List<Sha256Hash> getRewardBlocksWithPrevHash(Sha256Hash hash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_REWARD_WHERE_PREV_HASH_SQL);
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			List<Sha256Hash> list = new ArrayList<Sha256Hash>();
			while (resultSet.next()) {
				list.add(Sha256Hash.wrap(resultSet.getBytes(1)));
			}
			return list;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateMultiSignBlockBitcoinSerialize(String tokenid, long tokenindex, byte[] bytes)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_MULTISIGN1_SQL);
			preparedStatement.setBytes(1, bytes);
			preparedStatement.setString(2, tokenid);
			preparedStatement.setLong(3, tokenindex);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertOutputsMulti(OutputsMulti outputsMulti) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_OUTPUTSMULTI_SQL);
			preparedStatement.setBytes(1, outputsMulti.getHash().getBytes());
			preparedStatement.setString(2, outputsMulti.getToAddress());
			preparedStatement.setLong(3, outputsMulti.getOutputIndex());

			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			if (!(getDuplicateKeyErrorCode().equals(e.getSQLState())))
				throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	public List<OutputsMulti> queryOutputsMultiByHashAndIndex(byte[] hash, long index) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		List<OutputsMulti> list = new ArrayList<OutputsMulti>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_OUTPUTSMULTI_SQL);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	@Override
	public UserData queryUserDataWithPubKeyAndDataclassname(String dataclassname, String pubKey)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_USERDATA_SQL);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertUserData(UserData userData) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_USERDATA_SQL);
			preparedStatement.setBytes(1, userData.getBlockhash().getBytes());
			preparedStatement.setString(2, userData.getDataclassname());
			preparedStatement.setBytes(3, userData.getData());
			preparedStatement.setString(4, userData.getPubKey());
			preparedStatement.setLong(5, userData.getBlocktype());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<UserData> getUserDataListWithBlocktypePubKeyList(int blocktype, List<String> pubKeyList)
			throws BlockStoreException {
		if (pubKeyList.isEmpty()) {
			return new ArrayList<UserData>();
		}

		PreparedStatement preparedStatement = null;
		try {
			String sql = "select blockhash, dataclassname, data, pubKey, blocktype from userdata where blocktype = ? and pubKey in ";
			StringBuffer stringBuffer = new StringBuffer();
			for (String str : pubKeyList)
				stringBuffer.append(",'").append(str).append("'");
			sql += "(" + stringBuffer.substring(1) + ")";

			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setLong(1, blocktype);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<UserData> list = new ArrayList<UserData>();
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
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateUserData(UserData userData) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_USERDATA_SQL);
			preparedStatement.setBytes(1, userData.getBlockhash().getBytes());
			preparedStatement.setBytes(2, userData.getData());
			preparedStatement.setString(3, userData.getDataclassname());
			preparedStatement.setString(4, userData.getPubKey());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertPayPayMultiSign(PayMultiSign payMultiSign) throws BlockStoreException {
		String sql = "insert into paymultisign (orderid, tokenid, toaddress, blockhash, amount, minsignnumber,"
				+ " outputHashHex,  outputindex) values (?, ?, ?, ?, ?, ?, ?,?)";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertPayMultiSignAddress(PayMultiSignAddress payMultiSignAddress) throws BlockStoreException {
		String sql = "insert into paymultisignaddress (orderid, pubKey, sign, signInputData, signIndex) values (?, ?, ?, ?, ?)";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, payMultiSignAddress.getOrderid());
			preparedStatement.setString(2, payMultiSignAddress.getPubKey());
			preparedStatement.setInt(3, payMultiSignAddress.getSign());
			preparedStatement.setBytes(4, payMultiSignAddress.getSignInputData());
			preparedStatement.setInt(5, payMultiSignAddress.getSignIndex());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updatePayMultiSignAddressSign(String orderid, String pubKey, int sign, byte[] signInputData)
			throws BlockStoreException {
		String sql = "update paymultisignaddress set sign = ?, signInputData = ? where orderid = ? and pubKey = ?";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setInt(1, sign);
			preparedStatement.setBytes(2, signInputData);
			preparedStatement.setString(3, orderid);
			preparedStatement.setString(4, pubKey);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public int getMaxPayMultiSignAddressSignIndex(String orderid) throws BlockStoreException {
		String sql = "SELECT MAX(signIndex) AS signIndex FROM paymultisignaddress WHERE orderid = ?";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, orderid);
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getInt(1);
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public PayMultiSign getPayMultiSignWithOrderid(String orderid) throws BlockStoreException {
		String sql = "select orderid, tokenid, toaddress, blockhash, amount, minsignnumber, outputHashHex, outputindex from paymultisign where orderid = ?";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<PayMultiSignAddress> getPayMultiSignAddressWithOrderid(String orderid) throws BlockStoreException {
		String sql = "select orderid, pubKey, sign, signInputData, signIndex from paymultisignaddress where orderid = ? ORDER BY signIndex ASC";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, orderid.trim());
			ResultSet resultSet = preparedStatement.executeQuery();
			List<PayMultiSignAddress> list = new ArrayList<PayMultiSignAddress>();
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updatePayMultiSignBlockhash(String orderid, byte[] blockhash) throws BlockStoreException {
		String sql = "update paymultisign set blockhash = ? where orderid = ?";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setBytes(1, blockhash);
			preparedStatement.setString(2, orderid);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<PayMultiSign> getPayMultiSignList(List<String> pubKeys) throws BlockStoreException {
		if (pubKeys.isEmpty()) {
			return new ArrayList<PayMultiSign>();
		}
		String sql = "SELECT paymultisign.orderid, tokenid, toaddress, blockhash, amount, minsignnumber, outputHashHex,"
				+ "outputindex, sign,(select count(1) from  paymultisignaddress t where t.orderid=paymultisign.orderid AND sign!=0) as signcount "
				+ " FROM paymultisign LEFT JOIN paymultisignaddress ON paymultisign.orderid = paymultisignaddress.orderid "
				+ " WHERE paymultisignaddress.pubKey ";
		StringBuffer stringBuffer = new StringBuffer();
		for (String pubKey : pubKeys)
			stringBuffer.append(",'").append(pubKey).append("'");
		sql += " in (" + stringBuffer.substring(1) + ")";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<PayMultiSign> list = new ArrayList<PayMultiSign>();
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
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public int getCountPayMultiSignAddressStatus(String orderid) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					"select count(*) as count from paymultisignaddress where orderid = ? and sign = 1");
			preparedStatement.setString(1, orderid);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public UTXO getOutputsWithHexStr(byte[] hash, long outputindex) throws BlockStoreException {
		String sql = "SELECT coinvalue, scriptbytes, coinbase, toaddress,"
				+ " addresstargetable, blockhash, tokenid, fromaddress, memo, minimumsign, time, spent, confirmed, "
				+ " spendpending, spendpendingtime FROM outputs WHERE hash = ? and outputindex = ?";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setBytes(1, hash);
			preparedStatement.setLong(2, outputindex);
			ResultSet results = preparedStatement.executeQuery();
			if (!results.next()) {
				return null;
			}
			// Parse it.
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

			// long outputindex = results.getLong("outputindex");

			UTXO utxo = new UTXO(Sha256Hash.wrap(hash), outputindex, amount, coinbase, new Script(scriptBytes), address,
					blockhash, fromaddress, memo, tokenid, spent, confirmed, spendPending, 0,
					results.getLong("spendpendingtime"), results.getLong("time"), spenderblockhash);
			return utxo;
		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public byte[] getSettingValue(String name) throws BlockStoreException {
		PreparedStatement preparedStatement = null;

		try {
			preparedStatement = getConnection().prepareStatement(getSelectSettingsSQL());
			preparedStatement.setString(1, name);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next()) {
				return null;
			}
			return resultSet.getBytes(1);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertBatchBlock(Block block) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_BATCHBLOCK_SQL);
			preparedStatement.setBytes(1, block.getHash().getBytes());
			preparedStatement.setBytes(2, Gzip.compress(block.bitcoinSerialize()));
			preparedStatement.setTimestamp(3, new java.sql.Timestamp(System.currentTimeMillis()));
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void deleteBatchBlock(Sha256Hash hash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(DELETE_BATCHBLOCK_SQL);
			preparedStatement.setBytes(1, hash.getBytes());
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<BatchBlock> getBatchBlockList() throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_BATCHBLOCK_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<BatchBlock> list = new ArrayList<BatchBlock>();
			while (resultSet.next()) {
				BatchBlock batchBlock = new BatchBlock();
				batchBlock.setHash(Sha256Hash.wrap(resultSet.getBytes("hash")));
				batchBlock.setBlock(Gzip.decompressOut(resultSet.getBytes("block")));
				batchBlock.setInsertTime(resultSet.getDate("inserttime"));
				list.add(batchBlock);
			}
			return list;
		} catch (Exception ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertSubtanglePermission(String pubkey, String userdatapubkey, String status)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_SUBTANGLE_PERMISSION_SQL);
			preparedStatement.setString(1, pubkey);
			preparedStatement.setString(2, userdatapubkey);
			preparedStatement.setString(3, status);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void deleteSubtanglePermission(String pubkey) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(DELETE_SUBTANGLE_PERMISSION_SQL);
			preparedStatement.setString(1, pubkey);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateSubtanglePermission(String pubkey, String userdataPubkey, String status)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPATE_ALL_SUBTANGLE_PERMISSION_SQL);
			preparedStatement.setString(1, status);
			preparedStatement.setString(2, userdataPubkey);
			preparedStatement.setString(3, pubkey);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Map<String, String>> getAllSubtanglePermissionList() throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		List<Map<String, String>> list = new ArrayList<Map<String, String>>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ALL_SUBTANGLE_PERMISSION_SQL);

			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Map<String, String> map = new HashMap<String, String>();
				map.put("pubkey", resultSet.getString("pubkey"));
				map.put("userdataPubkey", resultSet.getString("userdataPubkey"));
				map.put("status", resultSet.getString("status"));
				list.add(map);
			}
			return list;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Map<String, String>> getSubtanglePermissionListByPubkey(String pubkey) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		List<Map<String, String>> list = new ArrayList<Map<String, String>>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ALL_SUBTANGLE_PERMISSION_SQL);
			preparedStatement.setString(1, pubkey);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Map<String, String> map = new HashMap<String, String>();
				map.put("pubkey", resultSet.getString("pubkey"));
				map.put("userdataPubkey", resultSet.getString("userdataPubkey"));
				map.put("status", resultSet.getString("status"));
				list.add(map);
			}
			return list;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Map<String, String>> getSubtanglePermissionListByPubkeys(List<String> pubkeys)
			throws BlockStoreException {
		String sql = SELECT_SUBTANGLE_PERMISSION_BY_PUBKEYS_SQL + " AND pubkey ";
		StringBuffer stringBuffer = new StringBuffer();
		for (String pubKey : pubkeys)
			stringBuffer.append(",'").append(pubKey).append("'");
		sql += " in (" + stringBuffer.substring(1) + ")";

		PreparedStatement preparedStatement = null;
		List<Map<String, String>> list = new ArrayList<Map<String, String>>();
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Map<String, String> map = new HashMap<String, String>();
				map.put("pubkey", resultSet.getString("pubkey"));
				map.put("userdataPubkey", resultSet.getString("userdataPubkey"));
				map.put("status", resultSet.getString("status"));
				list.add(map);
			}
			return list;
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public boolean getOrderConfirmed(Sha256Hash txHash, Sha256Hash issuingMatcherBlockHash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDER_CONFIRMED_SQL);
			preparedStatement.setBytes(1, txHash.getBytes());
			preparedStatement.setBytes(2, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
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
	public Sha256Hash getOrderSpender(Sha256Hash txHash, Sha256Hash issuingMatcherBlockHash)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDER_SPENDER_SQL);
			preparedStatement.setBytes(1, txHash.getBytes());
			preparedStatement.setBytes(2, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBytes(1) == null ? null : Sha256Hash.wrap(resultSet.getBytes(1));
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
	public boolean getOrderSpent(Sha256Hash txHash, Sha256Hash issuingMatcherBlockHash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDER_SPENT_SQL);
			preparedStatement.setBytes(1, txHash.getBytes());
			preparedStatement.setBytes(2, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			resultSet.next();
			return resultSet.getBoolean(1);
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
	public HashMap<Sha256Hash, OrderRecord> getOrderMatchingIssuedOrders(Sha256Hash issuingMatcherBlockHash)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		HashMap<Sha256Hash, OrderRecord> result = new HashMap<>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDERS_BY_ISSUER_SQL);
			preparedStatement.setBytes(1, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				result.put(Sha256Hash.wrap(resultSet.getBytes(1)), setOrder(resultSet));
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
	public HashMap<Sha256Hash, OrderRecord> getOrderMatchingIssuedOrdersNotSpent(Sha256Hash issuingMatcherBlockHash)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		HashMap<Sha256Hash, OrderRecord> result = new HashMap<>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDERS_NotSpent_BY_ISSUER_SQL);
			preparedStatement.setBytes(1, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				result.put(Sha256Hash.wrap(resultSet.getBytes(1)), setOrder(resultSet));
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
	public OrderRecord getOrder(Sha256Hash txHash, Sha256Hash issuingMatcherBlockHash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDER_SQL);
			preparedStatement.setBytes(1, txHash.getBytes());
			preparedStatement.setBytes(2, issuingMatcherBlockHash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (!resultSet.next())
				return null;

			return setOrder(resultSet);
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
	public void insertCancelOrder(OrderCancel orderCancel) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			// "blockhash, orderblockhash, confirmed, spent, spenderblockhash,time";
			preparedStatement = getConnection().prepareStatement(INSERT_OrderCancel_SQL);
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

		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateContractEventCancelSpent(Set<Sha256Hash> cancels, Sha256Hash blockhash, Boolean spent)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(CONTRACTEVENTCANCEL_UPDATE_SPENT_SQL);
			for (Sha256Hash o : cancels) {
				preparedStatement.setBoolean(1, spent);
				preparedStatement.setBytes(2, blockhash != null ? blockhash.getBytes() : null);
				preparedStatement.setBytes(3, o.getBytes());
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
	public void insertOrder(Collection<OrderRecord> records) throws BlockStoreException {
		if (records == null)
			return;

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_ORDER_SQL);
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
	public void updateOrderConfirmed(Sha256Hash initialBlockHash, Sha256Hash issuingMatcherBlockHash, boolean confirmed)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ORDER_CONFIRMED_SQL);
			preparedStatement.setBoolean(1, confirmed);
			preparedStatement.setBytes(2, initialBlockHash.getBytes());
			preparedStatement.setBytes(3, issuingMatcherBlockHash.getBytes());
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
	public void updateOrderConfirmed(Set<Sha256Hash> hashs, Sha256Hash issuingMatcherBlockHash, boolean confirmed)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ORDER_CONFIRMED_SQL);
			for (Sha256Hash o : hashs) {
				preparedStatement.setBoolean(1, confirmed);
				preparedStatement.setBytes(2, o.getBytes());
				preparedStatement.setBytes(3, issuingMatcherBlockHash.getBytes());
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
	public void updateOrderConfirmed(Collection<OrderRecord> orderRecords, boolean confirm) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ORDER_CONFIRMED_SQL);
			for (OrderRecord o : orderRecords) {
				preparedStatement.setBoolean(1, confirm);
				preparedStatement.setBytes(2, o.getBlockHash().getBytes());
				preparedStatement.setBytes(3, o.getIssuingMatcherBlockHash().getBytes());
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
	public void updateOrderSpent(Set<Sha256Hash> orderRecords, Sha256Hash blockhash, Boolean spent)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ORDER_SPENT_SQL);
			for (Sha256Hash o : orderRecords) {
				preparedStatement.setBoolean(1, spent);
				preparedStatement.setBytes(2, blockhash != null ? blockhash.getBytes() : null);
				preparedStatement.setBytes(3, o.getBytes());
				preparedStatement.setBytes(4, Sha256Hash.ZERO_HASH.getBytes());
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
	public void updateOrderSpent(Collection<OrderRecord> orderRecords) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ORDER_SPENT_SQL);
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
	public void insertContractEvent(Collection<ContractEventRecord> records) throws BlockStoreException {
		if (records == null)
			return;

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_CONTRACT_EVENT_SQL);
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
	public void updateContractEventSpent(Set<Sha256Hash> contractEventRecords, Sha256Hash spentBlock, boolean spent)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					getUpdate() + " contractevent SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ? ");
			for (Sha256Hash o : contractEventRecords) {
				preparedStatement.setBoolean(1, spent);
				preparedStatement.setBytes(2, spentBlock != null ? spentBlock.getBytes() : null);
				preparedStatement.setBytes(3, o.getBytes());

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
	public Sha256Hash getContractEventSpent(Sha256Hash contractevent) throws BlockStoreException {
		// one of the block is spent then, return Sha256Hash, otherwise null

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select spenderblockhash  from contractevent " + " WHERE blockhash = ? ");

			preparedStatement.setBytes(1, contractevent.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				byte[] spentbytes = resultSet.getBytes(1);
				if (spentbytes != null)
					return Sha256Hash.wrap(spentbytes);
			}

			return null;
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
	public boolean checkContractEventConfirmed(List<Sha256Hash> contractEventRecords) throws BlockStoreException {
		// one of the block is spent then, return true

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select confirmed  from contractevent " + " WHERE blockhash = ? ");
			for (Sha256Hash o : contractEventRecords) {
				preparedStatement.setBytes(1, o.getBytes());
				ResultSet resultSet = preparedStatement.executeQuery();
				if (resultSet.next()) {
					if (resultSet.getBoolean("confirmed"))
						return true;
				}
			}
			return false;
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
	public void updateContractEventConfirmed(Collection<Sha256Hash> records, boolean confirm)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_CONTRACT_EVENT_CONFIRMED_SQL);
			for (Sha256Hash o : records) {
				preparedStatement.setBoolean(1, confirm);
				preparedStatement.setBytes(2, o.getBytes());
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
	public List<String> getOpenContractid() throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_OPEN_CONTRACTID_CONTRACT_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			List<String> list = new ArrayList<>();
			while (resultSet.next()) {
				list.add(resultSet.getString("contracttokenid"));
			}
			return list;

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
	public Map<Sha256Hash, ContractEventRecord> getContractEventPrev(String contractid, Sha256Hash prevHash)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_PREV_CONTRACT_SQL);
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
	public ContractEventRecord getContractEvent(Sha256Hash blockhash, Sha256Hash collectinghash)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_CONTRACT_SQL);
			preparedStatement.setBytes(1, blockhash.getBytes());
			preparedStatement.setBytes(2, collectinghash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				return setContractEventRecord(resultSet);
			}
			return null;

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
	public void insertContractResult(ContractExecutionResult record) throws BlockStoreException {
		if (record == null)
			return;

		PreparedStatement preparedStatement = null;

		try {
			preparedStatement = getConnection().prepareStatement(INSERT_CONTRACT_RESULT_SQL);
			preparedStatement.setBytes(1, record.getBlockHash().getBytes());
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
		
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
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
	public void updateContractResultSpent(Sha256Hash contractResult, Sha256Hash spentBlock, boolean spent)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					getUpdate() + " contractresult SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ? ");

			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spentBlock != null ? spentBlock.getBytes() : null);
			preparedStatement.setBytes(3, contractResult.getBytes());
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
	public Sha256Hash checkContractResultSpent(Sha256Hash o) throws BlockStoreException {
		// one of the block is spent then, return Sha256Hash, otherwise null

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select spenderblockhash  from contractevent " + " WHERE blockhash = ? ");

			preparedStatement.setBytes(1, o.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				byte[] spentbytes = resultSet.getBytes(1);
				if (spentbytes != null)
					return Sha256Hash.wrap(spentbytes);
			}

			return null;
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
	public boolean checkContractResultConfirmed(Sha256Hash o) throws BlockStoreException {
		// one of the block is spent then, return true

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select confirmed  from contractresult " + " WHERE blockhash = ? ");

			preparedStatement.setBytes(1, o.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				if (resultSet.getBoolean("confirmed"))
					return true;
			}

			return false;
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
	public void updateContractResultConfirmed(Sha256Hash record, boolean confirm) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_CONTRACTRESULT_CONFIRMED_SQL);

			preparedStatement.setBoolean(1, confirm);
			preparedStatement.setBytes(2, record.getBytes());
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
	public Contractresult getContractresult(Sha256Hash blockhash) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_CONTRACTRESULT_HASH_SQL);
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				return setContractresult(resultSet);

			}
			return Contractresult.zeroContractresult();

		} catch (Exception e) {
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
	public Orderresult getOrderResult(Sha256Hash blockhash) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDERRESULT_HASH_SQL);
			preparedStatement.setBytes(1, blockhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				return setOrderresult(resultSet);

			}
			return Orderresult.zeroOrderresult();

		} catch (Exception e) {
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

 


	private Orderresult setOrderresult(ResultSet resultSet) throws SQLException {
		return new Orderresult(Sha256Hash.wrap(resultSet.getBytes("blockhash")), resultSet.getBoolean("confirmed"),
				resultSet.getBoolean("spent"), Sha256Hash.wrap(resultSet.getBytes("prevblockhash")),
				Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")), resultSet.getBytes("orderresult"),
				 resultSet.getLong("milestone"), resultSet.getLong("inserttime"));

	}

	private Contractresult setContractresult(ResultSet resultSet) throws SQLException {
		return new Contractresult(Sha256Hash.wrap(resultSet.getBytes("blockhash")), resultSet.getBoolean("confirmed"),
				resultSet.getBoolean("spent"), Sha256Hash.wrap(resultSet.getBytes("prevblockhash")),
				Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")), resultSet.getBytes("contractresult"),
				  resultSet.getString("contracttokenid"),
				resultSet.getLong("milestone"), resultSet.getLong("inserttime"));

	}

	@Override
	public void insertOrderResult(OrderExecutionResult record) throws BlockStoreException {
		if (record == null)
			return;

		PreparedStatement preparedStatement = null;

		try {
			preparedStatement = getConnection().prepareStatement(INSERT_ORDER_RESULT_SQL);
			preparedStatement.setBytes(1, record.getBlockHash().getBytes());

			preparedStatement.setBoolean(2, record.isConfirmed());
			preparedStatement.setBoolean(3, record.isSpent());
			preparedStatement.setBytes(4,
					record.getSpenderBlockHash() != null ? record.getSpenderBlockHash().getBytes() : null);
			preparedStatement.setBytes(5, record.toByteArray());
			preparedStatement.setBytes(6,
					record.getPrevblockhash() != null ? record.getPrevblockhash().getBytes() : null);
			preparedStatement.setLong(7, record.getTime()); 
			preparedStatement.setLong(8, -1);
			preparedStatement.executeUpdate();

		} catch (SQLException e) {
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
	public void updateOrderResultSpent(Sha256Hash blockhash, Sha256Hash spentBlock, boolean spent)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					getUpdate() + " orderresult SET spent = ?, spenderblockhash = ? " + " WHERE blockhash = ? ");

			preparedStatement.setBoolean(1, spent);
			preparedStatement.setBytes(2, spentBlock != null ? spentBlock.getBytes() : null);
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
	public Sha256Hash checkOrderResultSpent(Sha256Hash o) throws BlockStoreException {
		// one of the block is spent then, return Sha256Hash, otherwise null

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select spenderblockhash  from orderresult " + " WHERE blockhash = ? ");

			preparedStatement.setBytes(1, o.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				byte[] spentbytes = resultSet.getBytes(1);
				if (spentbytes != null)
					return Sha256Hash.wrap(spentbytes);
			}

			return null;
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
	public boolean checkOrderResultConfirmed(Sha256Hash o) throws BlockStoreException {
		// one of the block is spent then, return true

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select confirmed  from orderresult " + " WHERE blockhash = ? ");

			preparedStatement.setBytes(1, o.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				if (resultSet.getBoolean("confirmed"))
					return true;
			}

			return false;
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
	public void updateOrderResultConfirmed(Sha256Hash record, boolean confirm) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(UPDATE_ORDERRESULT_CONFIRMED_SQL);

			preparedStatement.setBoolean(1, confirm);
			preparedStatement.setBytes(2, record.getBytes());
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

	/*
	 * all spent order and older than a month will be deleted from order table.
	 */
	@Override
	public void prunedClosedOrders(Long beforetime) throws BlockStoreException {

		PreparedStatement deleteStatement = null;

		try {

			deleteStatement = getConnection()
					.prepareStatement(" delete FROM orders WHERE  spent=1 AND validToTime < ? limit 1000 ");
			deleteStatement.setLong(1, beforetime - 100 * NetworkParameters.ORDER_TIMEOUT_MAX);
			deleteStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {

			if (deleteStatement != null) {
				try {
					deleteStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	/*
	 * remove the blocks, only if : 1) there is no unspent transaction related to
	 * the block 2) this block is outside the cutoff height, reorg is possible 3)
	 * the spenderblock is outside the cutoff height, reorg is possible
	 */
	@Override
	public void prunedBlocks(Long height, Long chain) throws BlockStoreException {

		PreparedStatement deleteStatement = null;
		PreparedStatement preparedStatement = null;
		try {

			deleteStatement = getConnection().prepareStatement(" delete FROM blocks WHERE" + "   hash  = ? ");

			preparedStatement = getConnection()
					.prepareStatement("  select distinct( blocks.hash) from  blocks  , outputs "
							+ " where spenderblockhash = blocks.hash    "
							+ "  and blocks.milestone < ? and blocks.milestone > 0  " + " and ( blocks.blocktype = "
							+ Block.Type.BLOCKTYPE_TRANSFER.ordinal() + " or blocks.blocktype = "
							+ Block.Type.BLOCKTYPE_ORDER_OPEN.ordinal() + " or blocks.blocktype = "
							+ Block.Type.BLOCKTYPE_REWARD.ordinal() + "  ) limit 1000 ");
			// preparedStatement.setLong(1, height);
			preparedStatement.setLong(1, chain);

			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				deleteStatement.setBytes(1, resultSet.getBytes(1));
				deleteStatement.addBatch();
				;
			}

			// log.debug(deleteStatement.toString());
			int[] r = deleteStatement.executeBatch();
			log.debug(" deleteStatement.executeBatch() count = " + r.length);
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {

			if (deleteStatement != null) {
				try {
					deleteStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	/*
	 * all spent UTXO History and older than the maxRewardblock can be pruned.
	 */
	@Override
	public void prunedHistoryUTXO(Long maxRewardblock) throws BlockStoreException {

		PreparedStatement deleteStatement = null;
		try {
			deleteStatement = getConnection().prepareStatement(" delete FROM outputs WHERE  spent=1 AND "
					+ "spenderblockhash in (select hash from blocks where milestone < ? and milestone > 0 ) limit 1000 ");
			deleteStatement.setLong(1, maxRewardblock);
			deleteStatement.executeUpdate();
		} catch (SQLException e) {
			throw new BlockStoreException(e);
		} finally {

			if (deleteStatement != null) {
				try {
					deleteStatement.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
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
					// throw new BlockStoreException("Could not close statement");
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
		PreparedStatement s = null;
		try {
			// log.debug(sql);
			s = getConnection().prepareStatement(sql);
			int i = 1;

			if (tokenid != null && !tokenid.trim().isEmpty()) {
				s.setString(i++, tokenid);
				s.setString(i++, tokenid);
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

		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(SELECT_AVAILABLE_UTXOS_SORTED_SQL);
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
	public void insertMyserverblocks(Sha256Hash prevhash, Sha256Hash hash, Long inserttime) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" insert into myserverblocks (prevhash, hash, inserttime) values (?,?,?) ");
			preparedStatement.setBytes(1, prevhash.getBytes());
			preparedStatement.setBytes(2, hash.getBytes());
			preparedStatement.setLong(3, inserttime);
			preparedStatement.executeUpdate();
		} catch (SQLException e) {
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
			if (p2 != null) {
				try {
					p2.close();
				} catch (SQLException e) {
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	@Override
	public boolean existBlock(Sha256Hash hash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(" select hash from blocks where hash = ?");
			preparedStatement.setBytes(1, hash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			return resultSet.next();
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
	public boolean existMyserverblocks(Sha256Hash prevhash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(" select hash from myserverblocks where prevhash = ?");
			preparedStatement.setBytes(1, prevhash.getBytes());
			ResultSet resultSet = preparedStatement.executeQuery();
			return resultSet.next();
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
	public void insertMatchingEvent(List<MatchResult> matchs) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		// log.debug("insertMatchingEvent: " + matchs.size());
		try {

			preparedStatement = getConnection().prepareStatement(INSERT_MATCHING_EVENT_SQL);
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

	public void insertMatchingEventLast(List<MatchResult> matchs) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		PreparedStatement deleteStatement = null;
		try {
			for (MatchResult match : matchs) {
				deleteStatement = getConnection().prepareStatement(DELETE_MATCHING_EVENT_LAST_BY_KEY);
				deleteStatement.setString(1, match.getTokenid());
				deleteStatement.setString(2, match.getBasetokenid());
				deleteStatement.addBatch();
			}
			deleteStatement.executeBatch();

			for (MatchResult match : matchs) {
				preparedStatement = getConnection().prepareStatement(INSERT_MATCHING_EVENT_LAST_SQL);
				preparedStatement.setString(1, match.getTxhash());
				preparedStatement.setString(2, match.getTokenid());
				preparedStatement.setString(3, match.getBasetokenid());
				preparedStatement.setLong(4, match.getPrice());
				preparedStatement.setLong(5, match.getExecutedQuantity());
				preparedStatement.setLong(6, match.getInserttime());
				// log.debug(match.toString());
				// log.debug(preparedStatement.toString());
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

	public List<MatchResult> filterMatch(List<MatchResult> matchs) throws BlockStoreException {
		List<MatchResult> re = new ArrayList<MatchResult>();
		for (MatchResult match : matchs) {
			if (!re.stream().anyMatch(element -> element.getBasetokenid().equals(match.getBasetokenid())
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void deleteMatchingEvents(String hash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(DELETE_MATCHING_EVENT_BY_HASH);
			preparedStatement.setString(1, Utils.HEX.encode(hash.getBytes()));
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
	public Token queryDomainnameToken(Sha256Hash domainNameBlockHash) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_BY_DOMAINNAME_SQL);
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
	public Token getTokensByDomainname(String domainname) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_TOKENS_BY_DOMAINNAME_SQL0);
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
	public List<Sha256Hash> getWhereConfirmedNotMilestone() throws BlockStoreException {
		List<Sha256Hash> storedBlockHashes = new ArrayList<Sha256Hash>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_BLOCKS_CONFIRMED_AND_NOT_MILESTONE_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				storedBlockHashes.add(Sha256Hash.wrap(resultSet.getBytes(1)));
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
	public List<OrderCancel> getOrderCancelByOrderBlockHash(HashSet<String> orderBlockHashs)
			throws BlockStoreException {
		if (orderBlockHashs.isEmpty()) {
			return new ArrayList<OrderCancel>();
		}
		List<OrderCancel> orderCancels = new ArrayList<OrderCancel>();

		PreparedStatement preparedStatement = null;
		try {
			StringBuffer sql = new StringBuffer();
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<OrderCancel> getOrderCancelConfirmed() throws BlockStoreException {

		List<OrderCancel> orderCancels = new ArrayList<OrderCancel>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(ORDERCANCEL_CONFIRMED_SQL);
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void insertContractEventCancel(ContractEventCancel orderCancel) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			// "blockhash, orderblockhash, confirmed, spent, spenderblockhash,time";
			preparedStatement = getConnection().prepareStatement(INSERT_OrderCancel_SQL);
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

		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public void updateOrderCancelSpent(Set<Sha256Hash> cancels, Sha256Hash blockhash, Boolean spent)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(ORDERCANCEL_UPDATE_SPENT_SQL);
			for (Sha256Hash o : cancels) {
				preparedStatement.setBoolean(1, spent);
				preparedStatement.setBytes(2, blockhash != null ? blockhash.getBytes() : null);
				preparedStatement.setBytes(3, o.getBytes());
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
	public List<ContractEventCancel> getContractEventCancelByBlockHash(HashSet<String> blockHashs)
			throws BlockStoreException {
		if (blockHashs.isEmpty()) {
			return new ArrayList<ContractEventCancel>();
		}
		List<ContractEventCancel> ContractEventCancels = new ArrayList<ContractEventCancel>();

		PreparedStatement preparedStatement = null;
		try {
			StringBuffer sql = new StringBuffer();
			for (String s : blockHashs) {
				sql.append(",'").append(s).append("'");
			}
			preparedStatement = getConnection().prepareStatement(
					SELECT_CONTRACTEVENTCANCEL_SQL + " AND eventblockhash IN (" + sql.substring(1) + ")");
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				ContractEventCancel contractEventCancel = new ContractEventCancel();
				contractEventCancel.setBlockHash(Sha256Hash.wrap(resultSet.getBytes("blockhash")));
				contractEventCancel.setEventBlockHash(Sha256Hash.wrap(resultSet.getBytes("eventblockhash")));
				contractEventCancel.setConfirmed(resultSet.getBoolean("confirmed"));
				contractEventCancel.setSpent(resultSet.getBoolean("spent"));
				contractEventCancel.setSpenderBlockHash(Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")));
				contractEventCancel.setTime(resultSet.getLong("time"));
				ContractEventCancels.add(contractEventCancel);
			}
			return ContractEventCancels;
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
	public List<ContractEventCancel> getContractEventCancelConfirmed() throws BlockStoreException {

		List<ContractEventCancel> contractEventCancels = new ArrayList<ContractEventCancel>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(CONTRACTEVENTCANCEL_CONFIRMED_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				ContractEventCancel contractEventCancel = new ContractEventCancel();
				contractEventCancel.setBlockHash(Sha256Hash.wrap(resultSet.getBytes("blockhash")));
				contractEventCancel.setEventBlockHash(Sha256Hash.wrap(resultSet.getBytes("eventblockhash")));
				contractEventCancel.setConfirmed(resultSet.getBoolean("confirmed"));
				contractEventCancel.setSpent(resultSet.getBoolean("spent"));
				contractEventCancel.setSpenderBlockHash(Sha256Hash.wrap(resultSet.getBytes("spenderblockhash")));
				contractEventCancel.setTime(resultSet.getLong("time"));
				contractEventCancels.add(contractEventCancel);
			}
			return contractEventCancels;
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
	public List<MatchLastdayResult> getTimeBetweenMatchingEvents(String tokenid, String basetoken, Long startDate,
			Long endDate, int count) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			String sql = SELECT_MATCHING_EVENT + " where  basetokenid = ? and  tokenid = ? ";

			if (startDate != null)
				sql += " AND inserttime >= " + startDate;
			sql += "  ORDER BY inserttime DESC " + "LIMIT   " + count;
			// log.debug(sql + " tokenid = " +tokenid + " basetoken =" +
			// basetoken );
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
					// throw new BlockStoreException("Could not close statement");
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
			// log.debug(sql + " tokenid = " +tokenid + " basetoken =" +
			// basetoken );
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override

	public void insertAccessPermission(String pubKey, String accessToken) throws BlockStoreException {
		String sql = "insert into access_permission (pubKey, accessToken, refreshTime) value (?,?,?)";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, pubKey);
			preparedStatement.setString(2, accessToken);
			preparedStatement.setLong(3, System.currentTimeMillis());
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
	public int getCountAccessPermissionByPubKey(String pubKey, String accessToken) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					"select count(1) as count from access_permission where pubKey = ? and accessToken = ?");
			preparedStatement.setString(1, pubKey);
			preparedStatement.setString(2, accessToken);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
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
	public void insertAccessGrant(String address) throws BlockStoreException {
		String sql = "insert into access_grant (address, createTime) value (?,?)";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, address);
			preparedStatement.setLong(2, System.currentTimeMillis());
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
	public void deleteAccessGrant(String address) throws BlockStoreException {
		String sql = "delete from access_grant where address = ?";

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			preparedStatement.setString(1, address);
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
	public int getCountAccessGrantByAddress(String address) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement("select count(1) as count from access_grant where address = ?");
			preparedStatement.setString(1, address);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return resultSet.getInt("count");
			}
			return 0;
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

	public List<Block> findRetryBlocks(long minHeigth) throws BlockStoreException {

		String sql = "SELECT hash,  "
				+ " height, milestone, milestonelastupdate,  inserttime,  blocktype, solid, confirmed , block"
				+ "  FROM   blocks ";
		sql += " where solid=true and confirmed=false and height >= " + minHeigth;
		sql += " ORDER BY insertTime desc ";
		List<Block> result = new ArrayList<Block>();

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(sql);
			ResultSet resultSet = preparedStatement.executeQuery();
			while (resultSet.next()) {
				Block block = params.getDefaultSerializer().makeZippedBlock(resultSet.getBytes("block"));

				result.add(block);
			}
			return result;
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
	public void insertChainBlockQueue(ChainBlockQueue chainBlockQueue) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(INSERT_CHAINBLOCKQUEUE);
			preparedStatement.setBytes(1, chainBlockQueue.getHash());
			preparedStatement.setBytes(2, chainBlockQueue.getBlock());
			preparedStatement.setLong(3, chainBlockQueue.getChainlength());
			preparedStatement.setBoolean(4, chainBlockQueue.isOrphan());
			preparedStatement.setLong(5, chainBlockQueue.getInserttime());
			preparedStatement.executeUpdate();
			preparedStatement.close();

		} catch (SQLException e) {
			// It is possible we try to add a duplicate Block if we
			// upgraded
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
	public void deleteAllChainBlockQueue() throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(" delete from chainblockqueue ");
			preparedStatement.execute();
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
	public void deleteChainBlockQueue(List<ChainBlockQueue> chainBlockQueues) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(" delete from chainblockqueue  where hash = ?");

			for (ChainBlockQueue chainBlockQueue : chainBlockQueues) {
				preparedStatement.setBytes(1, chainBlockQueue.getHash());
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
	public List<ChainBlockQueue> selectChainblockqueue(boolean orphan, int limit) throws BlockStoreException {

		PreparedStatement s = null;
		List<ChainBlockQueue> list = new ArrayList<ChainBlockQueue>();
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
					// throw new BlockStoreException("Could not close statement");
				}
		}
	}

	private ChainBlockQueue setChainBlockQueue(ResultSet resultSet) throws SQLException, IOException {
		return new ChainBlockQueue(resultSet.getBytes("hash"), Gzip.decompressOut(resultSet.getBytes("block")),
				resultSet.getLong("chainlength"), resultSet.getBoolean("orphan"), resultSet.getLong("inserttime"));
	}

	@Override
	public void insertLockobject(LockObject lockObject) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" insert into lockobject (lockobjectid, locktime) values (?, ?)  ");
			preparedStatement.setString(1, lockObject.getLockobjectid());
			preparedStatement.setLong(2, lockObject.getLocktime());
			preparedStatement.execute();
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
	public void deleteLockobject(String lockobjectid) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(" delete from lockobject  where lockobjectid = ?");
			preparedStatement.setString(1, lockobjectid);
			preparedStatement.execute();
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
	public void deleteAllLockobject() throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(" delete from lockobject ");
			preparedStatement.execute();
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
	public void saveAvgPrice(AVGMatchResult matchResult) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					" insert into matchingdaily(matchday,tokenid,basetokenid,avgprice,totalQuantity,highprice,lowprice,inserttime) values(?,?,?,?,?,?,?,?) ");
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
					List<String> tokenids = new ArrayList<String>();
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
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(
					" insert into matchinglastday(tokenid,basetokenid,price,executedQuantity ,inserttime) values(?,?,?,?,? ) ");

			preparedStatement.setString(1, matchResult.getTokenid());
			preparedStatement.setString(2, matchResult.getBasetokenid());
			preparedStatement.setLong(3, matchResult.getPrice());
			preparedStatement.setLong(4, matchResult.getExecutedQuantity());

			preparedStatement.setLong(5, new Date().getTime());
			preparedStatement.execute();
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

	public void deleteLastdayPrice(MatchResult matchResult) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement("delete from  matchinglastday where tokenid=? and basetokenid=? ");

			preparedStatement.setString(1, matchResult.getTokenid());
			preparedStatement.setString(2, matchResult.getBasetokenid());
			preparedStatement.execute();
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
			List<Long> times = new ArrayList<Long>();
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	public int getCountYesterdayMatching() throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		Date yesterdayDate = new Date(System.currentTimeMillis() - 86400000L);
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		String yesterday = dateFormat.format(yesterdayDate);
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select count(1) from matchingdaily where matchday=?  ");
			preparedStatement.setString(1, yesterday);
			ResultSet resultSet = preparedStatement.executeQuery();
			int count = resultSet.getInt(1);
			return count;
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

	public int getCountMatching(String matchday) throws BlockStoreException {
		PreparedStatement preparedStatement = null;

		try {
			preparedStatement = getConnection()
					.prepareStatement(" select count(1) from matchingdaily where matchday=?  ");
			preparedStatement.setString(1, matchday);
			ResultSet resultSet = preparedStatement.executeQuery();
			int count = 0;
			if (resultSet.next()) {
				count = resultSet.getInt(1);
			}
			return count;
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
			List<AVGMatchResult> orderTickers = new ArrayList<AVGMatchResult>();
			while (resultSet.next()) {
				AVGMatchResult matchResult = new AVGMatchResult();
				matchResult.setTokenid(resultSet.getString(1));
				matchResult.setBasetokenid(resultSet.getString(2));
				matchResult.setPrice(resultSet.getLong(3) / resultSet.getLong(4));
				BigDecimal avgprice = BigDecimal.ZERO;
				avgprice.setScale(3);
				avgprice = new BigDecimal(resultSet.getLong(3)).divide(new BigDecimal(resultSet.getLong(4)));
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	public MatchResult queryTickerLast(long starttime, long endtime, String tokenid, String basetokenid)
			throws BlockStoreException {
		PreparedStatement preparedStatement = null;

		try {
			preparedStatement = getConnection()
					.prepareStatement(" select tokenid,basetokenid,  price,  executedQuantity "
							+ " from matching where inserttime>=? and inserttime<=?   and  tokenid=? and basetokenid=?  ");
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
	public LockObject selectLockobject(String lockobjectid) throws BlockStoreException {
		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection()
					.prepareStatement(" select lockobjectid, locktime from lockobject  where lockobjectid = ?");
			preparedStatement.setString(1, lockobjectid);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
				return new LockObject(lockobjectid, resultSet.getLong("locktime"));
			}
			return null;
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
				preparedStatement.setString(i++, tokenid);
			}

			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {
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
					// throw new BlockStoreException("Could not close statement");
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
				preparedStatement.setString(i++, tokenid);
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
					// throw new BlockStoreException("Could not close statement");
				}
			}
		}

	}

	public void addAccountCoinBatch(Map<String, Map<String, Coin>> toAddressMap) throws BlockStoreException {

		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement(
					"insert into accountBalance (address, tokenid,coinvalue,lastblockhash) values(?,?,?,?)");

			for (String toaddress : toAddressMap.keySet()) {
				for (String tokenid : toAddressMap.get(toaddress).keySet()) {
					s.setString(1, toaddress);
					s.setString(2, tokenid);
					s.setBytes(3, toAddressMap.get(toaddress).get(tokenid).getValue().toByteArray());
					s.setBytes(4, Sha256Hash.ZERO_HASH.getBytes());
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

	public void updateAccountCoinBatch(Map<String, Map<String, Coin>> fromaddressMap) throws BlockStoreException {

		PreparedStatement s = null;
		try {
			s = getConnection().prepareStatement("update accountBalance set coinvalue=? where address=? and tokenid=?");

			for (String fromaddress : fromaddressMap.keySet()) {
				for (String tokenid : fromaddressMap.get(fromaddress).keySet()) {
					s.setBytes(1, fromaddressMap.get(fromaddress).get(tokenid).getValue().toByteArray());
					s.setString(2, fromaddress);
					s.setString(3, tokenid);

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
				preparedStatement.setString(i++, tokenid);
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
	public Contractresult getMaxMilestoneContractresult(String contracttokenid) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_CONTRACTRESULT_MAX_MILESTONE_SQL);
			preparedStatement.setString(1, contracttokenid);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {

				return setContractresult(resultSet);
			} else
				return null;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public List<Contractresult> getConfirmedContractresultNotMilestone(String contracttokenid)
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;

		List<Contractresult> re = new ArrayList<Contractresult>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_CONTRACTRESULT_CONFIRMED_NOTMILESTONE_SQL);
			preparedStatement.setString(1, contracttokenid);
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				re.add(setContractresult(resultSet));
			}
			return re;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}

	@Override
	public Orderresult getMaxMilestoneOrderresult( ) throws BlockStoreException {

		PreparedStatement preparedStatement = null;
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDER_RESULT_MAX_MILESTONE_SQL);
			ResultSet resultSet = preparedStatement.executeQuery();
			if (resultSet.next()) {

				return setOrderresult(resultSet);
			} else
				return null;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
	@Override
	public List<Orderresult> getConfirmedOrderresultNotMilestone()
			throws BlockStoreException {

		PreparedStatement preparedStatement = null;

		List<Orderresult> re = new ArrayList<Orderresult>();
		try {
			preparedStatement = getConnection().prepareStatement(SELECT_ORDERRESULT_CONFIRMED_NOTMILESTONE_SQL);
			 
			ResultSet resultSet = preparedStatement.executeQuery();

			while (resultSet.next()) {
				re.add(setOrderresult(resultSet));
			}
			return re;

		} catch (SQLException ex) {
			throw new BlockStoreException(ex);
		} finally {
			if (preparedStatement != null) {
				try {
					preparedStatement.close();
				} catch (SQLException e) {
					// // throw new BlockStoreException("Could not close statement");
				}
			}
		}
	}
	
}
