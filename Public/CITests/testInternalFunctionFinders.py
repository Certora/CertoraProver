#!/usr/bin/env python3

#     The Certora Prover
#     Copyright (C) 2025  Certora Ltd.
#
#     This program is free software: you can redistribute it and/or modify
#     it under the terms of the GNU General Public License as published by
#     the Free Software Foundation, version 3 of the License.
#
#     This program is distributed in the hope that it will be useful,
#     but WITHOUT ANY WARRANTY; without even the implied warranty of
#     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#     GNU General Public License for more details.
#
#     You should have received a copy of the GNU General Public License
#     along with this program.  If not, see <https://www.gnu.org/licenses/>.

"""Every solc 0.8.x must still yield a usable finder for each internal function.

Internal-function summaries attach through the autofinders certora-cli instruments into the
sources. Two things have to hold for a summary to work: a finder is generated for the function,
and its marker survives compilation into the deployed bytecode, which is what the Prover matches
against. Either can be lost quietly, and whether they are depends on the compiler version, because
the optimizer settings certoraBuild sends are chosen per version: Yul optimizer steps from a
per-version ladder under via-IR, an optimizer.details flag under legacy codegen.

Losing them is silent. The build succeeds, the run verifies, and the summary simply never applies,
so nothing in a normal run says the internal function was not summarised. Hence a test that walks
the whole 0.8 line rather than a representative sample, compiling one fixture with four internal
functions of different shapes and checking both conditions for each of them, in both modes.

Expensive: one certoraRun compilation per compiler per mode, a couple of minutes for the full
matrix, and it needs the Certora solc set on PATH. Not part of the fast suite.

    python3 Public/CITests/testInternalFunctionFinders.py                  # every solc8.x on PATH
    python3 Public/CITests/testInternalFunctionFinders.py --versions 8.24,8.36
    python3 Public/CITests/testInternalFunctionFinders.py --mode via_ir

Exits non-zero when any compiler loses a finder, and prints the per-version table either way.
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

FIXTURE = """// SPDX-License-Identifier: MIT
pragma solidity >=0.8.0;

