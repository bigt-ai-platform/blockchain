import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['**/*Test.ts', '**/*.test.ts', '**/*.spec.ts'],
  },
});
