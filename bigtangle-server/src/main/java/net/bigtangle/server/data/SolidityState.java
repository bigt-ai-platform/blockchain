package net.bigtangle.server.data;

import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.TransactionOutPoint;

public class SolidityState {

    public enum State {
        Success, Invalid, MissingPredecessor, MissingCalculation
    }

    private static final SolidityState successState = new SolidityState(State.Success, null, false);
    private static final SolidityState failState = new SolidityState(State.Invalid, null, false);

    private final State state;
    private final Sha256Hash missingDependency;
    private final boolean directlyMissing;

    public SolidityState(State state, Sha256Hash missingDependency, boolean directlyMissing) {
        super();
        this.state = state;
        this.missingDependency = missingDependency;
        this.directlyMissing = directlyMissing;
    }

    public State getState() {
        return state;
    }

    public Sha256Hash getMissingDependency() {
        return missingDependency;
    }

    public boolean isSuccessState() {
        return this.state != successState.state;
    }

    public boolean isFailState() {
        return this.state == failState.state;
    }

    public static SolidityState getSuccessState() {
        return successState;
    }

    public static SolidityState getFailState() {
        return failState;
    }

    public boolean isDirectlyMissing() {
        return directlyMissing;
    }

    public static SolidityState fromMissingCalculation(Sha256Hash prevBlockHash) {
        return new SolidityState(State.MissingCalculation, prevBlockHash, false);
    }

    public static SolidityState from(Sha256Hash prevBlockHash, boolean directlyMissing) {
        return new SolidityState(State.MissingPredecessor, prevBlockHash, directlyMissing);
    }

    public static SolidityState from(TransactionOutPoint outpoint, boolean directlyMissing) {
        return new SolidityState(State.MissingPredecessor, outpoint.getBlockHash(), directlyMissing);
    }
    
     public static SolidityState fromReferenced(Sha256Hash prevBlockHash, boolean directlyMissing) {
        return new SolidityState(State.MissingPredecessor, prevBlockHash, directlyMissing);
    }

       public static SolidityState fromPrevReward(Sha256Hash prevBlockHash, boolean directlyMissing) {
        return new SolidityState(State.MissingPredecessor, prevBlockHash, directlyMissing);
    }

	@Override
	public String toString() {
		return "SolidityState [state=" + state + ", missingDependency=" + missingDependency + ", directlyMissing="
				+ directlyMissing + "]";
	}

}
