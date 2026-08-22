/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.params;

public enum ReqCmd {
	// Block
	submitTransaction, submitTransactions, getBlockByHash, findBlockEvaluation, searchBlockByBlockHashs, batchBlock,
	// Bridge
	processPegIn, processPegOut,

	getTip, getTips, adjustHeight, findRetryBlocks,
	// Chain
	getChainNumber, getAllConfirmedReward, blocksFromChainLength,blocksFromNonChainHeight,
	// Mempool
	getPendingTransactions,
	// Token
	searchTokens, getTokenById, getTokenIndex, getTokenSignByAddress, searchExchangeTokens, searchWebTokens,

	getTokenSignByTokenid, signToken, getTokenSigns, getTokenPermissionedAddresses, getDomainNameBlockHash,
	// Block Order
	getOrders, getOrdersTicker,
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
}
