export class DeterministicUpgradeRequiresPassword extends Error {
    constructor(message?: string) {
        super(message);
        this.name = "DeterministicUpgradeRequiresPassword";
        Object.setPrototypeOf(this, DeterministicUpgradeRequiresPassword.prototype);
    }
}
