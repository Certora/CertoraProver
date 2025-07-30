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

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Any, List, Set, Optional, BinaryIO

from CertoraProver import certoraType as CT
from CertoraProver.Compiler.CompilerCollector import CompilerCollector
from CertoraProver.Compiler.CompilerCollectorSol import CompilerLangSol
from CertoraProver.Compiler.CompilerCollectorVy import CompilerLangVy
from CertoraProver.certoraCompilerParameters import CompilerParameters
from CertoraProver.certoraContractFuncs import Func, InternalFunc, SourceBytes
from Shared import certoraUtils as Util


# logger for instrumentation for the function finder
instrumentation_logger = logging.getLogger("finder_instrumentation")

CONTRACTS = "contracts"


class ImmutableReference:
    def __init__(self, offset: str, length: str, varname: str, type: CT.TypeInstance):
        self.offset = offset
        self.length = length
        self.varname = varname
        self.type = type

    def as_dict(self) -> Dict[str, Any]:
        return {
            "offset": self.offset,
            "length": self.length,
            "varname": self.varname,
            "type": self.type.as_dict()
        }

    def __repr__(self) -> str:
        return repr(self.as_dict())


@dataclass
class ContractExtension:
    extension: "ContractInSDC"
    exclusion: List[str]

    def as_dict(self) -> Dict[str, Any]:
        return {
            "extension": self.extension.as_dict(),
            "exclusion": self.exclusion
        }


class SourceLoc:
    def __init__(self, source: int, begin: int, src_len: int):
        self.source = source
        self.begin = begin
        self.src_len = src_len

    def as_dict(self) -> Dict[str, Any]:
        return {
            "source": self.source,
            "begin": self.begin,
            "len": self.src_len
        }


class UnspecializedSourceFinder:
    def __init__(self, sourceLoc: SourceLoc):
        self.sourceLoc = sourceLoc
        self.finderType = "UnspecializedSourceFinder"

    def as_dict(self) -> Dict[str, Any]:
        return {
            "sourceLoc": self.sourceLoc.as_dict()
        }


