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
    Compile the Rust artefact and record elapsed time in *timings*.

    Args:
        context: The CertoraContext object containing the configuration.
        timings: A dictionary to store timing information.
    """
    log.debug("Build Rust target")
    start = time.perf_counter()
    set_sui_build_directory(context)
    timings["buildTime"] = round(time.perf_counter() - start, 4)
    if context.test == str(Util.TestValue.AFTER_BUILD):
        raise Util.TestResultsReady(context)


def set_sui_build_directory(context: CertoraContext) -> None:
    assert context.move_path, "build_sui_project: expecting move_path to link to a build directory"

    shutil.copytree(context.move_path, Util.get_build_dir() / Path(context.move_path).name)

    sources: Set[Path] = set()
    if getattr(context, 'conf_file', None) and Path(context.conf_file).exists():
        sources.add(Path(context.conf_file).absolute())

    try:
        # Create generators
        build_source_tree(sources, context)

    except Exception as e:
        raise Util.CertoraUserInputError(f"Collecting build files failed with the exception: {e}")
