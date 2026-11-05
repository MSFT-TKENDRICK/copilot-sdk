import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
    closeSync, copyFileSync, cpSync, existsSync, lstatSync, mkdirSync, openSync,
    readFileSync, readdirSync, realpathSync, statSync, writeFileSync,
} from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { gunzipSync } from "node:zlib";

const fixturesRoot = fileURLToPath(new URL("./", import.meta.url));
const sdkRoot = realpathSync(path.resolve(fixturesRoot, "..", ".."));
const sdkModules = path.join(sdkRoot, "node_modules");
const fixtureNames = [
    "runtime-common.mjs", "runtime-esm.mjs", "runtime-cjs.cjs",
    "types.mjs", "consumer.mts", "consumer.cts",
];
const now = () => new Date().toISOString();
const sha256 = (value) => createHash("sha256").update(value).digest("hex");
const readJson = (file) => JSON.parse(readFileSync(file, "utf8"));
const writeJson = (file, value) => writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
const inside = (root, file) => {
    const relative = path.relative(root, file);
    return relative !== ".." && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative);
};
const text = (value) => new TextDecoder("utf-8", { fatal: true }).decode(value);
const field = (header, start, length) => text(header.subarray(start, start + length)).split("\0")[0];
const octal = (value) => {
    assert.match(value.trim(), /^[0-7]+$/, "Unsupported tar numeric field.");
    return Number.parseInt(value.trim(), 8);
};

function paxFields(data) {
    const fields = {};
    for (let offset = 0; offset < data.length;) {
        const space = data.indexOf(32, offset);
        assert.ok(space > offset, "Malformed PAX length.");
        const lengthText = text(data.subarray(offset, space));
        assert.match(lengthText, /^[1-9][0-9]*$/);
        const length = Number(lengthText);
        assert.ok(Number.isSafeInteger(length) && length > space - offset + 2
            && offset + length <= data.length, "Malformed PAX record.");
        assert.equal(data[offset + length - 1], 10);
        const entry = text(data.subarray(space + 1, offset + length - 1));
        const equals = entry.indexOf("=");
        assert.ok(equals > 0);
        fields[entry.slice(0, equals)] = entry.slice(equals + 1);
        offset += length;
    }
    return fields;
}

function extractTarball(archive, destination) {
    assert.ok(statSync(archive).size <= 32 * 1024 * 1024, "SDK archive exceeds the 32-MiB smoke limit.");
    const compressed = readFileSync(archive);
    const data = gunzipSync(compressed, { maxOutputLength: 64 * 1024 * 1024 });
    const files = [];
    const seen = new Set();
    let globalPax = {};
    let nextPax = {};
    let offset = 0;
    let ended = false;
    // Accept npm's regular files, directories and PAX metadata, never links or special files.
    while (offset + 512 <= data.length) {
        const header = data.subarray(offset, offset + 512);
        if (header.every((byte) => byte === 0)) {
            assert.ok(data.subarray(offset).every((byte) => byte === 0), "Unexpected data after tar end.");
            ended = true;
            break;
        }
        const checksum = header.reduce((sum, byte, index) => sum + (index >= 148 && index < 156 ? 32 : byte), 0);
        assert.equal(octal(field(header, 148, 8)), checksum, "Tar header checksum mismatch.");
        const size = octal(field(header, 124, 12));
        assert.ok(Number.isSafeInteger(size) && offset + 512 + size <= data.length, "Invalid tar size.");
        const body = data.subarray(offset + 512, offset + 512 + size);
        const type = field(header, 156, 1);
        offset += 512 + Math.ceil(size / 512) * 512;
        assert.ok(offset <= data.length, "Truncated tar padding.");
        if (type === "x" || type === "g") {
            if (type === "g") globalPax = { ...globalPax, ...paxFields(body) };
            else nextPax = { ...nextPax, ...paxFields(body) };
            continue;
        }
        assert.ok(["", "0", "5"].includes(type), "Tar links and special files are not allowed.");
        const metadata = { ...globalPax, ...nextPax };
        nextPax = {};
        if (metadata.size !== undefined) assert.equal(Number(metadata.size), size, "Conflicting PAX size.");
        const prefix = field(header, 345, 155);
        const name = metadata.path ?? [prefix, field(header, 0, 100)].filter(Boolean).join("/");
        assert.ok(!/[\\:\0]/u.test(name), "Unsafe tar path.");
        const parts = name.replace(/\/$/u, "").split("/");
        assert.equal(parts.shift(), "package", "Unexpected npm tarball prefix.");
        assert.ok(parts.every((part) => part && part !== "." && part !== ".." && !/[. ]$/u.test(part)), "Unsafe tar path component.");
        if (parts.length === 0 && type === "5") continue;
        assert.ok(parts.length > 0, "Empty tar file path.");
        const relative = parts.join("/");
        assert.ok(!seen.has(relative.toLowerCase()), "Duplicate or case-aliased tar path.");
        seen.add(relative.toLowerCase());
        const target = path.join(destination, ...parts);
        assert.ok(inside(destination, target));
        if (type === "5") {
            assert.equal(size, 0);
            mkdirSync(target, { recursive: true });
        } else {
            assert.ok(parts[0] !== "src" && parts[0] !== "test", "The tarball contains SDK source/tests.");
            assert.ok(!/\.(?:ts|mts|cts)$/u.test(relative) || /\.d\.(?:ts|mts|cts)$/u.test(relative), "The tarball contains TypeScript implementation source.");
            mkdirSync(path.dirname(target), { recursive: true });
            writeFileSync(target, body, { flag: "wx" });
            files.push({ path: relative, sha256: sha256(body) });
        }
    }
    assert.ok(ended, "Tarball lacks a complete end marker.");
    for (const required of [
        "package.json", "LICENSE", "dist/index.js", "dist/extension.js",
        "dist/index.d.ts", "dist/extension.d.ts", "dist/cjs/package.json",
        "dist/cjs/index.js", "dist/cjs/extension.js",
    ]) assert.ok(files.some((file) => file.path === required), `Missing packed entry: ${required}`);
    return { path: archive, sha256: sha256(compressed), files };
}