class ContractInSDC:
    def __init__(self, name: str, source_file: str, lang: str,
                 report_source_file: str, address: str,
                 is_static_address: bool,
                 methods: Set[Func], bytecode: str,
                 constructor_bytecode: str,
                 srcmap: str, varmap: Any, constructor_srcmap: str,
                 storage_layout: Any, transient_storage_layout: Any,
                 immutables: List[ImmutableReference],
                 function_finders: Dict[str, InternalFunc], internal_funcs: Set[Func], public_funcs: Set[Func],
                 all_funcs: List[Func],
                 types: List[CT.Type],
                 compiler_collector: Optional[CompilerCollector],
                 source_bytes: Optional[SourceBytes],
                 compiler_parameters: Optional[CompilerParameters],
                 extension_contracts: List[ContractExtension],
                 local_assignments: Dict[str, UnspecializedSourceFinder],
                 branches: Dict[str, UnspecializedSourceFinder],
                 requires: Dict[str, UnspecializedSourceFinder],
                 internal_starts: List[int]
                 ):
        self.name = name
        self.original_file = source_file
        self.lang = lang
        self.report_source_file = report_source_file
        self.address = address
        self.is_static_address = is_static_address
        self.methods = methods
        self.bytecode = bytecode
        self.constructor_bytecode = constructor_bytecode
        self.srcmap = srcmap
        self.varmap = varmap
        self.constructorSrcmap = constructor_srcmap
        self.storage_layout = storage_layout
        self.transient_storage_layout = transient_storage_layout
        self.immutables = immutables
        # function finder: a mapping from external function ids to
        # the ids of the internal functions they are "finding" for us
        self.function_finders = function_finders
        # the internal functions of the contract NOT including imported library functions
        self.internal_funcs = internal_funcs
        # the public functions of the contract (this EXCLUDES external functions)
        self.public_funcs = public_funcs
        # all function types floating around (unique by internal signature), any AST location/ids from
        # these should not be used
        self.all_funcs = all_funcs
        self.types = types
        self.source_bytes = source_bytes
        self.original_file_name = Path(source_file).name
        self.compiler_collector = compiler_collector
        self.compiler_parameters = compiler_parameters
        self.internal_starts = internal_starts

        if not self.compiler_collector:
            compiler_version = ""
        elif self.compiler_collector.smart_contract_lang == CompilerLangVy():
            if isinstance(self.compiler_collector.compiler_version, tuple):
                compiler_version = '.'.join(str(e) for e in self.compiler_collector.compiler_version)
            else:
                # deal with it when we see it.
                compiler_version = "unknown"
        elif self.compiler_collector.smart_contract_lang == CompilerLangSol():
            # this is for Solidity
            if isinstance(self.compiler_collector.compiler_version, tuple):
                compiler_version = '.'.join(str(e) for e in self.compiler_collector.compiler_version)
            else:
                compiler_version = "unknown"
        else:
            compiler_version = "unknown"
        self.compiler_version = compiler_version
        self.extension_contracts = extension_contracts
        self.local_assignments = local_assignments
        self.branches = branches
        self.requires = requires
        self.internal_function_harnesses: Dict[str, str] = {}

    def as_dict(self) -> Dict[str, Any]:
        """
        :return: A dictionary representation of this SDC, including all attributes and their values
        """
        return {
            "name": self.name,
            "original_file": Util.find_filename_in(Util.get_certora_sources_dir(), self.original_file),
            "lang": self.lang,
            "file": self.report_source_file,
            "address": self.address,
            "is_static_address": self.is_static_address,
            "methods": list(map(lambda x: x.as_dict(), self.methods)),
            "bytecode": self.bytecode,
            "constructorBytecode": self.constructor_bytecode,
            "srcmap": self.srcmap,
            "varmap": self.varmap,
            "constructorSrcmap": self.constructorSrcmap,
            "storageLayout": self.storage_layout,
            "transientStorageLayout": self.transient_storage_layout,
            "immutables": list(map(lambda x: x.as_dict(), self.immutables)),
            # the function finder field
            # why are we using this and not the normal list of functions?
            "internalFunctions": {key: method.as_dict() for key, method in self.function_finders.items()},
            # this doesn't even have all functions
            "allMethods": [f.as_dict() for f in self.all_funcs],
            "solidityTypes": [x.as_dict() for x in self.types],
            "compilerName": "" if not self.compiler_collector else self.compiler_collector.compiler_name,
            "compilerVersion": self.compiler_version,
            "compilerParameters": None if not self.compiler_parameters else self.compiler_parameters.as_dict(),
            "sourceBytes": None if self.source_bytes is None else self.source_bytes.as_dict(),
            "extensionContracts": [e.as_dict() for e in self.extension_contracts],
            "localAssignments": {k: v.as_dict() for k, v in self.local_assignments.items()},
            "internalFunctionStarts": self.internal_starts,
            "internalFunctionHarnesses": self.internal_function_harnesses
        }
        # "sourceHints": {"localAssignments": {k: v.as_dict() for k, v in self.local_assignments.items()},
        #                 "branches": {k: v.as_dict() for k, v in self.branches.items()},
        #                 "requires": {k: v.as_dict() for k, v in self.requires.items()}}

    def as_printable_dict(self) -> Dict[str, Any]:
        """
        :return: A dictionary representation of this SDC meant for printing to logs.
        It does not include long, hard-to-read attributes: bytecodes and srcmaps of the contract and the constructor.
        """
        return {
            "name": self.name,
            "original_file": self.original_file,
            "lang": self.lang,
            "file": self.report_source_file,
            "address": self.address,
            "methods": list(map(lambda x: x.as_dict(), self.methods)),
            "storageLayout": self.storage_layout,
            "transientStorageLayout": self.transient_storage_layout,
            "immutables": list(map(lambda x: x.as_dict(), self.immutables)),
            "internalFunctions": {k: method.as_dict() for k, method in self.function_finders.items()},
            "localAssignments": {k: v.as_dict() for k, v in self.local_assignments.items()}
        }

    def find_method(self, name: str, fullArgs: List[CT.TypeInstance]) -> Optional[Func]:
        """
        attempts to find and return a matching method. from Solidity documentation,
        the combination of the method name and (ordered) parameter types
        is sufficient to disambiguate method overloading.
        """
        for method in self.methods:
            if method.name == name:
                if Util.eq_by(CT.TypeInstance.matches, method.fullArgs, fullArgs):
                    return method
        return None

    def has_method_with_name(self, name: str) -> bool:
        for method in self.methods:
            if method.name == name:
                return True
        return False

    def __repr__(self) -> str:
        return repr(self.as_printable_dict())

    def add_extension(self, extension: ContractExtension) -> None:
        self.extension_contracts.append(extension)


