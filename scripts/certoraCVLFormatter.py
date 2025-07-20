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


import os
import sys
import argparse
import subprocess
from pathlib import Path

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

from Shared import certoraUtils as Util
from pathlib import Path

FORMATTER_JAR = "ASTExtraction.jar"

scripts_dir_path = Path(__file__).parent.resolve()
sys.path.insert(0, str(scripts_dir_path))

def spec_file_type(spec_file: str) -> str:
    if not os.path.isfile(spec_file):
        raise argparse.ArgumentTypeError(f"File {spec_file} does not exist")
    return spec_file

def run_formatter_from_jar(spec_file: str, overwrite: bool) -> None:
    path_to_typechecker = Util.find_jar(FORMATTER_JAR)
    cmd = ['java', '-jar', str(path_to_typechecker), 'format', '--file', spec_file]

    result = subprocess.run(cmd, text=True, capture_output=True)

    if result.returncode != 0:
        raise Util.CertoraUserInputError(f"Error running formatter on {spec_file}: {result.stderr.strip() if result.stderr else ''}")

    if overwrite:
        with open(spec_file, 'w', encoding='utf-8') as f:
            f.write(result.stdout)
    else:
        print(result.stdout, end='', file=sys.stdout)

def parse_args() -> tuple[str, bool]:
    parser = argparse.ArgumentParser()
    parser.add_argument('spec_file', type=spec_file_type, help='Path to the .spec file')
    parser.add_argument('-w', '--overwrite', action='store_true', help='If set, output is written to the spec file instead of stdout')

    args = parser.parse_args()
    return args.spec_file, args.overwrite


def check_java_version() -> None:
    if not (Util.is_java_installed(Util.get_java_version())):
        raise Util.CertoraUserInputError(f"Java {Util.MIN_JAVA_VERSION} or higher is required to run the formatter. "
                                         f"Please install Java and try again.")


def entry_point() -> None:
    spec_file, overwrite = parse_args()
    check_java_version()
    run_formatter_from_jar(spec_file, overwrite)

if __name__ == '__main__':
    entry_point()
