
import { Buffer } from 'buffer';
import { ProbabilityBlock } from '../../src/net/bigtangle/utils/ProbabilityBlock';

describe('ProbabilityTest', () => {
    test.skip('testSigns', () => {
        for (let z = 1; z < 300; z++) {
            console.log(
                z + '=' + ProbabilityBlock.attackerSuccessProbability(0.3, z),
            );
        }
    });

    test('testRandomness', () => {
        const seedrandom = require('seedrandom');
        const rng = seedrandom('31243565477');
        const randomWin = Math.floor(rng() * 10);

        for (let i = 0; i < 100; i++) {
            const rng2 = seedrandom('31243565477');
            expect(Math.floor(rng2() * 10)).toBe(randomWin);
        }
    });
});
