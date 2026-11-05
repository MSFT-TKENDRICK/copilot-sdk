import { mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";

const root = fileURLToPath(new URL("../", import.meta.url));
const dist = path.join(root, "dist");
const formatHost = {
    getCanonicalFileName: (name) => name,
    getCurrentDirectory: () => root,
    getNewLine: () => "\n",
};

if (!process.features.require_module) {
    throw new Error("Building requires Node ^20.19.0 or >=22.12.0 with native require(esm) enabled.");
}

const config = ts.readConfigFile(path.join(root, "tsconfig.json"), ts.sys.readFile);
if (config.error) {
    throw new Error(ts.formatDiagnosticsWithColorAndContext([config.error], formatHost));
}
const parsed = ts.parseJsonConfigFileContent(config.config, ts.sys, root, {
    rootDir: path.join(root, "src"),
    outDir: dist,
    noEmit: false,
    emitDeclarationOnly: false,
    declaration: true,
    noEmitOnError: true,
});
const program = ts.createProgram(parsed.fileNames, parsed.options);
const diagnostics = [...parsed.errors, ...ts.getPreEmitDiagnostics(program)];
if (diagnostics.length) {
    throw new Error(ts.formatDiagnosticsWithColorAndContext(diagnostics, formatHost));
}

// Native CommonJS bridges require a synchronous ESM graph at the existing Node floor.
for (const source of program.getSourceFiles()) {
    if (source.isDeclarationFile) continue;
    const visit = (node) => {
        if (ts.isFunctionLike(node)) return;
        if (ts.isAwaitExpression(node)
            || (ts.isForOfStatement(node) && node.awaitModifier)
            || (ts.isVariableDeclarationList(node) && (node.flags & ts.NodeFlags.AwaitUsing) === ts.NodeFlags.AwaitUsing)) {
            throw new Error(`Top-level await is incompatible with the CommonJS entry point: ${source.fileName}`);
        }
        ts.forEachChild(node, visit);
    };
    visit(source);
}

rmSync(dist, { recursive: true, force: true });
const emitted = program.emit();
if (emitted.emitSkipped || emitted.diagnostics.length) {
    throw new Error(ts.formatDiagnosticsWithColorAndContext(emitted.diagnostics, formatHost) || "Package emission was skipped.");
}

const cjs = path.join(dist, "cjs");
mkdirSync(cjs);
writeFileSync(path.join(cjs, "package.json"), '{"type":"commonjs"}\n');
for (const entry of ["index", "extension"]) {
    writeFileSync(
        path.join(cjs, `${entry}.js`),
        `"use strict";\nmodule.exports = require("../${entry}.js");\n`,
    );
}

console.log(`Built native ESM, shared TypeScript declarations, and 2 CommonJS bridges in ${dist}`);
