
import { Buffer } from 'buffer';
import { ScriptBuilder } from '../../src/net/bigtangle/script/ScriptBuilder';
import { ScriptChunk } from '../../src/net/bigtangle/script/ScriptChunk';
import { Script } from '../../src/net/bigtangle/script/Script';
import {
    OP_PUSHDATA1,
    OP_PUSHDATA2,
    OP_PUSHDATA4,
} from '../../src/net/bigtangle/script/ScriptOpCodes';

describe('ScriptChunkTest', () => {
    test('testShortestPossibleDataPush', () => {
        expect(
            new ScriptBuilder().data(Buffer.from([])).build().getChunks()[0]
                .isShortestPossiblePushData(),
        ).toBe(true);

        for (let i = -1; i < 127; i++) {
            expect(
                new ScriptBuilder()
                    .data(Buffer.from([i]))
                    .build()
                    .getChunks()[0]
                    .isShortestPossiblePushData(),
            ).toBe(true);
        }

        for (let len = 2; len < Script.MAX_SCRIPT_ELEMENT_SIZE; len++) {
            expect(
                new ScriptBuilder()
                    .data(Buffer.alloc(len))
                    .build()
                    .getChunks()[0]
                    .isShortestPossiblePushData(),
            ).toBe(true);
        }

        // non-standard chunks
        for (let i = 1; i <= 16; i++) {
            expect(
                new ScriptChunk(1, Buffer.from([i])).isShortestPossiblePushData(),
            ).toBe(false);
        }
        expect(
            new ScriptChunk(
                OP_PUSHDATA1,
                Buffer.alloc(75),
            ).isShortestPossiblePushData(),
        ).toBe(false);
        expect(
            new ScriptChunk(
                OP_PUSHDATA2,
                Buffer.alloc(255),
            ).isShortestPossiblePushData(),
        ).toBe(false);
        expect(
            new ScriptChunk(
                OP_PUSHDATA4,
                Buffer.alloc(65535),
            ).isShortestPossiblePushData(),
        ).toBe(false);
    });
});