function dependencyCopier(consumer, records) {
    const copied = new Set();
    function copy(name, requested, parent = sdkRoot, optional = false) {
        assert.match(name, /^(?:@[a-z0-9_.-]+\/)?[a-z0-9_][a-z0-9_.-]*$/iu, "Invalid dependency name.");
        let source;
        for (let ancestor = parent; inside(sdkRoot, ancestor); ancestor = path.dirname(ancestor)) {
            const candidate = path.join(ancestor, "node_modules", ...name.split("/"));
            if (existsSync(candidate)) {
                source = candidate;
                break;
            }
            if (ancestor === sdkRoot) break;
        }
        if (!source && optional) return;
        assert.ok(source, `Missing installed dependency ${name}; run the documented npm bootstrap first.`);
        assert.ok(inside(sdkModules, realpathSync(source)) && !lstatSync(source).isSymbolicLink(),
            "Dependency links or paths outside this SDK's node_modules are not supported.");
        const metadata = readJson(path.join(source, "package.json"));
        if (/^\d+\.\d+\.\d+(?:-[a-z0-9.-]+)?$/iu.test(requested)) {
            assert.equal(metadata.version, requested, `Installed version mismatch: ${name}`);
        }
        if (copied.has(source)) return;
        copied.add(source);
        const target = path.join(consumer, "node_modules", path.relative(sdkModules, source));
        if (!existsSync(target)) {
            cpSync(source, target, {
                recursive: true, errorOnExist: true, force: false,
                filter: (file) => {
                    if ([".bin", ".cache", ".package-lock.json"].includes(path.basename(file))) return false;
                    const stat = lstatSync(file);
                    assert.ok(!stat.isSymbolicLink() && (stat.isFile() || stat.isDirectory()), "Dependency copies must contain only regular files/directories.");
                    return true;
                },
            });
        }
        records.push({ name, requested, version: metadata.version, source, copiedTo: target });
        const optionalDependencies = metadata.optionalDependencies ?? {};
        for (const [child, version] of Object.entries(metadata.dependencies ?? {})) {
            copy(child, version, source, Object.hasOwn(optionalDependencies, child));
        }
        for (const [child, version] of Object.entries(optionalDependencies)) copy(child, version, source, true);
    }
    return copy;
}

function runFixture(name, consumer, output, env, report) {
    const argv = [process.execPath, "--no-global-search-paths", path.join(consumer, name)];
    const resultPath = path.join(output, `${name}.result.json`);
    const record = {
        argv, cwd: consumer, startedAt: now(), completedAt: null, exitCode: null,
        stdout: path.join(output, `${name}.stdout.log`),
        stderr: path.join(output, `${name}.stderr.log`),
    };
    report.commands.push(record);
    const stdout = openSync(record.stdout, "wx");
    const stderr = openSync(record.stderr, "wx");
    let result;
    try {
        result = spawnSync(argv[0], argv.slice(1), {
            cwd: consumer, env: { ...env, COPILOT_PACKAGE_SMOKE_REPORT: resultPath },
            stdio: ["ignore", stdout, stderr], timeout: 600_000, windowsHide: true,
        });
    } finally {
        closeSync(stdout);
        closeSync(stderr);
        record.completedAt = now();
    }
    record.exitCode = result.status;
    record.signal = result.signal;
    record.error = result.error?.message;
    assert.ok(!result.error && result.status === 0, `${name} failed; see ${record.stderr}`);
    const checked = readJson(resultPath);
    assert.equal(checked.status, "passed", `${name} did not confirm its assertions.`);
    report.smoke[name] = checked;
}

