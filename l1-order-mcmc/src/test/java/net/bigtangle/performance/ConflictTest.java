package net.bigtangle.performance;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import net.bigtangle.core.Block;
import net.bigtangle.core.Coin;
import net.bigtangle.core.PQKey;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.UTXO;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.crypto.TransactionSignature;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.script.Script;
import net.bigtangle.script.ScriptBuilder;
import net.bigtangle.mcmc.test.AbstractIntegrationTest;
import net.bigtangle.wallet.FreeStandingTransactionOutput;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class ConflictTest extends AbstractIntegrationTest {

    /*
     * resolve mass conflict quickly
     */
    @Test
    public void testPayConflict() throws Exception {

        // Create blocks with conflict
        Transaction doublespendTX = create();

        for (int i = 0; i < 10; i++) {

            Block b1 = createAndAddNextBlockWithTransaction(UtilGeneseBlock.createGenesis(networkParameters),
                    UtilGeneseBlock.createGenesis(networkParameters), doublespendTX);
            blockGraph.addBlock(b1, true, store);
          
            // add blocks and want to get fast resolve of double spending
            mcmcServiceUpdate();
        }

    }

    public Transaction create() throws Exception {
        // Generate two conflicting blocks
        PQKey testKey = PQKey.createNew()Utils.HEX.decode(testPriv), Utils.HEX.decode(testPub));
        List<UTXO> outputs = getBalance(false, testKey);
        TransactionOutput spendableOutput = new FreeStandingTransactionOutput(this.networkParameters, outputs.get(0));
        Coin amount = Coin.valueOf(2, NetworkParameters.BIGTANGLE_TOKENID);
        Transaction doublespendTX = new Transaction(networkParameters);
        doublespendTX.addOutput(TransactionOutput.fromCoinKey(networkParameters, doublespendTX, amount, PQKey.createNew()));
        TransactionInput input = doublespendTX.addInput(outputs.get(0).getBlockHash(), spendableOutput);
        Sha256Hash sighash = doublespendTX.hashForSignature(0, spendableOutput.getScriptBytes(),
                Transaction.SigHash.ALL, false);

        TransactionSignature sig = new TransactionSignature(testKey.sign(sighash), Transaction.SigHash.ALL, false);
        Script inputScript = ScriptBuilder.createInputScript(sig);
        input.setScriptSig(inputScript);
        return doublespendTX;
    }

}
