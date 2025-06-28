package net.bigtangle.ui.wallet.utils;

import com.google.common.base.Strings;

import net.bigtangle.core.Address;
import net.bigtangle.core.Block;
import net.bigtangle.core.NetworkParameters;
import net.bigtangle.core.OrderOpenInfo;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.TokenInfo;
import net.bigtangle.core.Transaction;
import net.bigtangle.core.TransactionInput;
import net.bigtangle.core.TransactionOutPoint;
import net.bigtangle.core.TransactionOutput;
import net.bigtangle.core.Utils;
import net.bigtangle.core.BlockType;
import net.bigtangle.core.exception.ScriptException;
import net.bigtangle.script.Script;
import net.bigtangle.ui.wallet.Main;

public class BlockFormat {

    public static String block2string(Block block, NetworkParameters params) {
        StringBuilder s = new StringBuilder();
        s.append(Main.getText("blockhash") + ": ").append(block.getHashAsString()).append('\n');
        if (block.getTransactions() != null && block.getTransactions().size() > 0) {
            s.append("   ").append(block.getTransactions().size()).append(" " + Main.getText("transaction") + ":\n");
            for (Transaction tx : block.getTransactions()) {
                s.append(transaction2string(tx,params));
            }
        }
        s.append("   " + Main.getText("version") + ": ").append(block.getVersion());
        s.append('\n');
        s.append("   " + Main.getText("previous") + ": ").append(block.getPrevBlockHash()).append("\n");
        s.append("   " + Main.getText("branch") + ": ").append(block.getPrevBranchBlockHash()).append("\n");
        s.append("   " + Main.getText("merkle") + ": ").append(block.getMerkleRoot()).append("\n");
        s.append("   " + Main.getText("time") + ": ").append(block.getTimeSeconds()).append(" (")
                .append(Utils.dateTimeFormat(block.getTimeSeconds() * 1000)).append(")\n");

        s.append("   " + Main.getText("difficultytarget") + ": ").append(block.getDifficultyTarget()).append("\n");
        s.append("   " + Main.getText("nonce") + ": ").append(block.getNonce()).append("\n");
        if (block.getMinerAddress() != null)
            s.append("   " + Main.getText("mineraddress") + ": ").append(new Address(params, block.getMinerAddress()))
                    .append("\n");
        s.append("   " + Main.getText("chainlength") + ": ").append(block.getLastMiningRewardBlock()).append("\n");
        s.append("   " + Main.getText("blocktype") + ": ").append(block.getBlockType()).append("\n");

        if (block.getBlockType().equals(Type.BLOCKTYPE_REWARD)) {
            try {
                RewardInfo rewardInfo = new RewardInfo().parse(block.getTransactions().get(0).getData());
                s.append(rewardInfo.toString());
            } catch (Exception e) {
                // ignore throw new RuntimeException(e);
            }
        }

        if (block.getBlockType() == Type.BLOCKTYPE_ORDER_OPEN) {

            try {
                OrderOpenInfo info = new OrderOpenInfo().parse(block.getTransactions().get(0).getData());
                s.append(info.toString());
            } catch (Exception e) {
                // ignore throw new RuntimeException(e);
            }
        }
        
       
        if (block.getBlockType() == Type.BLOCKTYPE_TOKEN_CREATION) {

            try {
                TokenInfo info = new TokenInfo().parse(block.getTransactions().get(0).getData());
                s.append(info.toString());
            } catch (Exception e) {
                // ignore throw new RuntimeException(e);
            }

        }
        return s.toString();

    }

    public static String transaction2string(Transaction transaction, NetworkParameters params) {
        StringBuilder s = new StringBuilder();
        s.append("  ").append(transaction.getHashAsString()).append('\n');

        if (transaction.isTimeLocked()) {
            s.append("  time locked until ");
            if (transaction.getLockTime() < Transaction.LOCKTIME_THRESHOLD) {
                s.append("block ").append(transaction.getLockTime());

            } else {
                s.append(Utils.dateTimeFormat(transaction.getLockTime() * 1000));
            }
            s.append('\n');
        }

        if (transaction.isCoinBase()) {
            String script;
            String script2;
            try {
                script = transaction.getInputs().get(0).getScriptSig().toString();
                script2 = transaction.getOutputs().get(0).toString();
            } catch (ScriptException e) {
                script = "???";
                script2 = "???";
            }
            s.append(Main.getText("coinbase")).append(script).append("   (").append(script2).append(")\n");
            return s.toString();
        }
        if (!transaction.getInputs().isEmpty()) {
            for (TransactionInput in : transaction.getInputs()) {
                s.append("     ");
                s.append(Main.getText("input") + ":   ");

                try {
                    String scriptSigStr = in.getScriptSig().toString();
                    s.append(!Strings.isNullOrEmpty(scriptSigStr) ? scriptSigStr : " ");
                    if (in.getValue() != null)
                        s.append(" ").append(in.getValue().toString());
                    s.append("\n          ");
                    s.append(Main.getText("connectedOutput") + ": ");
                    final TransactionOutPoint outpoint = in.getOutpoint();
                    s.append(outpoint.toString());
                    final TransactionOutput connectedOutput = outpoint.getConnectedOutput();
                    if (connectedOutput != null) {
                        Script scriptPubKey = connectedOutput.getScriptPubKey();
                        if (scriptPubKey.isSentToAddress() || scriptPubKey.isPayToScriptHash()) {
                            s.append(" hash160:");
                            s.append(Utils.HEX.encode(scriptPubKey.getPubKeyHash()));
                        }
                    }
                    if (in.hasSequence()) {
                        s.append("\n          sequence:").append(Long.toHexString(in.getSequenceNumber()));
                    }
                } catch (Exception e) {
                    s.append("[exception: ").append(e.getMessage()).append("]");
                }
                s.append('\n');
            }
        } else {
            s.append("     ");
            // s.append("INCOMPLETE: No inputs!\n");
        }
        for (TransactionOutput out : transaction.getOutputs()) {
            s.append("     ");
            s.append("out  ");
            try {
                String scriptPubKeyStr = out.getScriptPubKey().toString();
                s.append(!Strings.isNullOrEmpty(scriptPubKeyStr) ? scriptPubKeyStr : "");
                s.append("\n ");
                s.append(" address: ").append(out.getScriptPubKey().getToAddress(params, true)).append("\n");
                s.append("\n ");
                s.append(out.getValue().toString());
                if (!out.isAvailableForSpending()) {
                    s.append(" Spent");
                }
                if (out.getSpentBy() != null) {
                    s.append(" by ");
                    s.append(out.getSpentBy().getParentTransaction().getHashAsString());
                }
            } catch (Exception e) {
                s.append("[exception: ").append(e.getMessage()).append("]");
            }
            s.append('\n');
        }

        return s.toString();
    }

}
