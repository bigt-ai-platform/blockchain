/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.performance;

import java.io.IOException;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.common.math.LongMath;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.MemoInfo;
import net.bigtangle.core.Token;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.InsufficientMoneyException;
import net.bigtangle.exception.UTXOProviderException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.params.ReqCmd;
import net.bigtangle.response.GetBalancesResponse;
import net.bigtangle.server.utils.UtilSort;
import net.bigtangle.utils.Json;
import net.bigtangle.utils.OkHttp3Util;
import net.bigtangle.wallet.FreeStandingTransactionOutput;
import net.bigtangle.wallet.Wallet;

public class Lottery {

    private static final Logger log = LoggerFactory.getLogger(Lottery.class);
    private String tokenid;
    private String contextRoot = "http://localhost:8088/";
    private Wallet walletAdmin;
    private NetworkParameters params;
    private String winner;
    private List<UTXO> userUtxos;
    private BigInteger winnerAmount;
    private boolean macthed;
    private ECKey accountKey;
    List<String> userAddress;
    /*
     * start check balance and check to X amount and collect all user in lottery
     * list of (each ticket, address) compute random selection of winner pay to
     * winner address
     * winnerAmount is the minimum defined winnerAmount and paid can be more than this 
     * Defined as contract with  tokenid, winnerAmount, payAmount and consensus check
     * No sign for consensus method to winner and no the contract address is protected only consensus method.
     * consensus method will run and verify on each node
     */
    public void start() throws Exception {
        // ECKey ecKey = ECKey.fromPublicOnly(Utils.HEX.decode(tokenid));
        walletAdmin = Wallet.fromKeys(params, accountKey);
        walletAdmin.setServerURL(contextRoot);
        //TODO the same amount of UTXO for lottery 
        List<UTXO> player = getBalance(accountKey);
        // TODO 1 million raw
        new UtilSort().sortUTXO(player);
        userUtxos = new ArrayList<UTXO>();
        if (canTakeWinner(player, userUtxos)) {
            doTakeWinner();
        }
    }

    private void doTakeWinner() throws Exception {
        Token t = walletAdmin.checkTokenId(tokenid);

         userAddress = baseList(userUtxos, t);

        HashMap<String, Object> requestParam = new HashMap<String, Object>();
        byte[] buf = OkHttp3Util.postAndGetBlock(contextRoot + ReqCmd.getTip.name(),
                Json.jsonmapper().writeValueAsString(requestParam));
        Block r1 = params.getDefaultSerializer().makeBlock(buf);
        // Deterministic randomization
        byte[] randomness = Utils.xor(r1.getPrevBlockHash().getBytes(), r1.getPrevBranchBlockHash().getBytes());
        SecureRandom se = new SecureRandom(randomness);

        winner = userAddress.get(se.nextInt(userAddress.size()));

        log.debug("winner " + winner +" sum ="+sum()+ " \n user address size: " + userAddress.size());

        List<Block> bl = batchGiveMoneyToECKeyList(winner, sum(), "win lottery", userUtxos);
        if (bl.isEmpty()) {
            log.error("payment of winner is failed");
        }
        for (Block b : bl) {
            log.debug("block " + (b == null ? "block is null" : b.toString()));
        }

    }

    /*
     * split the list for lottery pay
     */
    private List<String> baseList(List<UTXO> player, Token t) {
        List<String> addresses = new ArrayList<String>();
        for (UTXO u : player) {
            addresses.addAll(baseList(u, t));
        }
        return addresses;
    }

    private List<String> baseList(UTXO u, Token t) {
        List<String> addresses = new ArrayList<String>();
        if (checkUTXO(u)) {
            long roundCoin = roundCoin(u.getValue(), t);
            for (long i = 0; i < roundCoin; i++) {

                addresses.add(u.getFromaddress());
            }
        }
        return addresses;
    }

    private boolean checkUTXO(UTXO u) {
        return u.getFromaddress() != null && !"".equals(u.getFromaddress())
                && !u.getFromaddress().equals(u.getAddress());
    }

    /*
     * round without decimals
     */

    private long roundCoin(Coin c, Token t) {

        return LongMath.divide(c.getValue().longValue(), LongMath.checkedPow(10, t.getDecimals()), RoundingMode.DOWN);

    }

