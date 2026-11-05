import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

export async function checkRuntime(sdk, extension, resolved, mode, fixture) {
    const root = realpathSync(process.env.COPILOT_PACKAGE_EXPECTED_ROOT);
    const consumer = realpathSync(path.resolve(root, "..", "..", ".."));
    const fixturePath = realpathSync(fixture.startsWith("file:") ? fileURLToPath(fixture) : fixture);
    assert.equal(realpathSync(process.cwd()), consumer, "The runtime must execute inside the isolated consumer.");
    assert.equal(realpathSync(fileURLToPath(import.meta.url)), path.join(consumer, "runtime-common.mjs"));
    assert.equal(fixturePath, path.join(consumer, mode === "esm" ? "runtime-esm.mjs" : "runtime-cjs.cjs"),
        "The fixture must execute from the isolated consumer, not the checkout.");
    const reportPath = process.env.COPILOT_PACKAGE_SMOKE_REPORT;
    assert.ok(reportPath, "An evidence report path is required.");
    assert.ok(process.execArgv.includes("--no-global-search-paths"), "Global module fallback must be disabled.");
    assert.equal(existsSync(path.join(root, "src")), false, "The consumer must not contain SDK source.");
    const manifest = JSON.parse(readFileSync(path.join(root, "package.json"), "utf8"));
    assert.equal(manifest.name, "@github/copilot-sdk");
    const expected = mode === "esm"
        ? ["dist/index.js", "dist/extension.js"]
        : ["dist/cjs/index.js", "dist/cjs/extension.js"];
    const origins = resolved.map((value, index) => {
        const actual = realpathSync(value.startsWith("file:") ? fileURLToPath(value) : value);
        const intended = realpathSync(path.join(root, ...expected[index].split("/")));
        assert.equal(actual, intended, "A public entry resolved outside the extracted tarball's dist.");
        return { path: actual, sha256: createHash("sha256").update(readFileSync(actual)).digest("hex") };
    });
    assert.equal(typeof sdk.CopilotClient, "function");
    assert.equal(typeof sdk.CopilotSession, "function");
    assert.equal(typeof Object.getOwnPropertyDescriptor(sdk.CopilotSession.prototype, "decisions")?.get, "function");
    assert.equal(typeof extension.joinSession, "function");
    const deadline = new sdk.DecisionsError("timeout", "Package smoke deadline");
    assert.equal(deadline.name, "DecisionsError");
    assert.equal(deadline.kind, "timeout");
    const http = new sdk.DecisionsError("http", "Package smoke HTTP", { status: 429, retryAfterMs: 125 });
    assert.equal(http.status, 429);
    assert.equal(http.retryAfterMs, 125);
    assert.equal(sdk.defineTool("package-smoke", { description: "Offline package smoke" }).name, "package-smoke");
    delete process.env.SESSION_ID;
    await assert.rejects(extension.joinSession(), /intended for extensions running as child processes/);
    const commonJsOrigins = Object.keys(createRequire(import.meta.url).cache).map((file) => realpathSync(file));
    for (const file of commonJsOrigins) {
        const relative = path.relative(consumer, file);
        assert.ok(relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative),
            "A loaded CommonJS module escaped the isolated consumer.");
    }
    writeFileSync(reportPath, `${JSON.stringify({
        status: "passed", mode, node: process.version, packageVersion: manifest.version,
        packageRoot: root, origins, sourceTransformsUsed: false,
        fixture: { path: fixturePath, sha256: createHash("sha256").update(readFileSync(fixturePath)).digest("hex") },
        commonJsOrigins,
        globalModuleSearchDisabled: true,
        modelOrRuntimeConnectionsOpened: false,
    }, null, 2)}\n`);
}
