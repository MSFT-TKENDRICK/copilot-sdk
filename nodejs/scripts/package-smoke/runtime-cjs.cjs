const sdk = require("@github/copilot-sdk");
const extension = require("@github/copilot-sdk/extension");

import("./runtime-common.mjs").then(({ checkRuntime }) => checkRuntime(sdk, extension, [
    require.resolve("@github/copilot-sdk"),
    require.resolve("@github/copilot-sdk/extension"),
], "cjs", __filename)).catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
