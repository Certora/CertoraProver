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

import glob
import shutil
from pathlib import Path
from typing import Set

from CertoraProver.certoraBuild import build_source_tree
from CertoraProver.certoraContextClass import CertoraContext
from CertoraProver.certoraParseBuildScript import run_script_and_parse_json
from Shared import certoraUtils as Util


def build_rust_app(context: CertoraContext) -> None:
    if context.build_script:
        run_script_and_parse_json(context)
        if not context.rust_executables:
            raise Util.CertoraUserInputError("failed to get target executable")

        sources: Set[Path] = set()
        root_directory = Path(context.rust_project_directory)
        collect_files_from_rust_sources(context, sources, root_directory)

        try:
            # Create generators
            build_source_tree(sources, context)

            copy_files_to_build_dir(context)

        except Exception as e:
            raise Util.CertoraUserInputError(f"Collecting build files failed with the exception: {e}")
    else:
        if not context.files:
            raise Util.CertoraUserInputError("'files' or 'build_script' must be set for Rust projects")
        if len(context.files) > 1:
            raise Util.CertoraUserInputError("Rust projects must specify exactly one executable in 'files'.")
        context.rust_executables = context.files[0]

def add_solana_files(context: CertoraContext, sources: Set[Path], attr_name: str) -> None:
    attr_value = getattr(context, attr_name, None)
    if not attr_value or not isinstance(attr_value, list):
        return
    file_paths = [Path(s) for s in attr_value]
    for file_path in file_paths:
        if not file_path.exists():
            raise Util.CertoraUserInputError(f"in {attr_name} file {file_path} does not exist")
        sources.add(file_path.absolute())


def collect_files_from_rust_sources(context: CertoraContext, sources: Set[Path], root_directory: Path) -> None:
    patterns = ["*.rs", "*.so", "*.wasm", "Cargo.toml", "Cargo.lock", "justfile"]
    exclude_dirs = [".certora_internal"]

    if not root_directory.is_dir():
        raise ValueError(f"The given directory {root_directory} is not valid.")

    for source in context.rust_sources:
        for file in glob.glob(f'{root_directory.joinpath(source)}', recursive=True):
            file_path = Path(file)
            if any(excluded in file_path.parts for excluded in exclude_dirs):
                continue
            if file_path.is_file() and any(file_path.match(pattern) for pattern in patterns):
                sources.add(file_path)

    if Path(context.build_script).exists():
        sources.add(Path(context.build_script).resolve())
    if getattr(context, 'conf_file', None) and Path(context.conf_file).exists():
        sources.add(Path(context.conf_file).absolute())
    add_solana_files(context, sources, 'solana_inlining')
    add_solana_files(context, sources, 'solana_summaries')

def copy_files_to_build_dir(context: CertoraContext) -> None:
    rust_executable = Path(context.rust_project_directory) / context.rust_executables
    shutil.copyfile(rust_executable, Util.get_build_dir() / rust_executable.name)

    additional_files = (getattr(context, 'solana_inlining', None) or []) + \
                       (getattr(context, 'solana_summaries', None) or [])

    for file in additional_files:
        file_path = Path(file).resolve()
        shutil.copy(file_path, Util.get_build_dir() / file_path.name)

    if rust_logs := getattr(context, 'rust_logs_stdout', None):
        shutil.copy(Path(rust_logs), Util.get_build_dir() / Path(rust_logs).name)
    if rust_logs := getattr(context, 'rust_logs_stderr', None):
        shutil.copy(Path(rust_logs), Util.get_build_dir() / Path(rust_logs).name)
