#!/usr/bin/env python3

# The Certora Prover
# Copyright (C) 2025  Certora Ltd.
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, version 3 of the License.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY, without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR a PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.


import argparse
import json
import subprocess
import tempfile
import sys
from pathlib import Path
import os

SCRIPT_DIR = Path(__file__).resolve().parent

env = os.environ.copy()
env["RUSTFLAGS"] = "-C strip=none --emit=llvm-ir -C debuginfo=2"
# Command to run for compiling the rust project.
COMMAND = "cargo build --target=wasm32-unknown-unknown --release --features certora"

# JSON FIELDS
PROJECT_DIR = SCRIPT_DIR
SOURCES = ["**/*.rs", "Cargo.toml"]
#EXECUTABLES = "target/wasm32-unknown-unknown/release/certora_meridian24_token.wasm"
EXECUTABLES = "certora_meridian24_token.wasm"

VERBOSE = False

def log(msg):
    if VERBOSE:
        print(msg, file=sys.stderr)

def run_command(command, to_stdout=False):
    """Runs the build command and dumps output to temporary files."""
    log(f"Running '{command}'")
    try:
        if to_stdout:
            result = subprocess.run(
                command,
                shell=True,
                text=True,
                env=env
            )
            return None, None, result.returncode
        else:
            with tempfile.NamedTemporaryFile(delete=False, mode='w', prefix="certora_build_", suffix='.stdout') as stdout_file:
                with tempfile.NamedTemporaryFile(delete=False, mode='w', prefix="certora_build_", suffix='.stderr') as stderr_file:
                    # Compile rust project and redirect stdout and stderr to a temp file
                    result = subprocess.run(
                        command,
                        shell=True,
                        stdout=stdout_file,
                        stderr=stderr_file,
                        text=True,
                        env=env
                    )
                    return stdout_file.name, stderr_file.name, result.returncode
    except Exception as e:
        log(f"Error running command '{command}': {e}")
        return None, None - 1

def write_output(output_data, output_file=None):
    """Writes the JSON output either to a file or dumps it to the console."""
    if output_file:
        with open(output_file, 'w') as f:
            json.dump(output_data, f, indent=4)
        log(f"Output written to {output_file}")
    else:
        print(json.dumps(output_data, indent=4), file=sys.stdout)

def main():
    parser = argparse.ArgumentParser(description="Compile rust projects and generate JSON output to be used by Certora Prover.")
    parser.add_argument("-o", "--output", metavar="FILE", help="Path to output JSON to a file.")
    parser.add_argument("--json", action="store_true", help="Dump JSON output to the console.")
    parser.add_argument("-l", "--log", action="store_true", help="Show log outputs from cargo build on standard out.")
    parser.add_argument("-v", "--verbose", action="store_true", help="Be verbose.")

    args = parser.parse_args()
    global VERBOSE
    VERBOSE = args.verbose

    to_stdout = args.log

    # JSON template
    output_data = {
        "project_directory": str(PROJECT_DIR),
        "sources": SOURCES,
        "executables": EXECUTABLES,
        "success": True,
        "return_code": 0,
        "log" : {"stdout": "", "stderr": ""}
    }

    # Handle output based on the provided argument
    if args.output:
        write_output(output_data, args.output)

    if args.json:
        write_output(output_data)

    # Needed for mutations: if you run _this_ script inside another script, you can check this returncode and decide what to do
    sys.exit(0)

if __name__ == "__main__":
    main()