function main() {
    const { values } = parseArgs({ options: {
        tarball: { type: "string" }, output: { type: "string" }, help: { type: "boolean" },
    } });
    if (values.help) {
        console.log("Usage: node scripts/package-smoke/run.mjs --tarball <.tgz-or-directory> --output <new-directory-outside-sdk>");
        return;
    }
    assert.ok(values.tarball && values.output, "--tarball and --output are required.");
    assert.ok(process.features.require_module, "Use Node ^20.19.0 or >=22.12.0 with native require(esm).");
    let archive = realpathSync(values.tarball);
    if (statSync(archive).isDirectory()) {
        const entries = readdirSync(archive).filter((name) => name.endsWith(".tgz"));
        assert.equal(entries.length, 1, "The tarball directory must contain exactly one .tgz.");
        archive = realpathSync(path.join(archive, entries[0]));
    }
    const requestedOutput = path.resolve(values.output);
    assert.ok(!existsSync(requestedOutput), "Output must be a new directory; previous evidence is never overwritten.");
    mkdirSync(path.dirname(requestedOutput), { recursive: true });
    const output = path.join(realpathSync(path.dirname(requestedOutput)), path.basename(requestedOutput));
    assert.ok(!inside(sdkRoot, output), "Place consumer output outside the SDK checkout.");
    mkdirSync(output);
    const report = {
        schemaVersion: 1, status: "running", startedAt: now(), completedAt: null,
        node: { version: process.version, platform: process.platform, arch: process.arch },
        runnerSha256: sha256(readFileSync(fileURLToPath(import.meta.url))),
        buildsExecuted: 0, installsExecuted: 0, dependencyDownloadsAttempted: false,
        commands: [], dependencies: [], fixtures: [], smoke: {},
    };
    try {
        const consumer = path.join(output, "consumer");
        mkdirSync(consumer);
        writeJson(path.join(consumer, "package.json"), { private: true, type: "module" });
        const installed = path.join(consumer, "node_modules", "@github", "copilot-sdk");
        report.tarball = extractTarball(archive, installed);
        const packed = readJson(path.join(installed, "package.json"));
        const development = readJson(path.join(sdkRoot, "package.json"));
        assert.equal(packed.name, "@github/copilot-sdk");
        assert.deepEqual(packed.dependencies, development.dependencies, "Bootstrap must match the tarball's runtime dependencies.");
        report.packageVersion = packed.version;
        const copyDependency = dependencyCopier(consumer, report.dependencies);
        for (const [name, version] of Object.entries(packed.dependencies ?? {})) copyDependency(name, version);
        for (const name of fixtureNames) {
            const target = path.join(consumer, name);
            copyFileSync(path.join(fixturesRoot, name), target);
            report.fixtures.push({ path: target, sha256: sha256(readFileSync(target)) });
        }
        const env = { ...process.env };
        const controlled = new Set([
            "NODE_OPTIONS", "NODE_PATH", "NODE_COMPILE_CACHE", "NODE_V8_COVERAGE", "NODE_REDIRECT_WARNINGS",
            "NODE_DISABLE_COMPILE_CACHE", "COPILOT_PACKAGE_EXPECTED_ROOT", "COPILOT_PACKAGE_SMOKE_REPORT",
            "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "XDG_CACHE_HOME", "NPM_CONFIG_CACHE",
            "TMPDIR", "TMP", "TEMP",
        ]);
        for (const key of Object.keys(env)) if (controlled.has(key.toUpperCase())) delete env[key];
        Object.assign(env, {
            NODE_DISABLE_COMPILE_CACHE: "1", COPILOT_PACKAGE_EXPECTED_ROOT: installed,
            HOME: path.join(output, "home"), USERPROFILE: path.join(output, "home"),
            APPDATA: path.join(output, "home", "app-data"), LOCALAPPDATA: path.join(output, "home", "local-app-data"),
            XDG_CACHE_HOME: path.join(output, "cache"), npm_config_cache: path.join(output, "cache", "npm"),
            TMPDIR: path.join(output, "scratch"), TMP: path.join(output, "scratch"), TEMP: path.join(output, "scratch"),
        });
        for (const directory of [env.HOME, env.APPDATA, env.LOCALAPPDATA, env.XDG_CACHE_HOME, env.TMPDIR]) {
            mkdirSync(directory, { recursive: true });
        }
        runFixture("runtime-esm.mjs", consumer, output, env, report);
        runFixture("runtime-cjs.cjs", consumer, output, env, report);
        for (const name of ["typescript", "@types/node"]) copyDependency(name, development.devDependencies[name]);
        runFixture("types.mjs", consumer, output, env, report);
        report.status = "passed";
    } catch (error) {
        report.status = "failed";
        report.error = error.stack ?? String(error);
        process.exitCode = 1;
    } finally {
        report.completedAt = now();
        writeJson(path.join(output, "result.json"), report);
        console.log(JSON.stringify({ status: report.status, report: path.join(output, "result.json") }));
    }
}

try {
    main();
} catch (error) {
    console.error(error);
    process.exitCode = 1;
}