    public BigInteger sum() {
        BigInteger sum = BigInteger.ZERO;
        for (UTXO u : userUtxos) {
            sum = sum.add(u.getValue().getValue());
        }
        return sum;
    }

    /*
     * condition for execute the lottery 1) no other pending payment 2) can do
     * the send failed block again 3) the sum is ok
     */
    private boolean canTakeWinner(List<UTXO> player, List<UTXO> userlist) {

        BigInteger sum = BigInteger.ZERO;
        for (UTXO u : player) {
            if (checkUTXO(u)) {
                sum = sum.add(u.getValue().getValue());
                userlist.add(u);
                if (sum.compareTo(winnerAmount) >= 0) {
                    return macthed = true;
                }
            }
        }
        log.debug(" sum= " + sum);
        return macthed = false;

    }

    public synchronized List<Block> batchGiveMoneyToECKeyList(String address, BigInteger amount, String memo,
            List<UTXO> userlist)
            throws JsonProcessingException, IOException, InsufficientMoneyException, UTXOProviderException, Exception {

        List<FreeStandingTransactionOutput> candidates = new ArrayList<FreeStandingTransactionOutput>();
        for (UTXO u : userlist) {
            candidates.add(new FreeStandingTransactionOutput(this.params, u));
        }
        
        return walletAdmin.payFromList(null, address, new Coin(amount, Utils.HEX.decode(tokenid)), new MemoInfo(memo), candidates);

    }

    // get balance for the walletKeys
    protected List<UTXO> getBalance(List<ECKey> keys) throws Exception {
        List<UTXO> listUTXO = new ArrayList<UTXO>();
        List<String> keyStrHex000 = new ArrayList<String>();

        for (ECKey ecKey : keys) {
            // keyStrHex000.add(ecKey.toAddress(networkParameters).toString());
            keyStrHex000.add(Utils.HEX.encode(ecKey.getPubKeyHash()));
        }
          byte[] response = OkHttp3Util.post(contextRoot + ReqCmd.getBalances.name(),
                Json.jsonmapper().writeValueAsString(keyStrHex000).getBytes());

        GetBalancesResponse getBalancesResponse = Json.jsonmapper().readValue(response, GetBalancesResponse.class);

        // no pending utxo
        for (UTXO utxo : getBalancesResponse.getOutputs()) {
            if (utxo.getValue().getValue().signum() > 0 || utxo.getTokenId().equals(tokenid)) {
                if (!utxo.isSpendPending())
                    listUTXO.add(utxo);
            }
        }

        return listUTXO;
    }

    protected List<UTXO> getBalance(ECKey ecKey) throws Exception {
        List<ECKey> keys = new ArrayList<ECKey>();
        keys.add(ecKey);
        return getBalance(keys);
    }

    public String getTokenid() {
        return tokenid;
    }

    public void setTokenid(String tokenid) {
        this.tokenid = tokenid;
    }

  
    public NetworkParameters getParams() {
        return params;
    }

    public void setParams(NetworkParameters params) {
        this.params = params;
    }

    public String getContext_root() {
        return contextRoot;
    }

    public void setContext_root(String context_root) {
        this.contextRoot = context_root;
    }

    public String getWinner() {
        return winner;
    }

    public void setWinner(String winner) {
        this.winner = winner;
    }

    public List<UTXO> getUserUtxos() {
        return userUtxos;
    }

    public String getContextRoot() {
        return contextRoot;
    }

    public void setContextRoot(String contextRoot) {
        this.contextRoot = contextRoot;
    }

    public void setUserUtxos(List<UTXO> userUtxos) {
        this.userUtxos = userUtxos;
    }

    public BigInteger getWinnerAmount() {
        return winnerAmount;
    }

    public void setWinnerAmount(BigInteger winnerAmount) {
        this.winnerAmount = winnerAmount;
    }

    public boolean isMacthed() {
        return macthed;
    }

    public void setMacthed(boolean macthed) {
        this.macthed = macthed;
    }

    public ECKey getAccountKey() {
        return accountKey;
    }

    public void setAccountKey(ECKey accountKey) {
        this.accountKey = accountKey;
    }

}
