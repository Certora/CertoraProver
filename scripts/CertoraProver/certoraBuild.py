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

import enum
import json
import logging
import os
import re
import shutil
import sys
import typing
from collections import OrderedDict, defaultdict
from enum import Enum
from functools import lru_cache
from pathlib import Path
from Crypto.Hash import keccak
import tempfile

from typing import Any, Dict, List, Tuple, Optional, Set, Iterator, NoReturn


from CertoraProver.certoraBuildCacheManager import CertoraBuildCacheManager, CachedFiles
from CertoraProver.certoraBuildDataClasses import CONTRACTS, ImmutableReference, ContractExtension, ContractInSDC, SDC, \
    Instrumentation, InsertBefore, InsertAfter, UnspecializedSourceFinder, instrumentation_logger
from CertoraProver.certoraCompilerParameters import SolcParameters
from CertoraProver.certoraSourceFinders import add_source_finders
from CertoraProver.certoraVerifyGenerator import CertoraVerifyGenerator
from CertoraProver.certoraContractFuncs import Func, InternalFunc, STATEMUT, SourceBytes

scripts_dir_path = Path(__file__).parent.parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))
from CertoraProver.Compiler.CompilerCollector import CompilerLang, CompilerCollector
from CertoraProver.Compiler.CompilerCollectorSol import CompilerCollectorSol, CompilerLangSol
from CertoraProver.Compiler.CompilerCollectorYul import CompilerLangYul, CompilerCollectorYul
from CertoraProver.Compiler.CompilerCollectorVy import CompilerLangVy
from CertoraProver.Compiler.CompilerCollectorFactory import CompilerCollectorFactory, \
    get_relevant_compiler, get_compiler_lang
from CertoraProver.certoraNodeFilters import NodeFilters as Nf
import CertoraProver.certoraType as CT

from CertoraProver.certoraContextClass import CertoraContext
import CertoraProver.certoraContextAttributes as Attrs
from Shared import certoraValidateFuncs as Vf
from CertoraProver import certoraContextValidator as Cv
from Shared import certoraUtils as Util
import CertoraProver.certoraContext as Ctx
from CertoraProver import storageExtension
from CertoraProver.storageExtension import (
    NameSpacedStorage,
    NewStorageInfo,
)

BUILD_IS_LIBRARY = False
AUTO_FINDER_PREFIX = "autoFinder_"
CONF_FILE_ATTR = 'conf_file'
INTERNALTYPE = "internalType"
TYPE = "type"
SOL = ".sol"
VY = ".vy"
INPUTS = "inputs"
OUTPUTS = "outputs"
FUNCTION = "function"
NAME = "name"
MANUAL_MUTANTS = "manual_mutants"
MUTANTS_LOCATION = "mutants_location"

FunctionSig = Tuple[str, List[str], List[str], str]


# logger for building the abstract syntax tree
ast_logger = logging.getLogger("ast")
# logger for issues calling/shelling out to external functions
process_logger = logging.getLogger("rpc")
# logger for running the Solidity compiler and reporting any errors it emits
compiler_logger = logging.getLogger("compiler")

# logger of the build configuration
build_logger = logging.getLogger("build_conf")
# logger of the build cache
build_cache_logger = logging.getLogger("build_cache")

def fatal_error(logger: logging.Logger, msg: str) -> NoReturn:
    logger.fatal(msg)
    raise Exception(msg)

class InputConfig:
    def __init__(self, context: CertoraContext) -> None:
        """
        A class holding relevant attributes for the build string.
        :param context: command line input argument in an argparse.Namespace
        """

        self.context = context
        # populate fields relevant for build, handle defaults
        self.sorted_files = sorted(list(context.file_paths))
        self.fileToContractName = context.file_to_contract
        self.contract_to_file = context.contract_to_file
        self.prototypes = self.handle_prototypes(context)

    def __str__(self) -> str:
        return str(self.__dict__)

    @staticmethod
    def handle_prototypes(context: CertoraContext) -> Dict[str, List[str]]:
        to_ret: Dict[str, List[str]] = dict()
        if context.prototype is None:
            return to_ret
        for proto in context.prototype:
            (sig, nm) = proto.split("=")
            if nm not in to_ret:
                to_ret[nm] = []
            to_ret[nm].append(sig)
        return to_ret


class FinderGenerator(object):
    def __init__(self, internal_id: int):
        self.internal_id = internal_id
        self.alpha_renamings: List[str] = []

    def gen_key(self, flag: int) -> str:
        return f'0xffffff6e4604afefe123321beef1b01fffffffffffffffffffffffff{"%0.4x" % self.internal_id}{"%0.4x" % flag}'

    @staticmethod
    def is_decomposed(arg_ty: CT.TypeInstance) -> bool:
        if isinstance(arg_ty.type, CT.ArrayType):
            return arg_ty.location == CT.TypeLocation.CALLDATA and not arg_ty.type.is_static_array()

        if isinstance(arg_ty.type, CT.PackedBytes) or isinstance(arg_ty.type, CT.StringType):
            return arg_ty.location == CT.TypeLocation.CALLDATA

        return False

    @staticmethod
    def is_calldata_candidate_type(arg_ty: CT.Type) -> bool:
        return isinstance(arg_ty, CT.StringType) or isinstance(arg_ty, CT.PackedBytes) or \
            isinstance(arg_ty, CT.ArrayType) or isinstance(arg_ty, CT.StructType)

    @staticmethod
    def is_calldata_pointer(arg_ty: CT.TypeInstance) -> bool:
        return FinderGenerator.is_calldata_candidate_type(arg_ty.type) and \
            arg_ty.location == CT.TypeLocation.CALLDATA and not FinderGenerator.is_decomposed(arg_ty)

    def normalize_arg(self, idx: int, ty: CT.TypeInstance, arg_name: str) -> Optional[str]:
        if not FinderGenerator.is_opcode(v_name=arg_name):
            return arg_name
        renamed = f"certoraRename{self.internal_id}_{idx}"
        if ty.get_source_str() is None:
            instrumentation_logger.debug(f"Need to rename {arg_name} with type {ty}, but"
                                         f" it doesn't have a type declaration")
            return None
        self.alpha_renamings.append(f"{ty.get_source_and_location_str()} {renamed} = {arg_name};")
        return renamed

    @staticmethod
    def is_opcode(v_name: str) -> bool:
        return v_name in {"stop", "add", "sub", "mul", "div", "sdiv", "mod", "smod", "exp", "not", "lt", "gt",
                          "slt", "sgt", "eq", "iszero", "and", "or", "xor", "byte", "shl", "shr", "sar", "addmod",
                          "mulmod", "signextend", "keccak256", "pc", "pop", "mload", "mstore", "mstore8", "sload",
                          "sstore", "msize", "gas", "address", "balance", "selfbalance", "caller", "callvalue",
                          "calldataload", "calldatasize", "calldatacopy", "codesize", "codecopy", "extcodesize",
                          "extcodecopy", "returndatasize", "returndatacopy", "extcodehash", "create", "create2",
                          "call", "callcode", "delegatecall", "staticcall", "return", "revert", "selfdestruct",
                          "invalid", "log0", "log1", "log2", "log3", "log4", "chainid", "basefee", "origin",
                          "gasprice", "blockhash", "coinbase", "timestamp", "number", "difficulty", "gaslimit"}

    def renaming(self) -> str:
        return " ".join(self.alpha_renamings)


class PresetImmutableReference(ImmutableReference):
    def __init__(self,
                 offset: str,
                 length: str,
                 varname: str,
                 value: str,
                 type: CT.TypeInstance
                 ):
        ImmutableReference.__init__(self, offset, length, varname, type)
        self.value = value

    def as_dict(self) -> Dict[str, Any]:
        _dict = ImmutableReference.as_dict(self)
        _dict["value"] = self.value
        return _dict

    def __repr__(self) -> str:
        return repr(self.as_dict())


# this function is Solidity specific.
# todo: create certoraBuildUtilsSol.py file, where such solidity specific functions will be.
def generate_finder_body(f: Func, internal_id: int, sym: int, compiler_collector: CompilerCollectorSol,
                         compressed: bool = False) -> Optional[Tuple[List[int], str]]:
    if compressed:
        return generate_compressed_finder(
            f, internal_id, sym, compiler_collector
        )
    else:
        return generate_full_finder(
            f, internal_id, sym, compiler_collector
        )


def generate_compressed_finder(f: Func, internal_id: int, sym: int,
                               compiler_collector: CompilerCollectorSol) -> Optional[Tuple[List[int], str]]:
    finder_gen = FinderGenerator(internal_id)
    last_loggable_arg = None
    num_symbols = 0
    for i in range(0, len(f.fullArgs)):
        num_symbols += 1
        ty = f.fullArgs[i]
        if f.paramNames[i] != "" and (not finder_gen.is_decomposed(ty) or
                                      compiler_collector.supports_calldata_assembly(f.paramNames[i])):
            last_loggable_arg = i
        if finder_gen.is_decomposed(ty):
            num_symbols += 1

    type_layout = 0
    for ty in f.fullArgs:
        # generate the layout according to this spec:
        # https://www.notion.so/certora/Certora-Autofinder-Binary-Format-45327dff9de64b2fb1fdac4b1ec4d7f9
        if finder_gen.is_decomposed(ty):
            type_layout = (type_layout << 6 | 0b11010)
        elif finder_gen.is_calldata_pointer(ty):
            type_layout = (type_layout << 3 | 0b100)
        else:
            type_layout = (type_layout << 3) | 0b1
    assembly_prefix = compiler_collector.gen_memory_safe_assembly_prefix()
    common_prefix = f'{assembly_prefix} {{ mstore({finder_gen.gen_key(0)}, {sym}) ' \
                    f"mstore({finder_gen.gen_key(1)}, {num_symbols}) "

    if last_loggable_arg is None:
        to_return = common_prefix
        to_return += f"mstore({finder_gen.gen_key(4)}, {type_layout}) }}"
        return [], to_return
    else:
        logged_ty = f.fullArgs[last_loggable_arg]
        logged_name = f.paramNames[last_loggable_arg]
        body = ""

        alpha_renamed = finder_gen.normalize_arg(last_loggable_arg, logged_ty, logged_name)
        if alpha_renamed is None:
            instrumentation_logger.debug(f"Failed to alpha rename arg {last_loggable_arg}: "
                                         f"{logged_name} {logged_ty}")
            return None
        body += finder_gen.renaming()

        body += common_prefix
        body += f"mstore({finder_gen.gen_key(5)}, {type_layout}) "
        symbol_offset = 0
        for idx in range(0, last_loggable_arg):
            symbol_offset += 1
            if finder_gen.is_decomposed(f.fullArgs[idx]):
                symbol_offset += 1
        if finder_gen.is_decomposed(logged_ty):
            flag = 0x6100 + symbol_offset
            to_log = alpha_renamed + ".offset"
        elif finder_gen.is_calldata_pointer(logged_ty):
            flag = 0x6200 + symbol_offset
            # we *know* this is not storage, because it's a calldata pointer
            to_log = alpha_renamed
        else:
            flag = 0x6000 + symbol_offset
            to_log = \
                compiler_collector.normalize_storage(logged_ty.location == CT.TypeLocation.STORAGE, alpha_renamed)
        body += f"mstore({finder_gen.gen_key(flag)}, {to_log}) }}"
        return [last_loggable_arg], body


def generate_full_finder(f: Func, internal_id: int, sym: int,
                         compiler_collector: CompilerCollectorSol) -> Optional[Tuple[List[int], str]]:
    finder_gen = FinderGenerator(internal_id=internal_id)
    assembly_prefix = compiler_collector.gen_memory_safe_assembly_prefix()
    to_ret = assembly_prefix + ' { '
    to_ret += f"mstore({finder_gen.gen_key(0)}, {sym}) "
    num_arg_symbols = 0
    for arg_ty in f.fullArgs:
        num_arg_symbols += 1
        if finder_gen.is_decomposed(arg_ty):
            num_arg_symbols += 1
    to_ret += f"mstore({finder_gen.gen_key(1)}, {num_arg_symbols}) "
    used_symbols = []
    for i, ty in enumerate(f.fullArgs):
        arg_name = finder_gen.normalize_arg(i, ty, f.paramNames[i])
        used_symbols.append(i)
        if arg_name is None:
            return None

        if finder_gen.is_decomposed(ty):
            if compiler_collector.supports_calldata_assembly(arg_name):
                len_flag = 0x2000 + i
                offset_flag = 0x3000 + i
                # special encoding
                to_ret += f"mstore({finder_gen.gen_key(offset_flag)}, {arg_name}.offset) "
                to_ret += f"mstore({finder_gen.gen_key(len_flag)}, {arg_name}.length) "
            else:
                # place holder
                to_ret += f"mstore({finder_gen.gen_key(0x4000 + i)}, 0) "
            continue
        elif finder_gen.is_calldata_pointer(ty):
            # Why would the argument name be empty? Well, solidity let's you omit an argument name for a parameter
            # but will still pass the value on the stack. So this is how we detect the argument name is empty,
            # i.e., doesn't exist
            if len(arg_name) == 0:
                placeholder_flag = 0x8000 + i
                to_ret += f"mstore({finder_gen.gen_key(placeholder_flag)}, 0)"
            else:
                log_flag = 0x7000 + i
                to_ret += f"mstore({finder_gen.gen_key(log_flag)}, {arg_name})"
            continue
        if len(arg_name) == 0:
            to_ret += f"mstore({finder_gen.gen_key(0x5000 + i)}, 0) "
            continue
        flag = 0x1000 + i
        arg = compiler_collector.normalize_storage(getattr(ty, "location", None) == CT.TypeLocation.STORAGE, arg_name)
        to_ret += f"mstore({finder_gen.gen_key(flag)}, {arg}) "
    to_ret += "}"
    return used_symbols, finder_gen.renaming() + to_ret


def reprint_ast_type(node: Dict[str, Any]) -> Optional[str]:
    """
       Tries to pretty print the source code that was passed as node. That is, produce the concrete syntax that
       was parsed by the compiler and then represented by node. This can differ from the "canonical" printing, as it
       may not include the contract qualifier, as this is not always in scope.
    """
    if "nodeType" not in node:
        return None
    typ = node["nodeType"]
    if typ == "ArrayTypeName":
        if "baseType" not in node:
            return None
        reprint_base = reprint_ast_type(node["baseType"])
        if reprint_base is None:
            return None
        if "length" in node:
            lnode = node["length"]
            if type(lnode) is not dict or "nodeType" not in lnode or lnode["nodeType"] != "Literal" or \
                    "value" not in lnode:
                return None
            len_str = lnode["value"]
        else:
            len_str = ""
        return f"{reprint_base}[{len_str}]"
    elif typ == "UserDefinedTypeName":
        if "pathNode" not in node:
            return None
        p_node = node["pathNode"]
        if type(p_node) is not dict or "nodeType" not in p_node or \
                p_node["nodeType"] != "IdentifierPath" or "name" not in p_node:
            return None
        return p_node["name"]
    else:
        return None


def get_modifier_param_type_name(ind: int, def_node: Dict[str, Any], f: Func) -> str:
    """
       Get the type name to use in the modifier for paramter ind. Attempts to reprint the
       literal source code used in the original contract (via reprint_ast_type) or falls back
       to the canonical type name (which may not be valid syntax, see https://certora.atlassian.net/browse/CERT-6284
    """
    fallback = f.fullArgs[ind].get_source_and_location_str()
    d = def_node.get("parameters", None)
    if type(d) is not dict or d["nodeType"] != "ParameterList" or "parameters" not in d:
        return fallback
    params = d["parameters"]
    if type(params) is not list or len(params) != len(f.fullArgs):
        return fallback
    param_def_node = params[ind]
    if type(param_def_node) is not dict or param_def_node["nodeType"] != "VariableDeclaration" or \
            "typeName" not in param_def_node:
        return fallback
    param_type_node = param_def_node["typeName"]
    if type(param_type_node) is not dict:
        return fallback
    reprint = reprint_ast_type(param_type_node)
    if reprint is None:
        return fallback
    loc_string = ""
    if "storageLocation" in param_def_node:
        if param_def_node["storageLocation"] == "memory":
            loc_string = " memory"
        elif param_def_node["storageLocation"] == "calldata":
            loc_string = " calldata"
        elif param_def_node["storageLocation"] == "storage":
            loc_string = " storage"
    return f"{reprint}{loc_string}"


def generate_modifier_finder(f: Func, internal_id: int, sym: int,
                             compiler_collector: CompilerCollectorSol, def_node: Dict[str, Any],
                             compress: bool) -> Optional[Tuple[str, str]]:
    compressed = generate_finder_body(f, internal_id, sym, compiler_collector, compressed=compress)
    if compressed is None:
        return None
    modifier_name = f"logInternal{internal_id}"
    if len(compressed[0]) == 0:
        return f"{modifier_name}()", f"modifier {modifier_name}() {{ {compressed[1]} _; }}"
    else:
        loggable_types = map(lambda e: get_modifier_param_type_name(e, def_node, f), compressed[0])
        loggable_names = map(lambda e: f.paramNames[e], compressed[0])
        formal_strings = []
        arg_strings = []
        for (logged_ty, logged_name) in zip(loggable_types, loggable_names):
            if logged_name == "":
                continue
            arg_strings.append(logged_name)
            formal_strings.append(f"{logged_ty} {logged_name}")
        modifier_body = f"modifier {modifier_name}"
        modifier_body += f'({",".join(formal_strings)}) {{ '
        modifier_body += compressed[1]
        modifier_body += " _; }"
        return f'{modifier_name}({",".join(arg_strings)})', modifier_body


def generate_inline_finder(f: Func, internal_id: int, sym: int,
                           compiler_collector: CompilerCollectorSol, should_compress: bool) -> Optional[str]:
    finder = generate_finder_body(f, internal_id, sym, compiler_collector, compressed=should_compress)
    if finder is None:
        return None
    return finder[1]


def convert_pathname_to_posix(json_dict: Dict[str, Any], entry: str, smart_contract_lang: CompilerLang) -> None:
    """
    assuming the values kept in the entry [entry] inside [json_dict] are path names
    :param json_dict: dict to iterate on
    :param entry: entry in [json_dict] to look at
    """
    if entry in json_dict:
        json_dict_posix_paths = {}
        for file_path in json_dict[entry]:
            path_obj = Path(smart_contract_lang.normalize_file_compiler_path_name(file_path))
            if path_obj.is_file():
                json_dict_posix_paths[path_obj.as_posix()] = json_dict[entry][file_path]
            else:
                json_dict_str = str(json_dict)
                # protecting against long strings
                if len(json_dict_str) > 200:
                    json_dict_str = json_dict_str[:200] + '...'
                fatal_error(compiler_logger, f"The path of the source file {file_path} "
                                             f"in the standard json file does not exist!\n{json_dict_str} ")
        json_dict[entry] = json_dict_posix_paths


