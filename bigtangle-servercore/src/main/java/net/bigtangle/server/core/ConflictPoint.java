/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/

package net.bigtangle.server.core;

import javax.annotation.Nullable;

import com.google.common.base.Objects;

import net.bigtangle.core.ContractExecutionResult;
import net.bigtangle.core.OrderExecutionResult;
import net.bigtangle.core.RewardInfo;
import net.bigtangle.core.Token;
import net.bigtangle.core.TransactionOutPoint;

public class ConflictPoint {
	private ConflictType type;

	/** Null if not conflict of corresponding type */
	@Nullable
	private TransactionOutPoint connectedOutpoint;
	@Nullable
	private RewardInfo connectedReward;
	@Nullable
	private Token connectedToken;
	@Nullable
	private Token connectedDomainToken;

	@Nullable
	private ContractExecutionResult connectedContractExecute;
	@Nullable
	private OrderExecutionResult connectedOrderExecute;

	private ConflictPoint(ConflictType type, @org.jetbrains.annotations.Nullable TransactionOutPoint connectedOutpoint, @org.jetbrains.annotations.Nullable RewardInfo reward,
						  @org.jetbrains.annotations.Nullable Token connectedToken, @org.jetbrains.annotations.Nullable Token connectedDomainToken, @org.jetbrains.annotations.Nullable ContractExecutionResult connectedContracExecute,
						  @org.jetbrains.annotations.Nullable OrderExecutionResult connectedOrderExecute) {
		super();
		this.type = type;
		this.connectedOutpoint = connectedOutpoint;
		this.connectedReward = reward;
		this.connectedToken = connectedToken;
		this.connectedDomainToken = connectedDomainToken;
		this.connectedContractExecute = connectedContracExecute;
		this.connectedOrderExecute = connectedOrderExecute;

	}

	public static ConflictPoint fromTransactionOutpoint(TransactionOutPoint connectedOutpoint) {
		return new ConflictPoint(ConflictType.TXOUT, connectedOutpoint, null, null, null, null, null);
	}

	public static ConflictPoint fromReward(RewardInfo reward) {
		return new ConflictPoint(ConflictType.REWARDISSUANCE, null, reward, null, null, null, null);
	}

	public static ConflictPoint fromToken(Token token) {
		return new ConflictPoint(ConflictType.TOKENISSUANCE, null, null, token, null, null, null);
	}

	public static ConflictPoint fromDomainToken(Token token) {
		return new ConflictPoint(ConflictType.DOMAINISSUANCE, null, null, null, token, null, null);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		ConflictPoint other = (ConflictPoint) o;

		if (other.type != type)
			return false;

        return switch (type) {
            case REWARDISSUANCE ->
                    getConnectedReward().getPrevRewardHash().equals(other.getConnectedReward().getPrevRewardHash());
            case TOKENISSUANCE -> getConnectedToken().getTokenid().equals(other.getConnectedToken().getTokenid())
                    && getConnectedToken().getTokenindex() == other.getConnectedToken().getTokenindex();
            case TXOUT -> getConnectedOutpoint().getIndex() == other.getConnectedOutpoint().getIndex()
                    && getConnectedOutpoint().getHash().equals(other.getConnectedOutpoint().getHash());
            case DOMAINISSUANCE -> getConnectedDomainToken().getDomainNameBlockHash()
                    .equals(other.getConnectedDomainToken().getDomainNameBlockHash())
                    && getConnectedDomainToken().getTokenname().equals(other.getConnectedDomainToken().getTokenname())
                    && getConnectedDomainToken().getTokenindex() == other.getConnectedDomainToken().getTokenindex();
            case CONTRACTEXECUTE -> getConnectedContractExecute().getPrevblockhash()
                    .equals(other.getConnectedContractExecute().getPrevblockhash());
            case ORDEREXECUTE -> getConnectedOrderExecute().getPrevblockhash()
                    .equals(other.getConnectedOrderExecute().getPrevblockhash());
        };
	}

	@Override
	public int hashCode() {
        return switch (type) {
            case REWARDISSUANCE -> Objects.hashCode(type, getConnectedReward().getPrevRewardHash());
            case TOKENISSUANCE ->
                    Objects.hashCode(type, getConnectedToken().getTokenid(), getConnectedToken().getTokenindex());
            case TXOUT -> Objects.hashCode(type, getConnectedOutpoint().getIndex(), getConnectedOutpoint().getHash());
            case DOMAINISSUANCE -> Objects.hashCode(type, getConnectedDomainToken().getDomainNameBlockHash(),
                    getConnectedDomainToken().getTokenname(), getConnectedDomainToken().getTokenindex());
            case CONTRACTEXECUTE -> Objects.hashCode(type, getConnectedContractExecute().getPrevblockhash());
            case ORDEREXECUTE -> Objects.hashCode(type, getConnectedOrderExecute().getPrevblockhash());
        };
	}

	public enum ConflictType {
		TXOUT, TOKENISSUANCE, REWARDISSUANCE, DOMAINISSUANCE, CONTRACTEXECUTE, ORDEREXECUTE
	}

	public ConflictType getType() {
		return type;
	}

	public TransactionOutPoint getConnectedOutpoint() {
		return connectedOutpoint;
	}

	public RewardInfo getConnectedReward() {
		return connectedReward;
	}

	public Token getConnectedToken() {
		return connectedToken;
	}

	public Token getConnectedDomainToken() {
		return connectedDomainToken;
	}

	public ContractExecutionResult getConnectedContractExecute() {
		return connectedContractExecute;
	}
	public   OrderExecutionResult getConnectedOrderExecute() {
		return connectedOrderExecute;
	}

	@Override
	public String toString() {
		return "ConflictPoint [type=" + type + ", connectedOutpoint=" + connectedOutpoint + ", connectedReward="
				+ connectedReward + ", connectedToken=" + connectedToken + ", connectedDomainToken="
				+ connectedDomainToken + ", connectedContracExecute=" + connectedContractExecute + "]";
	}

}
