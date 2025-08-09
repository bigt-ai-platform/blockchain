/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.google.common.collect.Lists;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.ECKey;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.exception.VerificationException;
import net.bigtangle.params.MainNetParams;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.store.BlockStoreService;

/**
 * We don't do any wallet tests here, we leave that to {@link ChainSplitTest}
 */


public abstract class AbstractFullPrunedBlockChainTest {
    
  //  private static final Logger log = LoggerFactory.getLogger(AbstractFullPrunedBlockChainTest.class);

    protected static final NetworkParameters PARAMS = new MainNetParams();

    @Autowired
    protected BlockStoreService blockgraph;
    protected BlockStoreInterface store;

    @BeforeEach
    public void setUp() throws Exception {
 
    }

    public abstract BlockStoreInterface createStore(NetworkParameters params, int blockCount)
            throws BlockStoreException;

    public abstract void resetStore(BlockStoreInterface store) throws BlockStoreException;

  //  @Test
    public void skipScripts() throws Exception {
        store = createStore(PARAMS, 10);

        // Check that we aren't accidentally leaving any references
        // to the full StoredUndoableBlock's lying around (ie memory leaks)
 
        // Build some blocks on genesis block to create a spendable output
        Block rollingBlock =   UtilsTest.createBlock(PARAMS,  UtilGeneseBlock.createGenesis(PARAMS), UtilGeneseBlock.createGenesis(PARAMS));
    
        blockgraph.addBlock(rollingBlock, true,store);
        TransactionOutput spendableOutput = rollingBlock.getTransactions().get(0).getOutput(0);
        for (int i = 1; i < PARAMS.getSpendableCoinbaseDepth(); i++) {
            rollingBlock =UtilsTest.createBlock(PARAMS,rollingBlock, UtilGeneseBlock.createGenesis(PARAMS));
            blockgraph.addBlock(rollingBlock, true,store);
        }

        rollingBlock = UtilsTest.createBlock(PARAMS,rollingBlock, UtilGeneseBlock.createGenesis(PARAMS));
        Transaction t = new Transaction(PARAMS);
        t.addOutput(new TransactionOutput(PARAMS, t, Coin.COIN.multiply(50), new byte[] {}));
        TransactionInput input = t.addInput(rollingBlock.getHash(), spendableOutput);
        // Invalid script.
        input.clearScriptBytes();
        rollingBlock.addTransaction(t);
        rollingBlock.solve();
        
        // TAKEN OUT validatorService.setRunScripts(false);
        
        try {
            blockgraph.addBlock(rollingBlock, true,store);
        } catch (VerificationException e) {
            fail();
        }
        try {
            store.close();
        } catch (Exception e) {
        }
    }

   // @Test
    public void testFinalizedBlocks() throws Exception {
        final int UNDOABLE_BLOCKS_STORED = 10;
        store = createStore(PARAMS, UNDOABLE_BLOCKS_STORED);

        // Check that we aren't accidentally leaving any references
        // to the full StoredUndoableBlock's lying around (ie memory leaks)

        ECKey outKey = new ECKey();
 

        // Build some blocks on genesis block to create a spendable output
        Block rollingBlock =UtilsTest.createBlock(PARAMS, UtilGeneseBlock.createGenesis(PARAMS), UtilGeneseBlock.createGenesis(PARAMS));
        blockgraph.addBlock(rollingBlock, true,store);
        TransactionOutPoint spendableOutput = new TransactionOutPoint(PARAMS, 0, rollingBlock.getHash(),
                rollingBlock.getTransactions().get(0).getHash());
        byte[] spendableOutputScriptPubKey = rollingBlock.getTransactions().get(0).getOutputs().get(0).getScriptBytes();
        for (int i = 1; i < PARAMS.getSpendableCoinbaseDepth(); i++) {
            rollingBlock = UtilsTest.createBlock(PARAMS,rollingBlock, UtilGeneseBlock.createGenesis(PARAMS));
            blockgraph.addBlock(rollingBlock, true,store);
        }

            rollingBlock =UtilsTest.createBlock(PARAMS, rollingBlock, UtilGeneseBlock.createGenesis(PARAMS));

        Transaction t = new Transaction(PARAMS);
        // Entirely invalid scriptPubKey
        t.addOutput(new TransactionOutput(PARAMS, t, Coin.COIN.multiply(50), new byte[] {}));
        t.addSignedInput(spendableOutput, new Script(spendableOutputScriptPubKey), outKey);
        rollingBlock.addTransaction(t);
        rollingBlock.solve();

        blockgraph.addBlock(rollingBlock, true,store);

        try {
            store.close();
        } catch (Exception e) {
        }
    }

 

   // @Test
    public void testGetOpenTransactionOutputs() throws Exception {
        final int UNDOABLE_BLOCKS_STORED = 10;
        store = createStore(PARAMS, UNDOABLE_BLOCKS_STORED);
        resetStore(store);

        // Check that we aren't accidentally leaving any references
        // to the full StoredUndoableBlock's lying around (ie memory leaks)
        ECKey outKey = new ECKey();
 

        // Build some blocks on genesis block to create a spendable output
        Block rollingBlock =UtilsTest.createBlock(PARAMS, UtilGeneseBlock.createGenesis(PARAMS), UtilGeneseBlock.createGenesis(PARAMS));
        blockgraph.addBlock(rollingBlock, true,store);
        Transaction transaction = rollingBlock.getTransactions().get(0);
        TransactionOutPoint spendableOutput = new TransactionOutPoint(PARAMS, 0, rollingBlock.getHash(), transaction.getHash());
        byte[] spendableOutputScriptPubKey = transaction.getOutputs().get(0).getScriptBytes();
        for (int i = 1; i < PARAMS.getSpendableCoinbaseDepth(); i++) {
            rollingBlock = UtilsTest.createBlock(PARAMS,rollingBlock, UtilGeneseBlock.createGenesis(PARAMS));
            blockgraph.addBlock(rollingBlock, true,store);
        }
        rollingBlock = UtilsTest.createBlock(PARAMS,rollingBlock, UtilGeneseBlock.createGenesis(PARAMS));

        // Create bitcoin spend of 1 BTA.
        ECKey toKey = new ECKey();
        Coin amount = Coin.valueOf(10000, NetworkParameters.BIGTANGLE_TOKENID);
        Address address = new Address(PARAMS, toKey.getPubKeyHash());
        Coin totalAmount = Coin.ZERO;

        Transaction t = new Transaction(PARAMS);
        t.addOutput(TransactionOutput.fromCoinKey(PARAMS, t, amount, toKey));
        t.addSignedInput(spendableOutput, new Script(spendableOutputScriptPubKey), outKey);
        rollingBlock.addTransaction(t);
        rollingBlock.solve();
        blockgraph.addBlock(rollingBlock, true,store);

        totalAmount = totalAmount.add(amount);

        List<UTXO> outputs = store.getOpenTransactionOutputs(Lists.newArrayList(address));
        assertNotNull(outputs);
        assertEquals( 1, outputs.size(),"Wrong Number of Outputs");
        // UTXO output = outputs.get(0);
        // assertEquals("The address is not equal", address.toString(),
        // output.getAddress());
        // assertEquals("The amount is not equal", totalAmount,
        // output.getValue());

        outputs = null;
        // output = null;
        try {
            store.close();
        } catch (Exception e) {
        }
    }
 
 
}
