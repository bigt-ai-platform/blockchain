import { BigInteger } from 'big-integer';
import { ec } from 'elliptic';

// Initialize the elliptic curve
const secp256k1 = new ec('secp256k1');

// Export curve parameters
export const CURVE = {
    n: BigInteger(secp256k1.curve.n.toString()),
    g: secp256k1.curve.g,
    // Add other curve parameters as needed
};

// HALF_CURVE_ORDER = CURVE.n.shiftRight(1)
export const HALF_CURVE_ORDER = CURVE.n.shiftRight(1);
