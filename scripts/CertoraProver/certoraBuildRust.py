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

import glob
import shutil
import time
import logging
from pathlib import Path
from typing import Set, Dict

from CertoraProver.certoraBuild import build_source_tree
from CertoraProver.certoraContextClass import CertoraContext
from CertoraProver.certoraParseBuildScript import run_rust_build
import CertoraProver.certoraContextAttributes as Attrs
from Shared import certoraUtils as Util


log = logging.getLogger(__name__)


def build_rust_project(context: CertoraContext, timings: Dict) -> None:
    """
    Compile the Rust artefact and record elapsed time in *timings*.

    Args:
        context: The CertoraContext object containing the configuration.
        timings: A dictionary to store timing information.
    """
    log.debug("Build Rust target")
    start = time.perf_counter()
    set_rust_build_directory(context)
    timings["buildTime"] = round(time.perf_counter() - start, 4)
    if context.test == str(Util.TestValue.AFTER_BUILD):
        raise Util.TestResultsReady(context)


def set_rust_build_directory(context: CertoraContext) -> None:
    if not context.files:
        build_rust_app(context)

    copy_files_to_build_dir(context)

    sources: Set[Path] = set()
    collect_files_from_rust_sources(context, sources)

    try:
        # Create generators
        build_source_tree(sources, context)

    except Exception as e:
        raise Util.CertoraUserInputError(f"Collecting build files failed with the exception: {e}")


def build_rust_app(context: CertoraContext) -> None:
    assert not context.files, "build_rust_app: expecting files to be empty"
    if context.build_script:
        build_command = [context.build_script, '--json', '-l']
        feature_flag = '--cargo_features'
    else:  # cargo
        build_command = ["cargo", "certora-sbf", '--json']
        feature_flag = '--features'
        if context.cargo_tools_version:
            build_command.extend(["--tools-version", context.cargo_tools_version])
        context.rust_project_directory = Util.find_nearest_cargo_toml()

    if context.cargo_features is not None:
        build_command.append(feature_flag)
        build_command.append(' '.join(context.cargo_features))

    if context.test == str(Util.TestValue.SOLANA_BUILD_CMD):
        raise Util.TestResultsReady(build_command)

    run_rust_build(context, build_command)


def add_solana_files_to_sources(context: CertoraContext, sources: Set[Path]) -> None:
    for attr in [Attrs.SolanaProverAttributes.SOLANA_INLINING,
                 Attrs.SolanaProverAttributes.SOLANA_SUMMARIES,
                 Attrs.SolanaProverAttributes.BUILD_SCRIPT,
                 Attrs.SolanaProverAttributes.FILES]:
        attr_name = attr.get_conf_key()
        attr_value = getattr(context, attr_name, None)
        if not attr_value:
            continue
        if isinstance(attr_value, str):
            attr_value = [attr_value]
        if not isinstance(attr_value, list):
            raise Util.CertoraUserInputError(f"{attr_value} is not a valid value for {attr_name} {attr_value}. Value "
                                             f"must be a string or a llist ")
        file_paths = [Path(s) for s in attr_value]
        for file_path in file_paths:
            if not file_path.exists():
                raise Util.CertoraUserInputError(f"in {attr_name} file {file_path} does not exist")
            sources.add(file_path.absolute().resolve())


def collect_files_from_rust_sources(context: CertoraContext, sources: Set[Path]) -> None:
    patterns = ["*.rs", "*.so", "*.wasm", Util.CARGO_TOML_FILE, "Cargo.lock", "justfile"]
    exclude_dirs = [".certora_internal"]

    if hasattr(context, 'rust_project_directory'):
        project_directory = Path(context.rust_project_directory)

        if not project_directory.is_dir():
            raise ValueError(f"The given directory '{project_directory}' is not valid.")

        for source in context.rust_sources:
            for file in glob.glob(f'{project_directory.joinpath(source)}', recursive=True):
                file_path = Path(file)
                if any(excluded in file_path.parts for excluded in exclude_dirs):
                    continue
                if file_path.is_file() and any(file_path.match(pattern) for pattern in patterns):
                    sources.add(file_path)

        sources.add(project_directory.absolute())
        if context.build_script:
            sources.add(Path(context.build_script).resolve())
    if getattr(context, 'conf_file', None) and Path(context.conf_file).exists():
        sources.add(Path(context.conf_file).absolute())

    add_solana_files_to_sources(context, sources)


def copy_files_to_build_dir(context: CertoraContext) -> None:
    assert context.files, "copy_files_to_build_dir: expecting files to be non-empty"
    shutil.copyfile(context.files[0], Util.get_build_dir() / Path(context.files[0]).name)

    if rust_logs := getattr(context, 'rust_logs_stdout', None):
        shutil.copy(Path(rust_logs), Util.get_build_dir() / Path(rust_logs).name)
    if rust_logs := getattr(context, 'rust_logs_stderr', None):
        shutil.copy(Path(rust_logs), Util.get_build_dir() / Path(rust_logs).name)
