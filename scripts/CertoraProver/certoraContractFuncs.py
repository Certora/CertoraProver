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

from typing import List, Optional, Tuple, Dict, Any
from CertoraProver import certoraType as CT
from Shared import certoraUtils as Util

STATEMUT = "stateMutability"


class SourceBytes:
    """
    the start byte and the size in bytes of an element, in the source code where it is defined
    note this will always contain the data of the original source code, even for autofinders
    """
    def __init__(self, begin: int, size: int):
        self.begin = begin
        self.size = size

    def as_dict(self) -> Dict[str, int]:
        return {
            "begin": self.begin,
            "size": self.size,
        }

    @staticmethod
    def from_ast_node(node: Dict[str, Any]) -> Optional['SourceBytes']:
        try:
            src = node["src"]
            begin, size = src.split(":")[:2]
            begin, size = int(begin), int(size)
            return SourceBytes(begin, size)

        except (KeyError, ValueError):
            return None


class Func:
    def __init__(self,
                 name: str,
                 fullArgs: List[CT.TypeInstance],
                 paramNames: List[str],
                 returns: List[CT.TypeInstance],
                 sighash: str,
                 notpayable: bool,
                 fromLib: bool,  # actually serialized
                 isConstructor: bool,  # not serialized
                 is_free_func: bool,
                 stateMutability: str,
                 visibility: str,
                 implemented: bool,  # does this function have a body? (false for interface functions)
                 overrides: bool,  # does this function override an interface declaration or super-contract definition?
                 virtual: bool,
                 contractName: str,
                 source_bytes: Optional[SourceBytes],
                 ast_id: Optional[int],
                 original_file: Optional[str],
                 location: Optional[str],
                 body_location: Optional[str],
                 ):
        self.name = name
        self.fullArgs = fullArgs
        self.paramNames = paramNames
        self.returns = returns
        self.sighash = sighash
        self.notpayable = notpayable
        self.fromLib = fromLib
        self.isConstructor = isConstructor
        self.is_free_func = is_free_func
        self.stateMutability = stateMutability
        self.visibility = visibility
        self.original_file = original_file
        self.location = location
        self.body_location = body_location
        self.implemented = implemented
        self.ast_id = ast_id
        self.overrides = overrides
        self.virtual = virtual
        self.contractName = contractName
        self.source_bytes = source_bytes

    def as_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "paramNames": self.paramNames,
            "fullArgs": list(map(lambda x: x.as_dict(), self.fullArgs)),
            "returns": list(map(lambda x: x.as_dict(), self.returns)),
            "sighash": self.sighash,
            "notpayable": self.notpayable,
            STATEMUT: self.stateMutability,
            "visibility": self.visibility,
            "isLibrary": self.fromLib,
            "contractName": self.contractName,
            "sourceBytes": None if self.source_bytes is None else self.source_bytes.as_dict(),
            "originalFile": None if self.original_file is None else Util.resolve_original_file(self.original_file),
        }

    def name_for_contract(self, contract_name: str) -> str:
        return f"{contract_name}.{self.name}"

    def __repr__(self) -> str:
        return repr(self.as_dict())

    def __lt__(self, other: Any) -> bool:
        return self.source_code_signature() < other.source_code_signature()

    def __eq__(self, other: Any) -> bool:
        if not isinstance(other, Func):
            # don't attempt to compare against unrelated types
            return NotImplemented

        return self.source_code_signature() == other.source_code_signature() and self.signature() == other.signature()

    def __hash__(self) -> int:
        return int(self.sighash, 16)

    def signature(self) -> str:
        """
        Returns the ABI-conforming version of the signature
        """
        return Func.compute_signature(self.name, self.fullArgs, lambda x: x.get_abi_canonical_string(self.fromLib))

    def signature_for_contract(self, contract_name: str) -> str:
        return f"{contract_name}.{self.signature()}"

    def source_code_signature(self) -> str:
        """
        Returns the signature of the function in non-ABI format; The argument types are not
        encoded to the ABI version (e.g. an argument of type 'struct S' will show the struct
        name, not the destructered tuple the ABI uses)
        """
        return Func.compute_signature(self.name, self.fullArgs, lambda x: x.get_source_str())

    def where(self) -> Optional[Tuple[str, str]]:
        if self.original_file and self.body_location:
            return self.original_file, self.body_location
        else:
            return None

    @staticmethod
    def compute_signature(name: str, args: List[CT.TypeInstance], signature_getter: Any) -> str:
        return name + "(" + ",".join([signature_getter(x) for x in args]) + ")"

    def same_internal_signature_as(self, other: 'Func') -> bool:
        if self.name != other.name:
            return False
        args_match = (len(self.fullArgs) == len(other.fullArgs) and
                      all([my_arg.matches(other_arg) for my_arg, other_arg in zip(self.fullArgs, other.fullArgs)]))

        rets_match = (len(self.returns) == len(other.returns) and
                      all([my_ret.matches(other_ret) for my_ret, other_ret in zip(self.returns, other.returns)]))
        return args_match and rets_match

    @staticmethod
    def compute_getter_signature(variable_type: CT.TypeInstance) -> Tuple[List[CT.TypeInstance], List[CT.TypeInstance]]:
        return variable_type.compute_getter_signature()


class InternalFunc:
    def __init__(self, fileName: str, contractName: str, func: Func):
        self.canonical_id = f"{fileName}|{contractName}"
        self.declaringContract = contractName
        self.func = func

    def as_dict(self) -> Dict[str, Any]:
        return {
            "canonicalId": self.canonical_id,
            "declaringContract": self.declaringContract,
            "method": self.func.as_dict()
        }
