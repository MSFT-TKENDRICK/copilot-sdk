import { defineConfig } from "vitest/config";

export default defineConfig({
    test: {
        include: ["test/**/*.test.ts"],
        exclude: ["**/node_modules/**", "test/e2e/network/os_proxy.e2e.test.ts"],
        testTimeout: 30_000,
    },
});
