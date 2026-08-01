SELECT * FROM blocks WHERE chainlength < 0 ORDER BY height DESC;

SELECT * FROM mcmc;

SELECT * FROM contractevent;

SELECT * 
FROM contractevent a
JOIN outputs b 
  ON a.blockhash = b.blockhash
WHERE a.confirmed = false;

SELECT *
FROM contractevent a
JOIN outputs b
  ON a.blockhash = b.blockhash
WHERE a.spent = false
  AND a.confirmed = true;

SELECT * FROM contractresult ORDER BY rewardchainlength DESC;

SELECT * FROM contractresult ORDER BY inserttime DESC;

SELECT *
FROM contractresult
WHERE chainlength < 0
  AND confirmed = true;

SELECT *
FROM contractevent a
JOIN blocks b
  ON a.blockhash = b.hash
WHERE a.confirmed = 1
  AND a.spent = 0;

SELECT *
FROM contractevent a
JOIN blocks b
  ON a.blockhash = b.hash;

SELECT *
FROM blocks b
WHERE b.chainlength > 0;

SELECT *
FROM contractevent a
WHERE a.spent = 0
  AND confirmed = true;

SELECT *
FROM contractevent a
WHERE a.spent = 1
  AND confirmed = false;

SELECT *
FROM contractevent a
JOIN outputs b
  ON a.blockhash = b.blockhash
WHERE a.spent = true
  AND b.spent = false;

SELECT *
FROM contractevent a
JOIN contractresult b
  ON a.collectinghash = b.blockhash
WHERE a.confirmed = 1
  AND a.spent = 0;

SELECT *
FROM contractevent a
JOIN contractresult b
  ON a.collectinghash = b.blockhash
WHERE a.collectinghash = 0x00911194d5d0db7cc4c2098c671434496ee81da207e4d67ee292d9e920e5a926;

SELECT *
FROM contractevent a
JOIN contractresult b
  ON a.collectinghash = b.blockhash
WHERE a.confirmed = true
  AND a.spent = false;

SELECT *
FROM contractevent a
JOIN contractresult b
  ON a.collectinghash = b.blockhash
WHERE a.collectinghash = 0x0000000000000000000000000000000000000000000000000000000000000000
ORDER BY collectinghash;

SELECT *
FROM contractevent
ORDER BY spenderblockhash, collectinghash;

SELECT *
FROM contractevent
ORDER BY collectinghash, blockhash;

SELECT *
FROM contractresult b
JOIN mcmc m
  ON m.hash = b.blockhash;

SELECT *
FROM blocks b
JOIN mcmc m
  ON m.hash = b.hash
WHERE b.chainlength < 0;

SELECT *
FROM contractresult a
JOIN blocks b
  ON a.blockhash = b.hash
JOIN mcmc m
  ON m.hash = b.hash
WHERE b.chainlength IS NOT NULL;

SELECT *
FROM orders
WHERE spent = false
  AND confirmed = true
ORDER BY blockhash;

SELECT * FROM orderresult ORDER BY inserttime DESC;

SELECT *
FROM orderresult
JOIN blocks
  ON orderresult.blockhash = blocks.hash
WHERE chainlength > 0;

SELECT *
FROM orders a
JOIN blocks b
  ON a.blockhash = b.hash;

SELECT *
FROM orders a
JOIN outputs b
  ON a.blockhash = b.blockhash
WHERE a.spent = false
  AND a.confirmed = true;

SELECT *
FROM orders a
JOIN orderresult b
  ON a.collectinghash = b.blockhash
WHERE b.confirmed != a.confirmed;

SELECT *
FROM outputs
WHERE confirmed = true
  AND spent = false
  AND tokenid != 'bc'
  AND toaddress = '154AxPN4kEUYNY5Ubt8yCssoR7Zgppw8y4';

SELECT COUNT(*) FROM blocks;

SELECT COUNT(*) FROM blocks WHERE chainlength > 9;

SELECT COUNT(*)
FROM unsolidblocks
WHERE inserttime < 1515432033;

SELECT *
FROM unsolidblocks
ORDER BY inserttime ASC;

SELECT * FROM txreward ORDER BY chainlength DESC;

SELECT block, height, blocktype
FROM blocks
WHERE chainlength > 11670;

SELECT *
FROM blocks
WHERE height < 750;

SELECT *
FROM blocks
ORDER BY height DESC
LIMIT 500;

SELECT *
FROM blocks
WHERE height < 4000
ORDER BY height DESC
LIMIT 500;

SELECT *
FROM blocks
JOIN unsolidblocks
  ON blocks.hash = unsolidblocks.hash
ORDER BY blocks.height ASC
LIMIT 100;

SELECT *
FROM blocks
ORDER BY inserttime DESC
LIMIT 1000;

SELECT *
FROM blocks
WHERE confirmed = 1
ORDER BY height DESC
LIMIT 500;

SELECT *
FROM orders
WHERE spent = 0
  AND confirmed = 1;

SELECT * FROM mcmc;

SELECT * FROM orderresult;