class CertoraBuildGenerator:
    # 12,14,04,06,00,04,10 is 0xce4604a aka certora.
    CONSTRUCTOR_STRING = "constructor"
    FREEFUNCTION_STRING = "freeFunction"
    file_to_sdc_name: Dict[Path, str] = {}
    BASE_ADDRESS = (12 * 2 ** 24 + 14 * 2 ** 20 + 4 * 2 ** 16 + 6 * 2 ** 12 + 0 + 4 * 2 ** 4 + 10 * 2 ** 0)

    """ 6321 - "Unnamed return variable can remain unassigned"
          - emitted by solc versions 7.6 and up """
    SEVERE_COMPILER_WARNINGS = ["6321"]

    def __init__(self, input_config: InputConfig, context: CertoraContext) -> None:
        self.input_config = input_config
        self.context = context
        # SDCs describes the set of all 'Single Deployed Contracts' the solidity file whose contracts comprise a single
        # bytecode of interest. Which one it is - we don't know yet, but we make a guess based on the base filename.
        # An SDC corresponds to a single Solidity file.
        self.SDCs = {}  # type: Dict[str, SDC]

        build_logger.debug(f"Creating dir {Util.abs_posix_path(Util.get_certora_config_dir())}")
        Util.remove_and_recreate_dir(Util.get_certora_config_dir())

        self.library_addresses = []  # type: List[str]

        # ASTs will be lazily loaded
        # original source file -> contract file -> nodeid -> node
        # TODO - make this a proper class
        self.asts = {}  # type: Dict[str, Dict[str, Dict[int, Any]]]
        self.address_generator_counter = 0
        self.function_finder_generator_counter = 0
        self.function_finder_file_remappings: Dict[str, str] = {}
        self.compiler_coll_factory = CompilerCollectorFactory(self.context)
        # will be set to True if any autofinder generation failed
        self.auto_finders_failed = False
        self.source_finders_failed = False
        self.__compiled_artifacts_to_clean: Set[Tuple[str, CompilerLang]] = set()  # doesn't have to be persisted

    @staticmethod
    def CERTORA_CONTRACT_NAME() -> str:
        return Nf.CERTORA_CONTRACT_NAME()

    def check_primary_contact_is_in_build(self, verify_contract: Optional[str]) -> None:
        contract = verify_contract
        if contract is not None:
            if len(self.get_matching_sdc_names_from_SDCs(contract)) == 0:
                fatal_error(
                    build_logger,
                    f"Error: Could not find contract {contract} in contracts "
                    f"[{','.join(map(lambda x: x[1].primary_contract, self.SDCs.items()))}]")

    def is_library_def_node(self, contract_file: str, node_ref: int, build_arg_contract_file: str) -> bool:
        contract_def_node = self.asts[build_arg_contract_file][contract_file][node_ref]
        return "contractKind" in contract_def_node and contract_def_node["contractKind"] == "library"

    def get_contract_file_of(self, build_arg_contract_file: str, reference: int) -> str:
        """
        Returns the contract file that was created from a given original source file and contains the given node
        :param build_arg_contract_file: name of the original source file
        :param reference: the id of the node we are looking for
        :returns: the name of the contract file that contains this node
        """
        original_file_asts = self.asts[build_arg_contract_file]
        for contract in original_file_asts:
            if reference in original_file_asts[contract]:
                return contract
        # error if got here
        fatal_error(ast_logger, f"Could not find reference AST node {reference}")
        return ""

    def get_contract_file_of_non_autofinder(self, build_arg_contract_file: str, reference: int) -> str:
        """
        For purposes of type canonicalization, an autofinder is an alias of the file it instrumented. Therefore, we
        provide the option to find the file which the autofinder aliases so all canonical type names use the original
        file name (note this is necessary since targets of the function finder mappings--mapping generated key to method
        signature--are all written in terms of the original file).
        :param build_arg_contract_file: name of the original file
        :param reference:
        :return: the name of the non-aliased contract file that contains this node
        """
        file = Util.abs_posix_path(self.get_contract_file_of(build_arg_contract_file, reference))
        if file in self.function_finder_file_remappings.keys():
            ret = Path(self.function_finder_file_remappings[file])
        else:
            ret = Path(file)
        abs_sources_dir = Util.get_certora_sources_dir().absolute()
        if abs_sources_dir in ret.parents:
            # Normalize the file-name to never include the extra `.certora_internal/.../.certora_sources` part
            # of the path.
            ret = Util.abs_norm_path(ret.relative_to(abs_sources_dir))

        # things look nicer if they are rooted in .certora_sources
        # note that we only have those fully populated after starting to work on internal function finders
        # but that's okay - we will try to build a non-absolute canonical_id for types anyway
        in_sources = Util.find_in(Util.get_certora_sources_dir(), ret)
        if in_sources is not None:
            return str(in_sources)

        return str(ret)

    def get_original_def_node(self, build_arg_contract_file: str, reference: int) -> Dict[str, Any]:
        return self.asts[build_arg_contract_file][
            self.get_contract_file_of(build_arg_contract_file, reference)
        ][reference]

    """
    Cache size reasoning:
    The arguments for this file are contract files; there will not be too many of them.
    On packingTest, we get 4 misses and 16 hits, and reduce runtime by half a second.
    """

    @lru_cache(maxsize=32)
    def collect_type_descriptions(self, build_arg_contract_file: str, c_file: str) -> List[CT.Type]:
        user_defined_types = []  # type: List[CT.Type]
        flattened_ast = self.asts[build_arg_contract_file][c_file]
        for node_id in flattened_ast:
            node = flattened_ast[node_id]
            if Nf.is_user_defined_type_definition(node):
                _type = CT.UserDefinedType.from_def_node(
                    lambda ref: self.get_original_def_node(build_arg_contract_file, ref),
                    lambda ref: self.get_contract_file_of_non_autofinder(build_arg_contract_file, ref),
                    node
                )  # type: CT.Type
                user_defined_types.append(_type)
        return user_defined_types

    def collect_source_type_descriptions(self, contract_file: str, build_arg_contract_file: str, compile_wd: Path,
                                         smart_contract_lang: CompilerLang) -> List[CT.Type]:
        """
        Recursively enumerates all files that are imported by the source file (including the file itself),
        then returns a list of type descriptions defined in those files.
        @param contract_file - The path of the contract file to analyze, which may be relative or absolute
        @param build_arg_contract_file - The path of the contract file as defined in the build input
        @param compile_wd - The directory from which compilation is executed
        @param smart_contract_lang - Used to guard this function, which is only applicable if imports are supported
        """

        if not smart_contract_lang.get_supports_imports():
            return []

        if build_arg_contract_file not in self.asts:
            fatal_error(build_logger, f"Failed to find contract file {build_arg_contract_file} in {self.asts.keys()}")
        ast = self.asts[build_arg_contract_file]

        import_files = self.retrieve_imported_files(ast, contract_file, compile_wd)
        source_type_descriptions = []  # type: List[CT.Type]

        for c_file in set(import_files):
            _types = self.collect_type_descriptions(build_arg_contract_file, c_file)
            source_type_descriptions.extend(_types)

        return source_type_descriptions

    def get_solidity_type_from_ast_param(self, p: Dict[str, Any], file: str) -> CT.TypeInstance:
        assert "typeName" in p, f"Expected a \"typeName\" key, but got {p}"

        type_description = CT.Type.from_type_name_node(
            lambda ref: self.get_original_def_node(file, ref),
            lambda ref: self.get_contract_file_of_non_autofinder(file, ref),
            p["typeName"])

        # TODO:
        # I tried these assertions, I'm not sure any of them have passed, eventually I will investigate to make sure
        # we get default locations right
        # assert not is_default_ref or isinstance(type_description, PrimitiveType)\
        #        or isinstance(type_description, EnumType) or isinstance(type_description, StructType)\
        #        or isinstance(type_description, UserDefinedValueType)
        #
        # assert is_default_ref or is_memory_ref or is_calldata_ref or is_storage_ref
        #
        # assert is_default_ref or isinstance(type_description, MappingType)\
        #        or isinstance(type_description, ArrayType)
        return CT.TypeInstance(type_description, p["storageLocation"])

    def collect_funcs(self, data: Dict[str, Any], contract_file: str,
                      contract_name: str, build_arg_contract_file: str,
                      types: List[CT.Type], original_contract: Optional[ContractInSDC]) -> List[Func]:

        constructor_string = "constructor"

        def get_getter_func_node_from_abi(state_var_name: str) -> Dict[str, Any]:
            abi = data["abi"]
            abi_getter_nodes = [g for g in
                                filter(lambda x: x["type"] == "function" and x["name"] == state_var_name, abi)]

            assert len(abi_getter_nodes) != 0, \
                f"Failed to find a getter function of the state variable {state_var_name} in the ABI"
            assert len(abi_getter_nodes) == 1, \
                f"Found multiple candidates for a getter function of the state variable {state_var_name} in the ABI"

            return abi_getter_nodes[0]

        def collect_array_type_from_abi_rec(type_str: str, dims: List[int]) -> str:
            outer_dim = re.findall(r"\[\d*]$", type_str)
            if outer_dim:
                type_rstrip_dim = re.sub(r"\[\d*]$", '', type_str)
                if len(outer_dim[0]) == 2:
                    dims.append(-1)  # dynamic array
                else:
                    assert len(outer_dim[0]) > 2, f"Expected to find a fixed-size array, but found {type_str}"
                    dims.append(int(re.findall(r"\d+", outer_dim[0])[0]))
                return collect_array_type_from_abi_rec(type_rstrip_dim, dims)
            return type_str

        # Returns (list of array dimensions' lengths, the base type of the array)
        def collect_array_type_from_abi(type_str: str) -> Tuple[List[int], str]:
            dims = []  # type: List[int]
            base_type = collect_array_type_from_abi_rec(type_str, dims)
            return dims, base_type

        # Gets the CT.TypeInstance of a function parameter (either input or output) from the ABI
        def get_solidity_type_from_abi(abi_param_entry: Dict[str, Any]) -> CT.TypeInstance:
            assert "type" in abi_param_entry, f"Invalid ABI function parameter entry: {abi_param_entry}"
            array_dims, base_type = collect_array_type_from_abi(abi_param_entry["type"])

            internal_type_exists = "internalType" in abi_param_entry
            if internal_type_exists:
                array_dims_internal, internal_base_type = collect_array_type_from_abi(abi_param_entry["internalType"])
                assert array_dims_internal == array_dims
                user_defined_type_matches = [type for type in types if type.type_string == internal_base_type]
                if len(user_defined_type_matches) == 0:
                    # the "internal" type is often the same as the "external"
                    # TODO: we should probably just stop grabbing stuff from the abi json
                    user_defined_type = CT.TypeInstance(CT.Type.from_primitive_name(base_type))
                else:
                    user_defined_type = CT.TypeInstance(user_defined_type_matches[0])  # TODO: error on multiple matches
            else:
                user_defined_type = CT.TypeInstance(CT.Type.from_primitive_name(base_type))

            return user_defined_type

        def get_func_def_nodes_by_visibility(contract_file_ast: Dict[int, Any],
                                             visibility_modifiers: List[str],
                                             kinds: List[str],
                                             strict_filter_by_kinds: bool,
                                             only_in_contract: bool) -> List[Dict[str, Any]]:
            """
             for documentation:
             1. our node must be a FunctionDefinition
             2. and, either:
             2.a. it's kind is in the provided [kinds]
             2.b. or, we're not filtering strictly based on kinds
                    AND it's not a constructor, explicitly marked as so using isConstructor attribute,
                    AND it has a non-empty name (i.e., not fallback),
            3. it has a visibility and it's in the list of provided [visibility_modifiers]
            """
            fun_defs_in_file = [contract_file_ast[node_id] for node_id in filter(
                lambda node_id: "nodeType" in contract_file_ast[node_id] and
                                contract_file_ast[node_id]["nodeType"] == "FunctionDefinition" and
                                (("kind" in contract_file_ast[node_id] and
                                  (contract_file_ast[node_id]["kind"] in kinds)) or
                                 (not strict_filter_by_kinds and
                                  "isConstructor" in contract_file_ast[node_id] and
                                  contract_file_ast[node_id]["isConstructor"] is False and
                                  "name" in contract_file_ast[node_id] and
                                  contract_file_ast[node_id]["name"] != "")) and  # Not the fallback function (< solc6)
                                "visibility" in contract_file_ast[node_id] and
                                contract_file_ast[node_id]["visibility"] in visibility_modifiers, contract_file_ast)]

            if only_in_contract:
                assert all(self.CERTORA_CONTRACT_NAME() in fd for fd in fun_defs_in_file)

                fun_defs_in_given_contract = [fd for fd in fun_defs_in_file if
                                              fd[self.CERTORA_CONTRACT_NAME()] == c_name]
                return fun_defs_in_given_contract
            else:
                return fun_defs_in_file

        def get_func_def_nodes(contract_file_ast: Dict[int, Any]) -> List[Dict[str, Any]]:
            return get_func_def_nodes_by_visibility(contract_file_ast, ["public", "external", "private", "internal"],
                                                    ["function", constructor_string],
                                                    strict_filter_by_kinds=False, only_in_contract=True)

        def get_freefunc_def_nodes(contract_file_to_ast: Dict[str, Dict[int, Any]]) -> List[Tuple[str, Dict[str, Any]]]:
            ret = []
            for k in contract_file_to_ast.keys():
                only_free_funcs = get_func_def_nodes_by_visibility(contract_file_to_ast[k],
                                                                   ["public", "external", "private",
                                                                    "internal", ""],
                                                                   [CertoraBuildGenerator.FREEFUNCTION_STRING],
                                                                   strict_filter_by_kinds=True, only_in_contract=False)
                ret.extend([(k, f) for f in only_free_funcs])
            return ret

        def get_public_state_var_def_nodes(contract_file_ast: Dict[int, Any]) -> List[Dict[str, Any]]:
            public_var_defs_in_file = [contract_file_ast[node_id] for node_id in filter(
                lambda node_id: "nodeType" in contract_file_ast[node_id] and
                                contract_file_ast[node_id]["nodeType"] == "VariableDeclaration" and
                                "visibility" in contract_file_ast[node_id] and
                                contract_file_ast[node_id]["visibility"] == "public" and
                                "stateVariable" in contract_file_ast[node_id] and
                                contract_file_ast[node_id]["stateVariable"] is True, contract_file_ast)]

            assert all(self.CERTORA_CONTRACT_NAME() in vd for vd in public_var_defs_in_file)

            var_defs_in_given_contract = [vd for vd in public_var_defs_in_file if
                                          vd[self.CERTORA_CONTRACT_NAME()] == c_name]
            return var_defs_in_given_contract

        def get_function_selector(f_entry: Dict[str, Any], f_name: str,
                                  input_types: List[CT.TypeInstance], is_lib: bool,
                                  smart_contract_lang: CompilerLang) -> str:
            if "functionSelector" in f_entry:
                return f_entry["functionSelector"]

            f_base = Func.compute_signature(f_name, input_types, lambda x: x.get_abi_canonical_string(is_lib))

            assert f_base in data["evm"]["methodIdentifiers"], \
                f"Was about to compute the sighash of {f_name} based on the signature {f_base}.\n" \
                f"Expected this signature to appear in \"methodIdentifiers\"."

            f_hash = keccak.new(digest_bits=256)
            f_hash.update(str.encode(f_base))

            result = f_hash.hexdigest()[0:8]
            expected_result = data["evm"]["methodIdentifiers"][f_base]

            assert expected_result == smart_contract_lang.normalize_func_hash(result), \
                f"Computed the sighash {result} of {f_name} based on a (presumably) correct signature ({f_base}), " \
                f"but got an incorrect result. Expected result: {expected_result}"

            return result

        def is_constructor_func(name: str) -> bool:
            # Turns out constructor is a function with no name
            return name == ""

        funcs = list()
        collected_func_selectors = set()
        base_contract_files = self.retrieve_base_contracts_list(
            build_arg_contract_file,
            contract_file,
            contract_name)  # type: List[Tuple[str, str, bool]]
        ast_logger.debug(
            f"build arg contract file {build_arg_contract_file} and base contract files {base_contract_files}")

        for c_file, c_name, c_is_lib in base_contract_files:
            if c_is_lib:
                ast_logger.debug(f"{c_name} is a library")

            free_funcs = get_freefunc_def_nodes(self.asts[build_arg_contract_file])
            ast_logger.debug(f"found free funcs {free_funcs}")
            for containing_file, func_def in [(c_file, f) for f in
                                              get_func_def_nodes(
                                                  self.asts[build_arg_contract_file][c_file])] + free_funcs:
                func_name = func_def["name"]
                func_visibility = func_def["visibility"]
                params = [p for p in func_def["parameters"]["parameters"]]
                solidity_type_args = [self.get_solidity_type_from_ast_param(p, build_arg_contract_file) for p in params]
                is_constructor = is_constructor_func(func_name)

                if not is_constructor and func_visibility in ["public", "external"]:
                    func_selector = get_function_selector(func_def, func_name, solidity_type_args,
                                                          c_is_lib, CompilerLangSol())
                    if func_selector in collected_func_selectors:
                        continue
                    collected_func_selectors.add(func_selector)
                else:
                    # TODO: calculate func_selector for internal functions?
                    func_selector = "0"  # constructor doesn't have calldata (!!) so it doesn't really matter what
                    # we put here

                if is_constructor:
                    func_name = CertoraBuildGenerator.CONSTRUCTOR_STRING

                # Refer to https://github.com/OpenZeppelin/solidity-ast/blob/master/schema.json for more info
                return_params = func_def["returnParameters"]["parameters"]
                solidity_type_outs = \
                    [self.get_solidity_type_from_ast_param(p, build_arg_contract_file) for p in return_params]

                body_node = func_def.get("body")
                body_location: Optional[str] = None
                if body_node is not None and body_node["nodeType"] == "Block":
                    ast_logger.debug(
                        f'Found location of body of {func_name} at {body_node["src"]} in {containing_file}')
                    body_location = body_node["src"]
                elif body_node is None and func_def["implemented"]:
                    ast_logger.debug(f"No body for {func_def} but ast claims it is implemented")
                location: Optional[str] = func_def.get("src", None)

                if original_contract is not None:
                    if method := original_contract.find_method(func_name, solidity_type_args):
                        source_bytes = method.source_bytes
                        original_file = method.original_file
                    else:
                        # if we failed here, we still don't want to re-compute the data as we do below.
                        # this is because if we got here, we're collecting autofinders,
                        # and we never want to use autofinder data
                        source_bytes = None
                        original_file = None
                else:
                    source_bytes = SourceBytes.from_ast_node(func_def)
                    original_file = containing_file

                func = Func(
                    func_name,
                    solidity_type_args,
                    [p["name"] for p in params],
                    solidity_type_outs,
                    func_selector,
                    func_def[STATEMUT] in ["nonpayable", "view", "pure"],
                    c_is_lib,
                    is_constructor,
                    func_def.get("kind") == CertoraBuildGenerator.FREEFUNCTION_STRING,
                    func_def[STATEMUT],
                    func_visibility,
                    func_def["implemented"],
                    func_def.get("overrides", None) is not None,
                    func_def.get("virtual", False),
                    contract_name,
                    source_bytes,
                    ast_id=func_def.get("id", None),
                    original_file=original_file,
                    location=location,
                    body_location=body_location,
                )

                ast_logger.debug(f"Looking at Function {func}")

                # TODO: make some notion of contract equality (it *is* possible that two contracts with the
                #       same name but used separately could exist right?
                # Add to the current contract all public/external functions, and internal/private ones if
                # this is the declaring contract.
                if func_visibility in ("public", "external") or (c_name == contract_name):
                    funcs.append(func)
                    ast_logger.debug(f"Function {func.source_code_signature()} added")

            # Add automatically generated getter functions for public state variables.
            for public_state_var in get_public_state_var_def_nodes(self.asts[build_arg_contract_file][c_file]):
                getter_name = public_state_var["name"]
                ast_logger.debug(f"Getter {getter_name} automatically generated")
                getter_abi_data = get_getter_func_node_from_abi(getter_name)
                var_type = self.get_solidity_type_from_ast_param(public_state_var, build_arg_contract_file)
                solidity_type_args, solidity_type_outs = Func.compute_getter_signature(var_type)
                getter_selector = get_function_selector(public_state_var, getter_name, solidity_type_args,
                                                        c_is_lib, CompilerLangSol())
                if getter_selector in collected_func_selectors:
                    continue
                collected_func_selectors.add(getter_selector)

                if "payable" not in getter_abi_data:
                    is_not_payable = False
                else:  # Only if something is definitely non-payable, we treat it as such
                    is_not_payable = not getter_abi_data["payable"]

                if STATEMUT not in getter_abi_data:
                    state_mutability = "nonpayable"
                else:
                    state_mutability = getter_abi_data[STATEMUT]
                    # in solc6 there is no json field "payable", so we infer that if state_mutability is view
                    # or pure, then we're also non-payable by definition
                    # (state_mutability is also a newer field)
                    if not is_not_payable and state_mutability in ["view", "pure", "nonpayable"]:
                        is_not_payable = True  # definitely not payable

                funcs.append(
                    Func(
                        name=getter_name,
                        fullArgs=solidity_type_args,
                        paramNames=[],
                        returns=solidity_type_outs,
                        sighash=getter_selector,
                        notpayable=is_not_payable,
                        fromLib=c_is_lib,
                        isConstructor=False,
                        is_free_func=False,
                        stateMutability=state_mutability,
                        implemented=True,
                        overrides=public_state_var.get("overrides", None) is not None,
                        virtual=False,
                        # according to Solidity docs, getter functions have external visibility
                        visibility="external",
                        contractName=contract_name,
                        source_bytes=SourceBytes.from_ast_node(public_state_var),
                        ast_id=None,
                        original_file=c_file,
                        location=None,
                        body_location=None,
                    )
                )
                ast_logger.debug(f"Added an automatically generated getter function for {getter_name}")

        def verify_collected_all_abi_funcs(
            abi_funcs: List[Dict[str, Any]], collected_funcs: List[Func], is_lib: bool
        ) -> None:
            for fabi in abi_funcs:
                # check that we collected at least one function with the same name as the ABI function
                fs = [f for f in collected_funcs if f.name == fabi["name"]]
                assert fs, f"{fabi['name']} is in the ABI but wasn't collected"

                # check that at least one of the functions has the correct number of arguments
                fs = [f for f in fs if len(f.fullArgs) == len(fabi["inputs"])]
                assert fs, \
                    f"no collected func with name {fabi['name']} has the same \
                        amount of arguments as the ABI function of that name"

                # check that there is exactly one collected function with the same argument types as the ABI function
                def compareTypes(ct_type: CT.Type, i: Dict[str, Any]) -> bool:
                    def get_type(i: Dict[str, Any]) -> bool:
                        return i["internalType"] if "internalType" in i else i["type"]

                    solc_type = get_type(i)
                    ret = ct_type.type_string == solc_type
                    if not ret:
                        # The representation in the abi changed at some point, so hack up something that will pass
                        # for both older and newer solc versions
                        if isinstance(ct_type, CT.ContractType):
                            ret = solc_type == "address"
                        elif isinstance(ct_type, CT.StructType):
                            ret = solc_type == "tuple"
                        elif isinstance(ct_type, CT.EnumType):
                            ret = solc_type == "uint8"
                    return ret

                fs = [f for f in fs if all(compareTypes(a.type, i)
                                           for a, i in zip(f.fullArgs, fabi["inputs"]))]
                assert fs, \
                    f"no collected func with name {fabi['name']} has the same \
                        types of arguments as the ABI function of that name"

                if len(fs) > 1:
                    assert is_lib, "Collected too many functions with the same ABI specification (non-library)"
                    # if a function is in a library and its first argument is of storage, then it’s not ABI.
                    fs = [f for f in fs if f.fullArgs[0].location != CT.TypeLocation.STORAGE]
                    assert len(fs) == 1, "Collected too many (library) functions with the same ABI specification"

                # At this point we are certain we have just one candidate. Let's do some sanity checks also
                # on the return values
                f = fs[0]
                assert len(f.returns) == len(fabi["outputs"]), \
                    f"function collected for {fabi['name']} has the wrong number of return values"
                assert all(compareTypes(a.type, i) for a, i in zip(f.returns, fabi["outputs"])), \
                    f"function collected for {fabi['name']} has the wrong types of return values. {[t.type.type_string for t in f.returns]} vs {fabi['outputs']}"

        verify_collected_all_abi_funcs(
            [f for f in data["abi"] if f["type"] == "function"],
            [f for f in funcs if f.visibility in ("external", "public") and f.name != "constructor"],
            c_is_lib
        )

        return funcs

    def retrieve_imported_files(self, contract_ast: Dict[str, Dict[int, Any]], contract_file: str,
                                compile_wd: Path) -> List[str]:
        """
        Returns a list of all paths that are imported, directly or indirectly,
        in `contract_file`, which is a path to a spec file.
        `compile_wd` is the directory from which compilation is executed, which shares a
        common prefix with `contract_file`.
        note that either `compile_wd` or `contract_file` may be relative - no assumptions can be made here.
        `contract_ast` is the ast computed for `contract_file` (see `CertoraBuildGenerator.asts`).
        """

        # instead of returning `seen` directly, we use a separate variable for the returned list,
        # to avoid modifying the input paths
        imported_files = []
        seen: Set[str] = set()

        common_dir = self.longest_common_dir_of_imports(compile_wd, contract_ast)

        worklist = [contract_file]
        while worklist:
            curr = worklist.pop()

            if curr not in contract_ast:
                if curr == contract_file:
                    msg = f"Original contract file {contract_file} not found in {contract_ast.keys()}"
                else:
                    msg = f"Path {curr} should have been accessible from {contract_file}, " \
                          f"but could not be found in {contract_ast.keys()}"
                fatal_error(build_logger, msg)

            if self.mark_seen(seen, curr, common_dir):
                imported_files.append(curr)

                for node in contract_ast[curr].values():
                    if Nf.is_import(node):
                        import_node = node["absolutePath"]
                        import_node = Util.normalize_double_paths(import_node)
                        worklist.append(import_node)

        return imported_files

    @staticmethod
    def mark_seen(seen: Set[str], path_to_mark: str, common_dir: Path) -> bool:
        """
        if `path_to_mark` is in `seen`, returns `False`. otherwise, adds the path
        to `seen` and returns `True`.
        `path_to_mark` can be a relative or an absolute path, but must be resolvable
        in the directory `common_dir`. paths are stored in `seen` in relative form,
        as the relative path from `common_dir`.
        """
        relative_path = Util.find_filename_in(common_dir, path_to_mark)

        if not relative_path:
            fatal_error(build_logger, f"Path {path_to_mark} could not be resolved in {common_dir}")
        elif relative_path in seen:
            return False
        else:
            seen.add(relative_path)
            return True

    @staticmethod
    def longest_common_dir_of_imports(contract_wd: Path, contract_ast: Dict[str, Dict[int, Any]]) -> Path:
        """
        returns a Path which is the longest common directory of the directory `contract_wd`,
        and the set of paths in the ast `contract_ast`.
        assuming correct values in `contract_ast`, this would be a directory where every path in `contract_ast`
        can be resolved, including any relative paths
        """
        contract_wd = contract_wd.absolute()
        paths = [str(contract_wd)]

        for path in contract_ast.keys():
            if os.path.isabs(path):
                paths.append(path)

        common = os.path.commonpath(paths)
        return Path(common)

    def retrieve_base_contracts_list(self, build_arg_contract_file: str, contract_file: str,
                                     contract_name: str) -> List[Tuple[str, str, bool]]:
        """
        For each base contract, returns (base_contract_file, base_contract_name, is_library)
        @param build_arg_contract_file: input arg, contract file we want to work on
        @param contract_file: full path of contract file we want to work on
        @param contract_name: contract name without the extension
        @return: List of (base_contract_file, base_contract_name, is_library)

        NB the only member of the list for which is_library should be true should be [contract_file] (libraries can
           never be base contracts, even of other libraries, but since this list includes [contract_file] then up
           to one member of the "base contracts" may be a library
        """
        if get_compiler_lang(contract_file) == CompilerLangVy():
            return [(contract_file, contract_name, False)]

        def retrieve_base_contracts_list_rec(base_contracts_queue: List[Any],
                                             base_contracts_lst: List[Tuple[str, str, bool]]) -> None:
            (curr_contract_file, curr_contract_def_node_ref) = base_contracts_queue.pop()
            curr_contract_def = self.asts[build_arg_contract_file][curr_contract_file][curr_contract_def_node_ref]
            assert "baseContracts" in curr_contract_def, \
                f'Got a "ContractDefinition" ast node without a "baseContracts" key: {curr_contract_def}'
            for bc in curr_contract_def["baseContracts"]:
                assert "nodeType" in bc and bc["nodeType"] == "InheritanceSpecifier"
                assert "baseName" in bc and "referencedDeclaration" in bc["baseName"]
                next_bc_ref = bc["baseName"]["referencedDeclaration"]
                next_bc = self.get_contract_file_of(build_arg_contract_file, next_bc_ref)
                if next_bc not in base_contracts_lst:
                    base_contracts_lst.append(
                        (next_bc, self.asts[build_arg_contract_file][next_bc][next_bc_ref]["name"],
                         self.is_library_def_node(next_bc, next_bc_ref, build_arg_contract_file)))
                    base_contracts_queue.insert(0, (next_bc, bc["baseName"]["referencedDeclaration"]))

            if base_contracts_queue:
                retrieve_base_contracts_list_rec(base_contracts_queue, base_contracts_lst)

        contract_def_node_ref = self.get_contract_def_node_ref(build_arg_contract_file, contract_file, contract_name)
        base_contracts_queue = [(contract_file, contract_def_node_ref)]
        base_contracts_lst = [
            (contract_file, contract_name,
             self.is_library_def_node(contract_file, contract_def_node_ref, build_arg_contract_file))]
        retrieve_base_contracts_list_rec(base_contracts_queue, base_contracts_lst)

        # note the following assumption (as documented above), we turn it off because asserts in the python script
        # are scary
        # assert all([not is_lib or contract_name == c_name for _, c_name, is_lib in
        #             base_contracts_lst]), f'found a library in base_contracts_list {base_contracts_lst} ' \
        #                                   f'for contract {contract_name}'
        return base_contracts_lst

    # used by Equivalence Check
    def collect_func_source_code_signatures_source(self, contract_file: Path,
                                                   contract_name: str, solc: str) -> List[FunctionSig]:
        """
        returns a list of signatures for external or public functions in a given contract.
        This method is currently only used in the context of certoraEqCheck.
        @param contract_file: relative path to the file containing the targeted contract
        @param contract_name: name of the target contract
        @param solc: name of solc executable used to compile target contract
        @return: List of str containing the function signature (a tuple representing
          function name, inputs, outputs, stateMutability) for each contract
        """
        func_signatures = []
        file_abs_path = Path(self.to_autofinder_file(str(contract_file))).absolute()
        if file_abs_path.suffix == VY:
            smart_contract_lang: CompilerLang = CompilerLangVy()
            sdc_name = self.file_to_sdc_name[Path(contract_file).absolute()]
            standard_json_data = self.get_standard_json_data(sdc_name, smart_contract_lang)
            abi = standard_json_data[CONTRACTS][str(Path(contract_file).absolute())][contract_name]['abi']
            ast_logger.debug(f"abi is: \n{abi}")
            for f in filter(lambda x: self.is_imported_abi_entry(x), abi):
                func_signature = self.get_full_func_signature(f)
                ast_logger.debug(f"Collected function signature {func_signature} from ABI")
                func_signatures.append(func_signature)
            return func_signatures
        elif file_abs_path.suffix == SOL:
            smart_contract_lang = CompilerLangSol()
            sdc_name = self.file_to_sdc_name[file_abs_path]
            compilation_path = self.get_compilation_path(sdc_name)
            standard_json_data = self.get_standard_json_data(sdc_name, smart_contract_lang)
            storage_data = smart_contract_lang.collect_storage_layout_info(str(file_abs_path), compilation_path,
                                                                           solc, None,
                                                                           standard_json_data)
            abi = storage_data[CONTRACTS][str(file_abs_path)][contract_name]["abi"]
            ast_logger.debug(f"abi is: \n{abi}")
            for f in filter(lambda x: self.is_imported_abi_entry(x), abi):
                func_signature = self.get_full_func_signature(f)
                ast_logger.debug(f"Collected function signature {func_signature} from ABI")
                func_signatures.append(func_signature)
            return func_signatures
        else:
            raise Util.CertoraUserInputError(
                f'May only collect functions from Solidity/Vyper files: {file_abs_path} received.')

    @staticmethod
    def get_func_args_output_types(inputs_or_outputs: Any) -> List[str]:
        """
        A Helper to create a list of the types for all inputs/return values of a function.
        So if `get_func_args_output_types` gets this as input:
           `[{'internalType': 'uint256', 'name': 'a', 'type': 'uint256'},
           {'internalType': 'uint256', 'name': 'b', 'type': 'uint256'}]`
        it wil produce:
           `['uint256', 'uint256']`.
        :param inputs_or_outputs: a list of function arguments (or return values) and their types.
        :return: returns a list of the types of the inputs/return values of a function.
        """
        return [input_or_output[INTERNALTYPE] if INTERNALTYPE in input_or_output else input_or_output[TYPE] for
                input_or_output in inputs_or_outputs]

    @staticmethod
    def get_full_func_signature(f: Dict[str, Any]) -> FunctionSig:
        """
        grabs function name, inputs, outputs, stateMutability
        """
        name = CertoraBuildGenerator.get_abi_entry_name(f)
        func_inputs = CertoraBuildGenerator.get_func_args_output_types(f[INPUTS])
        func_outputs = CertoraBuildGenerator.get_func_args_output_types(f[OUTPUTS])
        mutability = f[STATEMUT]
        func_signature = (name, func_inputs, func_outputs, mutability)
        return func_signature

    @staticmethod
    def get_abi_entry_name(x: Dict[str, Any]) -> str:
        if x[TYPE] == FUNCTION:
            return x["name"]
        elif x[TYPE] == CertoraBuildGenerator.CONSTRUCTOR_STRING:
            return CertoraBuildGenerator.CONSTRUCTOR_STRING
        else:
            raise Exception("Abi entry name can either be a function name or a constructor.")  # Should be unreachable

    @staticmethod
    def get_func_signature(f: Dict[str, Any]) -> str:
        name = CertoraBuildGenerator.get_abi_entry_name(f)
        func_inputs = CertoraBuildGenerator.get_func_args_output_types(f[INPUTS])
        func_signature = f"{name}({','.join(func_inputs)})"
        return func_signature

    @staticmethod
    def is_imported_abi_entry(x: Dict[str, Any]) -> bool:
        return x[TYPE] == FUNCTION or x[TYPE] == CertoraBuildGenerator.CONSTRUCTOR_STRING

    @staticmethod
    def collect_srcmap(data: Dict[str, Any]) -> Tuple[str, str]:
        # no source map object in vyper
        deployed = data["evm"]["deployedBytecode"].get("sourceMap", "")
        if isinstance(deployed, dict):
            deployed = deployed.get("pc_pos_map_compressed", "")
        regular = data["evm"]["bytecode"].get("sourceMap", "")
        if isinstance(regular, dict):
            regular = regular.get("pc_pos_map_compressed", "")
        return deployed, regular

    @staticmethod
    def collect_varmap(contract: str, data: Dict[str, Any]) -> Any:
        return data[CONTRACTS][contract]["local-mappings"]

    @staticmethod
    def collect_storage_layout(data: Dict[str, Any]) -> Any:
        return data.get("storageLayout", None)

    @staticmethod
    def collect_transient_storage_layout(data: Dict[str, Any]) -> Any:
        return data.get('transientStorageLayout', None)

    # Cache info - on PackingTest there are 514 hits and 34 misses
    @lru_cache(maxsize=128)
    def get_contract_def_node_ref(self, build_arg_contract_file: str, contract_file: str, contract_name: str) -> int:
        """
        Extracts the proper AST from self, based on the [build_arg_contract_file] and the
        [contract_file] files, than invokes [get_contract_def_node_ref_func] to get the definition
        node's reference.
        """
        compiler_lang = get_compiler_lang(build_arg_contract_file)
        contract_file_ast = self.asts[build_arg_contract_file][contract_file]
        return compiler_lang.get_contract_def_node_ref(contract_file_ast, contract_file, contract_name)

    def collect_contract_bytes(self, contract_file: str, contract_name: str,
                               build_arg_contract_file: str) -> SourceBytes:
        ref = self.get_contract_def_node_ref(build_arg_contract_file, contract_file, contract_name)
        node = self.asts[build_arg_contract_file][contract_file][ref]
        if source_bytes := SourceBytes.from_ast_node(node):
            return source_bytes
        else:
            raise RuntimeError(f"failed to get contract bytes for {contract_name} in file {contract_file}")

    def get_standard_json_data(self, sdc_name: str, smart_contract_lang: CompilerLang) -> Dict[str, Any]:
        json_file = smart_contract_lang.compilation_output_path(sdc_name)
        process_logger.debug(f"reading standard json data from {json_file}")
        # jira CER_927 - under windows it happens the solc generate wrong
        # path names, we convert them here to posix format.
        json_dict = Util.read_json_file(json_file)
        entries = [CONTRACTS, "sources"]
        for ent in entries:
            convert_pathname_to_posix(json_dict, ent, smart_contract_lang)
        return json_dict

    def cleanup_compiler_outputs(self, sdc_name: str, smart_contract_lang: CompilerLang) -> None:
        for compilation_artifact in smart_contract_lang.all_compilation_artifacts(sdc_name):
            Util.remove_file(compilation_artifact)

    def backup_compiler_outputs(self, sdc_name: str, smart_contract_lang: CompilerLang, tag: str) -> None:
        for compilation_artifact in smart_contract_lang.all_compilation_artifacts(sdc_name):
            if compilation_artifact.exists():
                shutil.copy(compilation_artifact, f"{str(compilation_artifact)}.{tag}")

    @staticmethod
    def address_as_str(address: int) -> str:
        """
        Returns a 40 digits long hexadecimal string representation of address, filled by leading zeros
        """
        return "%0.40x" % address

    def find_contract_address(self, contract_file: str, contract_name: str,
                              contracts_with_chosen_addresses: List[Tuple[int, Any]]) -> Tuple[str, bool]:
        def contract_cmp(entry: Tuple[int, Any], c_file: str, c_contract: str) -> bool:
            e_file, e_contract = str(entry).split(':')
            return (c_contract == e_contract) and (
                e_file == c_file or Util.abs_posix_path(e_file) == Util.abs_posix_path(c_file)
            )

        address_and_contracts = [e for e in contracts_with_chosen_addresses if
                                 contract_cmp(e[1], contract_file, contract_name)]

        if len(address_and_contracts) == 0:
            msg = f"Failed to find a contract named {contract_name} in file {contract_file}. " \
                  f"Please make sure there is a file named like the contract, " \
                  f"or a file containing a contract with this name. Available contracts: " \
                  f"{','.join(map(lambda x: x[1], contracts_with_chosen_addresses))}"
            raise Util.CertoraUserInputError(msg)
        address_and_contract = address_and_contracts[0]
        address = address_and_contract[0]
        contract = address_and_contract[1].split(":")[1]
        is_static = False

        if self.context.address:
            ast_logger.debug(f"Custom addresses: {self.context.address}, looking for a match of "
                             f"{address_and_contract} from {contract_name} in {self.context.address.keys()}")
            if contract_name in self.context.address.keys():
                address = self.context.address[contract_name]
                address = int(str(address), 0)
                is_static = True
        ast_logger.debug(f"Candidate address for {contract} is {address}")
        # Can't have more than one! Otherwise we will have conflicting same address for different contracts
        assert len(set(address_and_contracts)) == 1
        return self.address_as_str(address), is_static

    def find_contract_address_str(self, contract_file: str, contract_name: str,
                                  contracts_with_chosen_addresses: List[Tuple[int, Any]]) -> str:
        contract_address, _ = self.find_contract_address(contract_file, contract_name, contracts_with_chosen_addresses)
        return contract_address

    def collect_and_link_bytecode(self,
                                  contract_name: str,
                                  contracts_with_chosen_addresses: List[Tuple[int, Any]],
                                  bytecode: str,
                                  links: Dict[str, Any]
                                  ) -> str:
        build_logger.debug(f"Working on contract {contract_name}")
        for address, _contract_name in contracts_with_chosen_addresses:
            if _contract_name == contract_name:
                build_logger.debug("Chosen address for %s is 0x%X" % (contract_name, address))
                break

        if links:
            # links are provided by solc as a map file -> contract -> (length, start)
            # flip the links from the "where" to the chosen contract address (based on file:contract).
            linked_bytecode = bytecode
            replacements = {}
            for link_file in links:
                for link_contract in links[link_file]:
                    for where in links[link_file][link_contract]:
                        replacements[where["start"]] = {"length": where["length"],
                                                        "address": self.find_contract_address_str(
                                                            link_file,
                                                            link_contract,
                                                            contracts_with_chosen_addresses)
                                                        }
            build_logger.debug(f"Replacements= {replacements}")
            where_list = list(replacements.keys())
            where_list.sort()
            where_list.reverse()
            for where in where_list:
                offset = where * 2
                length = replacements[where]["length"] * 2
                addr = replacements[where]["address"]
                build_logger.debug(f"replacing in {offset} of len {length} with {addr}")
                # is this *definitely* a push? then use our special "library link" opcode e0, which is unused
                if linked_bytecode[offset - 2:offset] == "73":
                    linked_bytecode = f"{linked_bytecode[:offset - 2]}e0{addr}{linked_bytecode[(offset + length):]}"
                else:
                    linked_bytecode = f"{linked_bytecode[0:offset]}{addr}{linked_bytecode[(offset + length):]}"
                self.library_addresses.append(addr)
            return linked_bytecode

        return bytecode

    def get_solc_via_ir_value(self, contract_file_path: Path) -> bool:
        match = Ctx.get_map_attribute_value(self.context, contract_file_path, 'solc_via_ir')
        assert isinstance(match, (bool, type(None))), f"Expected solc_via_ir to be bool or None, got {type(match)}"
        return bool(match)

    def get_solc_evm_version_value(self, contract_file_path: Path) -> Optional[str]:
        match = Ctx.get_map_attribute_value(self.context, contract_file_path, 'solc_evm_version')
        assert isinstance(match, (str, type(None))), f"Expected solc_evm_version to be string or None, got {type(match)}"
        return match

    def get_solc_optimize_value(self, contract_file_path: Path) -> Optional[str]:
        match = Ctx.get_map_attribute_value(self.context, contract_file_path, 'solc_optimize')
        assert isinstance(match, (str, int, type(None))), f"Expected solc_optimize to be string, integer , got {type(match)}"
        if isinstance(match, int):
            match = str(match)
        return match

    def _handle_via_ir(self, contract_file_path: Path, settings_dict: Dict[str, Any]) -> None:
        if self.get_solc_via_ir_value(contract_file_path):
            settings_dict["viaIR"] = True

    def _handle_evm_version(self, contract_file_path: Path, settings_dict: Dict[str, Any]) -> None:
        match = self.get_solc_evm_version_value(contract_file_path)
        if match:
            settings_dict["evmVersion"] = match

    def _handle_optimize(self, contract_file_path: Path, settings_dict: Dict[str, Any],
                         compiler_collector: CompilerCollector) -> None:
        """
        @param contract_file_path: the contract that we are working on
        @param settings_dict: data structure for sending to the solc compiler
        """
        match = self.get_solc_optimize_value(contract_file_path)
        if match:
            settings_dict["optimizer"] = {"enabled": True}
            if int(match) > 0:
                settings_dict["optimizer"]['runs'] = int(match)

        # if optimizer is true, we should also define settings_dict["optimizer"]["details"]
        # for both optimize map and optimize
        optimizer = settings_dict.get("optimizer")
        if optimizer and isinstance(optimizer, dict) and optimizer.get('enabled'):
            # if we are not disabling finder friendly optimizer specifically, enable it whenever viaIR is also enabled
            if not self.context.strict_solc_optimizer and self.get_solc_via_ir_value(contract_file_path):
                # The default optimizer steps (taken from libsolidity/interface/OptimiserSettings.h) but with the
                # full inliner step removed
                solc0_8_26_to_0_8_30 = ("dhfoDgvulfnTUtnIfxa[r]EscLMVcul[j]Trpeulxa[r]cLCTUca[r]LSsTFOtfDnca[r]" +
                                        "IulcscCTUtx[scCTUt]TOntnfDIuljmul[jul]VcTOculjmul")
                solc0_8_13_to_0_8_25 = "dhfoDgvulfnTUtnIf[xa[r]EscLMcCTUtTOntnfDIulLculVcul[j]T" + \
                                       "peulxa[rul]xa[r]cLgvifCTUca[r]LSsTFOtfDnca[r]Iulc]jmul[jul]VcTOculjmul"
                solc0_8_12 = "dhfoDgvulfnTUtnIf[xa[r]EscLMcCTUtTOntnfDIulLculVcul[j]T" + \
                             "peulxa[rul]xa[r]cLgvifCTUca[r]LsTFOtfDnca[r]Iulc]jmul[jul]VcTOculjmul"
                solc0_8_10_to_0_8_11 = "dhfoDgvulfnTUtnIf[xa[r]scLMcCTUtTOntnfDIulLculVcul[j]T" + \
                                       "peulxa[rul]xa[r]cLgvifCTUca[r]LsTFOtfDnca[r]Iulc]jmul[jul]VcTOculjmul"
                solc0_8_1_to_0_8_9 = "dhfoDgvulfnTUtnIf[xarrscLMcCTUtTOntnfDIulLculVculjjT" + \
                                     "peulxarulrulxarrcLgvifCTUcarrLsTFOtfDncarrIulc]jmuljuljulVcTOculjmul"
                solc0_7_6_to_0_8_0 = "dhfoDgvulfnTUtnIf[xarrscLMcCTUtTOntnfDIulLculVculjjT" + \
                                     "peulxarulrulxarrcLgvifCTUcarrLsTFOtfDncarrIulc]jmuljuljulVcTOculjmul"
                solc0_7_2_to_0_7_5 = "NdhfoDgvulfnTUtnIf[xarrscLMcCTUtTOntnfDIulLculVculjjT" + \
                                     "peulxarulrulxarrcLgvifCTUcarrLsTFOtfDncarrIulc]jmuljuljulVcTOculjmulN"
                solc0_6_7_to_0_7_1 = "dhfoDgvulfnTUtnIf[xarrscLMcCTUtTOntnfDIulLculVculjj" + \
                                     "eulxarulrulxarrcLgvifCTUcarrLsTFOtfDncarrIulc]jmuljuljulVcTOculjmul"

                def standard_replacement(optimizer_steps: str) -> str:
                    return optimizer_steps.replace("gvif", "gvf")  # replace gvif with gvf

                compiler_version = compiler_collector.compiler_version
                major, minor, patch = compiler_version

                err_msg = \
                    f"Unsupported solc version {major}.{minor}.{patch} for `solc_via_ir`. " \
                    f"Supported versions: 0.6.7 – 0.8.25.\n" \
                    f"Use `solc_via_ir_map` to disable `solc_via_ir` for specific files with older compiler versions."

                yul_optimizer_steps = None
                if major != 0:
                    raise Util.CertoraUserInputError(err_msg)
                elif minor < 6 or (minor == 6 and patch < 7):
                    raise Util.CertoraUserInputError(err_msg)
                elif self.context.yul_optimizer_steps:
                    yul_optimizer_steps = self.context.yul_optimizer_steps
                elif (minor == 6 and patch >= 7) or (minor == 7 and 0 <= patch <= 1):
                    yul_optimizer_steps = solc0_6_7_to_0_7_1
                elif minor == 7 and 2 <= patch <= 5:
                    yul_optimizer_steps = solc0_7_2_to_0_7_5
                elif (minor == 7 and 6 <= patch) or (minor == 8 and patch == 0):
                    yul_optimizer_steps = solc0_7_6_to_0_8_0
                elif minor == 8 and 1 <= patch <= 9:
                    yul_optimizer_steps = solc0_8_1_to_0_8_9
                elif minor == 8 and 10 <= patch <= 11:
                    yul_optimizer_steps = solc0_8_10_to_0_8_11
                elif minor == 8 and patch == 12:
                    yul_optimizer_steps = solc0_8_12
                elif minor == 8 and 13 <= patch <= 25:
                    yul_optimizer_steps = solc0_8_13_to_0_8_25
                elif minor == 8 and 26 <= patch <= 30:
                    yul_optimizer_steps = solc0_8_26_to_0_8_30
                assert yul_optimizer_steps is not None, \
                    'Yul Optimizer steps missing for requested Solidity version. Please contact Certora team.'

                yul_optimizer_steps = standard_replacement(yul_optimizer_steps)

                settings_dict["optimizer"]["details"] = {"inliner": False, "yulDetails": {
                    "optimizerSteps": yul_optimizer_steps
                }}
            elif not self.context.strict_solc_optimizer:
                compiler_version = compiler_collector.compiler_version
                major, minor, patch = compiler_version
                if major != 0:
                    raise Util.CertoraUserInputError(f"Unrecognized version {major}.{minor}.{patch}")
                if (minor == 8 and patch >= 5) or (minor > 8):
                    settings_dict["optimizer"]["details"] = {"inliner": False}
            if self.context.disable_solc_optimizers is not None:
                if "details" not in settings_dict["optimizer"]:
                    settings_dict["optimizer"]["details"] = {}
                for opt_pass in self.context.disable_solc_optimizers:
                    settings_dict["optimizer"]["details"][opt_pass] = False

    def _fill_codegen_related_options(self, contract_file_path: Path, settings_dict: Dict[str, Any],
                                      compiler_collector: CompilerCollector) -> None:
        """
        Fills options that control how we call solc and affect the bytecode in some way
        @param contract_file_path: the contract that we are working on
        @param settings_dict: data structure for sending to the solc compiler
        """

        self._handle_via_ir(contract_file_path, settings_dict)
        self._handle_evm_version(contract_file_path, settings_dict)
        self._handle_optimize(contract_file_path, settings_dict, compiler_collector)

    @staticmethod
    def solc_setting_optimizer_runs(settings_dict: Dict[str, Any]) -> Tuple[bool, Optional[int]]:
        """
        A small getter that given a properly configured settings dictionary object for solc,
        returns the number of optimizer runs, or None if it's disabled or default
        """
        if "optimizer" in settings_dict:
            optimizer_dict = settings_dict["optimizer"]
            if "enabled" in optimizer_dict and optimizer_dict["enabled"]:
                if "runs" in optimizer_dict:
                    return True, optimizer_dict["runs"]
                else:
                    return True, None
            else:
                return False, None
        else:
            return False, None

    @staticmethod
    def solc_setting_via_ir(settings_dict: Dict[str, Any]) -> bool:
        """
        A small getter that given a properly configured settings dictionary object for solc,
        returns whether viaIR mode is enabled
        """
        if "viaIR" in settings_dict:
            return settings_dict["viaIR"]
        return False

    def standard_json(self,
                      contract_file_posix_abs: Path,
                      contract_file_as_provided: str,
                      remappings: List[str],
                      compiler_collector: CompilerCollector) -> Dict[str, Any]:
        """
        when calling solc with the standard_json api, instead of passing it flags, we pass it json to request what we
        want -- currently we only use this to retrieve storage layout as this is the only way to do that,
        it would probably be good to migrate entirely to this API.
        @param contract_file_posix_abs: the absolute posix path of the file the user provided
        @param contract_file_as_provided: the file we are looking at as provided by the user
        @param remappings: package remappings for import resolution
        @param compiler_collector: Solidity or Vyper compiler collector
        @return:
        """
        compiler_collector_lang = compiler_collector.smart_contract_lang
        if compiler_collector_lang == CompilerLangSol() or compiler_collector_lang == CompilerLangYul():
            sources_dict = {str(contract_file_posix_abs): {
                "urls": [str(contract_file_posix_abs)]}}  # type: Dict[str, Dict[str, Any]]
            output_selection = ["transientStorageLayout", "storageLayout", "abi", "evm.bytecode",
                                "evm.deployedBytecode", "evm.methodIdentifiers", "evm.assembly",
                                "evm.bytecode.functionDebugData"]
            ast_selection = ["id", "ast"]
        elif compiler_collector_lang == CompilerLangVy():
            with open(contract_file_posix_abs) as f:
                contents = f.read()
                sources_dict = {str(contract_file_posix_abs): {"content": contents}}
                output_selection = ["abi", "evm.bytecode", "evm.deployedBytecode", "evm.methodIdentifiers"]
                ast_selection = ["ast"]

        settings_dict: Dict[str, Any] = \
            {
                "remappings": remappings,
                "outputSelection": {
                    "*": {
                        "*": output_selection,
                        "": ast_selection
                    }
                }
            }

        self._fill_codegen_related_options(Path(contract_file_as_provided), settings_dict, compiler_collector)

        result_dict = {"language": compiler_collector_lang.name, "sources": sources_dict, "settings": settings_dict}
        # debug_print("Standard json input")
        # debug_print(json.dumps(result_dict, indent=4))
        return result_dict

    def get_compilation_path(self, sdc_name: str) -> Path:
        return Util.get_certora_config_dir() / sdc_name

    def build_srclist(self,
                      data: Dict[str, Any],
                      sdc_name: str,
                      smart_contract_lang: CompilerLang) -> Dict[str, Any]:
        """
        Generates lists of sources for the given Single Deployed Contract.
        :param data: data from the json produced by the solidity compiler
        :param sdc_name: name of the "Single Deployed Contract" whose sources we are gathering
        :param smart_contract_lang: the smart-contract-language which we decide by how to copy contract's file to
               compilation path directory
        :return: The source list as seen by solc.
        """
        # srclist - important for parsing source maps
        srclist = {data["sources"][k]["id"]: k for k in data["sources"]}
        ast_logger.debug(f"Source list= {srclist}")

        return srclist

    def collect_asts(self, original_file: str, contract_sources: Dict[str, Dict[str, Any]]) -> None:
        """
        This function fetches the AST provided by solc and flattens it so that each node_id is mapped to a dict object,
        representing the node's contents.

        @param original_file: Path to a file
        @param contract_sources: represents the AST. Every sub-object with an "id" key is an AST node.
                                 The ast object is keyed by the original file for which we invoked solc.
        """

        if original_file.endswith(".vy"):
            contract_definition_type = "Module"
            node_id_attrb = "node_id"
            node_type_attrb = "ast_type"
        else:
            contract_definition_type = "ContractDefinition"
            node_id_attrb = "id"
            node_type_attrb = "nodeType"

        def stamp_value_with_contract_name(popped_dict: Dict[str, Any], curr_value: Any) -> None:
            if isinstance(curr_value, dict):
                if popped_dict[node_type_attrb] == contract_definition_type:
                    assert "name" in popped_dict
                    curr_value[self.CERTORA_CONTRACT_NAME()] = popped_dict["name"]
                elif self.CERTORA_CONTRACT_NAME() in popped_dict:
                    curr_value[self.CERTORA_CONTRACT_NAME()] = popped_dict[self.CERTORA_CONTRACT_NAME()]
            elif isinstance(curr_value, list):
                for node in curr_value:
                    stamp_value_with_contract_name(popped_dict, node)

        self.asts[original_file] = {}
        for c in contract_sources:
            ast_logger.debug(f"Adding ast of {original_file} for {c}")
            container = {}  # type: Dict[int, Any]
            self.asts[original_file][c] = container
            if "ast" not in contract_sources[c]:
                fatal_error(
                    ast_logger,
                    f"Invalid AST format for original file {original_file} - "
                    f"got object that does not contain an \"ast\" {contract_sources[c]}")
            queue = [contract_sources[c]["ast"]]
            while queue:
                pop = queue.pop(0)
                if isinstance(pop, dict) and node_id_attrb in pop:
                    idAttr = pop[node_id_attrb]
                    if isinstance(idAttr, int):
                        container[int(idAttr)] = pop
                    elif isinstance(idAttr, list) and len(idAttr) == 1 and isinstance(idAttr[0], int):
                        # In the bug reported in https://certora.atlassian.net/browse/CERT-7643 the id field is a list
                        # instead of a single integer - we except that the first element of the list is the actual id
                        # and unpack it.
                        container[int(idAttr[0])] = pop
                    else:
                        raise Exception(f"Unexpected type of attribute `{node_id_attrb}`, was {idAttr}, expected an integer or an int-typed list of length 1")

                    for key, value in pop.items():
                        if (node_type_attrb in pop and
                                pop[node_type_attrb] == "InlineAssembly" and key == "externalReferences"):
                            continue
                        stamp_value_with_contract_name(pop, value)
                        if isinstance(value, dict):
                            queue.append(value)
                        if isinstance(value, list):
                            queue.extend(value)

    @staticmethod
    def get_node_from_asts(asts: Dict[str, Dict[str, Dict[int, Any]]], original_file: str, node_id: int) -> Any:
        ast_logger.debug(f"Available keys in ASTs: {asts.keys()}")
        ast_logger.debug(f"Available keys in AST of original file: {asts[original_file].keys()}")
        for contract_file in asts[original_file]:
            node = asts[original_file].get(contract_file, {}).get(node_id)
            if node is not None:
                ast_logger.debug(f"In original file {original_file} in contract file {contract_file}, found for node "
                                 f"id {node_id}")
                return node  # Found the ast node of the given node_id
        return {}  # an ast node with the given node_id was not found

    def collect_immutables(self,
                           contract_data: Dict[str, Any],
                           build_arg_contract_file: str,
                           compiler_lang: CompilerLang
                           ) -> List[ImmutableReference]:
        out = []
        immutable_references = contract_data["evm"]["deployedBytecode"].get("immutableReferences", [])
        # Collect and cache the AST(s). We collect the ASTs of ALL contracts' files that appear in
        # contract_sources; the reason is that a key of an item in immutableReferences
        # is an id of an ast node that may belong to any of those contracts.
        ast_logger.debug(f"Got immutable references in {build_arg_contract_file}: {immutable_references}")
        for astnode_id in immutable_references:
            if compiler_lang.supports_typed_immutables:
                # handling weird solc wart, what is it? in solc_via_ir mode
                # https://github.com/ethereum/solidity/blob/6c7e686d86c33b3f329a6962728a1f1bed69d67a/libsolidity/codegen/ir/Common.cpp#L101
                if astnode_id == "library_deploy_address":
                    continue
                astnode = self.get_node_from_asts(self.asts, build_arg_contract_file, int(astnode_id))
                name = astnode.get("name", None)
                if name is None:
                    fatal_error(
                        ast_logger,
                        f"immutable reference does not point to a valid ast node {astnode} in "
                        f"{build_arg_contract_file}, node id {astnode_id}"
                    )
                ast_logger.debug(f"Name of immutable reference is {name}")
                # get type of immutable
                immutable_type = self.get_solidity_type_from_ast_param(astnode, build_arg_contract_file)
            else:
                # (in yul and potentially other languages which do not support typed immutables,
                # immutables are only uint256)
                immutable_type = CT.TypeInstance(CT.PrimitiveType('uint256', 'uint256'))
                # not really astnode_id in yul, it's just the name
                name = astnode_id

            for elem in immutable_references[astnode_id]:
                out.append(ImmutableReference(elem["start"], elem["length"], name, immutable_type))
        return out

    def generate_address(self) -> int:
        address = CertoraBuildGenerator.BASE_ADDRESS * 2 ** 100 + self.address_generator_counter
        # Don't forget for addresses there are only 160 bits
        self.address_generator_counter += 1
        return address

    def check_for_errors_and_warnings(self, data: Dict[str, Any], fail_on_compilation_error: bool) -> None:
        if "errors" in data:
            errors_list = data["errors"]
            severe_errors = [e for e in errors_list if "errorCode" in e and
                             e["errorCode"] in CertoraBuildGenerator.SEVERE_COMPILER_WARNINGS]
            if len(severe_errors) > 0:
                for i, e in enumerate(severe_errors):
                    raw_msg = e["formattedMessage"]
                    # warn only, if we skip severe warnings, or in non-fail mode (e.g., autofinders)
                    if self.context.ignore_solidity_warnings or not fail_on_compilation_error:
                        warn_msg = f"Severe compiler warning:\n{raw_msg}\n" \
                                   f"It's highly recommended to fix this warning before running the Certora Prover."
                        compiler_logger.warning(warn_msg)
                    else:
                        err_msg = f"Severe compiler warning:\n{raw_msg}\n" \
                                  f"Please fix this warning before running the Certora Prover."

                        # We log all the error messages, but only the last one will be in the exception
                        if i < len(severe_errors) - 1:
                            compiler_logger.error(err_msg)
                        else:
                            raise Util.CertoraUserInputError(err_msg)

    def collect_for_file(self,
                         build_arg_contract_file: str,
                         file_index: int,
                         smart_contract_lang: CompilerLang,
                         compile_wd: Path,
                         path_for_compiler_collector_file: str,
                         original_sdc: Optional[SDC],
                         fail_on_compilation_error: bool = True,
                         reroute_main_path: bool = False
                         ) -> List[SDC]:
        """
        Collects [ContractInSDC]s for all the contracts in a given file [build_arg_contract_file],
        by traversing the dependency graph of those contracts.
        @param build_arg_contract_file - the file we are looking at.
        @param file_index - unique index for the file [build_arg_contract_file].
        @param smart_contract_lang - an indicator for which high level language and compiler we work with
        @param fail_on_compilation_error - boolean parameter which indicates what exception is raised in case of
            a compilation error.
        @param reroute_main_path - boolean parameter which indicates whether we need to reroute the main path to be
            relative to the cwd.
        @param compile_wd - sets the working directory for the Solidity compiler
        @returns list of [SDC]s, each corresponds to a primary contract in [build_arg_contract_file].
        """
        # the contracts in the file we compile
        contracts_in_file = self.context.file_to_contract[build_arg_contract_file]
        file_abs_path = Util.abs_posix_path(build_arg_contract_file)
        is_vyper = smart_contract_lang == CompilerLangVy()
        sdc_name = f"{Path(build_arg_contract_file).name}_{file_index}"
        compilation_path = self.get_compilation_path(sdc_name)
        self.file_to_sdc_name[Util.abs_norm_path(build_arg_contract_file)] = sdc_name

        compiler_collector = self.compiler_coll_factory \
            .get_compiler_collector(Path(path_for_compiler_collector_file))

        # update remappings and collect_cmd:
        if not is_vyper:
            Util.safe_create_dir(compilation_path)
            compiler_ver_to_run = get_relevant_compiler(Path(build_arg_contract_file), self.context)
            """
            when we compile with autofinders, we compile from .certora_sources.
            to avoid compilation issues due to conflicting-but-not-really-conflicting imports
            in solc, we can re-route the packages to point to node_modules or whatever other packages_path
            they have in .certora_sources. This is the role of route_func.
            Also note that remappings expect a full absolute path.
            This is not applied to provided_remappings at the moment
            re. E731 - we don't care about the linter wanting a def instead of a lambda here.
            """
            compiler_logger.debug(f"compile_wd={compile_wd}, solc_allow_path={self.context.solc_allow_path}")
            if reroute_main_path:
                """
                Note that self.context.solc_allow_path is either a relative path (relative to the original cwd) or an
                absolute path which isn't a subpath of the original cwd. In the second case the following line of code
                is equivalent to `main_path = self.context.solc_allow_path`, and that's the best we can do in that
                case anyway.
                """
                main_path = (compile_wd / self.context.solc_allow_path).absolute()
            else:
                main_path = self.context.solc_allow_path
            self.context.solc_cmd_allow_paths = [f'"{main_path}"', '.']
            # ABI and bin-runtime cmds preparation
            if self.context.packages is not None:
                self.context.remappings = self.context.packages
                compiler_logger.debug(f"remappings={self.context.remappings}")

                remapping_pairs = list(map(lambda remap: remap.split("="), self.context.remappings))

                # solc is so annoying! if the remapped path ends with '/' we need the path to have it in the path too
                # otherwise, the package/ part will be replaced by solc with the full path without the '/',
                # leading to "File not found error".
                # We do this only for the remappings map, not for allow-paths.
                remapping_pairs = list(
                    map(lambda p: (p[0], f"{p[1]}/" if p[0].endswith("/") else p[1]), remapping_pairs))
                self.context.remappings = list(map(lambda remap: f'{remap[0]}={remap[1]}', remapping_pairs))
                paths_for_remappings = list(map(lambda remap: f'"{remap[1]}"', remapping_pairs))
                self.context.solc_cmd_allow_paths += paths_for_remappings

            collect_cmd = f'{compiler_ver_to_run} -o "{compilation_path}/" --overwrite ' \
                          f'--allow-paths {",".join(self.context.solc_cmd_allow_paths)} --standard-json'
            compiler_logger.debug(f"collect_cmd: {collect_cmd}\n")
        else:
            compiler_ver_to_run = get_relevant_compiler(Path(build_arg_contract_file), self.context)
            path_string = ""
            if compiler_collector.compiler_version[1] < 4:
                path_string = f' -p "{self.context.solc_allow_path}"'
            collect_cmd = f'{compiler_ver_to_run}{path_string} -o "{compilation_path}" ' \
                          f'--standard-json'

        # Make sure compilation artifacts are always deleted
        # Unless we're in debug mode, we prefer to exclude the stdout file which is potentially huge
        if not self.context.debug:
            self.__compiled_artifacts_to_clean.add((sdc_name, smart_contract_lang))
        else:
            # in debug mode, we want to keep artifacts. If we recompile the same contract (e.g. autofinders),
            # we want to preserve the previous artifacts too for a comprehensive view
            # (we do not try to save a big chain history of changes, just a previous and current)
            self.backup_compiler_outputs(sdc_name, smart_contract_lang, "prev")

        # Standard JSON
        remappings = [] if isinstance(compiler_collector, CompilerCollectorYul) else self.context.remappings
        input_for_solc = self.standard_json(Path(file_abs_path), build_arg_contract_file, remappings,
                                            compiler_collector)
        standard_json_input = json.dumps(input_for_solc).encode("utf-8")
        compiler_logger.debug(f"about to run in {compile_wd} the command: {collect_cmd}")
        compiler_logger.debug(f"solc input = {json.dumps(input_for_solc, indent=4)}")

        if self.context.test == str(Util.TestValue.CHECK_SOLC_OPTIONS) and eval(self.context.test_condition):
            raise Util.TestResultsReady({'standard_json_input': standard_json_input, 'main_path': main_path})
        Util.run_compiler_cmd(collect_cmd, f"{sdc_name}.standard.json", wd=compile_wd,
                              compiler_input=standard_json_input)

        compiler_logger.debug(f"Collecting standard json: {collect_cmd}")
        standard_json_data = self.get_standard_json_data(sdc_name, smart_contract_lang)

        for error in standard_json_data.get("errors", []):
            # is an error not a warning
            if error.get("severity", None) == "error":
                compiler_logger.debug(f"Error: standard-json invocation of solc encountered an error: {error}")
                # 6275 the error code of solc compiler for missing file
                if 'errorCode' in error and error['errorCode'] == '6275':
                    print_package_file_note()
                friendly_message = f"{compiler_ver_to_run} had an error:\n" \
                                   f"{error['formattedMessage']}"
                if fail_on_compilation_error:
                    raise Util.CertoraUserInputError(friendly_message)
                else:
                    # We get here when we fail compilation on the autofinders.
                    # This is not a user input error because we generated this Solidity code
                    raise Util.SolcCompilationException(friendly_message)

        # load data
        data = \
            smart_contract_lang.collect_storage_layout_info(file_abs_path, compilation_path, compiler_ver_to_run,
                                                            compiler_collector.compiler_version,
                                                            standard_json_data)  # Note we collected for just ONE file
        self.check_for_errors_and_warnings(data, fail_on_compilation_error)
        if smart_contract_lang.supports_ast_output:
            self.collect_asts(build_arg_contract_file, data["sources"])

        contracts_with_libraries = {}
        file_compiler_path = smart_contract_lang.normalize_file_compiler_path_name(file_abs_path)

        # But apparently this heavily depends on the Solidity AST format anyway

        # Need to add all library dependencies that are in a different file:
        seen_link_refs = {Path(file_compiler_path)}
        contracts_to_add_dependencies_queue = [Path(file_compiler_path)]
        resolved_to_orig: Dict[str, str] = {}
        build_logger.debug(f"collecting worklist for {file_compiler_path}")
        while contracts_to_add_dependencies_queue:
            contract_file_obj = contracts_to_add_dependencies_queue.pop()
            contract_file = str(contract_file_obj)
            build_logger.debug(f"Processing dependencies from file {contract_file}")
            # make sure path name is in posix format.
            contract_file_abs = str(Util.abs_norm_path(contract_file))

            # using os.path.relpath because Path.relative_to cannot go up the directory tree (no ..)
            contract_file_rel = os.path.relpath(Path(contract_file_abs), Path.cwd())

            build_logger.debug(f"available keys: {data['contracts'].keys()}")
            if contract_file_rel in data[CONTRACTS]:
                contract_file = contract_file_rel
                unsorted_contract_list = data[CONTRACTS][contract_file]
            elif contract_file_abs in data[CONTRACTS]:
                contract_file = contract_file_abs
                unsorted_contract_list = data[CONTRACTS][contract_file_abs]
            elif contract_file in data[CONTRACTS]:
                # when does this happen? Saw this in TrustToken on a package source file
                unsorted_contract_list = data[CONTRACTS][contract_file]
            elif resolved_to_orig.get(contract_file) in data[CONTRACTS]:
                unsorted_contract_list = data[CONTRACTS][resolved_to_orig[contract_file]]
                contract_file = resolved_to_orig[contract_file]
            else:
                # our file may be a symlink!
                raise Exception(
                    f"Worklist contains {contract_file} (relative {contract_file_rel}, "
                    f"absolute {contract_file_abs}), resolved from {resolved_to_orig.get(contract_file)} "
                    f"that does not exist in contract set {resolved_to_orig.get(contract_file) in data['contracts']}")

            contract_list = sorted([c for c in unsorted_contract_list])
            # every contract file may contain numerous primary contracts, but the dependent contracts
            # are the same for all primary contracts in a file
            contracts_with_libraries[contract_file] = contract_list

            if not is_vyper and smart_contract_lang != CompilerLangYul():
                for contract_name in contract_list:
                    # Collecting relevant Solidity files to work on: base, libraries externally called
                    # and libraries internally called
                    base_contracts = sorted(self.retrieve_base_contracts_list(build_arg_contract_file, contract_file,
                                                                              contract_name), key=lambda x: x[0])
                    for c_file, _, _ in base_contracts:
                        norm_c_file = Util.abs_norm_path(c_file)
                        resolved_to_orig[str(norm_c_file)] = c_file
                        if norm_c_file not in seen_link_refs:
                            build_logger.debug(f"Adding a base contract link ref {norm_c_file} to worklist")
                            contracts_to_add_dependencies_queue.append(norm_c_file)
                            seen_link_refs.add(norm_c_file)

                    compiler_logger.debug(f"base contracts {base_contracts}")
                    contract_object = data[CONTRACTS][contract_file][contract_name]
                    lib_link_refs = sorted(contract_object["evm"]["deployedBytecode"]["linkReferences"])
                    for lib_link_ref in lib_link_refs:  # linkReference is a library reference
                        norm_link_ref = Path(lib_link_ref).absolute()
                        resolved_to_orig[str(norm_link_ref)] = lib_link_ref
                        if norm_link_ref not in seen_link_refs:
                            build_logger.debug(f"Adding library link ref {norm_link_ref} to worklist")
                            contracts_to_add_dependencies_queue.append(norm_link_ref)
                            seen_link_refs.add(norm_link_ref)

                    # we're also adding libraries that are referenced by internal functions, not just delegations
                    internal_refs = self.get_libraries_referenced_with_internal_functions(build_arg_contract_file,
                                                                                          contract_file, contract_name)
                    for ref in sorted(set(internal_refs)):
                        # Save absolute paths.
                        # There may be confusion as to whether solidity's json output uses
                        # absolute or relative paths
                        # turns out, it can be neither.
                        # The resolving here can ruin us if the entries we get are symlinks.
                        contract_refs = [c_file for c_file in
                                         data[CONTRACTS].keys() if
                                         ref in data[CONTRACTS][c_file].keys()]
                        contract_files_resolved = sorted([Util.abs_norm_path(x) for x in contract_refs])

                        # There may be two non unique paths but actually referring to the same absolute path.
                        # Normalizing as absolute
                        if len(set(contract_files_resolved)) != 1:
                            build_logger.debug(f'Unexpectedly there are either 0 or multiple unique paths for the same'
                                               f' contract or library name, skipping adding link references: '
                                               f'{ref}, {contract_files_resolved}')
                        else:
                            # let's take the original ref. we know it's in data[CONTRACTS]
                            internal_link_ref = contract_files_resolved[0]
                            # we keep the original to handle symlinks
                            resolved_to_orig[str(internal_link_ref)] = contract_refs[0]
                            if internal_link_ref not in seen_link_refs:
                                build_logger.debug(f"Adding internal link ref {internal_link_ref} to worklist")
                                contracts_to_add_dependencies_queue.append(internal_link_ref)
                                seen_link_refs.add(internal_link_ref)

        build_logger.debug(
            f"Contracts in {sdc_name} (file {build_arg_contract_file}): "
            f"{contracts_with_libraries.get(file_compiler_path, None)}")
        contracts_with_chosen_addresses = \
            [(self.generate_address(), f"{contract_file}:{contract_name}") for contract_file, contract_list in
             sorted(contracts_with_libraries.items(), key=lambda entry: entry[0]) for contract_name in
             contract_list]  # type: List[Tuple[int, Any]]

        build_logger.debug(f"Contracts with their chosen addresses: {contracts_with_chosen_addresses}")
        sdc_lst_to_return = []
        if smart_contract_lang.supports_srclist_output:
            srclist = self.build_srclist(data, sdc_name, smart_contract_lang)
            report_srclist = self.build_report_srclist(srclist, build_arg_contract_file)
        else:
            srclist = {"0": file_abs_path}
            report_srclist = {"0": file_abs_path}

        report_source_file = report_srclist[[idx for idx in srclist if srclist[idx] == file_abs_path][0]]

        # all "contracts in SDC" are the same for every primary contract of the compiled file.
        # we can therefore compute those just once...
        # Solidity provides us with the list of contracts (non primary) that helped in compiling
        # the primary contract(s).
        contracts_in_sdc = []
        for contract_file, contract_list in sorted(list(contracts_with_libraries.items())):
            for contract_name in contract_list:
                original_contract = None if original_sdc is None else original_sdc.find_contract(contract_name)

                contract_in_sdc = self.get_contract_in_sdc(
                    contract_file,
                    contract_name,
                    contracts_with_chosen_addresses,
                    data,
                    report_source_file,
                    contracts_in_file,
                    build_arg_contract_file,
                    compiler_collector,
                    compile_wd,
                    original_contract,
                )
                contracts_in_sdc.append(contract_in_sdc)

        for primary_contract in contracts_in_file:
            # every contract inside the compiled file is a potential primary contract (if we requested it)
            build_logger.debug(f"For contracts of primary {primary_contract}")

            build_logger.debug(f"finding primary contract address of {file_compiler_path}:{primary_contract} in "
                               f"{contracts_with_chosen_addresses}")
            primary_contract_address = \
                self.find_contract_address_str(file_compiler_path,
                                               primary_contract,
                                               contracts_with_chosen_addresses)
            build_logger.debug(f"Contracts in SDC {sdc_name}: {[contract.name for contract in contracts_in_sdc]}")
            # Need to deduplicate the library_addresses list without changing the order
            deduplicated_library_addresses = list(OrderedDict.fromkeys(self.library_addresses))

            all_contract_files = SDC.sources_as_absolute(srclist)
            sdc = SDC(primary_contract,
                      compiler_collector,
                      primary_contract_address,
                      build_arg_contract_file,
                      srclist,
                      report_srclist,
                      sdc_name,
                      contracts_in_sdc,
                      deduplicated_library_addresses,
                      {},
                      {},
                      {},
                      all_contract_files
                      )
            sdc_lst_to_return.append(sdc)

        self.library_addresses.clear()  # Reset library addresses

        return sdc_lst_to_return

    def build_report_srclist(self,
                             srclist: Dict[str, Any],
                             build_arg_contract_file: str) -> Dict[str, str]:
        # CERT-4723 this is likely cleanable after addressing this ticket, keeping only the case new_path is not None
        # if certora_sources directory exists, we can resolve report_srclist
        # what is report_srclist? It is the list based on which we will resolve srcmaps
        # in the Prover jar. Before the jar is invoked it must be resolved relatively
        # to .certora_sources to make sure it's accessible
        if Util.get_certora_sources_dir().exists():
            # how was this source path mapped to .certora_sources?
            # we used a common path. so we try trimming prefixes until it can be relative to certora_sources
            new_srclist_map = {}
            successful = True
            for idx, orig_file in srclist.items():
                new_path = Util.find_filename_in(Util.get_certora_sources_dir(), orig_file)
                if new_path is None:
                    file_abs_path = Util.abs_posix_path(build_arg_contract_file)
                    # for vyper, because there are no autofinders, at least find the main file
                    if (orig_file == file_abs_path and
                        ((Util.get_certora_sources_dir() / build_arg_contract_file).exists() or
                         Path(build_arg_contract_file).exists())):
                        new_srclist_map[idx] = build_arg_contract_file
                    else:
                        successful = False
                        break
                else:
                    new_srclist_map[idx] = str(new_path)
            if successful:
                report_srclist = new_srclist_map
            else:  # we will try again and then after syncing of .certora_sources it will succeed
                report_srclist = srclist
        else:
            report_srclist = srclist
        return report_srclist

    def get_bytecode(self,
                     bytecode_object: Dict[str, Any],
                     contract_name: str,
                     primary_contracts: List[str],
                     contracts_with_chosen_addresses: List[Tuple[int, Any]],
                     fail_if_no_bytecode: bool
                     ) -> str:
        """
        Computes the linked bytecode object from the Solidity compiler output.
        First fetches the bytecode objects and then uses link references to replace library addresses.

        @param bytecode_object - the output from the Solidity compiler
        @param contract_name - the contract that we are working on
        @param primary_contracts - the names of the primary contracts we check to have a bytecode
        @param contracts_with_chosen_addresses - a list of tuples of addresses and the
            associated contract identifier
        @param fail_if_no_bytecode - true if the function should fail if bytecode object is missing,
            false otherwise
        @returns linked bytecode object
        """
        # TODO: Only contract_name should be necessary. This requires a lot more test cases to make sure we're not
        # missing any weird solidity outputs.
        bytecode_ = bytecode_object["object"]
        bytecode = self.collect_and_link_bytecode(contract_name, contracts_with_chosen_addresses,
                                                  bytecode_, bytecode_object.get("linkReferences", {}))
        if contract_name in primary_contracts and len(bytecode) == 0:
            msg = f"Contract {contract_name} has no bytecode. " \
                  f"It may be caused because the contract is abstract, " \
                  f"or is missing constructor code. Please check the output of the Solidity compiler."
            if fail_if_no_bytecode:
                raise Util.CertoraUserInputError(msg)
            else:
                build_logger.warning(msg)

        return bytecode

    def annotate_storage(self, storage_data: Dict, build_arg_contract_file: str) -> Dict:
        '''
        Summary
        -------
        Annotates the storage data dictionary in-place with more information needed by the prover.

        Parameters
        ----------
        storage_data : Dict
            The dictionary holding the storage data.

        Returns
        -------
        Dict
            A reference to the modified storage_data
        '''
        def annotate_type(desc: CT.Type, type_name: str, store_types: Dict[str, Any],
                          slot: Any = None, offset: Any = None) -> None:
            number_of_bytes = store_types[type_name]['numberOfBytes']
            lower_bound = store_types[type_name].get('lowerBound')
            upper_bound = store_types[type_name].get('upperBound')
            annotations = [CT.StorageAnnotation(number_of_bytes, slot, offset, lower_bound,
                                                upper_bound)]
            desc.annotate(annotations)

            if isinstance(desc, CT.StructType):
                for desc_member in desc.members:
                    for struct_member in store_types[type_name]['members']:
                        if desc_member.name == struct_member['label']:
                            annotate_type(desc_member.type, struct_member['type'], store_types,
                                          struct_member['slot'], struct_member['offset'])
            if isinstance(desc, CT.MappingType):
                annotate_type(desc.domain, store_types[type_name]['key'], store_types)
                annotate_type(desc.codomain, store_types[type_name]['value'], store_types)
            if isinstance(desc, CT.ArrayType):
                annotate_type(desc.elementType, store_types[type_name]['base'], store_types)

        if storage_data is None or not isinstance(storage_data, dict) or 'storage' not in storage_data:
            return storage_data

        declarations = storage_data['storage']
        if not isinstance(declarations, list):
            return storage_data

        for storage_slot in declarations:
            if not isinstance(storage_slot, dict) or not isinstance(storage_slot.get('astId', None), int):
                continue

            ast_id = storage_slot['astId']
            node = self.get_node_from_asts(self.asts, build_arg_contract_file, ast_id)
            if not isinstance(node, dict) or 'typeName' not in node:
                continue

            descriptor = CT.Type.from_type_name_node(
                lambda ref: self.get_original_def_node(build_arg_contract_file, ref),
                lambda ref: self.get_contract_file_of_non_autofinder(build_arg_contract_file, ref),
                node['typeName']
            )
            type_descriptor = descriptor
            annotate_type(descriptor, storage_slot['type'], storage_data['types'])
            storage_slot['descriptor'] = type_descriptor.as_dict()
        return storage_data

    def get_contract_in_sdc(self,
                            source_code_file: str,
                            contract_name: str,
                            contracts_with_chosen_addresses: List[Tuple[int, Any]],
                            data: Dict[str, Any],
                            report_source_file: str,
                            primary_contracts: List[str],
                            build_arg_contract_file: str,
                            compiler_collector_for_contract_file: CompilerCollector,
                            compile_wd: Path,
                            original_contract: Optional[ContractInSDC],
                            ) -> ContractInSDC:
        contract_data = data[CONTRACTS][source_code_file][contract_name]
        ast_logger.debug(f"Contract {contract_name} is in file {source_code_file}")
        compiler_lang = compiler_collector_for_contract_file.smart_contract_lang
        if compiler_lang == CompilerLangSol():
            lang = "Solidity"
            types = self.collect_source_type_descriptions(source_code_file, build_arg_contract_file, compile_wd,
                                                          compiler_lang)
            funcs = self.collect_funcs(contract_data, source_code_file, contract_name,
                                       build_arg_contract_file, types, original_contract)
        elif compiler_lang == CompilerLangVy():
            lang = compiler_lang.name
            (types, clfuncs) = compiler_lang.collect_source_type_descriptions_and_funcs(self.asts, contract_data,
                                                                                        source_code_file, contract_name,
                                                                                        build_arg_contract_file)
            funcs = [Func(
                name=x.name,
                fullArgs=x.fullArgs,
                paramNames=x.paramNames,
                returns=x.returns,
                sighash=x.sighash,
                notpayable=x.notpayable,
                fromLib=x.fromLib,
                isConstructor=x.isConstructor,
                is_free_func=False,
                stateMutability=x.stateMutability,
                visibility=x.visibility,
                implemented=x.implemented,
                overrides=x.overrides,
                virtual=False,
                contractName=x.contractName,
                ast_id=x.ast_id,
                source_bytes=x.source_bytes,
                original_file=x.original_file,
                location=None,
                body_location=x.body_location,
            ) for x in clfuncs]
        elif compiler_lang == CompilerLangYul():
            lang = compiler_lang.name
            # xxx ideally we'd merge this collect_source_type_descriptions_and_funcs, but it requires merging some type.
            # work for later...
            types = []  # no types in yul
            # solc does not give ABI info for yul unfortunately, we must get it with --yul_abi JSON option
            yul_abi_json_filepath = self.context.yul_abi
            # safe cast, xxx until we merge
            funcs = typing.cast(CompilerLangYul, compiler_lang).get_funcs(yul_abi_json_filepath, contract_name)
        else:
            raise Exception(f"No support for compiler language {compiler_lang}")
        external_funcs = {f for f in funcs if f.visibility in ['external', 'public']}
        public_funcs = {f for f in funcs if f.visibility in ['public']}
        internal_funcs = {f for f in funcs if f.visibility in ['private', 'internal']}

        if compiler_lang != CompilerLangYul():
            if original_contract is not None:
                source_bytes = original_contract.source_bytes
            else:
                source_bytes = self.collect_contract_bytes(source_code_file, contract_name, build_arg_contract_file)
            ast_logger.debug(f"Source bytes of {contract_name}: {source_bytes}")
        else:
            # if this changes to non-None, change type of ContractInSDC.source_bytes to non-Optional
            source_bytes = None

        ast_logger.debug(f"Internal Functions of {contract_name}: {[fun.name for fun in internal_funcs]}")
        ast_logger.debug(f"Functions of {contract_name}: {[fun.name for fun in funcs]}")
        (srcmap, constructor_srcmap) = self.collect_srcmap(contract_data)

        varmap = ""
        deployed_bytecode = self.get_bytecode(contract_data["evm"]["deployedBytecode"], contract_name,
                                              primary_contracts,
                                              contracts_with_chosen_addresses, True)
        deployed_bytecode = compiler_lang.normalize_deployed_bytecode(
            deployed_bytecode)
        constructor_bytecode = self.get_bytecode(contract_data["evm"]["bytecode"], contract_name, primary_contracts,
                                                 contracts_with_chosen_addresses, False)
        constructor_bytecode = compiler_lang.normalize_deployed_bytecode(
            constructor_bytecode)
        address, is_static_address = \
            self.find_contract_address(source_code_file, contract_name, contracts_with_chosen_addresses)
        storage_layout = self.annotate_storage(self.collect_storage_layout(contract_data), build_arg_contract_file)
        transient_storage_layout = self.annotate_storage(self.collect_transient_storage_layout(contract_data),
                                                         build_arg_contract_file)
        immutables = self.collect_immutables(contract_data, build_arg_contract_file, compiler_lang)

        internal_function_entrypoints = set([])

        if compiler_lang == CompilerLangSol() and "functionDebugData" in contract_data["evm"]["deployedBytecode"]:
            debug = contract_data["evm"]["deployedBytecode"]["functionDebugData"]
            for (_, v) in debug.items():
                if "entryPoint" in v and v["entryPoint"] is not None:
                    internal_function_entrypoints.add(v["entryPoint"])

        if self.context.internal_funcs is not None:
            all_internal_functions: Dict[str, Any] = \
                Util.read_json_file(self.context.internal_funcs)
            if contract_name in all_internal_functions:
                function_finders = all_internal_functions[contract_name]
            else:
                function_finders = {}
        else:
            function_finders = {}

        ast_logger.debug(f"Found internal functions for contract {contract_name}: {function_finders}")

        if compiler_lang == CompilerLangSol():
            settings_dict: Dict[str, Any] = {}
            self._fill_codegen_related_options(Path(build_arg_contract_file), settings_dict,
                                               compiler_collector_for_contract_file)
            solc_optimizer_on, solc_optimizer_runs = self.solc_setting_optimizer_runs(settings_dict)
            solc_via_ir = self.solc_setting_via_ir(settings_dict)
            compiler_parameters = SolcParameters(solc_optimizer_on, solc_optimizer_runs, solc_via_ir)
        else:
            compiler_parameters = None

        return ContractInSDC(contract_name,
                             # somehow make sure this is an absolute path which obeys the autofinder remappings
                             # (i.e. make sure this file doesn't start with autoFinder_)
                             source_code_file,
                             lang,
                             report_source_file,
                             address,
                             is_static_address,
                             external_funcs,
                             deployed_bytecode,
                             constructor_bytecode,
                             srcmap,
                             varmap,
                             constructor_srcmap,
                             storage_layout,
                             transient_storage_layout,
                             immutables,
                             function_finders,
                             internal_funcs=internal_funcs,
                             public_funcs=public_funcs,
                             all_funcs=list(),
                             types=types,
                             compiler_collector=compiler_collector_for_contract_file,
                             source_bytes=source_bytes,
                             compiler_parameters=compiler_parameters,
                             extension_contracts=list(),
                             local_assignments={},
                             branches={},
                             requires={},
                             internal_starts=list(internal_function_entrypoints)
                             )

    @staticmethod
    def get_sdc_key(contract: str, address: str) -> str:
        return f"{contract}_{address}"

    @staticmethod
    def get_primary_contract_from_sdc(contracts: List[ContractInSDC], primary: str) -> List[ContractInSDC]:
        return [x for x in contracts if x.name == primary]

    @staticmethod
    def generate_library_import(file_absolute_path: str, library_name: str) -> str:
        return f"\nimport {'{'}{library_name}{'}'} from '{file_absolute_path}';"

    def add_auto_finders(self, contract_file: str,
                         sdc: SDC) -> Optional[Tuple[Dict[str, InternalFunc], Dict[str, Dict[int, Instrumentation]]]]:
        function_finder_by_contract: Dict[str, InternalFunc] = dict()
        # contract file -> byte offset -> to insert
        function_finder_instrumentation: Dict[str, Dict[int, Instrumentation]] = dict()
        if not isinstance(sdc.compiler_collector, CompilerCollectorSol):
            raise Exception(f"Encountered a compiler collector that is not solc for file {contract_file}"
                            " when trying to add function autofinders")
        instrumentation_logger.debug(f"Using {sdc.compiler_collector} compiler to "
                                     f"add auto-finders to contract {sdc.primary_contract}")

        for c in sdc.contracts:
            for f in c.internal_funcs.union(c.public_funcs):
                if f.isConstructor:
                    continue
                function_parameters = [arg for arg in zip(f.fullArgs, f.paramNames) if
                                       isinstance(arg[0].type, CT.FunctionType)]
                """
                we don't support a generation of auto-finders for functions that have
                external function type parameters
                """
                if function_parameters:
                    instrumentation_logger.warning(
                        f"Cannot generate an auto-finder for {f.source_code_signature()} " +
                        f"in {c.name} due to external function type parameters: " +
                        ", ".join(map(lambda function_parameter: function_parameter[1],
                                      function_parameters)) +
                        ". Therefore, this function cannot be summarized.")
                    continue
                loc = f.where()
                if loc is None:
                    if not f.implemented:
                        continue
                    instrumentation_logger.debug(f"Found an (implemented) function {f.name} in"
                                                 f" {c.name} that doesn't have a location")
                    return None

                instrumentation_path = str(Util.abs_norm_path(loc[0]))
                if instrumentation_path not in function_finder_instrumentation:
                    instrumentation_logger.debug(f"New instrumentation for location {loc[0]}, " +
                                                 f"instrumentation path {instrumentation_path}")
                    function_finder_instrumentation[instrumentation_path] = dict()
                else:
                    instrumentation_logger.debug(f"Using existing instrumentation for location {loc[0]}, " +
                                                 f"instrumentation path {instrumentation_path}")

                if len(f.fullArgs) != len(f.paramNames):
                    instrumentation_logger.debug(f"Do not have argument names for {f.name} in"
                                                 f" {c.name}, giving up auto finders")
                    return None

                per_file_inst = function_finder_instrumentation[instrumentation_path]

                start_byte = int(loc[1].split(":")[0])
                # suuuuch a hack
                if start_byte in per_file_inst:
                    continue

                if f.ast_id is None:
                    instrumentation_logger.debug(f"No ast_id for function {f}, giving up here")
                    return None
                def_node = self.asts[contract_file].get(loc[0], dict()).get(f.ast_id, None)
                if def_node is None:
                    # could be a freefunc. If it is, we cannot find it in the ast of loc
                    def_node = self.get_node_from_asts(self.asts, contract_file, f.ast_id)
                    if "kind" not in def_node or def_node["kind"] != CertoraBuildGenerator.FREEFUNCTION_STRING:
                        instrumentation_logger.debug(f"Failed to find def node for {f} {def_node} {f.ast_id}")
                        return None
                mods = def_node.get("modifiers", [])  # type: List[Dict[str, Any]]

                internal_id = self.function_finder_generator_counter
                self.function_finder_generator_counter += 1
                function_symbol = 0xf196e50000 + internal_id
                full_contract_file = self.get_contract_file_of_non_autofinder(contract_file, def_node["id"])
                function_finder_by_contract["0x%x" % function_symbol] = InternalFunc(full_contract_file, c.name, f)
                # if no function finder mode set, determine based on viaIR enabled or not:
                if self.context.function_finder_mode is None:
                    # in via-ir, should not compress
                    if self.get_solc_via_ir_value(Path(contract_file)):
                        should_compress = False
                    else:
                        should_compress = True
                else:
                    # if function finder mode is set, should compress only if it's specifically set to default mode
                    # (deprecate this option later)
                    should_compress = self.context.function_finder_mode == Vf.FunctionFinderMode.DEFAULT.name

                if len(mods) > 0:
                    # we need to add the instrumentation in a modifer because solidity modifiers will (potentially)
                    # appear before any instrumentation we add to the literal source body, which will tank the detection
                    # process. We cannot instrument the modifiers directly because they can be shared among multiple
                    # implementations.
                    #
                    # Q: Why not always instrument with modifiers?
                    # A: Without modifiers already present, the solidity AST makes it extremely difficult to figure out
                    # where in the source such modifiers will go. In order to insert a modifier, we have to have at
                    # least one modifier already present, and then insert before the first modifier's location in the
                    # source code
                    mod_inst = generate_modifier_finder(f, internal_id, function_symbol, sdc.compiler_collector,
                                                        def_node, compress=should_compress)
                    if mod_inst is None:
                        instrumentation_logger.debug(f"Modifier generation for {f.name} @ {f.where()} failed")
                        return None
                    modifier_invocation, modifier_def = mod_inst
                    func_def_start_str = def_node.get("src", None)
                    if func_def_start_str is None or not isinstance(func_def_start_str, str):
                        instrumentation_logger.debug(f"Could not get source information for function "
                                                     f"{f.name} @ {f.where()}")
                        return None
                    func_loc_split = func_def_start_str.split(":")
                    func_end_byte = int(func_loc_split[0]) + int(func_loc_split[1]) - 1
                    per_file_inst[func_end_byte] = Instrumentation(expected=b'}', to_ins=modifier_def,
                                                                   mut=InsertAfter())

                    if any(map(
                        lambda mod: (
                            mod.get("nodeType", None) != "ModifierInvocation" or
                            type(mod.get("src", None)) != str
                        ),
                        mods
                    )):
                        instrumentation_logger.debug(f"Unrecognized modifier AST node for {f.name} @ {f.where()}")
                        return None
                    first_mod = min(mods, key=lambda mod: int(mod["src"].split(":")[0]))
                    modifier_name = first_mod.get("modifierName", dict()).get("name", None)
                    if not isinstance(modifier_name, str):
                        instrumentation_logger.debug(f"Can't infer expected name for modififer "
                                                     f"{modifier_invocation} for {f.name} @ {f.where()}")
                        return None
                    first_mod_offset = int(first_mod["src"].split(":")[0])
                    per_file_inst[first_mod_offset] = Instrumentation(expected=bytes(modifier_name[0:1], "utf-8"),
                                                                      to_ins=modifier_invocation, mut=InsertBefore())
                else:
                    finder_res = generate_inline_finder(f, internal_id, function_symbol,
                                                        sdc.compiler_collector, should_compress)
                    if finder_res is None:
                        instrumentation_logger.debug(f"Generating auto finder for {f.name} @ {f.where()}"
                                                     f" failed, giving up generation")
                        return None
                    finder_string = finder_res
                    per_file_inst[start_byte] = Instrumentation(expected=b'{', to_ins=finder_string,
                                                                mut=InsertAfter())
        return function_finder_by_contract, function_finder_instrumentation

    def add_internal_func_harnesses(self, contract_file: str, sdc: SDC, specCalls: List[str]) -> Optional[Tuple[Dict[str, str], Dict[str, Dict[int, Instrumentation]]]]:
        # contract file -> byte offset -> to insert
        harness_function_instrumentation: Dict[str, Dict[int, Instrumentation]] = defaultdict(dict)
        # internal function name -> harness fuction name
        harness_function_names: Dict[str, str] = {}

        if not isinstance(sdc.compiler_collector, CompilerCollectorSol):
            raise Exception(f"Encountered a compiler collector that is not solc for file {contract_file}"
                            " when trying to add function autofinders")
        instrumentation_logger.debug(f"Using {sdc.compiler_collector} compiler to "
                                     f"add external function harnesses to contract {sdc.primary_contract}")

        for c in sdc.contracts:
            for f in c.internal_funcs:
                if f"{sdc.primary_contract}.{f.name}" not in specCalls:
                    continue

                if f.fromLib:
                    # Even external library functions can't be called directly from spec,
                    # so skip harnessing internal ones.
                    continue

                if f.isConstructor:
                    continue

                if f.is_free_func:
                    # Free functions (declared outside of any contract/library) don't have visibility modifiers
                    continue

                orig_file = f.original_file
                if not orig_file:
                    instrumentation_logger.debug(f"missing file location for {f.name}")
                    continue

                loc = f.location
                if not loc:
                    instrumentation_logger.debug(f"Found a function {f.name} in "
                                                 f"{c.name} that doesn't have a location")
                    continue

                if f.implemented:
                    expected_end_char = b"}"
                else:
                    # This is a virtual function with no implementation. Declare also a virtual harness so that when
                    # Implementing a harness for the override we can just add the `override` keyword to the harness
                    # function as well without needing to have any extra logic at that point.
                    expected_end_char = b";"

                if len(f.fullArgs) != len(f.paramNames):
                    instrumentation_logger.debug(f"Do not have argument names for {f.name} in"
                                                 f" {c.name}, giving up internal function harnessing")
                    continue

                if any(ty.location == CT.TypeLocation.STORAGE for ty in f.fullArgs + f.returns):
                    instrumentation_logger.debug(f"Function {f.name} has input arguments with 'storage' location - cannot harness it")
                    continue

                instrumentation_path = str(Util.abs_norm_path(orig_file))
                per_file_inst = harness_function_instrumentation[instrumentation_path]

                start, size, _ = loc.split(":")
                body_end_byte = int(start) + int(size) - 1

                def get_local_type_name(ty: CT.TypeInstance) -> str:
                    # Handles imports that use 'as'. E.g. `import {A as B} from "A.sol";`
                    ret = ty.get_source_str()
                    assert orig_file
                    for node in self.asts[sdc.sdc_origin_file][orig_file].values():
                        if node["nodeType"] != "ImportDirective":
                            continue
                        for alias in node["symbolAliases"]:
                            if alias["foreign"]["name"] == ty.get_source_str() and "local" in alias:
                                ret = alias["local"]
                                break

                    # Now add the location
                    return ret + (f" {ty.location.value}" if ty.location != CT.TypeLocation.STACK else "")

                harness_name = f"{f.name}_external_harness"
                harness_string = f" function {harness_name}({', '.join(f'{get_local_type_name(ty)} {n}' for ty, n in zip(f.fullArgs, f.paramNames))}) external"

                if f.stateMutability in ["pure", "view"]:
                    harness_string += f" {f.stateMutability}"

                if f.virtual:
                    harness_string += " virtual"

                if f.overrides:
                    harness_string += " override"

                if f.returns:
                    harness_string += f" returns ({', '.join(get_local_type_name(r) for r in f.returns)})"

                if f.implemented:
                    harness_call = f"{f.name}({', '.join(f.paramNames)})"
                    if f.returns:
                        harness_string += f" {{ return {harness_call}; }}"
                    else:
                        harness_string += f" {{ {harness_call}; }}"
                else:
                    harness_string += ";"

                per_file_inst[body_end_byte] = Instrumentation(expected=expected_end_char, to_ins=harness_string,
                                                               mut=InsertAfter())
                harness_function_names[f.name] = harness_name

        return harness_function_names, harness_function_instrumentation

    def cleanup(self) -> None:
        for sdc_name, smart_contract_lang in self.__compiled_artifacts_to_clean:
            self.cleanup_compiler_outputs(sdc_name, smart_contract_lang)

    def get_all_function_call_refs(self, contract_file_ast: Dict[int, Any], contract_name: str) -> List[int]:
        """
        We assume that AST nodes that do not have self.CERTORA_CONTRACT_NAME() as a key, are not part of
        the contract; in particular, file level variable declarations cannot include contract functions' calls.
        For example, in solc8.12 one gets the following TypeError (note that only constant declarations are allowed):
        '
        TypeError: Initial value for constant variable has to be compile-time constant.
        | uint constant bla = cd.stakedBalance();
        |                     ^^^^^^^^^^^^^^^^^^
        '
        """
        return [int(contract_file_ast[node_id]["expression"]["referencedDeclaration"]) for node_id in
                contract_file_ast if
                "nodeType" in contract_file_ast[node_id] and contract_file_ast[node_id][
                    "nodeType"] == "FunctionCall" and "expression" in contract_file_ast[
                    node_id] and "referencedDeclaration" in contract_file_ast[node_id]["expression"].keys() and
                # a referencedDeclaration could be None
                contract_file_ast[node_id]["expression"]["referencedDeclaration"] is not None and
                self.CERTORA_CONTRACT_NAME() in contract_file_ast[node_id] and
                contract_file_ast[node_id][self.CERTORA_CONTRACT_NAME()] == contract_name]

    def get_libraries_referenced_with_internal_functions(self, build_arg_contract_file: str, contract_file: str,
                                                         contract_name: str) -> List[str]:
        ast = self.asts[build_arg_contract_file][contract_file]
        referenced_functions = self.get_all_function_call_refs(ast, contract_name)
        referenced_nodes = [self.get_node_from_asts(self.asts, build_arg_contract_file, node_id) for
                            node_id in referenced_functions]
        # some referenced function calls could be builtins like require, whose declarations we do not see
        return [node[self.CERTORA_CONTRACT_NAME()] for node in referenced_nodes if self.CERTORA_CONTRACT_NAME() in node]

    @staticmethod
    def get_fresh_backupdir(backupdir: Path) -> Path:
        """
        returns a non-existing directory for backing-up of sources pre/post-autofinder generation.
        Every compiled contract should backup the sources before trying to overwrite with new autofinders,
        in case the autofinder compilation fails and we need to restore the previous ones.
        We should also keep post-autofinders, because otherwise our kotlin-parsed sourcemaps are wrong.
        Note that this should *not* be called in a concurrent setting and assumes sequential compilation of contracts.
        """
        folder_id = 0
        base = Util.get_certora_sources_dir() / backupdir
        while True:
            candidate = Path(f"{str(base)}.{folder_id}")
            if not candidate.exists():
                break  # it's a do-while really

            folder_id += 1
        return candidate

    def build(self, certora_verify_generator: CertoraVerifyGenerator) -> None:
        context = self.context

        specCalls: List[str] = []
        if context.verify and not context.disallow_internal_function_calls:
            with tempfile.NamedTemporaryFile("r", dir=Util.get_build_dir()) as tmp_file:
                try:
                    Ctx.run_local_spec_check(False, context, ["-listCalls",  tmp_file.name], print_errors=False)
                    specCalls = tmp_file.read().split("\n")
                except Exception as e:
                    instrumentation_logger.warning(f"Failed to get calls from spec\n{e}")

        self.context.remappings = []
        for i, build_arg_contract_file in enumerate(sorted(self.input_config.sorted_files)):
            build_logger.debug(f"\nbuilding file {build_arg_contract_file}")
            compiler_lang = get_compiler_lang(build_arg_contract_file)
            path_for_compiler_collector_file = Util.abs_posix_path(build_arg_contract_file)
            orig_file_name = Path(build_arg_contract_file)
            Util.print_progress_message(f"Compiling {orig_file_name}...")
            sdc_pre_finders = self.collect_for_file(build_arg_contract_file, i, compiler_lang, Path(os.getcwd()),
                                                    path_for_compiler_collector_file, original_sdc=None)
            # Build sources tree
            build_logger.debug("Building source tree")
            sources_from_pre_finder_SDCs = set()
            for sdc in sdc_pre_finders:
                sources_from_pre_finder_SDCs |= sdc.all_contract_files
            sources = self.collect_sources(context, certora_verify_generator, sources_from_pre_finder_SDCs)
            try:
                build_source_tree(sources, context)
            except Exception as e:
                build_logger.debug(f"build_source_tree failed. Sources: {sources}", exc_info=e)
                raise

            # in case autofinder generation fails,
            # we still want to use the computed .certora_sources to fix report_srclist
            for sdc in sdc_pre_finders:
                new_report_srclist = self.build_report_srclist(sdc.original_srclist, build_arg_contract_file)
                sdc.report_srclist = new_report_srclist

            # .certora_sources, when zipped and sent to cloud, should be a pristine copy of the original files.
            # However, we need to keep both intermediate backups of pre-autofinder versions before compilation
            # (in order to restore previous state), and backups of post-autofinders, for srcmap resolution in Prover.
            # Having .certora_sources be pristine means we can get a clean zipInput file,
            # on which we can run certoraRun for idempotent results.
            # The complications start with multliple contracts. If we compile contract A, B, C, and we successfully
            # got autofinders for A and B, but failed for C. What happens? We roll back _everything_ including
            # A and B. But sources maps for A and B refer to the instrumented versions. Oops!
            # We therefore need new backup dirs to checkpoint every successful autofinder generation.
            # It also tells us we cannot have full parallel compilation of all contracts.
            if not context.disable_internal_function_instrumentation:
                pre_backup_dir = self.get_fresh_backupdir(Util.PRE_AUTOFINDER_BACKUP_DIR)
                ignore_patterns = shutil.ignore_patterns(f"{Util.PRE_AUTOFINDER_BACKUP_DIR}.*",
                                                         f"{Util.POST_AUTOFINDER_BACKUP_DIR}.*",
                                                         f"{Util.CWD_FILE}")
                build_logger.debug(f"Backing up current .certora_sources to {pre_backup_dir}")
                sources_dir = Util.get_certora_sources_dir()
                Util.safe_copy_folder(sources_dir, pre_backup_dir, ignore_patterns)

            # Instrument autofinders
            if compiler_lang == CompilerLangSol() and not context.disable_internal_function_instrumentation:
                # We start by trying to instrument _all_ finders, both autofinders and source finders
                added_finders, all_finders_success, src_finders_gen_success, post_backup_dir = self.finders_compilation_round(
                    build_arg_contract_file, i, ignore_patterns, path_for_compiler_collector_file, pre_backup_dir,
                    sdc_pre_finders, not context.disable_source_finders, specCalls)

                # we could have a case where source finders failed but regular finders succeeded.
                # e.g. if we processed the AST wrong and skipped source finders generation
                if not src_finders_gen_success:
                    self.source_finders_failed = True

                if not all_finders_success and not context.disable_source_finders:
                    self.source_finders_failed = True
                    # let's try just the function autofinders
                    added_finders, function_autofinder_success, _, post_backup_dir = self.finders_compilation_round(
                        build_arg_contract_file, i, ignore_patterns, path_for_compiler_collector_file, pre_backup_dir,
                        sdc_pre_finders, False, specCalls)

                    if not function_autofinder_success:
                        self.auto_finders_failed = True

                if not self.auto_finders_failed or not self.source_finders_failed:
                    # setup source_dir. note that post_backup_dir must include the finders in this case
                    for _, _, _, sdc in added_finders:
                        sdc.source_dir = str(post_backup_dir.relative_to(Util.get_certora_sources_dir()))
                        sdc.orig_source_dir = str(pre_backup_dir.relative_to(Util.get_certora_sources_dir()))
            else:
                # no point in running autofinders in vyper right now
                added_finders = [({}, {}, {}, sdc_pre_finder) for sdc_pre_finder in sdc_pre_finders]

            for added_func_finders, added_source_finders, added_internal_function_harnesses, sdc in added_finders:
                for contract in sdc.contracts:
                    all_functions: List[Func] = list()
                    for k, v in added_func_finders.items():
                        # we also get the auto finders of the other contracts in the same file.
                        contract.function_finders[k] = v
                    for source_key, source_value in added_source_finders.items():
                        contract.local_assignments[source_key] = source_value
                    if contract.name == sdc.primary_contract:
                        contract.internal_function_harnesses = added_internal_function_harnesses
                    all_functions.extend(contract.methods)
                    all_functions.extend(contract.internal_funcs)
                    functions_unique_by_internal_rep = list()  # type: List[Func]
                    for f in all_functions:
                        if not any([f.same_internal_signature_as(in_list) for in_list in
                                    functions_unique_by_internal_rep]):
                            functions_unique_by_internal_rep.append(f)
                    # sorted to ease comparison between sdcs
                    contract.all_funcs = sorted(functions_unique_by_internal_rep)

                if sdc.primary_contract in self.input_config.prototypes:
                    sdc.prototypes += self.input_config.prototypes[sdc.primary_contract]

                # First, add library addresses as SDCs too (they should be processed first)
                build_logger.debug(f"Libraries to add = {sdc.library_addresses}")
                for library_address in sdc.library_addresses:
                    library_contract_candidates = [contract for contract in sdc.contracts
                                                   if contract.address == library_address]
                    if len(library_contract_candidates) != 1:
                        fatal_error(
                            build_logger,
                            f"Error: Expected to have exactly one library address for {library_address}, "
                            f"got {library_contract_candidates}"
                        )

                    library_contract = library_contract_candidates[0]
                    build_logger.debug(f"Found library contract {library_contract}")
                    # TODO: What will happen to libraries with libraries?
                    all_contract_files = SDC.sources_as_absolute(sdc.original_srclist)
                    sdc_lib = SDC(library_contract.name,
                                  sdc.compiler_collector,
                                  library_address,
                                  library_contract.original_file,
                                  sdc.original_srclist,
                                  sdc.report_srclist,
                                  f"{sdc.sdc_name}_{library_contract.name}",
                                  self.get_primary_contract_from_sdc(sdc.contracts, library_contract.name),
                                  [],
                                  {},
                                  {},
                                  {},
                                  all_contract_files)
                    sdc_lib.source_dir = sdc.source_dir
                    sdc_lib.orig_source_dir = sdc.orig_source_dir
                    self.SDCs[self.get_sdc_key(sdc_lib.primary_contract, sdc_lib.primary_contract_address)] = sdc_lib

                # Filter out irrelevant contracts, now that we extracted the libraries, leave just the primary
                sdc.contracts = self.get_primary_contract_from_sdc(sdc.contracts, sdc.primary_contract)
                assert len(
                    sdc.contracts) == 1, f"Found multiple primary contracts ({sdc.contracts}) in SDC {sdc.sdc_name}"

                self.SDCs[self.get_sdc_key(sdc.primary_contract, sdc.primary_contract_address)] = sdc

        self.handle_links()
        self.handle_struct_links()
        self.handle_contract_extensions()
        if self.context.storage_extension_annotation:
            self.handle_erc7201_annotations()
        self.handle_storage_extension_harnesses()

    def extract_slayout(self, original_file: str, ns_storage: Set[NameSpacedStorage], compiler_version: str) -> NewStorageInfo:
        """
        Given a file containing a contract with namespaced storage, extract the storage information
        corresponding to the namespaced types.

        Args:
            original_file: Path to the Solidity file containing namespaced storage declarations
            ns_storage: Set of tuples (type_name, namespace) for each namespaced storage

        Returns:
            NewStorageInfo: A tuple (fields, types) where:
                - fields: List of new fields added by the namespaced storage
                - types: Dictionary of types referenced by the fields
        """
        file_dir = Path(original_file).parent

        # Generate a unique name for the harness contract based on the contract names in the file
        harness_name = storageExtension.generate_harness_name(original_file)

        with tempfile.NamedTemporaryFile(mode="w+t", suffix=".sol", dir=file_dir, delete=True) as tmp_file:
            # Import the original file.
            # Note we import and don't inline the file's contents since that's how the
            # original file is accessed also in the actual code, and compiling it
            # directly can cause issues.
            tmp_file.write(f"import \"{original_file}\";\n\n")
            rel_path = os.path.relpath(tmp_file.name, Path.cwd())
            if self.context.compiler_map:
                self.context.compiler_map.update({rel_path: compiler_version}, last=False)
            # Write the harness contract with dummy fields for each namespaced storage
            var_to_slot = storageExtension.write_harness_contract(tmp_file, harness_name, ns_storage)
            tmp_file.flush()

            if self.context.extract_storage_extension_annotation:
                # If the flag is set, save the storage extension contract
                build_dir = Util.get_build_dir()
                shutil.copyfile(
                    Path(tmp_file.name),
                    build_dir / f"{Path(original_file).stem}_storage_extension.sol"
                )

            # normalize the path exactly the way collect_for_file expects it:
            abs_path = Util.abs_posix_path(tmp_file.name)
            self.context.file_to_contract[abs_path] = {harness_name}

            try:
                # Compile & fetch the raw storage_layout
                compile_idx = storageExtension.get_next_file_index(self.file_to_sdc_name)
                sdcs = self.collect_for_file(tmp_file.name, compile_idx, CompilerLangSol(), Path.cwd(), Util.abs_posix_path(tmp_file.name), None)
                if not sdcs:
                    raise RuntimeError(f"Failed to compile harness contract for {tmp_file}")
                layout = storageExtension.extract_harness_contract_layout(sdcs, harness_name)

                # Remap each slot according to the ERC-7201 namespace
                remapped_fields = storageExtension.remapped_fields_from_layout(layout, var_to_slot)

                return (remapped_fields, layout.get('types', {}))

            except Exception as e:
                build_logger.error(f"Error extracting storage layout for {original_file}: {str(e)}")
                raise
            finally:
                # Delete the key from the context
                self.context.file_to_contract.pop(abs_path, None)

    def handle_erc7201_annotations(self) -> None:
        """
        Look for contracts that use erc-7201 namespaced storage layout
        (see https://eips.ethereum.org/EIPS/eip-7201).

        Find contracts A s.t. A contain a type declaration with such an annotation, e.g.
          /** @custom:storage-location erc-7201:some.name.space */
          struct T { ... }

        Then, for any contract C that has A as a base contract, _extend_ C's storage layout
        information such that it contains the information for a `T` at the slot
        erc-7201(some.name.space) as defined in the EIP.
        """
        # Find all erc7201-like contracts, generate+compile a harness & extract layout information
        # maps (path,contract) -> new storage info added by (path,contract)
        slayouts: Dict[Tuple[str, str], NewStorageInfo] = {}

        # Scan all of the contracts (including dependencies of targets) for namespaced storage
        # layout information
        for target_file in self.context.file_paths:
            if target_file not in self.asts:
                # No AST for this file, so we can't do anything
                continue
            for (imported_file, imported_file_ast) in self.asts[target_file].items():
                for def_node in imported_file_ast.values():
                    if def_node.get("nodeType") != "ContractDefinition":
                        continue

                    # Construct a key for the contract definition node
                    contract_name = def_node.get("name")
                    key = (imported_file, contract_name)
                    if key in slayouts:
                        # We already have this contract's storage layout information
                        continue

                    # Collect any @custom:storage-location annotations
                    ns_storage = storageExtension.get_namespace_storage_from_ast(def_node)

                    if not ns_storage:
                        # No namespaced storage found in this contract
                        continue

                    # Now that we have all the storage layout information, extract it once
                    slayouts[key] = self.extract_slayout(imported_file, ns_storage,
                                                         get_relevant_compiler(Path(target_file), self.context))

        if self.context.test == str(Util.TestValue.STORAGE_EXTENSION_LAYOUT):
            raise Util.TestResultsReady(slayouts)

        if not slayouts:
            # No contracts with namespaced storage found
            return

        # Finally, extend each target contract with the storage layout info from
        # all of its base contracts
        for target in self.get_primary_contracts_from_sdcs():
            if target.name not in self.context.contract_to_file:
                # This is a contract that was not compiled, so we don't have a file for it
                continue
            target_file = self.context.contract_to_file[target.name]
            base_contracts = self.retrieve_base_contracts_list(
                target_file,
                Util.abs_posix_path(target_file),
                target.name
            )
            extensions: Set[str] = set()
            harnesses: Dict[str, NewStorageInfo] = {}
            for base in base_contracts:
                layout = slayouts.get((base[0], base[1]))
                if layout is not None:
                    extensions.add(base[1])
                    harnesses[base[1]] = layout
                else:
                    build_logger.warning(f"Could not find storage layout for {base[1]} in {base[0]}")
            storageExtension.apply_extensions(target, extensions, harnesses)

    def handle_storage_extension_harnesses(self) -> None:
        def new_field_of_node(ext_instance: Any, node: Dict[str, Any]) -> Optional[Dict[str, Any]]:
            """
            If this node is a storage variable declaration like the following:

            /** @custom:certoralink 0x12345 */
            T x;

            and if so, return a storage field matching the type of x but using 0x12345 as the slot
            """

            if node.get("nodeType") != "VariableDeclaration":
                return None
            if not node.get("stateVariable"):
                return None
            if "documentation" not in node:
                return None
            if node["documentation"].get("nodeType") != "StructuredDocumentation":
                return None
            annot_link = node["documentation"].get("text")
            var_name = node.get("name")
            if var_name is None or not isinstance(annot_link, str):
                return None

            link_regex = r'@custom:certoralink\s+(0x[0-9a-fA-F]+)\s*'
            link_match = re.search(link_regex, annot_link)

            if link_match is None:
                raise Util.CertoraUserInputError(f"Couldn't parse annotation in on "
                                                 f"{ext_instance.original_file_name}.{var_name}: {annot_link}")
            try:
                link_slot = int(link_match.group(1), 16)
            except ValueError:
                raise Util.CertoraUserInputError(f"Invalid certoralink slot value in annotation on"
                                                 f"{ext_instance.original_file_name}.{var_name}: {annot_link}")
            storage_field_info = None
            for field in ext_instance.storage_layout["storage"]:
                if field["label"] == var_name:
                    storage_field_info = field
                    break
            if storage_field_info is None:
                return None
            cloned_field = storage_field_info.copy()
            cloned_field["slot"] = str(link_slot)
            # Don't bother trying to uniquify the name
            cloned_field["label"] = var_name
            return cloned_field

        def handle_one_extension(storage_ext: str) -> tuple[Any, str, List[Dict[str, Any]]] :
            """
            Get the extension contract plus the new fields
            """
            extension_sdc_cands = self.get_matching_sdc_names_from_SDCs(storage_ext)
            assert len(extension_sdc_cands) == 1, f"Zero or multiple possible contracts for {storage_ext}"
            extension_sdc_name = extension_sdc_cands[0]
            extension_sdc = self.SDCs.get(extension_sdc_name)
            assert extension_sdc and extension_sdc.primary_contract == storage_ext, f"Could not find SDC for {extension_sdc_name}"
            ext_instance = extension_sdc.find_contract(storage_ext)
            assert ext_instance is not None, f"Could not find extension contract {storage_ext}"

            # let's try to find the AST now
            build_file_ast = None
            for (k, v) in self.asts.items():
                if ext_instance.original_file in v:
                    build_file_ast = k
                    break
            if build_file_ast is None:
                raise RuntimeError(f"Couldn't find file for storage extension file {storage_ext}")

            contract_def_node = self.get_contract_def_node_ref(
                build_file_ast,
                ext_instance.original_file,
                storage_ext
            )
            def_node = self.asts[build_file_ast][ext_instance.original_file][contract_def_node]
            nodes = def_node.get("nodes")
            if not isinstance(nodes, list):
                raise RuntimeError(f"Couldn't find nodes for body of {storage_ext}")

            new_fields = []
            for node in nodes:
                new_field = new_field_of_node(ext_instance, node)
                if new_field is not None:
                    new_fields.append(new_field)

            return (ext_instance, extension_sdc_name, new_fields)
        extension_contracts: Set[str] = set()
        storage_extensions: Dict[str, Set[str]] = defaultdict(set)
        storage_ext = self.context.storage_extension_harnesses

        if storage_ext is None:
            return

        for i in storage_ext:
            target_contract, extension_contract = i.split("=")
            extension_contracts.add(extension_contract)
            storage_extensions[target_contract].add(extension_contract)

        # These are the new fields to add for each contract
        extension_to_fields_and_types: Dict[str, Tuple[List[Dict[str, Any]], Dict[str, Any]]] = {}

        for storage_ext in extension_contracts:
            (ext_instance, extension_sdc_name, new_fields) = handle_one_extension(storage_ext)
            new_types = ext_instance.storage_layout["types"]
            extension_to_fields_and_types[storage_ext] = (new_fields, {} if new_types is None else new_types)
            del self.SDCs[extension_sdc_name]

        for (target, extensions) in storage_extensions.items():
            target_sdc_names = self.get_matching_sdc_names_from_SDCs(target)
            assert len(target_sdc_names) == 1, f"no matching sdc for {target}"
            target_sdc = target_sdc_names[0]
            sdc = self.SDCs[target_sdc]
            target_contract = sdc.find_contract(target)
            assert target_contract is not None, f"could not find contract for {target}"
            storageExtension.apply_extensions(target_contract, extensions, extension_to_fields_and_types)

    def finders_compilation_round(self,
                                  build_arg_contract_file: str,
                                  i: int,
                                  ignore_patterns: Any,
                                  path_for_compiler_collector_file: str,
                                  pre_backup_dir: Path,
                                  sdc_pre_finders: List[SDC],
                                  with_source_finders: bool,
                                  specCalls: List[str]) -> Tuple[
            List[Tuple[Dict[str, InternalFunc], Dict[str, UnspecializedSourceFinder], Dict[str, str], SDC]], bool, bool, Path]:
        added_finders_to_sdc, finders_compilation_success, source_finders_gen_success = \
            self.instrument_auto_finders(
                build_arg_contract_file, i, sdc_pre_finders,
                path_for_compiler_collector_file, with_source_finders, specCalls)
        # successful or not, we backup current .certora_sources for either debuggability, or for availability
        # of sources.
        post_backup_dir = self.get_fresh_backupdir(Util.POST_AUTOFINDER_BACKUP_DIR)
        build_logger.debug(f"Backing up instrumented .certora_sources to {post_backup_dir}")
        Util.safe_copy_folder(Util.get_certora_sources_dir(), post_backup_dir, ignore_patterns)
        # we're rolling back anyway to make extra sure we won't get dirty files in the next compilation
        # (although this is guaranteed by the other call to build_source_tree),
        # and to keep .certora_sources pristine.
        # roll back .certora_sources by copying from backup directory
        build_logger.debug(f"Rolling back .certora_sources to {pre_backup_dir} version")
        shutil.copytree(pre_backup_dir, Util.get_certora_sources_dir(), dirs_exist_ok=True,
                        ignore=ignore_patterns)
        return added_finders_to_sdc, finders_compilation_success, source_finders_gen_success, post_backup_dir

    @staticmethod
    def get_cwd_rel_in_sources(sources: Set[Path]) -> Tuple[Path, Path]:
        """
        Updates the cwd_rel_in_sources field
        @returns a pair of cwd-relative-to-sources, and the common path of the sources
        """
        cwd = Path.cwd()
        # The common path is the directory that is a common ancestor of all source files used by the certoraRun script.
        # By getting the relative paths of all the sources the original directory structure can be copied to a new
        # location. In order to be able to rerun the certoraRun, also the current working directory should be mapped
        # that is why CWD is added to the list of sources
        common_path = Path(os.path.commonpath(list(sources.union({cwd}))))
        return cwd.relative_to(common_path), common_path

    @staticmethod
    def normalize_path(path: str) -> str:
        """
        get wrekt solidity:
        When you write `import "yeet.sol" the solidity compiler in its ast output claims that the
        absolute path of the import is... "yeet.sol" despite that clearly being a relative path.
        according to the official solidity documentation, the *actual* conversion to a relative path
        is handled by the virtual file system, which prepends base paths or the other include paths to
        the name, and then tries to find a file at that absolute address.
        so we do the same thing: we know we don't pass a different base path (or other include paths)
        so we use solidity's documented default: the working directory of the compiler, which is the
        working directory of this script
        :param path: the string of the path to normalize
        :return: the original path if it was absolute, or the path placed relative to the CWD and the resolved
        """
        p = Path(path)
        if p.is_absolute():
            return path
        return str(Path.cwd() / p.absolute())

    @staticmethod
    def merge_dicts_instrumentation(dict1: Dict[str, Dict[int, Instrumentation]],
                                    dict2: Dict[str, Dict[int, Instrumentation]]) -> Dict[
            str, Dict[int, Instrumentation]]:
        """
        Merges two nested dictionaries, ignoring duplicates from dict2.
        dict1 takes precedence over dict2.
        """
        result = dict1.copy()
        for key, inner_dict in dict2.items():
            if key not in result:
                result[key] = inner_dict
            else:
                # Only add new inner keys
                result[key].update({
                    k: v for k, v in inner_dict.items()
                    if k not in result[key]
                })
        return result

    def instrument_auto_finders(self, build_arg_contract_file: str, i: int,
                                sdc_pre_finders: List[SDC],
                                path_for_compiler_collector_file: str,
                                instrument_source_finders: bool,
                                specCalls: List[str]) -> Tuple[
            List[Tuple[Dict[str, InternalFunc], Dict[str, UnspecializedSourceFinder], Dict[str, str], SDC]], bool, bool]:

        # initialization
        ret: List[Tuple[Dict[str, InternalFunc], Dict[str, UnspecializedSourceFinder], Dict[str, str], SDC]] = []
        instrumentation_logger.debug(f"Instrumenting auto finders in {build_arg_contract_file}")
        # all of the [SDC]s inside [sdc_pre_finders] have the same list of [ContractInSDC]s
        # (generated in the [collect_from_file] function).
        sdc_pre_finder = sdc_pre_finders[0]
        added_function_finders_tuple = self.add_auto_finders(build_arg_contract_file, sdc_pre_finder)
        if added_function_finders_tuple is None:
            instrumentation_logger.warning(
                f"Computing function finder instrumentation failed for {build_arg_contract_file}")
            return [({}, {}, {}, old_sdc) for old_sdc in sdc_pre_finders], False, False

        (added_function_finders, function_instr) = added_function_finders_tuple

        instr = function_instr

        added_internal_function_harnesses: Dict[str, str] = {}
        if not self.context.disallow_internal_function_calls:
            added_internal_func_harness_tuple = self.add_internal_func_harnesses(build_arg_contract_file, sdc_pre_finder, specCalls)
            if added_internal_func_harness_tuple:
                instr = CertoraBuildGenerator.merge_dicts_instrumentation(function_instr, added_internal_func_harness_tuple[1])
                added_internal_function_harnesses = added_internal_func_harness_tuple[0]

        source_finders_gen_succeeded = False
        if instrument_source_finders:
            try:
                added_source_finders_tuple = add_source_finders(self.asts, build_arg_contract_file, sdc_pre_finder)

                (added_source_finders, source_instr) = added_source_finders_tuple
                # Update instr with additional instrumentations. Recall it is a map file -> offset -> instr.
                # Function finders take precedence
                instr = CertoraBuildGenerator.merge_dicts_instrumentation(instr, source_instr)
                source_finders_gen_succeeded = True
            except:  # noqa: E722
                instrumentation_logger.warning(
                    f"Computing source finder instrumentation failed for {build_arg_contract_file}")
                added_source_finders = {}
        else:
            added_source_finders = {}

        abs_build_arg_contract_file = Util.abs_posix_path(build_arg_contract_file)
        if abs_build_arg_contract_file not in instr:
            instrumentation_logger.debug(
                f"Adding {build_arg_contract_file} as {abs_build_arg_contract_file} to instrumentation")
            instr[abs_build_arg_contract_file] = dict()

        autofinder_remappings = {}  # type: Dict[str, str]

        for contract_file, instr_loc in instr.items():
            new_name = self.to_autofinder_file(contract_file)
            old_abs_path = Path(contract_file)
            new_abs_path = Path(new_name)

            if new_name in autofinder_remappings:
                # instrumentation should be keyed only using absolute paths
                instrumentation_logger.warning(f"Already generated autofinder for {new_name}, "
                                               f"cannot instrument again for {contract_file}")
                return [({}, {}, {}, old_sdc) for old_sdc in sdc_pre_finders], False, False

            autofinder_remappings[new_name] = contract_file

            instr_rewrites: List[Tuple[int, Instrumentation]] = list(instr_loc.items())
            instrumentation_logger.debug(
                f"Generating autofinder file for {new_name} based on {contract_file}, "
                f"has {len(instr_rewrites)} rewrites")
            ordered_rewrite = sorted(instr_rewrites, key=lambda it: it[0])

            with old_abs_path.open('rb') as in_file:
                with new_abs_path.open("wb+") as output:
                    read_so_far = 0
                    for byte_offs, to_insert in ordered_rewrite:
                        instrumentation_logger.debug(f"Next chunk: {byte_offs}, inserting {to_insert.to_ins}")
                        amt = byte_offs - read_so_far
                        next_chunk = in_file.read(amt)
                        old_pos = in_file.tell()
                        output.write(next_chunk)
                        next_byte = in_file.read(1)
                        if next_byte != to_insert.expected:
                            instrumentation_logger.warning(f"Failed to find {repr(to_insert.expected)} at offset"
                                                           f" {byte_offs} in {old_abs_path} (got {repr(next_byte)})")
                            instrumentation_logger.warning(f"Underlying file reports {in_file.tell()}"
                                                           f" (before read: {old_pos})")
                            if instrument_source_finders:
                                instrumentation_logger.warning("Skipping source finder generation!")
                            else:
                                instrumentation_logger.warning("Skipping internal function finder generation!")
                            return [({}, {}, {}, old_sdc) for old_sdc in sdc_pre_finders], False, False
                        to_skip = to_insert.mut.insert(to_insert.to_ins, to_insert.expected, output)
                        if to_skip != 0:
                            in_file.read(to_skip)
                        read_so_far += amt + 1 + to_skip
                    output.write(in_file.read(-1))

        new_file = self.to_autofinder_file(build_arg_contract_file)
        self.context.file_to_contract[new_file] = self.context.file_to_contract[
            build_arg_contract_file]

        # add generated file to map attributes
        for map_attr in self.context.app.attr_class.all_map_attrs():
            map_attr_value = getattr(self.context, map_attr)
            if map_attr_value and build_arg_contract_file in map_attr_value:
                map_attr_value[new_file] = map_attr_value[build_arg_contract_file]

        autofinder_remappings[new_file] = build_arg_contract_file
        # TODO: I think this file name gets passed on to kotlin?? Not sure if it'll ever want to open the
        #       file, or if it'll only get the .certora_config one??
        try:
            orig_file_name = Path(build_arg_contract_file)
            if instrument_source_finders:
                Util.print_progress_message(
                    f"Compiling {orig_file_name} to expose internal function information and local variables...")
            else:
                Util.print_progress_message(f"Compiling {orig_file_name} to expose internal function information...")
            # record what aliases we have created (for the purposes of type canonicalization, the generated autofinder
            # is an alias of the original file)
            for k, v in autofinder_remappings.items():
                self.function_finder_file_remappings[Util.abs_posix_path(k)] = Util.abs_posix_path(v)
            new_sdcs = self.collect_for_file(new_file, i, get_compiler_lang(build_arg_contract_file),
                                             Util.get_certora_sources_dir() / self.context.cwd_rel_in_sources,
                                             path_for_compiler_collector_file,
                                             sdc_pre_finder,
                                             fail_on_compilation_error=False,
                                             reroute_main_path=True)
            for new_sdc in new_sdcs:
                ret.append((added_function_finders, added_source_finders, added_internal_function_harnesses, new_sdc))

        except Util.SolcCompilationException as e:
            print(f"Encountered an exception generating autofinder {new_file} ({e}), falling back to original "
                  f"file {Path(build_arg_contract_file).name}")
            ast_logger.debug(f"Encountered an exception generating autofinder {new_file}, "
                             f"falling back to the original file {Path(build_arg_contract_file).name}", exc_info=e)
            # clean up mutation
            self.function_finder_file_remappings = {}
            return [({}, {}, {}, sdc_pre_finder) for sdc_pre_finder in sdc_pre_finders], False, False
        return ret, True, source_finders_gen_succeeded

    def to_autofinder_file(self, contract_file: str) -> str:
        """
        Autofinder files are generated in the same directory of the contract under .certora_sources.
        In times past, they had a different name. Not anymore! If autofinder generation failed, we just
        copy back the sources to the .certora_sources and overwrite the failed attempt.
        """
        # we do normalizations towards re-rooting the file in .certora_sources
        contract_path = Util.abs_posix_path_obj(contract_file)
        rel_directory = Path(os.path.relpath(contract_file, '.')).parent
        contract_filename = contract_path.name
        new_path = Util.get_certora_sources_dir() / self.context.cwd_rel_in_sources / rel_directory / contract_filename
        new_path.parent.mkdir(parents=True, exist_ok=True)
        return str(new_path)

    def abs_path_relative_to_certora_sources(self, path: str) -> str:
        """
        Used to remap allowed paths and package paths to their new location under .certora_sources.
        This assumes those paths can be related to cwd.
        """
        rel_to_cwd_path = Path(os.path.relpath(path, '.'))
        new_path = Util.get_certora_sources_dir() / self.context.cwd_rel_in_sources / rel_to_cwd_path
        return str(new_path.absolute())

    def handle_links(self) -> None:
        # Link processing
        if self.context.link is not None:
            links = self.context.link
            for link in links:
                src, dst = link.split("=", 2)
                src_contract, reference_to_replace_with_link = src.split(":", 2)
                sources_to_update = self.get_matching_sdc_names_from_SDCs(src_contract)
                if len(sources_to_update) > 1:
                    build_logger.fatal(
                        f"Not expecting to find multiple SDC matches {sources_to_update} for {src_contract}")
                if len(sources_to_update) == 0:
                    build_logger.fatal(f"No contract to link to with the name {src_contract}")
                source_to_update = sources_to_update[0]
                # Primary contract name should match here
                if self.has_sdc_name_from_SDCs_starting_with(dst):
                    example_dst = self.get_one_sdc_name_from_SDCs(dst)  # Enough to pick one
                    dst_address = self.SDCs[example_dst].primary_contract_address
                else:
                    if Util.is_hex(dst):
                        dst = Util.hex_str_to_cvt_compatible(dst)
                        # The jar doesn't accept numbers with 0x prefix
                    dst_address = dst  # Actually, just a number

                # Decide how to link
                matching_immutable = list({(c, x.varname) for c in self.SDCs[source_to_update].contracts for x in
                                           c.immutables
                                           if
                                           x.varname == reference_to_replace_with_link and c.name == src_contract})
                if len(matching_immutable) > 1:
                    fatal_error(
                        ast_logger,
                        f"Not expecting to find multiple immutables with the name {reference_to_replace_with_link}, "
                        f"got matches {matching_immutable}")

                """
                Three kinds of links, resolved in the following order:
                1. Immutables. We expect at most one pair of (src_contract, immutableVarName) that matches
                2. Field names. Allocated in the storage - we fetch their slot number. (TODO: OFFSET)
                3. Slot numbers in EVM. Requires knowledge about the Solidity compilation. (TODO: OFFSET)
                """
                build_logger.debug(f"Reference to replace with link: {reference_to_replace_with_link}")
                if len(matching_immutable) == 1 and reference_to_replace_with_link == matching_immutable[0][1]:
                    contract_match = matching_immutable[0][0]

                    def map_immut(immutable_reference: ImmutableReference) -> ImmutableReference:
                        if immutable_reference.varname == reference_to_replace_with_link:
                            return PresetImmutableReference(immutable_reference.offset, immutable_reference.length,
                                                            immutable_reference.varname, dst_address,
                                                            immutable_reference.type)
                        else:
                            return immutable_reference

                    contract_match.immutables = [map_immut(immutable_reference) for immutable_reference in
                                                 contract_match.immutables]

                    continue
                elif not reference_to_replace_with_link.isnumeric() and not Util.is_hex(reference_to_replace_with_link):
                    # We need to convert the string to a slot number
                    resolved_src_slot = self.resolve_slot(src_contract, reference_to_replace_with_link)
                else:
                    # numeric case
                    if Util.is_hex(reference_to_replace_with_link):
                        # if hex, need to remove the 0x
                        reference_to_replace_with_link = Util.hex_str_to_cvt_compatible(reference_to_replace_with_link)
                    else:
                        # need to convert the dec to hex
                        reference_to_replace_with_link = \
                            Util.decimal_str_to_cvt_compatible(reference_to_replace_with_link)
                    resolved_src_slot = reference_to_replace_with_link
                build_logger.debug(f"Linking slot {resolved_src_slot} of {src_contract} to {dst}")
                build_logger.debug(' '.join(k for k in self.SDCs.keys()))

                build_logger.debug(f"Linking {src_contract} ({source_to_update}) to {dst_address} "
                                   f"in slot {resolved_src_slot}")
                self.SDCs[source_to_update].state[resolved_src_slot] = dst_address

    def handle_contract_extensions(self) -> None:
        if not self.context.contract_extensions:
            return

        contracts_by_name = {c.name: c for c in self.get_primary_contracts_from_sdcs()}
        for extended, extending in self.context.contract_extensions.items():
            if extended not in contracts_by_name:
                raise Util.CertoraUserInputError(f"Can't find extended contract {extended} for contract extension")
            extended_contract = contracts_by_name[extended]
            for ext in extending:
                extension = ext["extension"]
                if extension not in contracts_by_name.keys():
                    raise Util.CertoraUserInputError(f"Can't find extending contract {extension}"
                                                     f"for contract extension")
                extension_contract = contracts_by_name[extension]
                for f in ext["exclude"]:
                    if not extension_contract.has_method_with_name(f):
                        raise Util.CertoraUserInputError(f"Can't find a method named {f} in contract "
                                                         f"{extension_contract.name}")
                extended_contract.add_extension(ContractExtension(extension_contract, ext["exclude"]))

    def check_if_immutable_is_defined_in_ast(self, reference_to_replace_with_link: str, src_contract: str) -> None:
        """
        @param reference_to_replace_with_link: The immutable we could not find in immutable_references
        @param src_contract: The contract in which we are looking for the immutable

        If there is an  AST declaration of the immutable in src_contract, will emit an error to the user explaining
        why the link is impossible. This is friendlier than saying that the name reference_to_replace_with_link
        is not defined as neither as a storage slot or as an immutable, as we show here it is declared, just unused.
        """
        appears_in_asts_as_immutable = False
        for name in self.asts.keys():
            if appears_in_asts_as_immutable:
                break
            contract_ast = self.asts[name]
            for path in contract_ast.keys():
                contract_file_ast = contract_ast[path]
                contract_def_refs = list(filter(
                    lambda node_id: (
                        contract_file_ast[node_id].get("nodeType") == "VariableDeclaration" and
                        contract_file_ast[node_id].get("certora_contract_name") == src_contract and
                        contract_file_ast[node_id].get("name") == reference_to_replace_with_link
                    ),
                    contract_file_ast))
                if len(contract_def_refs) > 0:
                    appears_in_asts_as_immutable = True
                    break
        if appears_in_asts_as_immutable:
            raise Util.CertoraUserInputError(
                f"Link to an immutable variable `{reference_to_replace_with_link}` that isn't"
                f" used in the contract {src_contract} is impossible, and likely meaningless."
            )

    def handle_struct_links(self) -> None:
        # struct link processing
        if self.context.struct_link is not None:
            build_logger.debug('handling struct linking')
            links = self.context.struct_link
            for link in links:
                src, dst = link.split("=", 2)
                src_contract, reference_to_replace_with_link = src.split(":", 2)
                sources_to_update = self.get_matching_sdc_names_from_SDCs(src_contract)
                if len(sources_to_update) > 1:
                    fatal_error(build_logger,
                                f"Not expecting to find multiple SDC matches {sources_to_update} for {src_contract}")
                source_to_update = sources_to_update[0]
                # Primary contract name should match here
                if self.has_sdc_name_from_SDCs_starting_with(dst):
                    example_dst = self.get_one_sdc_name_from_SDCs(dst)  # Enough to pick one
                    dst_address = self.SDCs[example_dst].primary_contract_address
                else:
                    dst_address = dst  # Actually, just a number

                build_logger.debug(f"STRUCT Reference to replace with link: {reference_to_replace_with_link}")

                if not reference_to_replace_with_link.isnumeric() and not Util.is_hex(reference_to_replace_with_link):
                    self.SDCs[source_to_update].structLinkingInfo[reference_to_replace_with_link] = dst_address
                else:
                    if Util.is_hex(reference_to_replace_with_link):
                        resolved_src_slot = Util.hex_str_to_cvt_compatible(reference_to_replace_with_link)
                    else:
                        resolved_src_slot = Util.decimal_str_to_cvt_compatible(reference_to_replace_with_link)
                    build_logger.debug(f"STRUCT Linking slot {resolved_src_slot} of {src_contract} to {dst}")
                    build_logger.debug(' '.join(k for k in self.SDCs.keys()))

                    build_logger.debug(f"STRUCT Linking {src_contract} ({source_to_update}) to {dst_address} in slot "
                                       f"{resolved_src_slot}")
                    self.SDCs[source_to_update].legacyStructLinking[resolved_src_slot] = dst_address

    def has_sdc_name_from_SDCs_starting_with(self, potential_contract_name: str) -> bool:
        candidates = self.get_matching_sdc_names_from_SDCs(potential_contract_name)
        return len(candidates) > 0

    def __get_matching_sdc_names_for_SDCs_iterator(self, contract: str) -> Iterator[str]:
        return (k for k, v in self.SDCs.items() if k.startswith(f"{contract}_"))

    def get_one_sdc_name_from_SDCs(self, contract: str) -> str:
        return next(self.__get_matching_sdc_names_for_SDCs_iterator(contract))

    def get_matching_sdc_names_from_SDCs(self, contract: str) -> List[str]:
        return list(self.__get_matching_sdc_names_for_SDCs_iterator(contract))

    def get_primary_contracts_from_sdcs(self) -> List[ContractInSDC]:
        ret = [v.find_contract_with_exception(v.primary_contract) for v in self.SDCs.values()]
        assert all(c is not None for c in ret), "failed to find some primary contracts"
        return ret

    class SlotResolution(Enum):
        SLOT_NO_STORAGE_LAYOUT = enum.auto()
        SLOT_INVALID_STORAGE_LAYOUT = enum.auto()
        SLOT_NOT_FOUND = enum.auto()
        SLOT_FOUND_MULTIPLE = enum.auto()
        SLOT_RESOLVED = enum.auto()

    @staticmethod
    def resolve_slot_from_storage_layout(primary_contract: str, slot_name: str,
                                         sdc: SDC) -> Tuple[SlotResolution, Optional[str], Optional[str]]:
        """
        @param primary_contract: Name of the contract
        @param slot_name: Name of the field we wish to associate with a slot number
        @param sdc: The object representing an invocation of solc where we hope to find storageLayout
        @return: A tuple: SlotResolution - enum depicting If there is a valid storage layout and a valid slot number
                          string - returns the slot number associated with slot_name as hex without preceding 0x (or 0X)
                          string - relevant slots found, in case more than 1 slot found.
        """
        storage_layouts = [c.storage_layout for c in sdc.contracts if
                           c.name == primary_contract and c.storage_layout is not None]
        if len(storage_layouts) != 1:
            build_logger.debug(f"Expected exactly one storage layout matching {primary_contract}, "
                               f"got {len(storage_layouts)}")
            return CertoraBuildGenerator.SlotResolution.SLOT_NO_STORAGE_LAYOUT, None, None

        storage_layout = storage_layouts[0]
        if storage_layout is None or "storage" not in storage_layout:
            build_logger.debug(f"Storage layout should be an object containing a 'storage'"
                               f" field, but got {storage_layout}")
            return CertoraBuildGenerator.SlotResolution.SLOT_INVALID_STORAGE_LAYOUT, None, None

        relevant_slots = [slot for slot in storage_layout["storage"] if "label" in slot and slot["label"] == slot_name]
        relevant_slots_set = {slot['slot'] for slot in relevant_slots}
        build_logger.debug(f"Found relevant slots in storage layout of {primary_contract}: {relevant_slots}")
        if not relevant_slots:
            return CertoraBuildGenerator.SlotResolution.SLOT_NOT_FOUND, None, None
        elif len(relevant_slots_set) == 1:
            slot_number = relevant_slots_set.pop()
            cvt_compatible = Util.decimal_str_to_cvt_compatible(slot_number)
            # slot_number from storage layout is already in decimal.
            return \
                CertoraBuildGenerator.SlotResolution.SLOT_RESOLVED, cvt_compatible, None
        else:
            return CertoraBuildGenerator.SlotResolution.SLOT_FOUND_MULTIPLE, None, str(relevant_slots)

    def resolve_slot(self, primary_contract: str, slot_name: str) -> str:
        """
        @param primary_contract: Name of the contract
        @param slot_name: Name of the field we wish to associate with a slot number
        @return: The resolved slot number as hex without preceding 0x (or 0X)
        """
        build_logger.debug(f"Resolving slots for {primary_contract} out of {self.SDCs.keys()}")
        sdc = self.SDCs[self.get_one_sdc_name_from_SDCs(primary_contract)]  # Enough to pick one

        slot_result, slot_number_from_storage_layout, relevant_slots = \
            self.resolve_slot_from_storage_layout(primary_contract, slot_name, sdc)

        def slot_not_found_error(slot_name: str) -> None:
            raise Util.CertoraUserInputError(f"Error in linkage: link {primary_contract}:{slot_name}, "
                                             f"variable {slot_name} does not exist in contract {primary_contract}")

        if slot_result == CertoraBuildGenerator.SlotResolution.SLOT_RESOLVED:
            return typing.cast(str, slot_number_from_storage_layout)
        elif slot_result == CertoraBuildGenerator.SlotResolution.SLOT_NOT_FOUND:
            # try to emit a proper message if it's an immutable
            self.check_if_immutable_is_defined_in_ast(slot_name, primary_contract)
            slot_not_found_error(slot_name)
        elif slot_result == CertoraBuildGenerator.SlotResolution.SLOT_FOUND_MULTIPLE:
            raise RuntimeError(f"Cannot link, found multiple matches for {slot_name} "
                               f"in storage layout of contract {primary_contract}: {relevant_slots}")

        build_logger.debug(
            f"Storage layout not available for contract {primary_contract}. "
            "Matching slots from ASM output instead"
        )

        file = sdc.sdc_origin_file
        file_of_primary_contract = self.input_config.contract_to_file[
            primary_contract]  # maybe its the same as [file]
        solc_ver_to_run = get_relevant_compiler(Path(file_of_primary_contract), self.context)

        asm_collect_cmd = f'{solc_ver_to_run} -o {Util.get_certora_config_dir()}/ --overwrite --asm ' \
                          f'--allow-paths "{Path.cwd() / self.context.solc_allow_path}" "{Util.abs_posix_path(file)}"'
        if self.context.packages is not None:
            asm_collect_cmd = f"{asm_collect_cmd} {' '.join(self.context.packages)}"

        Util.run_compiler_cmd(asm_collect_cmd, f"{primary_contract}.asm", Path.cwd())

        evm_file_path = Util.get_certora_config_dir() / f'{primary_contract}.evm'
        with evm_file_path.open() as asm_file:
            build_logger.debug(f"Got asm {asm_file}")
            saw_match = False
            candidate_slots = []
            for line in asm_file:
                if saw_match:
                    candidate_slots.append(line)
                    saw_match = False
                else:
                    regex = r'/\* "[a-zA-Z0-9./_\-: ]+":[0-9]+:[0-9]+\s* %s \*/' % (slot_name,)
                    saw_match = re.search(regex, line) is not None
                    if saw_match:
                        build_logger.debug(f"Saw match for {regex} on line {line}")
            build_logger.debug(f"Candidate slots: {candidate_slots}")
            normalized_candidate_slots = [x.strip() for x in candidate_slots]
            build_logger.debug(f"Candidate slots: {normalized_candidate_slots}")
            filtered_candidate_slots = [x for x in normalized_candidate_slots if re.search('^0[xX]', x)]
            set_candidate_slots = set(filtered_candidate_slots)
            build_logger.debug(f"Set of candidate slots: {set_candidate_slots}")
            if len(set_candidate_slots) == 1:
                # Auto detect base (should be 16 though thanks to 0x)
                slot_number = hex(int(list(set_candidate_slots)[0], 0))[2:]
                build_logger.debug(f"Got slot number {slot_number}")
            else:
                if len(set_candidate_slots) > 1:
                    msg = f"Cannot link, Found multiple matches for {slot_name}" \
                          f" in {primary_contract}, valid candidates: {set_candidate_slots}"
                    raise RuntimeError(msg)
                else:
                    slot_not_found_error(slot_name)

        return slot_number

    @staticmethod
    def get_manual_mutants_file(context: CertoraContext) -> Set[Path]:
        """
        Returns a set of mutant Solidity files based on the manual mutations specified in the context.
        These files are added to .certora_sources.

        certoraRun generally skips the mutations object therefore no exceptions are raised if mutant files
        cannot be read due to bad syntax or any other error

        :param context:
        :return: list of mutants file
        """

        def get_sol_files(path: Path) -> Set[Path]:
            sol_files: Set[Path] = set()

            if path.is_file() and path.suffix == SOL:
                sol_files.add(path)
            elif path.is_dir():
                for file_path in path.rglob(fr'*{SOL}'):
                    sol_files.add(file_path)
            return sol_files

        result: Set[Path] = set()
        try:
            manual_mutations = context.mutations[MANUAL_MUTANTS]
            for mutation in manual_mutations:
                try:
                    mutation_path = mutation[MUTANTS_LOCATION]
                except Exception:
                    continue
                result |= get_sol_files(Path(mutation_path))
        except Exception:
            pass
        return result

    # The sources that are collected for the .certora_sources directory are all the files that are provided as input
    # (i.e. they are not generated during the certora build process) that are needed for precise rerunning certoraRun.
    #
    # Including:
    #
    #   1) All contract files, including those in packages
    #   2) The package.json file for parsing dependencies
    #   3) All spec files, including imported specs
    #   4) bytecode files (spec and json)
    @staticmethod
    def collect_sources(context: CertoraContext, certora_verify_generator: CertoraVerifyGenerator,
                        sources_from_SDCs: Set[Path]) -> Set[Path]:
        def add_to_sources(path_to_file: Path) -> None:
            if path_to_file.exists():
                sources.add(Path(os.path.normpath(Path.cwd() / path_to_file)))
            else:
                raise Util.CertoraUserInputError(f"collect_sources: {path_to_file} does not exist cwd - {Path.cwd()}."
                                                 f"abs - {os.path.normpath(Path.cwd() / path_to_file)}")

        sources = set(sources_from_SDCs)
        if context.files:
            sources.update(Path(p) for p in context.files)  # all files in "files" attribute are uploaded
        sources |= certora_verify_generator.get_spec_files()
        if Util.PACKAGE_FILE.exists():
            add_to_sources(Util.PACKAGE_FILE)
        if Util.REMAPPINGS_FILE.exists():
            add_to_sources(Util.REMAPPINGS_FILE)
        foundry_toml = Util.find_nearest_foundry_toml()
        if foundry_toml:
            # if we find foundry.toml we add it to source tree and, if exists, the remappings.txt file from the
            # root directory
            foundry_root = foundry_toml.parent
            add_to_sources(foundry_root / Util.FOUNDRY_TOML_FILE)
            remappings_file_in_root = foundry_root / Util.REMAPPINGS_FILE
            if remappings_file_in_root.exists():
                add_to_sources(remappings_file_in_root)
        if context.bytecode_jsons:
            for file in context.bytecode_jsons:
                add_to_sources(Path(file))
        if context.bytecode_spec:
            sources.add(Path(context.bytecode_spec))
        if context.yul_abi:
            sources.add(Path(context.yul_abi))
        if context.override_base_config:
            sources.add(Path(context.override_base_config))

        if hasattr(context, Attrs.EvmProverAttributes.PROVER_RESOURCE_FILES.get_conf_key()) \
                and context.prover_resource_files:
            for value in context.prover_resource_files:
                _, file_path = value.split(':')
                add_to_sources(Path(file_path))

        # if certoraRun runs from conf file the sources in the conf file
        # will replace the conf file in context.files so we add the conf file separately
        if hasattr(context, CONF_FILE_ATTR):
            add_to_sources(Path(getattr(context, CONF_FILE_ATTR)))

        sources |= CertoraBuildGenerator.get_manual_mutants_file(context)
        return sources

    def __del__(self) -> None:
        try:
            self.cleanup()
        except ImportError:
            # Avoiding Python interpreter shutdown exceptions which are safe to ignore
            pass


