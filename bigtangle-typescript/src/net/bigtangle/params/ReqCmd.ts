// Enum for request commands, adapted from Java: net.bigtangle.params.ReqCmd

export enum ReqCmd {
    // Block
    saveBlock = 'saveBlock',
    getBlockByHash = 'getBlockByHash',
    findBlockEvaluation = 'findBlockEvaluation',
    searchBlockByBlockHashs = 'searchBlockByBlockHashs',
    batchBlock = 'batchBlock',
    blockEvaluationFromHashs = 'blockEvaluationFromHashs',
    getTip = 'getTip',
    adjustHeight = 'adjustHeight',
    findRetryBlocks = 'findRetryBlocks',
    // Chain
    getChainNumber = 'getChainNumber',
    getAllConfirmedReward = 'getAllConfirmedReward',
    blocksFromChainLength = 'blocksFromChainLength',
    blocksFromNonChainHeight = 'blocksFromNonChainHeight',
    // Token
    searchTokens = 'searchTokens',
    getTokenById = 'getTokenById',
    getTokenIndex = 'getTokenIndex',
    getTokenSignByAddress = 'getTokenSignByAddress',
    searchExchangeTokens = 'searchExchangeTokens',
    searchTokenDomain = 'searchTokenDomain',
    searchWebTokens = 'searchWebTokens',
    searchContractTokens = 'searchContractTokens',
    getTokenSignByTokenid = 'getTokenSignByTokenid',
    signToken = 'signToken',
    getTokenSigns = 'getTokenSigns',
    getTokenPermissionedAddresses = 'getTokenPermissionedAddresses',
    getDomainNameBlockHash = 'getDomainNameBlockHash',
    // Block Order
    getOrders = 'getOrders',
    getOrdersTicker = 'getOrdersTicker',
    // Outputs
    getOutputByKey = 'getOutputByKey',
    getOutputs = 'getOutputs',
    getOutputsHistory = 'getOutputsHistory',
    outputsOfTokenid = 'outputsOfTokenid',
    getBalances = 'getBalances',
    outputsByBlockhash = 'outputsByBlockhash',
    getAccountBalances = 'getAccountBalances',
    // payment
    launchPayMultiSign = 'launchPayMultiSign',
    payMultiSign = 'payMultiSign',
    getPayMultiSignList = 'getPayMultiSignList',
    getPayMultiSignAddressList = 'getPayMultiSignAddressList',
    payMultiSignDetails = 'payMultiSignDetails',
    // user data
    getUserData = 'getUserData',
    userDataList = 'userDataList',
    // subtangle
    regSubtangle = 'regSubtangle',
    updateSubtangle = 'updateSubtangle',
    getSessionRandomNum = 'getSessionRandomNum',
    // permissioned
    addAccessGrant = 'addAccessGrant',
    deleteAccessGrant = 'deleteAccessGrant',
    // check point value
    getCheckPoint = 'getCheckPoint',
    serverinfolist = 'serverinfolist'
}
