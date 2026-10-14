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

"""Under via-IR, an internal function must still be a function in the generated Yul.

certoraBuild sends a per-version Yul optimizer-steps string whenever via-IR is on. Each rung is
upstream's default sequence with the full inliner ('i') taken out. What that buys is checked here
directly: with the full inliner running, solc merges an internal function's body into its caller
and there is no longer a callable unit for a summary to replace. The autofinder marker still ends
up in the bytecode either way, so marker presence proves nothing; the enclosing Yul function is
the thing to look at.

The fixture has four internal functions of different shapes. For each compiler the test compiles
the autofinder-instrumented sources twice through --standard-json with viaIR and the optimizer on:

  default   no optimizer.details.yulDetails.optimizerSteps at all, i.e. upstream's sequence
  ladder    the string certoraBuild would send for this version, read out of certoraBuild.py

and then walks the irOptimized Yul, tracking brace scopes, to find which Yul function each finder
marker landed in. The test fails when a marker sits outside any function definition under the
ladder arm, which is exactly the case where the internal function stopped existing as a unit.

The default arm is reported for contrast and never fails the run; a rung that has stopped removing
the inliner shows up as the two arms agreeing.

    python3 Public/CITests/testYulOptimizerStepsInlining.py                 # every solc8.x on PATH
    python3 Public/CITests/testYulOptimizerStepsInlining.py --versions 8.24,8.36
    python3 Public/CITests/testYulOptimizerStepsInlining.py --keep-yul /tmp/yul

Needs the Certora solc set and certoraRun on PATH. One certoraRun instrumentation plus two solc
invocations per compiler.
"""

import argparse
import ast
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

REPO_ROOT = Path(__file__).resolve().parents[2]
CERTORA_BUILD = REPO_ROOT / "scripts" / "CertoraProver" / "certoraBuild.py"

CONTRACT = "Finders"
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

Version = Tuple[int, int, int]


# ---------------------------------------------------------------- the ladder

def joined_string(node: ast.AST) -> Optional[str]:
    """The value of a string literal, however many pieces it was written in.

    The rungs are spelled both ways in certoraBuild.py, as adjacent literals inside parentheses
    and as literals joined with `+`, and ast.literal_eval only understands the first.
    """
    if isinstance(node, ast.Constant):
        return node.value if isinstance(node.value, str) else None
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Add):
        left = joined_string(node.left)
        right = joined_string(node.right)
        return None if left is None or right is None else left + right
    return None


def read_ladder(path: Path) -> List[Tuple[Version, Version, str]]:
    """The optimizer-steps ladder as certoraBuild.py spells it, newest rung last.

    The rungs are local string assignments named after the range they cover
    (solc0_8_13_to_0_8_25, solc0_8_12), which is the same range the if/elif chain below them
    dispatches on, so the names carry the mapping and nothing has to be duplicated here.
    """
    tree = ast.parse(path.read_text())
    name_re = re.compile(r"^solc(\d+)_(\d+)_(\d+)(?:_to_(\d+)_(\d+)_(\d+))?$")
    rungs = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Assign) or len(node.targets) != 1:
            continue
        target = node.targets[0]
        if not isinstance(target, ast.Name):
            continue
        m = name_re.match(target.id)
        if not m:
            continue
        steps = joined_string(node.value)
        if steps is None:
            continue
        lo = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
        hi = lo if m.group(4) is None else (int(m.group(4)), int(m.group(5)), int(m.group(6)))
        rungs.append((lo, hi, steps))
    if not rungs:
        raise SystemExit(f"no optimizer-steps rungs found in {path}")
    return sorted(rungs)


def rung_for(rungs: List[Tuple[Version, Version, str]], version: Version) -> Optional[str]:
    for lo, hi, steps in rungs:
        if lo <= version <= hi:
            return steps
    return None


def read_rewrite(path: Path) -> Tuple[str, str]:
    """The (find, replace) pair certoraBuild applies to a rung before sending it.

    Read out of the source rather than repeated here, so this test always measures the string
    that actually reaches solc.
    """
    tree = ast.parse(path.read_text())
    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef) or node.name != "without_full_inliner":
            continue
        for call in ast.walk(node):
            if (isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute)
                    and call.func.attr == "replace" and len(call.args) == 2):
                find, repl = (joined_string(a) for a in call.args)
                if find is not None and repl is not None:
                    return find, repl
    raise SystemExit(f"no without_full_inliner rewrite found in {path}")


# ------------------------------------------------------------ instrument + compile