# make sure each source file exists and its path is in absolute format
def sources_to_abs(sources: Set[Path]) -> Set[Path]:
    result = set()  # Set[Path]
    for p in sources:
        if p.exists():
            result.add(Path(os.path.normpath(p.absolute())))
    return result


def build_source_tree(sources: Set[Path], context: CertoraContext, overwrite: bool = False) -> None:
    """
    Copies files to .certora_sources
    """
    sources = sources_to_abs(sources)
    context.cwd_rel_in_sources, context.common_path = CertoraBuildGenerator.get_cwd_rel_in_sources(sources)

    for source_path in sources:
        is_dir = source_path.is_dir()
        # copy file to the path of the file from the common root under the sources directory

        # make sure directory exists
        target_path = Util.get_certora_sources_dir() / source_path.relative_to(context.common_path)
        target_directory = target_path if is_dir else target_path.parent
        try:
            target_directory.mkdir(parents=True, exist_ok=True)
        except OSError as e:
            build_logger.debug(f"Failed to create directory {target_directory}", exc_info=e)
            raise

        # copy files. if we got a directory, nothing to do
        if is_dir:
            build_logger.debug(f"Skipping directory {source_path}")
            continue

        try:
            if overwrite:
                # expecting the target path to exist.
                if target_path.exists():
                    build_logger.debug(f"Overwriting {target_path} by copying from {source_path}")
                else:
                    build_logger.warning(f"Supposed to overwrite {target_path} by copying from {source_path}" +
                                         " but it does not exist... this may indicate bad things happen")
            if overwrite or not target_path.exists():
                build_logger.debug(f"Copying {source_path} to {target_path}")
                shutil.copyfile(source_path, target_path)
        except OSError as e:
            build_logger.debug(f"Couldn't copy {source_path} to {target_path}", exc_info=e)
            raise

    #  the empty file .cwd is written in the source tree to denote the current working directory
    cwd_file_path = Util.get_certora_sources_dir() / context.cwd_rel_in_sources / Util.CWD_FILE
    cwd_file_path.parent.mkdir(parents=True, exist_ok=True)
    cwd_file_path.touch()

    #  the empty file .project_directory is written in the source tree to denote the project directory
    rust_proj_dir = getattr(context, 'rust_project_directory', None)
    if rust_proj_dir:
        proj_dir_parent_relative = os.path.relpath(rust_proj_dir, os.getcwd())
        assert Path(rust_proj_dir).is_dir(), f"build_source_tree: not a directory {rust_proj_dir}"
        proj_dir_parent_file = (Util.get_certora_sources_dir() / context.cwd_rel_in_sources / proj_dir_parent_relative /
                                Util.PROJECT_DIR_FILE)
        proj_dir_parent_file.parent.mkdir(parents=True, exist_ok=True)
        proj_dir_parent_file.touch()

    # Copy the repro conf file to the sources as the overall way to reproduce this run easily.
    # The file is being copied in here and not added through collect_sources, because we want
    # to avoid building the whole tree up to the common path for this file. We simply want to copy
    # the repro file from .certora_internal to .certora_sources so it will be uploaded as a run
    # resource.
    try:
        shutil.copy(Util.get_last_conf_file(), Util.get_certora_sources_dir() / Util.LAST_CONF_FILE)
    except OSError as e:
        build_logger.debug("Couldn't copy repro conf to certora sources.", exc_info=e)
        raise

