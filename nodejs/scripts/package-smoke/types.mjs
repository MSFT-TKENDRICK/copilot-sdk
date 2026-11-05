import assert from "node:assert/strict";
import { realpathSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const consumer = realpathSync(fileURLToPath(new URL("./", import.meta.url)));
const sdkRoot = realpathSync(process.env.COPILOT_PACKAGE_EXPECTED_ROOT);
const reportPath = process.env.COPILOT_PACKAGE_SMOKE_REPORT;
assert.ok(reportPath, "An evidence report path is required.");
assert.equal(realpathSync(process.cwd()), consumer);
assert.equal(sdkRoot, realpathSync(path.join(consumer, "node_modules", "@github", "copilot-sdk")));
assert.ok(process.execArgv.includes("--no-global-search-paths"), "Global module fallback must be disabled.");
const compiler = realpathSync(fileURLToPath(import.meta.resolve("typescript")));
const compilerRoot = realpathSync(path.join(consumer, "node_modules", "typescript"));
assert.ok(compiler.startsWith(`${compilerRoot}${path.sep}`), "The compiler must come from the isolated dependency copy.");
const options = {
    noEmit: true, strict: true, skipLibCheck: false, target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.NodeNext, moduleResolution: ts.ModuleResolutionKind.NodeNext, types: ["node"],
};
const fixtures = ["consumer.mts", "consumer.cts"].map((name) => path.join(consumer, name));
const program = ts.createProgram(fixtures, options);
const diagnostics = ts.getPreEmitDiagnostics(program);
if (diagnostics.length) {
    throw new Error(ts.formatDiagnosticsWithColorAndContext(diagnostics, {
        getCanonicalFileName: (name) => name, getCurrentDirectory: () => consumer, getNewLine: () => "\n",
    }));
}
const origins = [];
for (const [fixture, mode, directory] of [
    [fixtures[0], ts.ModuleKind.ESNext, "dist"],
    [fixtures[1], ts.ModuleKind.CommonJS, "dist"],
]) {
    for (const [specifier, entry] of [
        ["@github/copilot-sdk", "index.d.ts"],
        ["@github/copilot-sdk/extension", "extension.d.ts"],
    ]) {
        const resolved = ts.resolveModuleName(specifier, fixture, options, ts.sys, undefined, undefined, mode).resolvedModule;
        assert.ok(resolved, `Declaration resolution failed: ${specifier}`);
        const actual = realpathSync(resolved.resolvedFileName);
        assert.equal(actual, realpathSync(path.join(sdkRoot, directory, entry)));
        assert.ok(program.getSourceFile(resolved.resolvedFileName), "The checked program did not load the expected dist declaration.");
        origins.push({ fixture, specifier, path: actual });
    }
}
for (const source of program.getSourceFiles()) {
    const relative = path.relative(consumer, realpathSync(source.fileName));
    assert.ok(!relative.startsWith("..") && !path.isAbsolute(relative), "A type-check dependency escaped the isolated consumer.");
}
writeFileSync(reportPath, `${JSON.stringify({
    status: "passed", typescript: ts.version, compiler, fixtureRunner: fileURLToPath(import.meta.url), fixtures, origins,
    checkedFiles: program.getSourceFiles().length, skipLibCheck: false, sourceTransformsUsed: false,
}, null, 2)}\n`);
