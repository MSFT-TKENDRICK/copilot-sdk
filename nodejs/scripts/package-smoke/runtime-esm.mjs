import * as sdk from "@github/copilot-sdk";
import * as extension from "@github/copilot-sdk/extension";
import { checkRuntime } from "./runtime-common.mjs";

await checkRuntime(sdk, extension, [
    import.meta.resolve("@github/copilot-sdk"),
    import.meta.resolve("@github/copilot-sdk/extension"),
], "esm", import.meta.url);
