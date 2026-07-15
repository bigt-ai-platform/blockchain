/*******************************************************************************
 *  Copyright   2018  Inasset GmbH. 
 *  
 *******************************************************************************/
package net.bigtangle.params;

public enum ReqCmd {
	// Block
	saveBlock, submitTransaction, getBlockByHash, findBlockEvaluation, searchBlockByBlockHashs, batchBlock, blockEvaluationFromHashs,

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
    serverinfolist,
    getAnchors;
}
