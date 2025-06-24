// This is a simplified placeholder for MonetaryFormat.
// In a real scenario, this would handle currency formatting.
export class MonetaryFormat {
    public static FIAT = new MonetaryFormat(); // Dummy instance

    public noCode(): MonetaryFormat {
        return this;
    }

    public format(value: number, decimals: number): string {
        // Simple formatting for now
        return value.toFixed(decimals);
    }
}
