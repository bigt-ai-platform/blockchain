
import { Buffer } from 'buffer';
import { TestParams } from '../../src/net/bigtangle/params/TestParams';
import { Block } from '../../src/net/bigtangle/core/Block';
import { Utils } from '../../src/net/bigtangle/utils/Utils';
import { MainNetParams } from '../../src/net/bigtangle/params/MainNetParams';
import { Gzip } from '../../src/net/bigtangle/utils/Gzip';
import { NetworkParameters } from '../../src/net/bigtangle/core/NetworkParameters';
import { Address } from '../../src/net/bigtangle/core/Address';

describe('UtilsTest', () => {
    test('testReverseBytes', () => {
        expect(
            Buffer.compare(
                Buffer.from([1, 2, 3, 4, 5]),
                Utils.reverseBytes(Buffer.from([5, 4, 3, 2, 1])),
            ),
        ).toBe(0);
    });

    test('testReverseDwordBytes', () => {
        expect(
            Buffer.compare(
                Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]),
                Utils.reverseDwordBytes(
                    Buffer.from([4, 3, 2, 1, 8, 7, 6, 5]),
                    -1,
                ),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                Buffer.from([1, 2, 3, 4]),
                Utils.reverseDwordBytes(
                    Buffer.from([4, 3, 2, 1, 8, 7, 6, 5]),
                    4,
                ),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                Buffer.from([]),
                Utils.reverseDwordBytes(
                    Buffer.from([4, 3, 2, 1, 8, 7, 6, 5]),
                    0,
                ),
            ),
        ).toBe(0);
        expect(
            Buffer.compare(
                Buffer.from([]),
                Utils.reverseDwordBytes(Buffer.from([]), 0),
            ),
        ).toBe(0);
    });

    test('testMaxOfMostFreq', () => {
        expect(Utils.maxOfMostFreq()).toBe(0);
        expect(Utils.maxOfMostFreq(0, 0, 1)).toBe(0);
        expect(Utils.maxOfMostFreq(1, 1, 2, 2)).toBe(2);
        expect(Utils.maxOfMostFreq(1, 1, 2, 2, 1)).toBe(1);
        expect(Utils.maxOfMostFreq(-1, -1, 2, 2, -1)).toBe(-1);
    });

    test('compactEncoding', () => {
        expect(Utils.decodeCompactBits(0x05123456)).toBe(BigInt('0x1234560000'));
        expect(Utils.decodeCompactBits(0x0600c0de)).toBe(BigInt('0xc0de000000'));
        expect(Utils.encodeCompactBits(BigInt('0x1234560000'))).toBe(0x05123456);
        expect(Utils.encodeCompactBits(BigInt('0xc0de000000'))).toBe(0x0600c0de);
    });

    test('dateTimeFormat', () => {
        expect(Utils.dateTimeFormat(1416135273781)).toBe('2014-11-16T10:54:33Z');
        expect(Utils.dateTimeFormat(new Date(1416135273781))).toBe(
            '2014-11-16T10:54:33Z',
        );
    });

    test('gzip', async () => {
        const b = Buffer.from('Hallo', 'utf-8');
        const compressed = await Gzip.compress(b);
        const decompressed = await Gzip.decompressOut(compressed);
        expect(Buffer.compare(decompressed, b)).toBe(0);
    });
});

export class UtilsTest {
    public static createBlock(
        params: NetworkParameters,
        prevBlock: Block,
        branchBlock: Block,
    ): Block {
        return UtilsTest.createNextBlock(
            prevBlock,
            branchBlock,
            NetworkParameters.BLOCK_VERSION_GENESIS,
            Address.fromBase58(
                params,
                '1Kbm8rqjcX6j5oLbq9J8FapksdvrfGUA88',
            ).getHash160(),
        );
    }

    /**
     * Returns a solved, valid empty block that builds on top of this one and
     * the specified other Block.
     */
    public static createNextBlock(
        prevBlock: Block,
        branchBlock: Block,
        version: number,
        mineraddress: Buffer,
    ): Block {
        const b = new Block(prevBlock.getParams(), version);

        b.setMinerAddress(mineraddress);
        b.setPrevBlockHash(prevBlock.getHash());
        b.setPrevBranchBlockHash(branchBlock.getHash());

        // Set difficulty according to previous consensus
        // only BLOCKTYPE_REWARD and BLOCKTYPE_INITIAL should overwrite this
        b.setLastMiningRewardBlock(
            Math.max(
                prevBlock.getLastMiningRewardBlock(),
                branchBlock.getLastMiningRewardBlock(),
            ),
        );
        b.setDifficultyTarget(
            prevBlock.getLastMiningRewardBlock() >=
                branchBlock.getLastMiningRewardBlock()
                ? prevBlock.getDifficultyTarget()
                : branchBlock.getDifficultyTarget(),
        );

        b.setHeight(Math.max(prevBlock.getHeight(), branchBlock.getHeight()) + 1);

        // Don't let timestamp go backwards
        const currTime = Math.floor(Date.now() / 1000);
        const minTime = Math.max(currTime, branchBlock.getTimeSeconds());
        if (currTime >= minTime) b.setTime(currTime + 1);
        else b.setTime(minTime);
        b.solve();
        try {
            b.verifyHeader();
        } catch (e) {
            throw new Error(e); // Cannot happen.
        }
        if (b.getVersion() !== version) {
            throw new Error();
        }
        return b;
    }
}
