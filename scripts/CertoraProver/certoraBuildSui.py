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

    move_toml_file: Path | None = None

    if context.move_path:
        # If move_path is specified, we assume the user has already built the spec package separately.  This is a legacy
        # option that should not be used by new projects; they should use `spec_package_path` and/or `build_script`
        # instead.
        assert not context.build_script, "cannot have move_path and build_script together"
        assert not context.spec_package_path, "cannot have move_path and spec_package_path together"
        context.sui_package_summary_path = None
    else:
        if context.spec_package_path:
            # Verify that there is a Move.toml at the specified spec_package_path
            spec_package_dir = Path(context.spec_package_path)
            move_toml_file = spec_package_dir / "Move.toml"
            if not move_toml_file.exists():
                raise Util.CertoraUserInputError(f"Could not find Move.toml at specified spec_package_path '{context.spec_package_path}'")
        else:
            # If spec_package_path is not specified, try to find the package that the current directory is in.
            move_toml_file = Util.find_file_in_parents("Move.toml")
            if not move_toml_file:
                raise Util.CertoraUserInputError("Could not find Move.toml, and no spec_package_path was specified.")
            spec_package_dir = move_toml_file.parent
            context.spec_package_path = str(spec_package_dir)

        sources.add(move_toml_file.absolute())
        context.move_path = str(spec_package_dir / "build")
        context.sui_package_summary_path = spec_package_dir / "package_summaries"

        if context.build_script:
            # Run the user-provided build script
            script_path = Path(context.build_script).resolve()
            sources.add(script_path)
            run_sui_build([str(script_path), str(spec_package_dir)])
        else:
            # Build the package using `sui move summary`, which will also produce the package summaries.
            run_sui_build(["sui", "move", "summary", "--test", "--path", str(move_toml_file.parent)])

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

    # Copy package summary directory if it exists.  Projects built manually with "sui move build" may not have this.
    if context.sui_package_summary_path and context.sui_package_summary_path.exists():
        assert context.sui_package_summary_path.is_dir(), f"Package summary path '{context.sui_package_summary_path}' is not a directory"
        shutil.copytree(context.sui_package_summary_path,
                        Util.get_build_dir() / context.sui_package_summary_path.name)

    try:
        # Create generators
        build_source_tree(sources, context)

    except Exception as e:
        raise Util.CertoraUserInputError(f"Collecting build files failed with the exception: {e}")


def run_sui_build(build_cmd: list[str]) -> None:
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
