"""Check built Python artifacts and exercise the wheel, not the source checkout."""

import argparse
import asyncio
import hashlib
import importlib
import inspect
import json
import sys
import tarfile
import zipfile
import zipimport
from pathlib import Path


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--dist", type=Path, required=True)
    args = parser.parse_args()
    source, dist = args.source.resolve(), args.dist.resolve()
    wheels, sdists = list(dist.glob("*.whl")), list(dist.glob("*.tar.gz"))
    require(len(wheels) == len(sdists) == 1, "Expected exactly one wheel and sdist")
    wheel, sdist = wheels[0], sdists[0]
    hashes = {}
    with zipfile.ZipFile(wheel) as archive, tarfile.open(sdist, "r:gz") as sources:
        for name in ("copilot/decisions.py", "copilot/_jsonrpc.py"):
            original = source.joinpath(*name.split("/")).read_bytes()
            require(archive.read(name) == original, f"Wheel source differs: {name}")
            matches = [item for item in sources.getmembers() if item.name.endswith("/" + name)]
            require(len(matches) == 1 and matches[0].isfile(), f"Invalid sdist: {name}")
            extracted = sources.extractfile(matches[0])
            if extracted is None:
                raise AssertionError(f"Missing sdist source: {name}")
            with extracted:
                require(extracted.read() == original, f"Sdist source differs: {name}")
            hashes[name] = hashlib.sha256(original).hexdigest()
        readme = sources.extractfile(sdist.name.removesuffix(".tar.gz") + "/README.md")
        if readme is None:
            raise AssertionError("Missing packaged README")
        with readme:
            require(
                readme.read() == (source / "README.md").read_bytes(),
                "Packaged README differs",
            )
        metadata = [name for name in archive.namelist() if name.endswith(".dist-info/METADATA")]
        require(len(metadata) == 1, "Expected one wheel metadata record")
        require(
            b"Question instructions accept text/image/JSON parts" in archive.read(metadata[0]),
            "Wheel metadata lacks the corrected question-instructions documentation",
        )

    sys.path.insert(0, str(wheel))
    modules = [
        importlib.import_module(name)
        for name in ("copilot", "copilot.decisions", "copilot._jsonrpc")
    ]
    for module in modules:
        loader = module.__loader__
        require(
            isinstance(loader, zipimport.zipimporter) and Path(loader.archive).resolve() == wheel,
            f"{module.__name__} did not load from the built wheel",
        )
    _, decisions, rpc = modules
    require(
        inspect.signature(decisions.SessionDecisionsApi.create).parameters["timeout"].default
        == 600.0,
        "Packaged default deadline differs",
    )
    ok, value = decisions._loads_strict(
        '{"model":"smoke","answers":[],"usage":'
        '{"input_tokens":9223372036854775807,"output_tokens":-9223372036854775808}}'
    )
    require(ok, "Packaged strict JSON parser rejected the signed-int64 fixture")
    result = decisions._parse_decisions_result(value)
    require(result.usage.input_tokens == (1 << 63) - 1, "Positive int64 was narrowed")
    require(result.usage.output_tokens == -(1 << 63), "Negative int64 was narrowed")
    require(result.usage.total_tokens == 0, "Missing usage default differs")
    request = {
        "model": "smoke",
        "input": "input",
        "questions": [
            {
                "type": "predicate",
                "instructions": [{"type": "input_json", "data": {"enabled": True}}],
            }
        ],
    }
    require(
        decisions._serialize_decisions_request(request) == request,
        "Packaged question JSON was not preserved",
    )
    loop = asyncio.new_event_loop()
    try:
        future = loop.create_future()
        future.cancel()
        rpc.JsonRpcClient._complete_response(future, {"late": True})
        rpc.JsonRpcClient._complete_response(future, error=RuntimeError("synthetic late error"))
        require(future.cancelled(), "Late RPC completion changed the cancelled future")
    finally:
        loop.close()
    print(
        json.dumps(
            {
                "scope": "python-package-smoke",
                "status": "passed",
                "sourceSha256": hashes,
                "wheelSha256": hashlib.sha256(wheel.read_bytes()).hexdigest(),
                "sdistSha256": hashlib.sha256(sdist.read_bytes()).hexdigest(),
                "checks": [
                    "wheel-and-sdist-source",
                    "packaged-documentation",
                    "zipimport-origins",
                    "default-deadline",
                    "signed-int64",
                    "missing-usage",
                    "question-json",
                    "cancelled-rpc-completion",
                ],
            },
            indent=2,
        )
    )


if __name__ == "__main__":
    main()
