import bigInt, { BigInteger } from 'big-integer';

export enum RoundingMode {
  HALF_UP = "HALF_UP",
  DOWN = "DOWN",
  UP = "UP",
  CEILING = "CEILING",
  FLOOR = "FLOOR",
  HALF_DOWN = "HALF_DOWN",
  HALF_EVEN = "HALF_EVEN"
}

export class MonetaryFormat {
  private static readonly MAX_DECIMALS = 8;
  
  constructor(
    private readonly _negativeSign: string = '-',
    private readonly _positiveSign: string = '',
    private readonly _zeroDigit: string = '0',
    private readonly _decimalMark: string = '.',
    private readonly _minDecimals: number = 2,
    private readonly _decimalGroups: number[] | null = null,
    private readonly _shift: number = 0,
    private readonly _roundingMode: RoundingMode = RoundingMode.HALF_UP,
    private readonly _codes: string[] | null = null,
    private readonly _codeSeparator: string = ' ',
    private readonly _codePrefixed: boolean = true
  ) {}

  public negativeSign(sign: string): MonetaryFormat {
    return new MonetaryFormat(
      sign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public positiveSign(sign: string): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      sign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public zeroDigit(digit: string): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      digit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public decimalMark(mark: string): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      mark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public minDecimals(decimals: number): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      decimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public optionalDecimals(...groups: number[]): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      groups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public repeatOptionalDecimals(decimals: number, repetitions: number): MonetaryFormat {
    const groups = Array(repetitions).fill(decimals);
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      groups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public shift(shift: number): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public roundingMode(mode: RoundingMode): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      mode,
      this._codes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public noCode(): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      null,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public code(codeShift: number, code: string): MonetaryFormat {
    const newCodes = this._codes ? [...this._codes] : Array(MonetaryFormat.MAX_DECIMALS).fill(null);
    newCodes[codeShift] = code;
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      newCodes,
      this._codeSeparator,
      this._codePrefixed
    );
  }

  public codeSeparator(separator: string): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      separator,
      this._codePrefixed
    );
  }

  public prefixCode(): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      true
    );
  }

  public postfixCode(): MonetaryFormat {
    return new MonetaryFormat(
      this._negativeSign,
      this._positiveSign,
      this._zeroDigit,
      this._decimalMark,
      this._minDecimals,
      this._decimalGroups,
      this._shift,
      this._roundingMode,
      this._codes,
      this._codeSeparator,
      false
    );
  }

  public format(value: BigInteger, decimal: number = 8): string {
    // Handle sign
    const isNegative = value.isNegative();
    const absValue = value.abs();

    // Calculate shift divisor
    const shiftDivisor = Math.pow(10, decimal - this._shift);
    
    // Split into integer and fractional parts
    const numbers = absValue.divide(shiftDivisor);
    const decimals = absValue.mod(shiftDivisor);
    
    // Format fractional part
    let decimalsStr = decimals.toString().padStart(decimal - this._shift, '0');
    
    // Trim trailing zeros
    while (decimalsStr.length > this._minDecimals && decimalsStr.endsWith('0')) {
      decimalsStr = decimalsStr.slice(0, -1);
    }
    
    // Apply decimal groups
    let formattedDecimals = decimalsStr;
    if (this._decimalGroups && decimalsStr.length > this._minDecimals) {
      let i = this._minDecimals;
      for (const group of this._decimalGroups) {
        if (decimalsStr.length > i && decimalsStr.length < i + group) {
          formattedDecimals = decimalsStr.padEnd(i + group, '0');
          break;
        }
        i += group;
      }
    }
    
    // Combine integer and fractional parts
    let result = numbers.toString();
    if (formattedDecimals) {
      result += this._decimalMark + formattedDecimals;
    }
    
    // Add sign
    if (isNegative) {
      result = this._negativeSign + result;
    } else if (this._positiveSign) {
      result = this._positiveSign + result;
    }
    
    // Add currency code
    if (this._codes && this._codes[this._shift]) {
      const code = this._codes[this._shift];
      if (this._codePrefixed) {
        result = code + this._codeSeparator + result;
      } else {
        result += this._codeSeparator + code;
      }
    }
    
    // Convert digits if zeroDigit is not '0'
    if (this._zeroDigit !== '0') {
      const offset = this._zeroDigit.charCodeAt(0) - '0'.charCodeAt(0);
      result = result.replace(/\d/g, d => String.fromCharCode(d.charCodeAt(0) + offset));
    }
    
    return result;
  }

  public parse(str: string, decimal: number = 8): BigInteger {
    str = str.trim();
    if (!str) throw new Error("empty string");
    
    // Handle sign
    let isNegative = false;
    if (str.startsWith(this._negativeSign)) {
      isNegative = true;
      str = str.substring(this._negativeSign.length);
    } else if (this._positiveSign && str.startsWith(this._positiveSign)) {
      str = str.substring(this._positiveSign.length);
    }
    
    // Remove currency code if present
    if (this._codes && this._codes[this._shift]) {
      const code = this._codes[this._shift];
      if (this._codePrefixed && str.startsWith(code + this._codeSeparator)) {
        str = str.substring(code.length + this._codeSeparator.length);
      } else if (!this._codePrefixed && str.endsWith(this._codeSeparator + code)) {
        str = str.substring(0, str.length - (code.length + this._codeSeparator.length));
      }
    }
    
    // Convert digits if zeroDigit is not '0'
    if (this._zeroDigit !== '0') {
      const offset = '0'.charCodeAt(0) - this._zeroDigit.charCodeAt(0);
      str = str.replace(new RegExp(`[${this._zeroDigit}-${String.fromCharCode(this._zeroDigit.charCodeAt(0) + 9)}]`, 'g'), 
        (c) => String.fromCharCode(c.charCodeAt(0) + offset));
    }
    
    // Split into integer and fractional parts
    let [integerPart, fractionalPart = ''] = str.split(this._decimalMark);
    
    // Validate fractional part
    if (fractionalPart.includes(this._decimalMark)) {
      throw new Error("multiple decimal marks");
    }
    
    // Pad fractional part
    fractionalPart = fractionalPart.padEnd(decimal - this._shift, '0');
    
    // Combine parts
    const fullNumber = integerPart + fractionalPart;
    
    // Validate digits
    if (!/^\d+$/.test(fullNumber)) {
      throw new Error("invalid characters");
    }
    
    // Create BigInteger
    let result = bigInt(fullNumber);
    if (isNegative) {
      result = result.multiply(-1);
    }
    
    return result;
  }

  public currentCode(): string | null {
    if (!this._codes || !this._codes[this._shift]) return null;
    return this._codes[this._shift];
  }
}
