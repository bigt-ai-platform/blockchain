/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.params;

import net.bigtangle.core.StoreDomain;

/**
 * Every command a node can receive over the wire. Each constant declares which
 * store domain it needs ({@link #getDomain()}), so a layer-minimal node rejects
 * commands whose tables it does not provision instead of silently failing in
 * the store layer.
 */
public enum ReqCmd {
	// Block
	submitTransaction, submitTransactions, getBlockByHash, findBlockEvaluation, searchBlockByBlockHashs, batchBlock,
	// Bridge
	processPegIn, processPegOut,

	getTip, adjustHeight, findRetryBlocks,
	// Chain
	getChainNumber, getAllConfirmedReward, blocksFromChainLength,blocksFromNonChainHeight,
	// Token
	searchTokens, getTokenById, getTokenIndex, getTokenSignByAddress, searchExchangeTokens, searchWebTokens,

	getTokenSignByTokenid, signToken, getTokenSigns, getTokenPermissionedAddresses, getDomainNameBlockHash,
	// Block Order (order-matching tables)
	getOrders(StoreDomain.ORDER), getOrdersTicker(StoreDomain.ORDER),
	// Outputs
	getOutputByKey, getOutputs, getOutputsHistory, outputsOfTokenid, getBalances,getAccountBalances, getTransactionStatus, getTransactionsStatusByAddress,

	// payment
	launchPayMultiSign, payMultiSign,

	getPayMultiSignList, getPayMultiSignAddressList, payMultiSignDetails,
	 
	//user data
	getUserData, userDataList,  
	// subtangle
	regSubtangle, updateSubtangle, getSessionRandomNum,
	// permissioned
	addAccessGrant, deleteAccessGrant,
    // check point value
    getAnchors,
    // PoS
    submitAttestation, getAttestations, processWithdrawal, submitSlashingProof, requestValidatorExit,
    stakeDeposit, activateValidator, getValidators, getBaseFee, setValidatorKey, getValidatorKey,
    // NFT
    createNft, saveUserdata;

	private final StoreDomain domain;

	ReqCmd() {
		this.domain = StoreDomain.CORE;
	}

	ReqCmd(StoreDomain domain) {
		this.domain = domain;
	}

	/**
	 * The store domain this command requires. A node whose store domain does not
	 * satisfy it (see {@link StoreDomain#satisfies(StoreDomain)}) must reject the
	 * command at the API boundary.
	 */
	public StoreDomain getDomain() {
		return domain;
	}
}
