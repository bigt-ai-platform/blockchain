module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  globals: {
    'ts-jest': {
      tsconfig: 'bigtangle-typescript/tsconfig.json'
    }
  }
};