SELECT *
FROM blocks
JOIN mcmc
  ON blocks.hash = mcmc.hash
WHERE solid = 2
  AND chainlength = -1
  AND confirmed = false
  AND mcmc.rating >= 5;

SELECT *
FROM info.blocks
WHERE hash = 0x0017a6120fecbf4eb1731def0dd0660c9dc350fed488f104aa3a4dbef27ea9a3;

SELECT *
FROM outputs
WHERE blockhash = 0x0009a0d2309039774e93ab211205a865737ab7f82bd235b6c63519b05d47bf05;

SELECT *
FROM outputs
WHERE hash = 0x0aef356676f4ba274b40c52020e04c9b9e9e4fba1b1da51962278a13ec4d8897;

SELECT *
FROM blocks
WHERE blocktype = 2
ORDER BY height DESC
LIMIT 500;

SELECT *
FROM blocks
WHERE chainlength = 339
  AND blocktype = 3;

SELECT * FROM blocks ORDER BY chainlength;

SELECT * FROM blocks WHERE chainlength < 0;

SELECT * FROM blocks WHERE chainlength > 0;

SELECT MAX(height) FROM blocks;

SELECT COUNT(*)
FROM blocks
JOIN mcmc
  ON blocks.hash = mcmc.hash
WHERE solid = 2
  AND chainlength = -1
  AND confirmed = false;

SELECT *
FROM blocks
JOIN mcmc
  ON blocks.hash = mcmc.hash
WHERE solid = 2
  AND chainlength = -1
  AND confirmed = false;

SELECT *
FROM blocks
WHERE chainlength = -1
  AND solid = 2;

SELECT blockhash, txreward.confirmed, txreward.spent, txreward.spenderblockhash, 
       txreward.prevblockhash, txreward.difficulty, txreward.chainlength
FROM txreward
ORDER BY txreward.chainlength DESC
JOIN blocks
  ON blocks.hash = txreward.blockhash
WHERE chainlength = 339;

DELETE FROM txreward WHERE chainlength = 197088;

SELECT COUNT(*) FROM txreward;

SELECT *
FROM txreward
ORDER BY chainlength DESC
LIMIT 10;

SELECT *
FROM blocks
WHERE hash = 0x0000000c1c45469ab3bcea91afbf582027800e7280c2dd90e05b5249296ed7f28;

SELECT blockhash, txreward.confirmed, txreward.spent, txreward.spenderblockhash,
       txreward.prevblockhash, txreward.difficulty, txreward.chainlength
FROM txreward
JOIN blocks
  ON blocks.hash = txreward.blockhash
WHERE blocks.solid >= 1
  AND chainlength = (
    SELECT MAX(chainlength)
    FROM txreward
    JOIN blocks
      ON blocks.hash = txreward.blockhash
    WHERE blocks.solid >= 1
  );

SELECT missingdependency, height
FROM unsolidblocks
WHERE directlymissing = 1;

SELECT * FROM blocks WHERE hash = 373;

SELECT *
FROM blocks
WHERE hash = 0x0138b2c2db2e4e6ba6ad5d1797b07632bc91b87a8ef07e8a67e097aef8dc3e44;

SELECT *
FROM blocks
JOIN outputs
  ON blocks.hash = outputs.blockhash
WHERE blocks.hash = 0x000039b6b149700642826b603800cbbbbe73a8b9af24980b3fb9154c2a0119e8;

SELECT *
FROM blocks
WHERE hash = 0x00000075491105d21a1654d8f4566dd819c111b100818c07b66a3ae8a8b4de76;

SELECT * FROM blocks WHERE blocktype = 12;

SELECT * FROM blocks WHERE confirmed = 1;

SELECT * FROM blocks WHERE chainlength = 36;

SELECT *
FROM blocks
JOIN outputs
  ON blocks.hash = outputs.blockhash
WHERE blocks.blocktype = 12
  AND tokenid = '02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a';

SELECT COUNT(*)
FROM outputs
WHERE spent = 1
  AND confirmed = 1;

SELECT *
FROM outputs
WHERE spent = 0
  AND confirmed = 1;

SELECT *
FROM orders
WHERE spent = 0
  AND confirmed = 1;

SELECT * FROM orderresult ORDER BY rewardchainlength DESC;

SELECT * FROM ordercancel;

UPDATE blocks SET chainlength = 0 WHERE height = 0;

SELECT COUNT(*)
FROM outputs
WHERE confirmed = 1
  AND spent = 0
  AND tokenid = '02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a';

SELECT *
FROM orders
WHERE confirmed = 1
  AND spent = 0
  AND offertokenid = '02a717921ede2c066a4da05b9cdce203f1002b7e2abeee7546194498ef2fa9b13a';

SELECT *
FROM outputs
WHERE blockhash = 0x000011bdbd7f6ad529d8e1d5ef30ea8afd82913ef7f7099e3f1aa2ebc50b1da3;

SELECT *
FROM txreward
JOIN orders
  ON txreward.blockhash = orders.collectinghash
ORDER BY toheight DESC;

SELECT COUNT(*) FROM txreward;