def build_from_scratch(context: CertoraContext,
                       certora_build_generator: CertoraBuildGenerator,
                       certora_verify_generator: CertoraVerifyGenerator,
                       build_cache_enabled: bool) -> CachedFiles:
    """
    @returns `.certora_build.json` file, and a set of sources files
    """
    # Start to collect information from solc:
    # main side effect is populating certora_build_generator.SDCs
    certora_build_generator.build(certora_verify_generator)
    certora_build_generator.check_primary_contact_is_in_build(certora_verify_generator.verify_contract)

    # Output the SDCs to .certora_build.json
    certora_build_file = Util.get_certora_build_file()
    build_logger.debug(f"writing file {Util.abs_posix_path(certora_build_file)}")
    with certora_build_file.open("w+") as output_file:
        json.dump({k: v.as_dict() for k, v in certora_build_generator.SDCs.items()},
                  output_file,
                  indent=4,
                  sort_keys=True)

    all_contract_files = set()
    saw_paths_not_in_sources = False
    paths_not_in_sources = set()
    may_store_in_build_cache = True
    absolute_sources_dir = Util.get_certora_sources_dir().absolute()
    for sdc in certora_build_generator.SDCs.values():

        # add to cache also source files that were found in the SDCs (e.g., storage extensions)
        paths_set = sdc.all_contract_files
        for f in context.files:
            path = f.split(':')[0]  # `f` is either 'path/to/file.sol' or 'path/to/file.sol:ContractName'
            paths_set.add(Path(path).absolute())

        # the contract files in SDCs are relative to .certora_sources. Which isn't good for us here.
        # Need to be relative to original paths
        for f in paths_set:
            if f.is_relative_to(absolute_sources_dir):
                rel_f = f.relative_to(absolute_sources_dir)
            else:
                # may be an absolute path already outside .certora_sources, so we can keep it.
                # Can happen with dependencies
                # no need to fail because of it, but let's make sure the path does exist in .certora_sources
                if not (absolute_sources_dir / f).exists():
                    saw_paths_not_in_sources = True
                    paths_not_in_sources.add(f)
                rel_f = f
            all_contract_files.add(rel_f)

    all_contract_files = sources_to_abs(all_contract_files)
    if saw_paths_not_in_sources:
        if build_cache_enabled:
            build_cache_logger.warning("Saw some paths that were not rooted in a common directory, " +
                                       f"and not in known sources, thus disabling build cache: {paths_not_in_sources}")
        may_store_in_build_cache = False

    # write additional props file
    build_output_props_file = Util.get_built_output_props_file()
    build_logger.debug(f"writing file {Util.abs_posix_path(build_output_props_file)}")
    with build_output_props_file.open("w+") as output_file:
        json.dump({"auto_finders_failed": certora_build_generator.auto_finders_failed,
                   "source_finders_failed": certora_build_generator.source_finders_failed},
                  output_file,
                  indent=4,
                  sort_keys=True)

    return CachedFiles(certora_build_file, all_contract_files, build_output_props_file, may_store_in_build_cache,
                       path_with_additional_included_files=absolute_sources_dir)


