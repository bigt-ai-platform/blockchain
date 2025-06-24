// push value
export const OP_0 = 0x00; // push empty vector
export const OP_FALSE = OP_0;
export const OP_PUSHDATA1 = 0x4c;
export const OP_PUSHDATA2 = 0x4d;
export const OP_PUSHDATA4 = 0x4e;
export const OP_1NEGATE = 0x4f;
export const OP_RESERVED = 0x50;
export const OP_1 = 0x51;
export const OP_TRUE = OP_1;
export const OP_2 = 0x52;
export const OP_3 = 0x53;
export const OP_4 = 0x54;
export const OP_5 = 0x55;
export const OP_6 = 0x56;
export const OP_7 = 0x57;
export const OP_8 = 0x58;
export const OP_9 = 0x59;
export const OP_10 = 0x5a;
export const OP_11 = 0x5b;
export const OP_12 = 0x5c;
export const OP_13 = 0x5d;
export const OP_14 = 0x5e;
export const OP_15 = 0x5f;
export const OP_16 = 0x60;

// control
export const OP_NOP = 0x61;
export const OP_VER = 0x62;
export const OP_IF = 0x63;
export const OP_NOTIF = 0x64;
export const OP_VERIF = 0x65;
export const OP_VERNOTIF = 0x66;
export const OP_ELSE = 0x67;
export const OP_ENDIF = 0x68;
export const OP_VERIFY = 0x69;
export const OP_RETURN = 0x6a;

// stack ops
export const OP_TOALTSTACK = 0x6b;
export const OP_FROMALTSTACK = 0x6c;
export const OP_2DROP = 0x6d;
export const OP_2DUP = 0x6e;
export const OP_3DUP = 0x6f;
export const OP_2OVER = 0x70;
export const OP_2ROT = 0x71;
export const OP_2SWAP = 0x72;
export const OP_IFDUP = 0x73;
export const OP_DEPTH = 0x74;
export const OP_DROP = 0x75;
export const OP_DUP = 0x76;
export const OP_NIP = 0x77;
export const OP_OVER = 0x78;
export const OP_PICK = 0x79;
export const OP_ROLL = 0x7a;
export const OP_ROT = 0x7b;
export const OP_SWAP = 0x7c;
export const OP_TUCK = 0x7d;

// splice ops
export const OP_CAT = 0x7e;
export const OP_SUBSTR = 0x7f;
export const OP_LEFT = 0x80;
export const OP_RIGHT = 0x81;
export const OP_SIZE = 0x82;

// bit logic
export const OP_INVERT = 0x83;
export const OP_AND = 0x84;
export const OP_OR = 0x85;
export const OP_XOR = 0x86;
export const OP_EQUAL = 0x87;
export const OP_EQUALVERIFY = 0x88;
export const OP_RESERVED1 = 0x89;
export const OP_RESERVED2 = 0x8a;

// numeric
export const OP_1ADD = 0x8b;
export const OP_1SUB = 0x8c;
export const OP_2MUL = 0x8d;
export const OP_2DIV = 0x8e;
export const OP_NEGATE = 0x8f;
export const OP_ABS = 0x90;
export const OP_NOT = 0x91;
export const OP_0NOTEQUAL = 0x92;
export const OP_ADD = 0x93;
export const OP_SUB = 0x94;
export const OP_MUL = 0x95;
export const OP_DIV = 0x96;
export const OP_MOD = 0x97;
export const OP_LSHIFT = 0x98;
export const OP_RSHIFT = 0x99;
export const OP_BOOLAND = 0x9a;
export const OP_BOOLOR = 0x9b;
export const OP_NUMEQUAL = 0x9c;
export const OP_NUMEQUALVERIFY = 0x9d;
export const OP_NUMNOTEQUAL = 0x9e;
export const OP_LESSTHAN = 0x9f;
export const OP_GREATERTHAN = 0xa0;
export const OP_LESSTHANOREQUAL = 0xa1;
export const OP_GREATERTHANOREQUAL = 0xa2;
export const OP_MIN = 0xa3;
export const OP_MAX = 0xa4;
export const OP_WITHIN = 0xa5;

