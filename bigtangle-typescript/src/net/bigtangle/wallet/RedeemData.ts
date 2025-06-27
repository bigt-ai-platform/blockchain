import { ECKey } from '../core/ECKey';
import { Script } from '../script/Script';

/**
 * This class aggregates data required to spend transaction output.
 *
 * For pay-to-address and pay-to-pubkey transactions it will have only a single key and CHECKSIG program as redeemScript.
 * For multisignature transactions there will be multiple keys one of which will be a full key and the rest are watch only,
 * redeem script will be a CHECKMULTISIG program. Keys will be sorted in the same order they appear in
 * a program (lexicographical order).
 */
export class RedeemData {
    public readonly redeemScript: Script;
    public readonly keys: ECKey[];

    private constructor(keys: ECKey[], redeemScript: Script) {
        this.redeemScript = redeemScript;
        // Assuming ECKey.PUBKEY_COMPARATOR exists and sorts correctly
        const sortedKeys = [...keys].sort((a, b) => {
            // Implement ECKey.PUBKEY_COMPARATOR logic here
            // For now, a simple comparison based on public key bytes
            const pubA = a.getPubKeyBytes();
            const pubB = b.getPubKeyBytes();
            for (let i = 0; i < Math.min(pubA.length, pubB.length); i++) {
                if (pubA[i] !== pubB[i]) {
                    return pubA[i] - pubB[i];
                }
            }
            return pubA.length - pubB.length;
        });
        this.keys = sortedKeys;
    }

    public static of(keys: ECKey[], redeemScript: Script): RedeemData;
    public static of(key: ECKey | null, program: Script): RedeemData | null;
    public static of(...args: any[]): RedeemData | null {
        if (Array.isArray(args[0])) {
            // of(keys: ECKey[], redeemScript: Script)
            return new RedeemData(args[0], args[1]);
        } else if (args.length === 2) {
            // of(key: ECKey | null, program: Script)
            const key = args[0];
            const program = args[1];
            if (!program.isSentToAddress() && !program.isSentToRawPubKey()) {
                throw new Error("Program is not pay-to-address or pay-to-pubkey");
            }
            return key !== null ? new RedeemData([key], program) : null;
        }
        throw new Error("Invalid arguments for RedeemData.of");
    }

    /**
     * Returns the first key that has private bytes
     */
    public getFullKey(): ECKey | null {
        for (const key of this.keys) {
            if (key.getPrivKeyBytes() !== null) {
                return key;
            }
        }
        return null;
    }
}