def build_from_cache_or_scratch(context: CertoraContext,
                                certora_build_generator: CertoraBuildGenerator,
                                certora_verify_generator: CertoraVerifyGenerator) \
        -> Tuple[bool, bool, CachedFiles]:
    """
    Builds either from cache (fast path) or from scratch (slow path)
    @returns 1st tuple element whether there was a cache hit or not
    @returns 2nd tuple element whether the build cache is enabled and applicable
    @returns 3rd tuple element the artifacts of the build to potentially be cached
    """
    cache_hit = False
    cached_files: Optional[CachedFiles] = None

    if not context.build_cache:
        cached_files = build_from_scratch(context, certora_build_generator,
                                          certora_verify_generator,
                                          False)
        return cache_hit, False, cached_files

    build_cache_applicable = CertoraBuildCacheManager.cache_is_applicable(context)

    if not build_cache_applicable:
        build_cache_disabling_options = CertoraBuildCacheManager.cache_disabling_options(context)
        build_logger.warning("Requested to enable the build cache, but the build cache is not applicable "
                             f"to this run because of the given options: {build_cache_disabling_options}")
        cached_files = build_from_scratch(context, certora_build_generator,
                                          certora_verify_generator,
                                          False)
        return cache_hit, False, cached_files

    cached_files = CertoraBuildCacheManager.build_from_cache(context)
    # if no match, will rebuild from scratch
    if cached_files is not None:
        # write to .certora_build.json
        shutil.copyfile(cached_files.certora_build_file, Util.get_certora_build_file())
        # write build_output_props file
        shutil.copyfile(cached_files.build_output_props_file, Util.get_built_output_props_file())
        # write build_cache indicator file
        with open(Util.get_build_cache_indicator_file(), "w+") as indicator_handle:
            json.dump({"build_cache_hit": True}, indicator_handle)
        # write in sources all the additional paths found
        for p in cached_files.path_with_additional_included_files.glob("*"):
            if p.is_dir():
                Util.safe_copy_folder(p,
                                      Util.get_certora_sources_dir() / p.name,
                                      shutil.ignore_patterns())
            else:
                shutil.copyfile(p, Util.get_certora_sources_dir() / p.name)
        cache_hit = True
    else:
        # rebuild
        cached_files = build_from_scratch(context, certora_build_generator,
                                          certora_verify_generator,
                                          True)

    return cache_hit, True, cached_files


