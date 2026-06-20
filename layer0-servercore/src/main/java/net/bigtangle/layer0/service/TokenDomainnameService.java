package net.bigtangle.layer0.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import net.bigtangle.core.ECKey;
import net.bigtangle.core.MultiSignAddress;
import net.bigtangle.core.PermissionDomainname;
import net.bigtangle.core.Sha256Hash;
import net.bigtangle.core.Token;
import net.bigtangle.core.UtilGeneseBlock;
import net.bigtangle.core.Utils;
import net.bigtangle.exception.BlockStoreException;
import net.bigtangle.params.NetworkParameters;
import net.bigtangle.response.AbstractResponse;
import net.bigtangle.response.GetDomainTokenResponse;
import net.bigtangle.response.PermissionedAddressesResponse;
import net.bigtangle.store.BlockStoreInterface;
import net.bigtangle.utils.DomainnameUtil;

@Service
public class TokenDomainnameService {

   
 
    @Autowired
    private NetworkParameters networkParameters;

    /**
     * query token type is domainname
     *
     */

    public PermissionedAddressesResponse queryDomainnameTokenPermissionedAddresses(String domainNameBlockHash,BlockStoreInterface store)
            throws BlockStoreException {
        if (domainNameBlockHash.equals(UtilGeneseBlock.createGenesis(networkParameters ) .getHashAsString())) {
            List<MultiSignAddress> multiSignAddresses = new ArrayList<>();
            for (PermissionDomainname permissionDomainname : networkParameters.getPermissionDomainnameList()) {
                ECKey ecKey = permissionDomainname.getOutKey();
                multiSignAddresses.add(new MultiSignAddress("", "", ecKey.getPublicKeyAsHex()));
            }
            return (PermissionedAddressesResponse) PermissionedAddressesResponse
                    .create("", false, multiSignAddresses);
        } else {
            Token token =  store.getTokenByBlockHash(Sha256Hash.wrap(domainNameBlockHash));
            final String domainName = token.getTokenname();

            List<MultiSignAddress> multiSignAddresses = this
                    .queryDomainnameTokenMultiSignAddresses(token.getBlockHash(),store);

            return (PermissionedAddressesResponse) PermissionedAddressesResponse
                    .create(domainName, false, multiSignAddresses);
        }
    }

    /**
     * get domainname token multi sign address
     *
     */
    public List<MultiSignAddress> queryDomainnameTokenMultiSignAddresses(Sha256Hash domainNameBlockHash,BlockStoreInterface store)
            throws BlockStoreException {
        if (domainNameBlockHash.equals(UtilGeneseBlock.createGenesis(networkParameters ).getHash())) {
            List<MultiSignAddress> multiSignAddresses = new ArrayList<>();
            for (PermissionDomainname permissionDomainname : networkParameters.getPermissionDomainnameList()) {
                ECKey ecKey = permissionDomainname.getOutKey();
                multiSignAddresses.add(new MultiSignAddress("", "", ecKey.getPublicKeyAsHex()));
            }
            return multiSignAddresses;
        } else {
            Token token = store.queryDomainnameToken(domainNameBlockHash);
            if (token == null)
                throw new BlockStoreException("token not found");

            final String tokenid = token.getTokenid();
            return store
                    .getMultiSignAddressListByTokenidAndBlockHashHex(tokenid, token.getBlockHash());
        }
    }

    public AbstractResponse queryParentDomainnameBlockHash(String domainname,BlockStoreInterface store) throws BlockStoreException {
        domainname = DomainnameUtil.matchParentDomainname(domainname);
        return queryDomainnameBlockHash(domainname,store);
    }

    public AbstractResponse queryDomainnameBlockHash(String domainname,BlockStoreInterface store) throws BlockStoreException {
        AbstractResponse response;

        if (Utils.isBlank(domainname)) {

            response = GetDomainTokenResponse.createGetDomainBlockHashResponse(Token.genesisToken(networkParameters));
        } else {
            Token token =  store.getTokensByDomainname(domainname);
            if (token == null) {
                throw new BlockStoreException("token domain name not found : " + domainname);
            }

            response = GetDomainTokenResponse.createGetDomainBlockHashResponse(token);
        }
        return response;
    }
}