contract Finders {
    uint256 public total;

    // Called once, from one place: what a full inliner exists to remove.
    function addOnce(uint256 a) internal pure returns (uint256) {
        return a + 1;
    }

    // Called from several places, small enough to be worth inlining anyway.
    function scale(uint256 a, uint256 b) internal pure returns (uint256) {
        return a * b + 3;
    }

    // Two return values, so the finder has to survive a multi-value stack shape.
    function split(uint256 a) internal pure returns (uint256, uint256) {
        return (a / 2, a % 2);
    }

    // Touches storage, so it cannot be folded away entirely.
    function bump(uint256 a) internal returns (uint256) {
        total += a;
        return total;
    }

    function entry(uint256 x, uint256 y) external returns (uint256) {
        uint256 v = addOnce(x);
        v = scale(v, y);
        (uint256 q, uint256 r) = split(v);
        return bump(q + r) + scale(q, r);
    }
}
"""

SPEC = "rule sanity { assert true; }\n"

CONTRACT = "Finders"
EXPECTED = ("addOnce", "scale", "split", "bump")

# via_ir is the path where certoraBuild picks Yul optimizer steps per compiler version; legacy is
# where it sends optimizer.details.inliner instead. Both are meant to preserve the finders.
MODES = {
    "via_ir": ["--solc_via_ir", "--solc_optimize", "200"],
    "legacy": ["--solc_optimize", "200"],
}


def available_solcs(pattern: str = r"^solc8\.\d+$") -> List[str]:
    """Every solc 0.8.x on PATH, oldest first."""
    found = set()
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        try:
            for f in Path(entry).iterdir():
                if re.match(pattern, f.name):
                    found.add(f.name)
        except (OSError, PermissionError):
            continue
    return sorted(found, key=lambda n: int(n.split(".")[1]))


def finders_of(build_json: Path) -> Optional[Tuple[List[str], int, int]]:
    """(finder names, markers found in the compiled bytecode, finders declared), or None.

    Two things have to hold for a summary to attach. The finder has to be generated at all, which
    is what the names show, and its marker has to survive compilation into the deployed bytecode,
    which is what the Prover matches against. A finder that is declared and then optimised out of
    the bytecode is the silent failure this test exists to catch.
    """
    try:
        data = json.loads(build_json.read_text())
    except (json.JSONDecodeError, OSError):
        return None
    for sdc in data.values():
        for contract in sdc.get("contracts", []):
            if contract.get("name") != CONTRACT:
                continue
            names = []
            finders = contract.get("internalFunctions") or {}
            for finder in finders.values():
                method = finder.get("method") or {}
                if method.get("name"):
                    names.append(method["name"])
            bytecode = (contract.get("bytecode") or "").lower()
            in_bytecode = sum(1 for key in finders if key[2:].lower() in bytecode)
            return sorted(set(names)), in_bytecode, len(finders)
    return None


def run_one(solc: str, mode: str, keep_logs: Optional[Path]) -> Tuple[List[str], str]:
    """Compile the fixture with *solc* under *mode*; return (finder names, note)."""
    with tempfile.TemporaryDirectory(prefix=f"finders-{solc}-{mode}-") as tmp:
        work = Path(tmp)
        (work / f"{CONTRACT}.sol").write_text(FIXTURE)
        (work / "sanity.spec").write_text(SPEC)
        cmd = [
            "certoraRun", f"{CONTRACT}.sol:{CONTRACT}",
            "--verify", f"{CONTRACT}:sanity.spec",
            "--compilation_steps_only",
            "--solc", solc,
            *MODES[mode],
            "--msg", f"internal function finders {solc} {mode}",
        ]
        proc = subprocess.run(cmd, cwd=work, capture_output=True, text=True)
        if keep_logs:
            keep_logs.mkdir(parents=True, exist_ok=True)
            (keep_logs / f"{solc}-{mode}.log").write_text(proc.stdout + proc.stderr)
        if proc.returncode != 0:
            tail = (proc.stdout + proc.stderr).strip().splitlines()
            return [], f"compilation failed (rc={proc.returncode}): {tail[-1][:90] if tail else ''}"
        builds = []
        for root, _, files in os.walk(work / ".certora_internal"):
            builds += [Path(root) / f for f in files if f.endswith(".certora_build.json")]
        if not builds:
            return [], "no build output produced"
        found = finders_of(sorted(builds)[-1])
        if found is None:
            return [], f"no {CONTRACT} in the build output"
        names, in_bytecode, declared = found
        if declared and in_bytecode < declared:
            return names, f"{declared - in_bytecode} of {declared} finder markers missing from bytecode"
        return names, ""


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--versions", help="comma-separated minors, e.g. 8.24,8.36 (default: all on PATH)")
    ap.add_argument("--mode", choices=sorted(MODES), action="append",
                    help="repeatable; default is every mode")
    ap.add_argument("--keep-logs", type=Path, help="write each certoraRun log here")
    args = ap.parse_args()

    solcs = [f"solc{v.strip()}" for v in args.versions.split(",")] if args.versions else available_solcs()
    if not solcs:
        print("no solc8.x binaries on PATH", file=sys.stderr)
        return 2
    modes = args.mode or sorted(MODES)

    print(f"{len(solcs)} compiler(s) x {len(modes)} mode(s); expecting finders for "
          f"{', '.join(EXPECTED)}\n")
    failures: Dict[str, List[str]] = {}
    for solc in solcs:
        row = []
        for mode in modes:
            names, note = run_one(solc, mode, args.keep_logs)
            missing = [f for f in EXPECTED if f not in names]
            if note:
                row.append(f"{mode}: ERROR {note}")
                failures.setdefault(solc, []).append(f"{mode}: {note}")
            elif missing:
                row.append(f"{mode}: {len(names)}/{len(EXPECTED)} missing {','.join(missing)}")
                failures.setdefault(solc, []).append(f"{mode}: missing {','.join(missing)}")
            else:
                row.append(f"{mode}: ok")
        print(f"  {solc:9} " + "   ".join(row), flush=True)

    print()
    if failures:
        print(f"FAILED: {len(failures)} compiler(s) lost internal-function finders")
        for solc, notes in failures.items():
            print(f"  {solc}: {'; '.join(notes)}")
        return 1
    print(f"OK: every compiler kept a finder for all {len(EXPECTED)} internal functions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