def build(context: CertoraContext, ignore_spec_syntax_check: bool = False) -> None:
    """
    This is the main function of certoraBuild
    @param context: A namespace including command line arguments. We expect the namespace to include validated arguments
    @param ignore_spec_syntax_check: If true, we skip checking the spec file for syntax errors.
           Otherwise, if syntax errors are found, we quit immediately
    @returns True if succeeded, False otherwise
    """

    try:
        input_config = InputConfig(context)
        context.main_cache_key = CertoraBuildCacheManager.get_main_cache_key(context)
        # Create generators
        certora_build_generator = CertoraBuildGenerator(input_config, context)

        # Build .certora_verify.json
        certora_verify_generator = CertoraVerifyGenerator(context)
        certora_verify_generator.dump()  # first dump

        # Start by syntax checking, if we're in the right mode
        if Cv.mode_has_spec_file(context) and not context.build_only and not ignore_spec_syntax_check:
            if Ctx.should_run_local_speck_check(context):
                Ctx.run_local_spec_check(False, context)
            else:
                build_logger.warning(
                    "Local checks of CVL specification files disabled. It is recommended to enable the checks.")

        cache_hit, build_cache_enabled, cached_files = build_from_cache_or_scratch(context,
                                                                                   certora_build_generator,
                                                                                   certora_verify_generator)

        # avoid running the same test over and over again for each split run, context.split_rules is true only for
        # the first run and is set to [] for split runs
        if Ctx.should_run_local_speck_check(context) and context.split_rules:
            Ctx.run_local_spec_check(True, context)

        # .certora_verify.json is always constructed even if build cache is enabled
        # Sources construction should only happen when rebuilding
        # Build sources tree
        sources = certora_build_generator.collect_sources(context, certora_verify_generator,
                                                          cached_files.all_contract_files)
        try:
            # Copies files, not updating state
            build_source_tree(sources, context)
        except Exception as e:
            build_logger.debug("build_source_tree failed", exc_info=e)

        # save in build cache
        if not cache_hit and build_cache_enabled and cached_files.may_store_in_build_cache:
            CertoraBuildCacheManager.save_build_cache(context, cached_files)

        certora_verify_generator.update_certora_verify_struct(True)
        certora_verify_generator.dump()  # second dump with properly rooted specs

        # in autofinder assertion mode, we want to hard-fail.
        with cached_files.build_output_props_file.open() as build_output_props_handle:
            build_output_props = json.load(build_output_props_handle)
            auto_finders_failed = "auto_finders_failed" in build_output_props and \
                                  build_output_props["auto_finders_failed"]
            if auto_finders_failed and context.assert_autofinder_success:
                raise Exception("Failed to create autofinders, failing")
            source_finders_failed = "source_finders_failed" in build_output_props and \
                                    build_output_props["source_finders_failed"]
            if source_finders_failed and context.assert_source_finders_success:
                raise Exception("Failed to generate source finders, failing")

    except Exception as e:
        build_logger.debug("build failed")
        raise e


def print_package_file_note() -> None:
    if not Util.REMAPPINGS_FILE.exists() and not Util.PACKAGE_FILE.exists():
        print('\n\n**********\n'
              'The Solidity compiler failed to locate a package.\n'
              'Note that we could not find any of the package mapping files package.json (for npm) or remappings.txt '
              '(for Foundry)\n'
              '**********\n\n')
