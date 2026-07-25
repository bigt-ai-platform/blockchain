/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.params;

public enum ReqCmd {
	// Block
	submitTransaction, submitTransactions, getBlockByHash, findBlockEvaluation, searchBlockByBlockHashs, batchBlock, blockEvaluationFromHashs,
	// Bridge
	processPegIn, processPegOut,

	getTip, adjustHeight, findRetryBlocks,
	// Chain
	getChainNumber, getAllConfirmedReward, blocksFromChainLength,blocksFromNonChainHeight,
	// Token
	searchTokens, getTokenById, getTokenIndex, getTokenSignByAddress, searchExchangeTokens, searchTokenDomain,searchWebTokens,searchContractTokens,

	getTokenSignByTokenid, signToken, getTokenSigns, getTokenPermissionedAddresses, getDomainNameBlockHash,
	// Block Order
	getOrders, getOrdersTicker,
	// Outputs
	getOutputByKey, getOutputs, getOutputsHistory, outputsOfTokenid, getBalances,outputsByBlockhash,getAccountBalances,

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
    getCheckPoint,
    getAnchors,
    // PoS
    submitAttestation, getAttestations, processWithdrawal, submitSlashingProof,
    stakeDeposit, activateValidator, getValidators, getBaseFee, setValidatorKey, getValidatorKey,
    // NFT
    createNft, saveUserdata, uploadFile;
}
