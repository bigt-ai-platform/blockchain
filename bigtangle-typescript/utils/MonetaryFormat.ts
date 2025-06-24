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
    private readonly negativeSign: string = '-',
    private readonly positiveSign: string = '',
    private readonly zeroDigit: string = '0',
    private readonly decimalMark: string = '.',
    private readonly minDecimals: number = 2,
    private readonly decimalGroups: number[] | null = null,
    private readonly shift: number = 0,
    private readonly roundingMode: RoundingMode = RoundingMode.HALF_UP,
    private readonly codes: string[] | null = null,
    private readonly codeSeparator: string = ' ',
    private readonly codePrefixed: boolean = true
  ) {}

  public negativeSign(sign: string): MonetaryFormat {
    return new MonetaryFormat(
      sign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public positiveSign(sign: string): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      sign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public zeroDigit(digit: string): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      digit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public decimalMark(mark: string): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      mark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public minDecimals(decimals: number): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      decimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public optionalDecimals(...groups: number[]): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      groups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public repeatOptionalDecimals(decimals: number, repetitions: number): MonetaryFormat {
    const groups = Array(repetitions).fill(decimals);
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      groups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public shift(shift: number): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public roundingMode(mode: RoundingMode): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      mode,
      this.codes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public noCode(): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      null,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public code(codeShift: number, code: string): MonetaryFormat {
    const newCodes = this.codes ? [...this.codes] : Array(MonetaryFormat.MAX_DECIMALS).fill(null);
    newCodes[codeShift] = code;
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      newCodes,
      this.codeSeparator,
      this.codePrefixed
    );
  }

  public codeSeparator(separator: string): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      separator,
      this.codePrefixed
    );
  }

  public prefixCode(): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      true
    );
  }

  public postfixCode(): MonetaryFormat {
    return new MonetaryFormat(
      this.negativeSign,
      this.positiveSign,
      this.zeroDigit,
      this.decimalMark,
      this.minDecimals,
      this.decimalGroups,
      this.shift,
      this.roundingMode,
      this.codes,
      this.codeSeparator,
      false
    );
  }

  public format(value: BigInteger, decimal: number = 8): string {
    // Handle sign
    const isNegative = value.isNegative();
    const absValue = value.abs();

    // Calculate shift divisor
    const shiftDivisor = Math.pow(10, decimal - this.shift);
    
    // Split into integer and fractional parts
    const numbers = absValue.divide(shiftDivisor);
    const decimals = absValue.mod(shiftDivisor);
    
    // Format fractional part
    let decimalsStr = decimals.toString().padStart(decimal - this.shift, '0');
    
    // Trim trailing zeros
    while (decimalsStr.length > this.minDecimals && decimalsStr.endsWith('0')) {
      decimalsStr = decimalsStr.slice(0, -1);
    }
    
    // Apply decimal groups
    let formattedDecimals = decimalsStr;
    if (this.decimalGroups && decimalsStr.length > this.minDecimals) {
      let i = this.minDecimals;
      for (const group of this.decimalGroups) {
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
      result += this.decimalMark + formattedDecimals;
    }
    
    // Add sign
    if (isNegative) {
      result = this.negativeSign + result;
    } else if (this.positiveSign) {
      result = this.positiveSign + result;
    }
    
    // Add currency code
    if (this.codes && this.codes[this.shift]) {
      const code = this.codes[this.shift];
      if (this.codePrefixed) {
        result = code + this.codeSeparator + result;
      } else {
        result += this.codeSeparator + code;
      }
    }
    
    // Convert digits if zeroDigit is not '0'
    if (this.zeroDigit !== '0') {
      const offset = this.zeroDigit.charCodeAt(0) - '0'.charCodeAt(0);
      result = result.replace(/\d/g, d => 
        String.fromCharCode(d.charCodeAt(0) + offset);
    }
    
    return result;
  }

  public parse(str: string, decimal: number = 8): BigInteger {
    str = str.trim();
    if (!str) throw new Error("empty string");
    
    // Handle sign
    let isNegative = false;
    if (str.startsWith(this.negativeSign)) {
      isNegative = true;
      str = str.substring(this.negativeSign.length);
    } else if (this.positiveSign && str.startsWith(this.positiveSign)) {
      str = str.substring(this.positiveSign.length);
    }
    
    // Remove currency code if present
    if (this.codes && this.codes[this.shift]) {
      const code = this.codes[this.shift];
      if (this.codePrefixed && str.startsWith(code + this.codeSeparator)) {
        str = str.substring(code.length + this.codeSeparator.length);
      } else if (!this.codePrefixed && str.endsWith(this.codeSeparator + code)) {
        str = str.substring(0, str.length - (code.length + this.codeSeparator.length));
      }
    }
    
    // Convert digits if zeroDigit is not '0'
    if (this.zeroDigit !== '0') {
      const offset = '0'.charCodeAt(0) - this.zeroDigit.charCodeAt(0);
      str = str.replace(new RegExp(`[${this.zeroDigit}-${String.fromCharCode(this.zeroDigit.charCodeAt(0) + 9}]`, 'g'), 
        (c) => String.fromCharCode(c.charCodeAt(0) + offset));
    }
    
    // Split into integer and fractional parts
    let [integerPart, fractionalPart = ''] = str.split(this.decimalMark);
    
    // Validate fractional part
    if (fractionalPart.includes(this.decimalMark)) {
      throw new Error("multiple decimal marks");
    }
    
    // Pad fractional part
    fractionalPart = fractionalPart.padEnd(decimal - this.shift, '0');
    
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
    if (!this.codes || !this.codes[this.shift]) return null;
    return this.codes[this.shift];
  }
}
