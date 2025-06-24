module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'node',
  transform: {
    '^.+\\.tsx?$': [
      'ts-jest',
      {
        tsconfig: 'bigtangle-typescript/tsconfig.json'
      }
    ]
  },
  testMatch: [
    "**/test/**/*.test.ts"
  ],
  moduleNameMapper: {
    '^big-integer$': 'big-integer'
  }
};