def instrument(solc: str, work: Path) -> Optional[str]:
    """The autofinder-instrumented sources certoraRun produces for the fixture.

    Instrumented under legacy codegen, because the instrumentation is a source rewrite and does
    not depend on the codegen path, while a via-IR certoraRun would drag the ladder itself into
    the step that is supposed to produce this test's input.
    """
    (work / f"{CONTRACT}.sol").write_text(FIXTURE)
    (work / "sanity.spec").write_text(SPEC)
    cmd = ["certoraRun", f"{CONTRACT}.sol:{CONTRACT}", "--verify", f"{CONTRACT}:sanity.spec",
           "--compilation_steps_only", "--solc", solc, "--solc_optimize", "200",
           "--msg", f"yul steps inlining {solc}"]
    proc = subprocess.run(cmd, cwd=work, capture_output=True, text=True)
    if proc.returncode != 0:
        return None
    hits = sorted(work.glob(f".certora_internal/*/.certora_sources/.post_autofinders.*/{CONTRACT}.sol"))
    return hits[-1].read_text() if hits else None


def compile_ir(solc: str, source: str, steps: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    settings: Dict[str, object] = {
        "optimizer": {"enabled": True, "runs": 200},
        "viaIR": True,
        "outputSelection": {"*": {"*": ["irOptimized"]}},
    }
    if steps is not None:
        settings["optimizer"]["details"] = {"yulDetails": {"optimizerSteps": steps}}  # type: ignore[index]
    payload = {"language": "Solidity",
               "sources": {f"{CONTRACT}.sol": {"content": source}},
               "settings": settings}
    proc = subprocess.run([solc, "--standard-json"], input=json.dumps(payload),
                          capture_output=True, text=True)
    try:
        out = json.loads(proc.stdout)
    except json.JSONDecodeError:
        return None, (proc.stderr.strip().splitlines() or ["no output from solc"])[-1]
    errors = [e for e in out.get("errors", []) if e.get("severity") == "error"]
    if errors:
        return None, errors[0]["message"].strip().splitlines()[0]
    try:
        return out["contracts"][f"{CONTRACT}.sol"][CONTRACT]["irOptimized"], None
    except KeyError:
        return None, "no irOptimized in the solc output"


# ------------------------------------------------------------------ Yul scoping

def strip_noise(yul: str) -> str:
    """Comments and string literals out of the way, so braces can be counted.

    solc quotes the original source into /// @src comments, and that source has braces of its own.
    """
    yul = re.sub(r"/\*.*?\*/", " ", yul, flags=re.S)
    yul = re.sub(r"//[^\n]*", " ", yul)
    return re.sub(r'"(?:[^"\\]|\\.)*"', '""', yul)


def markers_of(instrumented: str) -> Dict[str, str]:
    """finder id -> the internal function it was instrumented into."""
    pattern = re.compile(
        r"function\s+(\w+)\s*\([^)]*\)\s*internal[^{]*\{\s*assembly[^}]*?mstore\(\s*0x[0-9a-fA-F]+\s*,\s*(\d+)\)")
    return {m.group(2): m.group(1) for m in pattern.finditer(instrumented)}


def enclosing_functions(yul: str, ids: Dict[str, str]) -> Dict[str, List[Optional[str]]]:
    """For each finder id, the Yul function(s) its marker ended up in (None = no function)."""
    found: Dict[str, List[Optional[str]]] = {i: [] for i in ids}
    id_re = re.compile("(" + "|".join(re.escape(i) for i in ids) + ")") if ids else None
    if id_re is None:
        return found
    scopes: List[Optional[str]] = []
    pending: Optional[str] = None
    for line in strip_noise(yul).split("\n"):
        opens = re.match(r"\s*function\s+([A-Za-z0-9_$]+)\s*\(", line)
        if opens:
            pending = opens.group(1)
        current = next((s for s in reversed(scopes) if s), None)
        for hit in id_re.findall(line):
            found[hit].append(current)
        for ch in line:
            if ch == "{":
                scopes.append(pending)
                pending = None
            elif ch == "}" and scopes:
                scopes.pop()
    return found


def own_function(solidity_name: str, yul_name: Optional[str]) -> bool:
    """Whether a marker's enclosing Yul function is the internal function's own translation.

    solc names an internal function's IR translation fun_<name>, sometimes with the AST id
    appended. Landing in some other function - typically the caller the body was inlined into -
    is the failure this test is about, so the name has to be checked and not just the nesting.
    """
    if yul_name is None:
        return False
    return re.fullmatch(rf"fun_{re.escape(solidity_name)}(_\d+)?", yul_name) is not None


def own_function_count(yul: str, ids: Dict[str, str]) -> Tuple[int, Dict[str, Optional[str]]]:
    placement = enclosing_functions(yul, ids)
    where = {}
    kept = 0
    for finder_id, solidity_name in ids.items():
        names = placement[finder_id]
        own = [n for n in names if own_function(solidity_name, n)]
        # Where it did land matters when it is not its own function: inlined into a caller and
        # hoisted out of every function are different breakages.
        where[finder_id] = own[0] if own else (names[0] if names else None)
        if own:
            kept += 1
    return kept, where


# ---------------------------------------------------------------------- driver

def available_solcs() -> List[str]:
    found = set()
    for entry in os.environ.get("PATH", "").split(os.pathsep):
        try:
            for f in Path(entry).iterdir():
                if re.match(r"^solc8\.\d+$", f.name):
                    found.add(f.name)
        except (OSError, PermissionError):
            continue
    return sorted(found, key=lambda n: int(n.split(".")[1]))


def version_of(solc: str) -> Version:
    minor_patch = solc.replace("solc", "")
    minor, patch = minor_patch.split(".")
    return (0, int(minor), int(patch))


def check(solc: str, rungs: List[Tuple[Version, Version, str]], rewrite: Tuple[str, str],
          keep_yul: Optional[Path]) -> Tuple[str, Optional[str]]:
    """(one-line report, failure reason or None)."""
    version = version_of(solc)
    rung = rung_for(rungs, version)
    if rung is None:
        return f"{solc:9} no ladder rung covers {version[0]}.{version[1]}.{version[2]}", \
               "no ladder rung"
    steps = rung.replace(*rewrite)

    with tempfile.TemporaryDirectory(prefix=f"yulsteps-{solc}-") as tmp:
        instrumented = instrument(solc, Path(tmp))
    if instrumented is None:
        return f"{solc:9} instrumentation failed", "certoraRun could not instrument the fixture"
    ids = markers_of(instrumented)
    if not ids:
        return f"{solc:9} no finder markers in the instrumented sources", "no finders generated"

    report = [f"{solc:9}"]
    failure = None
    results = {}
    for arm, arm_steps in (("default", None), ("ladder", steps)):
        yul, err = compile_ir(solc, instrumented, arm_steps)
        if err:
            results[arm] = None
            report.append(f"{arm}: ERROR {err[:60]}")
            if arm == "ladder":
                failure = f"solc rejected the ladder string: {err}"
            continue
        if keep_yul:
            keep_yul.mkdir(parents=True, exist_ok=True)
            (keep_yul / f"{solc}-{arm}.yul").write_text(yul)
        kept, where = own_function_count(yul, ids)
        results[arm] = (kept, where)
        report.append(f"{arm}: {kept}/{len(ids)} in own fn")

    ladder = results.get("ladder")
    if ladder is not None:
        _, where = ladder
        lost = sorted((ids[i], fn) for i, fn in where.items() if not own_function(ids[i], fn))
        if lost:
            detail = ", ".join(f"{name} in {fn}" if fn else f"{name} in no function" for name, fn in lost)
            failure = f"inlined away under the ladder steps: {detail}"
            report.append(f"[{detail}]")
    return "  ".join(report), failure


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--versions", help="comma-separated minors, e.g. 8.24,8.36 (default: all on PATH)")
    ap.add_argument("--keep-yul", type=Path, help="write each irOptimized here")
    ap.add_argument("--certora-build", type=Path, default=CERTORA_BUILD,
                    help="certoraBuild.py to read the ladder from")
    args = ap.parse_args()

    rungs = read_ladder(args.certora_build)
    rewrite = read_rewrite(args.certora_build)
    solcs = [f"solc{v.strip()}" for v in args.versions.split(",")] if args.versions else available_solcs()
    if not solcs:
        print("no solc8.x binaries on PATH", file=sys.stderr)
        return 2

    print(f"ladder from {args.certora_build}: {len(rungs)} rungs, rewrite {rewrite[0]} -> {rewrite[1]}")
    print("each internal function must sit inside its own Yul function under the ladder steps\n")
    failures = {}
    for solc in solcs:
        line, why = check(solc, rungs, rewrite, args.keep_yul)
        print("  " + line, flush=True)
        if why:
            failures[solc] = why

    print()
    if failures:
        print(f"FAILED: {len(failures)} compiler(s) lost an internal function to the inliner")
        for solc, why in failures.items():
            print(f"  {solc}: {why}")
        return 1
    print("OK: every compiler kept all internal functions as separate Yul functions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
