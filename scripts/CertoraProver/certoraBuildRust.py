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
import json
import os
import shutil
import subprocess
import time
import logging
from collections.abc import Iterable
from pathlib import Path
from typing import Any, Set, Dict, cast

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

    if getattr(context, 'solana_sbf_arch', None) is not None:
        build_command.extend(["--arch", context.solana_sbf_arch])

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
    if hasattr(context, 'rust_project_directory'):
        project_directory = Path(context.rust_project_directory)

        if not project_directory.is_dir():
            raise ValueError(f"The given directory '{project_directory}' is not valid.")

        collect_declared_sources(context, project_directory, sources)
        sources.update(collect_build_inputs(context, project_directory.resolve()))

        sources.add(project_directory.absolute())
        if context.build_script:
            sources.add(Path(context.build_script).resolve())
    if getattr(context, 'conf_file', None) and Path(context.conf_file).exists():
        sources.add(Path(context.conf_file).absolute())

    add_solana_files_to_sources(context, sources)


def collect_declared_sources(context: CertoraContext, project_directory: Path, sources: Set[Path]) -> None:
    """
    The files matched by the `sources` globs the build reported (from `[package.metadata.certora]` or the build
    script's JSON), restricted to the kinds of files the report knows how to show.
    """
    patterns = ["*.rs", "*.so", "*.wasm", Util.CARGO_TOML_FILE, "Cargo.lock", "justfile"]
    exclude_dirs = [".certora_internal"]

    for source in context.rust_sources:
        for file in glob.glob(f'{project_directory.joinpath(source)}', recursive=True):
            file_path = Path(file)
            if any(excluded in file_path.parts for excluded in exclude_dirs):
                continue
            if file_path.is_file() and any(file_path.match(pattern) for pattern in patterns):
                sources.add(file_path)


# cargo and rustup look these up in the working directory and each of its ancestors
CARGO_CONFIG_FILES = [".cargo/config.toml", ".cargo/config", "rust-toolchain.toml", "rust-toolchain"]


def collect_build_inputs(context: CertoraContext, project_directory: Path) -> set[Path]:
    """
    Every local file the cargo build actually consumed, so that the uploaded sources suffice to rebuild the artifact:
    the files listed in cargo's dep-info for the artifact, the manifests of all path packages in the dependency graph,
    the workspace manifest and lockfile, and the cargo/rustup configuration files visible from the working
    directories. Each part degrades independently; a missing dep-info or a failing `cargo metadata` only leaves that
    part out, since the artifact may have been prebuilt or the build script may not go through cargo at all.
    """
    metadata = cargo_metadata(project_directory)
    workspace_root = Path(metadata["workspace_root"]).resolve() if metadata else project_directory
    target_directory = Path(metadata["target_directory"]).resolve() if metadata else workspace_root / "target"
    local_manifests: set[Path] = set()
    if metadata:
        local_manifests = {Path(p["manifest_path"]).resolve() for p in metadata["packages"] if p["source"] is None}

    inputs: set[Path] = {workspace_root / Util.CARGO_TOML_FILE, workspace_root / "Cargo.lock"}
    inputs.update(local_manifests)

    dep_info = find_dep_info(Path(context.files[0]).resolve(), target_directory)
    if dep_info is None:
        log.debug(f"No cargo dep-info found for {context.files[0]}; uploading only the declared sources")
    else:
        dep_files = parse_dep_info(dep_info, workspace_root)
        log.debug(f"Read {len(dep_files)} build inputs from {dep_info}")
        inputs.update(dep_files)
        # the manifests of the crates these files belong to, for when `cargo metadata` was unavailable
        for directory in {f.parent for f in dep_files}:
            manifest = enclosing_manifest(directory, workspace_root)
            if manifest is not None:
                inputs.add(manifest)

    start_dirs = {Path.cwd().resolve(), workspace_root, project_directory} | {m.parent for m in local_manifests}
    inputs.update(find_toolchain_config_files(start_dirs))

    excluded_roots = [target_directory, *external_tool_roots()]

    def is_project_file(file: Path) -> bool:
        return file.is_file() and ".certora_internal" not in file.parts and \
            not any(file.is_relative_to(root) for root in excluded_roots)

    return {f for f in inputs if is_project_file(f)}


def cargo_metadata(project_directory: Path) -> dict[str, Any] | None:
    """
    `cargo metadata` for the workspace containing project_directory, or None if it cannot be obtained. `--frozen`
    keeps cargo off the network and away from the lockfile; `--all-features` lists optional path dependencies too,
    since cargo reads their manifests whether or not they end up compiled.
    """
    manifest = project_directory / Util.CARGO_TOML_FILE
    if not manifest.is_file():
        return None
    cmd = ["cargo", "metadata", "--format-version", "1", "--frozen", "--all-features",
           "--manifest-path", str(manifest)]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.TimeoutExpired) as e:
        log.debug(f"Could not run `{' '.join(cmd)}`: {e}")
        return None
    if result.returncode != 0:
        log.debug(f"`{' '.join(cmd)}` failed:\n{result.stderr}")
        return None
    try:
        metadata = json.loads(result.stdout)
    except json.JSONDecodeError as e:
        log.debug(f"Could not parse the output of `{' '.join(cmd)}`: {e}")
        return None
    return metadata if isinstance(metadata, dict) else None


