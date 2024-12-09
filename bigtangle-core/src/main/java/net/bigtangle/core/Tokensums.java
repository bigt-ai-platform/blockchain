package net.bigtangle.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class Tokensums  extends DataClass implements java.io.Serializable {
    /**
     * 
     */
    private static final long serialVersionUID = 1L;
    
    String tokenid;
    BigInteger initial = BigInteger.ZERO;
    BigInteger unspent = BigInteger.ZERO;
    BigInteger order = BigInteger.ZERO;
    BigInteger contract = BigInteger.ZERO;
    //It must be sorted list for calculation hash
    List<UTXO> utxos = new ArrayList<>();
    List<OrderRecord> orders = new ArrayList<>();
    List<ContractEventRecord> contracts = new ArrayList<>();
    

    public void calculate() {
        calcOutputs();
        ordersum();
        contractsum();
    }
    
    public void calcOutputs() { 
        for (UTXO u : utxos) {
            if (u.isConfirmed() && !u.isSpent()) {
                unspent = unspent.add(u.getValue().getValue());
            } 
        }
    }

    public void ordersum() {
        for (OrderRecord orderRecord : orders) {
            if (orderRecord.getOfferTokenid().equals(tokenid)) {
                order = order.add(BigInteger.valueOf(orderRecord.getOfferValue()));
            }
      
        }
    }

    public void contractsum() {
        for (ContractEventRecord c : contracts) {
            if (c.getTargetTokenid().equals(tokenid)) {
            	contract = contract.add( c.getTargetValue());
            }
        }
    }

    
    @Override
    public String toString() {
        return "Tokensums [ \n tokenid=" + tokenid + ",  \n initial=" + initial + ", \n unspent UTXO=" + unspent 
        		+ ", \n order=" + order
        		+ ", \n contract=" + contract
                + " \n unspentUTXO.add(order).add(contract) = " + unspent.add(order).add(contract) + "]";
    }

    public boolean check() {
        if (NetworkParameters.BIGTANGLE_TOKENID_STRING.equals(tokenid)) {
        	//fee payment, only create reward block will fee to miner
            return initial.compareTo(unspent.add(order).add(contract)) >= 0;
        } else {
            return initial.compareTo(unspent.add(order).add(contract)) == 0;
        }
    }

    

    public byte[] toByteArray() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            DataOutputStream dos = new DataOutputStream(baos); 
            dos.write(super.toByteArray()); 
            Utils.writeNBytesString(dos, tokenid);
            Utils.writeNBytes(dos, initial.toByteArray());
            Utils.writeNBytes(dos, unspent.toByteArray());
            Utils.writeNBytes(dos, order.toByteArray());
            dos.writeInt(utxos.size());
            for (UTXO c : utxos)
                dos.write(c.toByteArray());
            dos.writeInt(utxos.size());
            for (OrderRecord c : orders)
                dos.write(c.toByteArray());
            dos.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }
 
    
    public BigInteger unspentOrderSum() {
        return unspent.add(order);
    }

    public String getTokenid() {
        return tokenid;
    }

    public void setTokenid(String tokenid) {
        this.tokenid = tokenid;
    }

    public BigInteger getInitial() {
        return initial;
    }

    public void setInitial(BigInteger initial) {
        this.initial = initial;
    }

    public BigInteger getUnspent() {
        return unspent;
    }

    public void setUnspent(BigInteger unspent) {
        this.unspent = unspent;
    }

    public BigInteger getOrder() {
        return order;
    }

    public void setOrder(BigInteger order) {
        this.order = order;
    }

    public List<UTXO> getUtxos() {
        return utxos;
    }

    public void setUtxos(List<UTXO> utxos) {
        this.utxos = utxos;
    }

    public List<OrderRecord> getOrders() {
        return orders;
    }

    public void setOrders(List<OrderRecord> orders) {
        this.orders = orders;
    }

	public BigInteger getContract() {
		return contract;
	}

	public void setContract(BigInteger contract) {
		this.contract = contract;
	}

	public List<ContractEventRecord> getContracts() {
		return contracts;
	}

	public void setContracts(List<ContractEventRecord> contracts) {
		this.contracts = contracts;
	}

   
}
