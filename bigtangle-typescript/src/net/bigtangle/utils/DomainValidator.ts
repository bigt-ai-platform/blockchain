// TypeScript translation of DomainValidator.java (stub)
// NOTE: This is a stub. Full porting of all validation logic is non-trivial and should use a library like 'validator' for production use.
import validator from 'validator';

export class DomainValidator {
    static isValid(domain: string): boolean {
        // Basic domain validation using validator.js
        return validator.isFQDN(domain);
    }
}
