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

from Shared import certoraUtils as Util
from pathlib import Path
from functools import lru_cache
from typing import Set, Callable
import re
import logging
from CertoraProver.Compiler.CompilerCollector import CompilerLang, CompilerCollector
from CertoraProver.Compiler.CompilerCollectorSol import CompilerCollectorSol, CompilerLangSol
from CertoraProver.Compiler.CompilerCollectorYul import CompilerLangYul, CompilerCollectorYul
from CertoraProver.Compiler.CompilerCollectorVy import CompilerCollectorVy, CompilerLangVy
from Shared.certoraUtils import match_path_to_mapping_key, remove_file, get_certora_config_dir
from CertoraProver.certoraContextClass import CertoraContext

# logger for running the Solidity compiler and reporting any errors it emits
compiler_logger = logging.getLogger("compiler")


def get_relevant_compiler(contract_file_path: Path, context: CertoraContext) -> str:
    """
    @param contract_file_path: the contract that we are working on
    @param context: context object
    @return: the name of the compiler executable we want to run on this file (as a string, could be a path
             or a resolvable executable name)
    """

    if contract_file_path.is_absolute():
        contract_file_path = Path(os.path.relpath(contract_file_path, Path.cwd()))
    if context.compiler_map:
        match = match_path_to_mapping_key(contract_file_path, context.compiler_map)
        if match:
            return match
        else:
            raise RuntimeError(f'cannot match compiler to {contract_file_path} from compiler_map')

    if Util.is_vyper_file(str(contract_file_path)):
        if context.vyper:
            return context.vyper
        else:
            return Util.DEFAULT_VYPER_COMPILER
    elif Util.is_solidity_file(str(contract_file_path)):
        if context.solc:
            return context.solc
        else:
            return Util.DEFAULT_SOLC_COMPILER
    raise RuntimeError(f'cannot match compiler to {contract_file_path}')


class CompilerCollectorFactory:
    """
    Returns [CompilerCollector] instance, based on type of the file [file_name] and the file path
    compiler_args: input args optimize and optimize_runs
    optimize_map: input arg mapping contract to optimized number of runs
    compiler_mappings: input arg mapping contract to the compiler executable (solc/vyper)
    default_compiler: compiler exec we want to run in case the specified file_name is not in compiler_mappings

    We added context as first step of making it the only parameters (the other params already appear in Context)
    """

    def __init__(self, context: CertoraContext):
        self.context = context
        self._stdout_paths_to_clean: Set[Path] = set()
        self._stderr_paths_to_clean: Set[Path] = set()

    @lru_cache(maxsize=32)
    def get_compiler_collector(self, path: Path) -> CompilerCollector:
        """
        1. Same file path will get the same compiler collector
        2. autoFinder_X file will get the compiler collector of X file
        @returns [CompilerCollector] instance, based on type of the file [file_name] and the file path
        @param path: path of the file to create [CompilerCollector] for
        """
        if str(path).endswith(".vy"):
            version = self.__get_vyper_version(path)
            return CompilerCollectorVy(version)
        elif str(path).endswith(".sol"):
            version = self.__get_solc_version(path)

            return CompilerCollectorSol(version, CompilerLangSol(), not self.context.no_memory_safe_autofinders)
        elif str(path).endswith(".yul"):
            version = self.__get_solc_version(path)
            return CompilerCollectorYul(version, CompilerLangYul())
        else:
            raise RuntimeError(f'expected {path} to represent a Solidity, Yul, or Vyper file')

    def __get_vyper_version(self, contract_file_path: Path) -> Util.CompilerVersion:
        """
        @param contract_file_path: the contract that we are working on
        @return: the running Vyper version
        """
        vyper_exec_to_run = get_relevant_compiler(contract_file_path, self.context)
        version = self.__get_compiler_exe_version(vyper_exec_to_run, self.__version_string_handler_vyper)
        return version

    def __get_solc_version(self, contract_file_path: Path) -> Util.CompilerVersion:
        """
        @param contract_file_path: the contract that we are working on
        @return: the running solc version
        """
        compiler_logger.debug(f"visiting contract file {contract_file_path}")
        solc_path = get_relevant_compiler(contract_file_path, self.context)
        version = self.__get_compiler_exe_version(solc_path, self.__version_string_handler_solc)
        return version

    @lru_cache(maxsize=32)
    def __get_compiler_exe_version(self, compiler_name: str,
                                   version_string_handler:
                                   Callable[[str], Util.CompilerVersion]) -> Util.CompilerVersion:
        """
        @param compiler_name: name of the solc we want to run on this contract
        @return: the running compiler version
        """
        out_name = f"version_check_{Path(compiler_name).name}"
        stdout_path = get_certora_config_dir() / f'{out_name}.stdout'
        stderr_path = get_certora_config_dir() / f'{out_name}.stderr'
        self._stdout_paths_to_clean.add(stdout_path)
        self._stderr_paths_to_clean.add(stderr_path)

        Util.run_compiler_cmd(
            f"{compiler_name} --version",
            wd=Path(os.getcwd()),
            output_file_name=out_name)

        with stdout_path.open() as r:
            version_string = r.read(-1)
        return version_string_handler(version_string)

    @staticmethod
    def __version_string_handler_vyper(version_string: str) -> Util.CompilerVersion:
        version_matches = re.findall(Util.version_triplet_regex(), version_string, re.MULTILINE)
        if len(version_matches) != 1:
            msg = f"Couldn't extract Vyper version from output {version_string}, giving up"
            compiler_logger.debug(msg)
            raise RuntimeError(msg)
        match = version_matches[0]
        return int(match[0]), int(match[1]), int(match[2])

    @staticmethod
    def __version_string_handler_solc(version_string: str) -> Util.CompilerVersion:
        version_matches = re.findall(Util.version_triplet_regex(prefix="Version: "), version_string, re.MULTILINE)
        if len(version_matches) != 1:
            msg = f"Couldn't extract Solidity version from output {version_string}, giving up"
            compiler_logger.debug(msg)
            raise RuntimeError(msg)
        match = version_matches[0]
        return int(match[0]), int(match[1]), int(match[2])

    def __del__(self) -> None:
        for path in self._stdout_paths_to_clean:
            remove_file(path)
        for path in self._stderr_paths_to_clean:
            remove_file(path)


def get_compiler_lang(file_name: str) -> CompilerLang:
    """
    Returns [CompilerLang] instance, based on type of the file [file_name]
    :param file_name: name of the file to create [CompilerLang] from
    """
    if file_name.endswith(".vy"):
        return CompilerLangVy()
    elif file_name.endswith(".sol"):
        return CompilerLangSol()
    elif file_name.endswith(".yul"):
        return CompilerLangYul()
    else:
        raise RuntimeError(f'expected {file_name} to represent a Solidity or Vyper file')