def find_dep_info(artifact: Path, target_directory: Path) -> Path | None:
    """
    Cargo writes the dep-info of an uplifted artifact next to it, with the artifact's stem and a `.d` suffix. A build
    script may report a copy of the artifact instead, so fall back to looking for that stem in the profile directories
    under the cargo target directory, taking the most recently written one.
    """
    beside = artifact.with_suffix(".d")
    if beside.is_file():
        return beside
    if not target_directory.is_dir():
        return None
    candidates = [p for pattern in [f"*/{artifact.stem}.d", f"*/*/{artifact.stem}.d"]
                  for p in target_directory.glob(pattern) if p.is_file()]
    return max(candidates, key=lambda p: cast(Path, p).stat().st_mtime, default=None)


def parse_dep_info(dep_info: Path, base_dir: Path) -> set[Path]:
    """
    Parse a cargo dep-info file. It is in Makefile syntax: `target: prerequisite prerequisite ...`, with spaces inside
    paths escaped as `\\ ` and long lines continued with a trailing backslash. Cargo emits absolute paths unless
    `build.dep-info-basedir` is configured, so relative paths are resolved against base_dir.
    """
    text = dep_info.read_text().replace("\\\n", " ")
    files: set[Path] = set()
    for line in text.splitlines():
        tokens = split_make_tokens(line)
        if tokens and tokens[0].endswith(":"):
            prerequisites = tokens[1:]
        elif len(tokens) > 1 and tokens[1] == ":":
            prerequisites = tokens[2:]
        else:
            continue
        for token in prerequisites:
            path = Path(token)
            files.add((path if path.is_absolute() else base_dir / path).resolve())
    return files


def split_make_tokens(line: str) -> list[str]:
    """
    Split a Makefile rule line on unescaped whitespace, undoing the `\\ `, `\\#`, `\\\\` and `$$` escapes.
    """
    tokens: list[str] = []
    current: list[str] = []
    i = 0
    while i < len(line):
        char = line[i]
        if char == "\\" and i + 1 < len(line) and line[i + 1] in " #\\":
            current.append(line[i + 1])
            i += 2
        elif line.startswith("$$", i):
            current.append("$")
            i += 2
        elif char.isspace():
            if current:
                tokens.append("".join(current))
                current = []
            i += 1
        else:
            current.append(char)
            i += 1
    if current:
        tokens.append("".join(current))
    return tokens


def enclosing_manifest(directory: Path, workspace_root: Path) -> Path | None:
    """
    The manifest of the crate a source directory belongs to: the nearest Cargo.toml at or above it, not looking
    further up than the workspace root.
    """
    for candidate_dir in [directory, *directory.parents]:
        manifest = candidate_dir / Util.CARGO_TOML_FILE
        if manifest.is_file():
            return manifest
        if candidate_dir == workspace_root:
            return None
    return None


def find_toolchain_config_files(start_dirs: Iterable[Path]) -> set[Path]:
    """
    cargo and rustup read their configuration from the working directory and each of its ancestors. The walk stops
    before the home directory and anything above it: configuration there is the user's rather than the project's, and
    including it would hoist the root of .certora_sources up to the home directory.
    """
    home = Path.home().resolve()
    user_level_dirs = {home, *home.parents}
    found: set[Path] = set()
    for start in start_dirs:
        for directory in [start, *start.parents]:
            if directory in user_level_dirs and directory != start:
                break
            for name in CARGO_CONFIG_FILES:
                candidate = directory / name
                if candidate.is_file():
                    found.add(candidate)
    return found


def external_tool_roots() -> list[Path]:
    """
    Where toolchains and registry/git checkouts live. Files under these are never part of the user's project.
    """
    home = Path.home()
    return [Path(os.environ.get("CARGO_HOME", home / ".cargo")).resolve(),
            Path(os.environ.get("RUSTUP_HOME", home / ".rustup")).resolve(),
            Path(os.environ.get("CERTORA_PLATFORM_TOOLS_ROOT", home / ".cache" / "solana")).resolve()]


def copy_files_to_build_dir(context: CertoraContext) -> None:
    assert context.files, "copy_files_to_build_dir: expecting files to be non-empty"
    shutil.copyfile(context.files[0], Util.get_build_dir() / Path(context.files[0]).name)

    if rust_logs := getattr(context, 'rust_logs_stdout', None):
        shutil.copy(Path(rust_logs), Util.get_build_dir() / Path(rust_logs).name)
    if rust_logs := getattr(context, 'rust_logs_stderr', None):
        shutil.copy(Path(rust_logs), Util.get_build_dir() / Path(rust_logs).name)
