
import { Buffer } from 'buffer';
import { ByteListResp } from './ByteListResp';
import { ByteResp } from './ByteResp';
import { Sha256Hash } from '../../src/net/bigtangle/core/Sha256Hash';
import { Coin } from '../../src/net/bigtangle/core/Coin';
import { NetworkParameters } from '../../src/net/bigtangle/core/NetworkParameters';
import { BlockMCMC } from '../../src/net/bigtangle/core/BlockMCMC';
import { Utils } from '../../src/net/bigtangle/utils/Utils';

describe('JsonMapperTest', () => {
    test('testJsonMapperByteList', () => {
        const byteListResp = new ByteListResp();
        const byteResp = new ByteResp();
        byteResp.setData(Buffer.from([0x00, 0x01, 0x02, 0x03]));
        byteListResp.getList().push(byteResp);
        const jsonStr = JSON.stringify(byteListResp);

        console.log(jsonStr);
        const parsedByteListResp = JSON.parse(jsonStr, (key, value) => {
            if (value && value.type === 'Buffer') {
                return Buffer.from(value.data);
            }
            return value;
        }) as ByteListResp;

        const parsedByteResp = parsedByteListResp.getList()[0];
        for (const b of parsedByteResp.getData()) {
            console.log(b.toString(16).padStart(2, '0'));
        }
    });

    test('testJsonMapperSha256Hash', () => {
        const rawHashBytes = Buffer.alloc(32);
        for (let i = 0; i < rawHashBytes.length; i++) {
            rawHashBytes[i] = Math.floor(Math.random() * 256);
        }
        let sha256Hash = Sha256Hash.wrap(rawHashBytes);
        console.log(Utils.HEX.encode(sha256Hash.getBytes()));
        let jsonStr = JSON.stringify(sha256Hash);

        sha256Hash = JSON.parse(jsonStr, (key, value) => {
            if (value && value.type === 'Buffer') {
                return Buffer.from(value.data);
            }
            return value;
        }) as Sha256Hash;
        console.log(Utils.HEX.encode(sha256Hash.getBytes()));

        let coin = Coin.valueOf(10000, NetworkParameters.BIGTANGLE_TOKENID);
        jsonStr = JSON.stringify(coin);

        console.log(jsonStr);

        coin = JSON.parse(jsonStr) as Coin;
        console.log(coin);
    });

    test('testJsonBlockMCMC', () => {
        const blockMCMC = BlockMCMC.defaultBlockMCMC(Sha256Hash.ZERO_HASH);

        const jsonStr = JSON.stringify(blockMCMC);

        console.log(jsonStr);
        JSON.parse(jsonStr);
    });
});
