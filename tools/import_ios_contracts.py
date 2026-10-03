#!/usr/bin/env python3
"""Deterministically import pinned, non-secret AxonHub iOS contract resources."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path


def extract_documents(source: Path) -> dict[str, str]:
    text = source.read_text(encoding="utf-8")
    pattern = re.compile(r"static let (\w+) = \"\"\"\n(.*?)\n\s*\"\"\"", re.S)
    documents = {name: body.rstrip() + "\n" for name, body in pattern.findall(text)}
    if len(documents) < 100:
        raise SystemExit(f"unexpected document count: {len(documents)}")
    return dict(sorted(documents.items()))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("ios_root", type=Path)
    parser.add_argument("android_assets", type=Path)
    args = parser.parse_args()
    source = args.ios_root / "Axonhub"
    output = args.android_assets
    output.mkdir(parents=True, exist_ok=True)

    documents = extract_documents(source / "AdminDocuments.swift")
    (output / "admin_documents.json").write_text(
        json.dumps(documents, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    for name in ("AdminSchema.json", "PresentationLabels.json"):
        shutil.copyfile(source / name, output / name)
    fixture_dir = output / "fixtures"
    fixture_dir.mkdir(exist_ok=True)
    for name in (
        "admin-contract-fixtures.json",
        "api-contract-fixtures.json",
        "channel-model-contract-fixtures.json",
        "observability-contract-fixtures.json",
        "playground-graphql-fixtures.json",
    ):
        shutil.copyfile(args.ios_root / "scripts" / name, fixture_dir / name)
    shutil.copyfile(args.ios_root / "AxonhubTests" / "playground-contract-fixtures.json", fixture_dir / "playground-contract-fixtures.json")

    provenance = {
        "upstream": "https://github.com/Likhixang/Axonapp-iOS",
        "upstreamCommit": subprocess.check_output(["git", "-C", str(args.ios_root), "rev-parse", "HEAD"], text=True).strip(),
        "schemaRevision": json.loads((source / "AdminSchema.json").read_text())["revision"],
        "documentCount": len(documents),
        "documentsSha256": hashlib.sha256((output / "admin_documents.json").read_bytes()).hexdigest(),
        "note": "API documents and fixtures copied for interoperability and regression testing; no credentials.",
    }
    (output / "UPSTREAM_PROVENANCE.json").write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
