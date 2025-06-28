module.exports = {
  preset: 'ts-jest/presets/default-esm',
  testEnvironment: 'node',
  useESM: true,
  transform: {
    '^.+\\.(ts|tsx)$': ['ts-jest', { useESM: true }],
  },
  extensionsToTreatAsEsm: ['.ts'],
  transformIgnorePatterns: [
    '/node_modules/(?!(?:@noble/secp256k1)/)'
  ],
};