SELECT *
FROM blocks
WHERE blocktype > 9
  AND chainlength = 37;

-- OUTPUTS
SELECT *
FROM outputs
WHERE tokenid != 'bc'
  AND coinbase = true;

SELECT * FROM tokens;

SELECT *
FROM orders
WHERE orderbasetoken != 'bc'
LIMIT 1;

SELECT *
FROM orders
WHERE collectinghash = 0x000000b95317048c9a90e779769e5c15bcb8757a7379917ab6c9c09b9e5337a2;

SELECT *
FROM orders
WHERE blockhash = 0x000005964AF7DB191AEB73BEDD6FA739324D338F31D21A7F5FA03F2130C392BC;

SELECT *
FROM ordercancel
WHERE confirmed = true
  AND spent = false;

SELECT * FROM contractresult;

SELECT MAX(chainlength)
FROM orders
JOIN blocks
  ON blockhash = 0x000005964AF7DB191AEB73BEDD6FA739324D338F31D21A7F5FA03F2130C392BC
  AND collectinghash = hash;

SELECT * FROM multisign;

SELECT COUNT(*)
FROM orders
WHERE collectinghash = 0x0000000000000000000000000000000000000000000000000000000000000000;

SELECT * FROM txreward ORDER BY chainlength DESC;

SELECT COUNT(DISTINCT difficulty) FROM txreward;

DELETE FROM txreward WHERE difficulty <= 2490057664;

DELETE FROM txreward WHERE chainlength > 91100;

SELECT * FROM matching;

SELECT * FROM multisignaddress;

SELECT * FROM multisign;

SELECT COUNT(*) FROM chainblockqueue;

SELECT *
FROM chainblockqueue
WHERE orphan = true;

SELECT * FROM tips;

SELECT COUNT(*) FROM tips;

SELECT blocks.hash, rating, depth, cumulativeweight, height, chainlength, chainlengthlastupdate,
       inserttime, block, solid, confirmed
FROM blocks
JOIN tips
  ON tips.hash = blocks.hash
WHERE chainlength < 0;

SELECT blocks.hash, rating, depth, cumulativeweight, height, chainlength, chainlengthlastupdate,
       inserttime, block, solid, confirmed
FROM blocks
ORDER BY inserttime DESC
LIMIT 50;

SELECT * FROM account;

-- HELPER
SELECT * FROM tokenserial;

SELECT COUNT(*)
FROM outputs
WHERE fromaddress != '';

SELECT *
FROM outputs
WHERE toaddress = '14Kt2zgLFL3DSi4eHofBjZisQWogBRnZhN'
  AND fromaddress = ''
  AND coinbase = false;

SELECT * FROM blockevaluation;

SELECT * FROM multisign;

SELECT * FROM multisignaddress;

SELECT * FROM exchange;

SELECT * FROM ordermatch;

SELECT * FROM orderpublish;

SELECT * FROM orders;

SELECT * FROM blocks;

SELECT blockhash
FROM blocks
JOIN orders
  ON orders.blockhash = blocks.hash
WHERE blocks.height <= 99999999
  AND blocks.chainlength = 1
  AND orders.spent = 0;

SELECT blockhash, height
FROM blocks
JOIN orders
  ON orders.blockhash = blocks.hash
WHERE orders.confirmed = 0
  AND orders.spent = 0
  AND orders.collectinghash = '0x0000000000000000000000000000000000000000000000000000000000000000';

SELECT blockhash, height
FROM blocks
JOIN orders
  ON orders.blockhash = blocks.hash
WHERE orders.collectinghash = '0x0000000000000000000000000000000000000000000000000000000000000000';

SELECT * FROM vm_deposit;

SELECT *
FROM tokens
WHERE tokenid = '0201ad11827c4ed13a079ecca5e0506757065278bfda325533379fdc29ddb905f0';

SELECT * FROM wechatinvite;

DELETE FROM vm_deposit WHERE amount <= 0;

SELECT userid, useraccount, amount, d.status, pubkey
FROM vm_deposit d
JOIN Account a
  ON d.userid = a.id
JOIN wechatinvite w
  ON a.email = w.wechatId
  AND w.pubkey IS NOT NULL;

SELECT COUNT(*)
FROM outputs
WHERE confirmed = 1
  AND spent = 0
  AND tokenid = 'bc';

SELECT COUNT(*)
FROM outputs
WHERE confirmed = 1
  AND spent = 0
  AND tokenid = 'bc'
GROUP BY toaddress;

SELECT COUNT(*)
FROM outputs
WHERE confirmed = 1
  AND spent = 0
  AND tokenid = '03bed6e75294e48556d8bb2a53caf6f940b70df95760ee4c9772681bbf90df85ba';

SELECT blockhash, txreward.confirmed, txreward.spent, txreward.spenderblockhash,
       txreward.prevblockhash, txreward.difficulty, txreward.chainlength
FROM txreward
WHERE chainlength = 446310;

SELECT * FROM userdata;

ALTER USER 'root' IDENTIFIED WITH mysql_native_password BY 'test1234';
FLUSH PRIVILEGES;
USE info;