// crypto
export const OP_RIPEMD160 = 0xa6;
export const OP_SHA1 = 0xa7;
export const OP_SHA256 = 0xa8;
export const OP_HASH160 = 0xa9;
export const OP_HASH256 = 0xaa;
export const OP_CODESEPARATOR = 0xab;
export const OP_CHECKSIG = 0xac;
export const OP_CHECKSIGVERIFY = 0xad;
export const OP_CHECKMULTISIG = 0xae;
export const OP_CHECKMULTISIGVERIFY = 0xaf;

// block state
/** Check lock time of the block. Introduced in BIP 65, replacing OP_NOP2 */
export const OP_CHECKLOCKTIMEVERIFY = 0xb1;

// expansion
export const OP_NOP1 = 0xb0;
/** Deprecated by BIP 65 */
export const OP_NOP2 = OP_CHECKLOCKTIMEVERIFY;
export const OP_NOP3 = 0xb2;
export const OP_NOP4 = 0xb3;
export const OP_NOP5 = 0xb4;
export const OP_NOP6 = 0xb5;
export const OP_NOP7 = 0xb6;
export const OP_NOP8 = 0xb7;
export const OP_NOP9 = 0xb8;
export const OP_NOP10 = 0xb9;
export const OP_INVALIDOPCODE = 0xff;

