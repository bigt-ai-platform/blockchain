package net.bigtangle.core;

import java.util.ArrayList;
import java.util.List;

public class FilterToken {

    String tokenid;

    public static List<FilterToken> filter() {
        List<FilterToken> re = new ArrayList<>();
        // ada@etf.com
        FilterToken a = new FilterToken("023e509610e40aa6f95c0579d0ff4a9a488ab0d3077267c1d8f7bbc685041fd974");
        re.add(a);
        // fil@etf.com
        a = new FilterToken("025aef367beaeb5099c400ea19e5dcacb312966070a99ffe86af58dc4195448fef");
        re.add(a);
        //usdt@etf.com
        a = new FilterToken("02da3b615e2c79b1b54c6cf452d55cb6a234324c20ecb8ebc3384ab1dd0f7ed272");
        re.add(a); 
        
        //人民币@bigtangle
        a = new FilterToken("03bed6e75294e48556d8bb2a53caf6f940b70df95760ee4c9772681bbf90df85ba");
        re.add(a);
        //大网矿池基金@大网电商.com
        a = new FilterToken("039808fc31a9903c8144317ef26c70be4d9c2ed07134ae77e5398a212571ba8fe3");
        re.add(a);
     
        //shib@etf.com
        a = new FilterToken("0235e3b3f94a6ba3bb470fd7acc42eb1719db58e5273889611d05c8a2139eb8c9f");
        re.add(a);
        
        //trx@etf.com
        a = new FilterToken("034ae4c861cfdb4f30987c4e03d772fd114a37ca55852e684785a72a66f4ea26d7");
        re.add(a);
        
        //uni@etf.com
        a = new FilterToken("023ed1342df0225dab67070bc0362f4685518609cb7bcd8828285bc1354a45d5e7");
        re.add(a); 
        
        //apple@etf.com
        a = new FilterToken("02c4c33c1eeac2930800d0a7d7164b9c8075ad2b126c831d7a56cfa68e14977ecb");
        re.add(a);
         
        
        
        return re;
    }

    public static boolean filter(String tokenid) {
        for (FilterToken s : filter()) {
            if (s.getTokenid().equals(tokenid))
                return true;
        }
        return false;
    }

    public String getTokenid() {
        return tokenid;
    }

    public void setTokenid(String tokenid) {
        this.tokenid = tokenid;
    }

    public FilterToken(String tokenid) {
        super();
        this.tokenid = tokenid;
    }

}
