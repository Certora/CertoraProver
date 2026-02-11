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

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

scripts_dir_path = Path(__file__).parent.parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

import shutil
import time
import logging
from pathlib import Path
from typing import Set, Dict

from CertoraProver.certoraBuild import build_source_tree
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util


log = logging.getLogger(__name__)


def build_sui_project(context: CertoraContext, timings: Dict) -> None:
    """
    Compile the Sui artefact and record elapsed time in *timings*.

    Args:
        context: The CertoraContext object containing the configuration.
        timings: A dictionary to store timing information.
    """
    log.debug("Build Sui target")
    start = time.perf_counter()
    set_sui_build_directory(context)
    timings["buildTime"] = round(time.perf_counter() - start, 4)
    if context.test == str(Util.TestValue.AFTER_BUILD):
        raise Util.TestResultsReady(context)


def set_sui_build_directory(context: CertoraContext) -> None:
    sources: Set[Path] = set()

    # If no move_path was specified, try to build the package
    if not context.move_path:
        if context.build_script:
            raise Util.CertoraUserInputError("move_path must be specified when using build_script.")
        move_toml_file = Util.find_file_in_parents("Move.toml")
        if not move_toml_file:
            raise Util.CertoraUserInputError("Could not find Move.toml, and no move_path was specified.")
        sources.add(move_toml_file.absolute())
        context.move_path = str(move_toml_file.parent / "build")
        run_sui_build(context, ["sui", "move", "build", "--test", "--path", str(move_toml_file.parent)])
    elif context.build_script:
        script_path = Path(context.build_script).resolve()
        sources.add(script_path)
        run_sui_build(context, [str(script_path)])

    assert context.move_path, "expecting move_path to be set after build"
    move_dir = Path(context.move_path)
    assert move_dir.exists(), f"Output path '{move_dir}' does not exist"
    assert move_dir.is_dir(), f"Output path '{move_dir}' is not a directory"

    # Add all source files.  We get these from the Sui build output, because it includes dependencies as well, and is
    # available even if we didn't run the build ourselves.
    sources.update(move_dir.rglob("*.move"))

    # Add conf file if it exists
    if getattr(context, 'conf_file', None) and Path(context.conf_file).exists():
        sources.add(Path(context.conf_file).absolute())

    # Copy the binary modules and source maps
    shutil.copytree(move_dir,
                    Util.get_build_dir() / move_dir.name,
                    ignore=shutil.ignore_patterns('*.move'))

    try:
        # Create generators
        build_source_tree(sources, context)

    except Exception as e:
        raise Util.CertoraUserInputError(f"Collecting build files failed with the exception: {e}")

def run_sui_build(context: CertoraContext, build_cmd: list[str]) -> None:
    try:
        build_cmd_text = ' '.join(build_cmd)
        log.info(f"Building by calling `{build_cmd_text}`")
        result = subprocess.run(build_cmd, capture_output=False)

        # Check if the script executed successfully
        if result.returncode != 0:
            raise Util.CertoraUserInputError(f"Error running `{build_cmd_text}`")

    except Util.TestResultsReady as e:
        raise e
    except Util.CertoraUserInputError as e:
        raise e
    except Exception as e:
        raise Util.CertoraUserInputError(f"An unexpected error occurred: {e}")