class SDC:
    """
    'Single Deployed Contracts' the solidity file whose contracts comprise a single bytecode of interest
    """

    def __init__(self, primary_contract: str, compiler_collector: CompilerCollector, primary_contract_address: str,
                 sdc_origin_file: str, original_srclist: Dict[Any, Any], report_srclist: Dict[Any, Any], sdc_name: str,
                 contracts: List[ContractInSDC], library_addresses: List[str],
                 state: Dict[str, str], struct_linking_info: Dict[str, str], legacy_struct_linking: Dict[str, str],
                 all_contract_files: Set[Path]):
        self.primary_contract = primary_contract
        self.primary_contract_address = primary_contract_address
        self.sdc_origin_file = sdc_origin_file
        self.original_srclist = original_srclist
        self.report_srclist = report_srclist
        self.sdc_name = sdc_name
        self.contracts = contracts
        self.library_addresses = library_addresses
        self.state = state
        self.structLinkingInfo = struct_linking_info
        self.legacyStructLinking = legacy_struct_linking
        self.prototypes = []  # type: List[str]
        self.compiler_collector = compiler_collector
        # The source dir relative to which we resolve srclists
        self.source_dir = "."
        self.orig_source_dir = "."
        # all contract files for this SDC (does not need to be serialized, used for collect_sources)
        self.all_contract_files: Set[Path] = all_contract_files

    def as_dict(self) -> Dict[str, Any]:
        return {
            "primary_contract": self.primary_contract,
            "primary_contract_address": self.primary_contract_address,
            "srclist": self.report_srclist,
            "sdc_name": self.sdc_name,
            CONTRACTS: list(map(lambda x: x.as_dict(), self.contracts)),
            "library_addresses": self.library_addresses,
            "state": self.state,
            "structLinkingInfo": self.structLinkingInfo,
            "legacyStructLinking": self.legacyStructLinking,
            "prototypeFor": self.prototypes,
            "sourceDir": self.source_dir,
            "origSourceDir": self.orig_source_dir
        }

    @staticmethod
    def sources_as_absolute(original_srclist: Dict[Any, Any]) -> Set[Path]:
        return set(map(lambda x: Path(x).absolute(), original_srclist.values()))

    def find_contract(self, name: str) -> Optional[ContractInSDC]:
        for contract in self.contracts:
            if contract.name == name:
                return contract
        return None

    def find_contract_with_exception(self, name: str) -> ContractInSDC:
        res = self.find_contract(name)
        if res:
            return res
        else:
            raise RuntimeError(f"find_contract_with_exception: cannot find contract {str}")


class MutationType(object):
    def insert(self, what: str, expected: bytes, file: BinaryIO) -> int:
        raise NotImplementedError("Did not implement insertion")


class Instrumentation:
    def __init__(self, expected: bytes, mut: MutationType, to_ins: str) -> None:
        self.expected = expected
        self.mut = mut
        self.to_ins = to_ins


class InsertBefore(MutationType):
    def __init__(self) -> None:
        pass

    def insert(self, what: str, expected: bytes, file: BinaryIO) -> int:
        file.write(bytes(what, "utf-8"))
        file.write(expected)
        return 0


class InsertAfter(MutationType):
    def __init__(self) -> None:
        pass

    def insert(self, what: str, expected: bytes, file: BinaryIO) -> int:
        file.write(expected)
        file.write(bytes(what, "utf-8"))
        return 0


class Replace(MutationType):
    def __init__(self, amt: int) -> None:
        self.to_delete = amt

    def insert(self, what: str, expected: bytes, file: BinaryIO) -> int:
        to_read = self.to_delete - len(expected)
        file.write(bytes(what, "utf-8"))
        return to_read