const opCodeMap: { [key: number]: string } = {
    [OP_0]: "0",
    [OP_PUSHDATA1]: "PUSHDATA1",
    [OP_PUSHDATA2]: "PUSHDATA2",
    [OP_PUSHDATA4]: "PUSHDATA4",
    [OP_1NEGATE]: "1NEGATE",
    [OP_RESERVED]: "RESERVED",
    [OP_1]: "1",
    [OP_2]: "2",
    [OP_3]: "3",
    [OP_4]: "4",
    [OP_5]: "5",
    [OP_6]: "6",
    [OP_7]: "7",
    [OP_8]: "8",
    [OP_9]: "9",
    [OP_10]: "10",
    [OP_11]: "11",
    [OP_12]: "12",
    [OP_13]: "13",
    [OP_14]: "14",
    [OP_15]: "15",
    [OP_16]: "16",
    [OP_NOP]: "NOP",
    [OP_VER]: "VER",
    [OP_IF]: "IF",
    [OP_NOTIF]: "NOTIF",
    [OP_VERIF]: "VERIF",
    [OP_VERNOTIF]: "VERNOTIF",
    [OP_ELSE]: "ELSE",
    [OP_ENDIF]: "ENDIF",
    [OP_VERIFY]: "VERIFY",
    [OP_RETURN]: "RETURN",
    [OP_TOALTSTACK]: "TOALTSTACK",
    [OP_FROMALTSTACK]: "FROMALTSTACK",
    [OP_2DROP]: "2DROP",
    [OP_2DUP]: "2DUP",
    [OP_3DUP]: "3DUP",
    [OP_2OVER]: "2OVER",
    [OP_2ROT]: "2ROT",
    [OP_2SWAP]: "2SWAP",
    [OP_IFDUP]: "IFDUP",
    [OP_DEPTH]: "DEPTH",
    [OP_DROP]: "DROP",
    [OP_DUP]: "DUP",
    [OP_NIP]: "NIP",
    [OP_OVER]: "OVER",
    [OP_PICK]: "PICK",
    [OP_ROLL]: "ROLL",
    [OP_ROT]: "ROT",
    [OP_SWAP]: "SWAP",
    [OP_TUCK]: "TUCK",
    [OP_CAT]: "CAT",
    [OP_SUBSTR]: "SUBSTR",
    [OP_LEFT]: "LEFT",
    [OP_RIGHT]: "RIGHT",
    [OP_SIZE]: "SIZE",
    [OP_INVERT]: "INVERT",
    [OP_AND]: "AND",
    [OP_OR]: "OR",
    [OP_XOR]: "XOR",
    [OP_EQUAL]: "EQUAL",
    [OP_EQUALVERIFY]: "EQUALVERIFY",
    [OP_RESERVED1]: "RESERVED1",
    [OP_RESERVED2]: "RESERVED2",
    [OP_1ADD]: "1ADD",
    [OP_1SUB]: "1SUB",
    [OP_2MUL]: "2MUL",
    [OP_2DIV]: "2DIV",
    [OP_NEGATE]: "NEGATE",
    [OP_ABS]: "ABS",
    [OP_NOT]: "NOT",
    [OP_0NOTEQUAL]: "0NOTEQUAL",
    [OP_ADD]: "ADD",
    [OP_SUB]: "SUB",
    [OP_MUL]: "MUL",
    [OP_DIV]: "DIV",
    [OP_MOD]: "MOD",
    [OP_LSHIFT]: "LSHIFT",
    [OP_RSHIFT]: "RSHIFT",
    [OP_BOOLAND]: "BOOLAND",
    [OP_BOOLOR]: "BOOLOR",
    [OP_NUMEQUAL]: "NUMEQUAL",
    [OP_NUMEQUALVERIFY]: "NUMEQUALVERIFY",
    [OP_NUMNOTEQUAL]: "NUMNOTEQUAL",
    [OP_LESSTHAN]: "LESSTHAN",
    [OP_GREATERTHAN]: "GREATERTHAN",
    [OP_LESSTHANOREQUAL]: "LESSTHANOREQUAL",
    [OP_GREATERTHANOREQUAL]: "GREATERTHANOREQUAL",
    [OP_MIN]: "MIN",
    [OP_MAX]: "MAX",
    [OP_WITHIN]: "WITHIN",
    [OP_RIPEMD160]: "RIPEMD160",
    [OP_SHA1]: "SHA1",
    [OP_SHA256]: "SHA256",
    [OP_HASH160]: "HASH160",
    [OP_HASH256]: "HASH256",
    [OP_CODESEPARATOR]: "CODESEPARATOR",
    [OP_CHECKSIG]: "CHECKSIG",
    [OP_CHECKSIGVERIFY]: "CHECKSIGVERIFY",
    [OP_CHECKMULTISIG]: "CHECKMULTISIG",
    [OP_CHECKMULTISIGVERIFY]: "CHECKMULTISIGVERIFY",
    [OP_NOP1]: "NOP1",
    [OP_CHECKLOCKTIMEVERIFY]: "CHECKLOCKTIMEVERIFY",
    [OP_NOP3]: "NOP3",
    [OP_NOP4]: "NOP4",
    [OP_NOP5]: "NOP5",
    [OP_NOP6]: "NOP6",
    [OP_NOP7]: "NOP7",
    [OP_NOP8]: "NOP8",
    [OP_NOP9]: "NOP9",
    [OP_NOP10]: "NOP10",
};

const opCodeNameMap: { [key: string]: number } = {};
for (const key in opCodeMap) {
    opCodeNameMap[opCodeMap[key as any]] = parseInt(key);
}

/**
 * Converts the given OpCode into a string (eg "0", "PUSHDATA", or "NON_OP(10)")
 */
export function getOpCodeName(opcode: number): string {
    if (opCodeMap[opcode] !== undefined) {
        return opCodeMap[opcode];
    }
    return `NON_OP(${opcode})`;
}

/**
 * Converts the given pushdata OpCode into a string (eg "PUSHDATA2", or "PUSHDATA(23)")
 */
export function getPushDataName(opcode: number): string {
    if (opCodeMap[opcode] !== undefined) {
        return opCodeMap[opcode];
    }
    return `PUSHDATA(${opcode})`;
}

/**
 * Converts the given OpCodeName into an int
 */
export function getOpCode(opCodeName: string): number {
    if (opCodeNameMap[opCodeName] !== undefined) {
        return opCodeNameMap[opCodeName];
    }
    return OP_INVALIDOPCODE;
}
