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

import itertools
import json
import logging
import operator
import re
import subprocess
from abc import ABC, abstractmethod
from pathlib import Path
from typing import Any, Dict, List, Set, Tuple, Optional

import CertoraProver.certoraType as CT
from Crypto.Hash import keccak
from CertoraProver.Compiler.CompilerCollector import CompilerCollector, CompilerLang, CompilerLangFunc
from Shared.certoraUtils import Singleton, DEFAULT_VYPER_COMPILER, CompilerVersion
from Shared.certoraUtils import print_failed_to_run, get_certora_config_dir

ast_logger = logging.getLogger("ast")


class CompilerLangVy(CompilerLang, metaclass=Singleton):
    """
    [CompilerLang] for Vyper.
    """

    @property
    def name(self) -> str:
        return DEFAULT_VYPER_COMPILER.capitalize()  # yes, Vyper wants to be spelled "Vyper" in the json input

    @property
    def compiler_name(self) -> str:
        return DEFAULT_VYPER_COMPILER

    @staticmethod
    def normalize_func_hash(func_hash: str) -> str:
        try:
            return hex(int(func_hash, 16))
        except ValueError:
            raise Exception(f'{func_hash} is not convertible to hexadecimal')

    @staticmethod
    def normalize_file_compiler_path_name(file_abs_path: str) -> str:
        if not file_abs_path.startswith('/'):
            return '/' + file_abs_path
        return file_abs_path

    @staticmethod
    def normalize_deployed_bytecode(deployed_bytecode: str) -> str:
        assert deployed_bytecode.startswith("0x"), f'expected {deployed_bytecode} to have hexadecimal prefix'
        return deployed_bytecode[2:]

    @staticmethod
    def get_contract_def_node_ref(contract_file_ast: Dict[int, Any], contract_file: str, contract_name: str) -> \
            int:
        # in vyper, "ContractDefinition" is "Module"
        denormalized_contract_file = contract_file[1:] if contract_file.startswith('/') else contract_file
        contract_def_refs = list(filter(
            lambda node_id: contract_file_ast[node_id].get("ast_type") == "Module" and
            (contract_file_ast[node_id].get("name") == contract_file, contract_file_ast) or
            contract_file_ast[node_id].get("name") == denormalized_contract_file, contract_file_ast))
        assert len(contract_def_refs) != 0, \
            f'Failed to find a "Module" ast node id for the file {contract_file}'
        assert len(contract_def_refs) == 1, f'Found multiple "Module" ast node ids for the same file' \
            f'{contract_file}: {contract_def_refs}'
        return contract_def_refs[0]

    @staticmethod
    def compilation_output_path(sdc_name: str) -> Path:
        return get_certora_config_dir() / f"{sdc_name}"

    # Todo - add this for Vyper too and make it a CompilerLang class method one day
    @staticmethod
    def compilation_error_path(sdc_name: str) -> Path:
        return get_certora_config_dir() / f"{sdc_name}.standard.json.stderr"

    @staticmethod
    def all_compilation_artifacts(sdc_name: str) -> Set[Path]:
        """
        Returns the set of paths for all files generated after compilation.
        """
        return {CompilerLangVy.compilation_output_path(sdc_name),
                CompilerLangVy.compilation_error_path(sdc_name)}

    @staticmethod
    def pad(x: int) -> int:
        return (x + 31) & ~31

    class VyperType(ABC):
        uniqueId: int = 0

        @classmethod
        def get_unique_id(cls) -> int:
            r = cls.uniqueId
            cls.uniqueId += 1
            return r

        @abstractmethod
        def size_in_bytes(self, storage: bool = False) -> int:
            pass

        @abstractmethod
        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            """Returns a JSON-style object containing the fields of a [bridge.SolcType] (in kotlin)"""
            pass

        @abstractmethod
        def get_canonical_vyper_name(self) -> str:
            pass

        @abstractmethod
        def get_used_types(self) -> List[Any]:
            pass

        def resolve_forward_declared_types(self, resolution_dict: Dict[str, Any]) -> Any:
            """
            update [self] by (recursively) dereferencing all [VyperTypeNameReference]s using [resolution_dict]
            :return: [self], dereferenced if necessary.
            """
            return self

        @abstractmethod
        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            pass

        @abstractmethod
        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            """Returns a JSON-style object containing the fields of [bridge.SourceTypeDescription] (in kotlin)"""
            pass

    class VyperTypeNameReference(VyperType):
        def __init__(self, name: str):
            self.name = name

        def size_in_bytes(self, storage: bool = False) -> int:
            raise NotImplementedError

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            raise NotImplementedError

        def get_canonical_vyper_name(self) -> str:
            return self.name

        def get_used_types(self) -> List[Any]:
            raise NotImplementedError

        def resolve_forward_declared_types(self, resolution_dict: Dict[str, Any]) -> Any:
            if self.name in resolution_dict:
                return resolution_dict[self.name]
            raise ValueError(f"Unknown type: {self.name}")

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            raise AssertionError("can't generate_ct_type for a forward name reference")

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            raise AssertionError("can't get_storage_type_descriptor for a forward name reference")

    class VyperTypeStaticArray(VyperType):
        def __init__(self, element_type: Any, max_num_elements: int):
            self.element_type = element_type
            self.max_num_elements = max_num_elements

        def size_in_bytes(self, storage: bool = False) -> int:
            element_size = self.element_type.size_in_bytes(storage)
            # bytes _are_ actually packed in storage arrays
            if storage and self.element_type != CompilerLangVy.primitive_types['byte']:
                element_size = CompilerLangVy.pad(element_size)
                return element_size * self.max_num_elements
            else:
                return CompilerLangVy.pad(element_size * self.max_num_elements)

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            return {
                'label': self.get_canonical_vyper_name(),
                'encoding': 'inplace',
                'base': self.element_type.get_canonical_vyper_name(),
                'numberOfBytes': str(self.size_in_bytes(storage))
            }

        def get_canonical_vyper_name(self) -> str:
            return f'{self.element_type.get_canonical_vyper_name()}[{self.max_num_elements}]'

        def resolve_forward_declared_types(self, resolution_dict: Dict[str, Any]) -> Any:
            self.element_type = self.element_type.resolve_forward_declared_types(resolution_dict)
            return self

        def get_used_types(self) -> List[Any]:
            return [self] + self.element_type.get_used_types()

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            return CT.ArrayType(self.element_type.get_canonical_vyper_name(),
                                self.element_type.get_certora_type(contract_name, ref),
                                self.max_num_elements,
                                contract_name, ref)

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            return {
                "type": "StaticArray",
                "staticArrayBaseType": self.element_type.get_storage_type_descriptor(),
                "staticArraySize": f"{self.max_num_elements}",
            }

    class VyperTypeEnum(VyperType):
        def __init__(self, name: str, members: List[str]):
            self.name    = name
            self.members = members

        def size_in_bytes(self, storage: bool = False) -> int:
            return 1

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            return {
                'label': self.get_canonical_vyper_name(),
                'encoding': 'inplace',
                'numberOfBytes': str(self.size_in_bytes(storage))
            }

        def get_canonical_vyper_name(self) -> str:
            return self.name

        def get_used_types(self) -> List[Any]:
            return [self]

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            return CT.EnumType(self.name, f"enum {self.name}", self.name, self.members, contract_name, ref, file=None)

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            return {
                'type':        'UserDefinedEnum',
                'enumName':    self.name,
                'enumMembers': [{'name': m} for m in self.members],
                'canonicalId': self.name,
                'astId':       0,
                'containingContract': None
            }

    class VyperTypeStruct(VyperType):
        def __init__(self, name: str, members: List[Tuple[str, Any]]):
            self.name = name
            self.members = members

        def size_in_bytes(self, storage: bool = False) -> int:
            def mem_size(f: Tuple[str, Any]) -> int:
                sz = f[1].size_in_bytes(storage)
                if storage:
                    sz = CompilerLangVy.pad(sz)
                return sz
            return sum([mem_size(f) for f in self.members])

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            bytes_so_far_rounded_up = 0
            slots = {}
            for n, t in self.members:
                slots.update({n: bytes_so_far_rounded_up // 32})
                bytes_so_far_rounded_up += CompilerLangVy.pad(t.size_in_bytes(storage))
            members_field = [
                {
                    'label': n,
                    'slot': str(slots[n]),
                    'offset': 0,
                    'type': t.get_canonical_vyper_name()
                }
                for (n, t) in self.members]
            return {
                'label': self.get_canonical_vyper_name(),
                'encoding': 'inplace',
                'members': members_field,
                'numberOfBytes': str(self.size_in_bytes(storage))
            }

        def get_canonical_vyper_name(self) -> str:
            return self.name

        def resolve_forward_declared_types(self, resolution_dict: Dict[str, Any]) -> Any:
            self.members = [(n, t.resolve_forward_declared_types(resolution_dict)) for n, t in self.members]
            return self

        def get_used_types(self) -> List[Any]:
            return [self] + list(itertools.chain.from_iterable([t.get_used_types() for _, t in self.members]))

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            members = \
                [CT.StructType.StructMember(x[0], x[1].get_certora_type(contract_name, ref)) for x in self.members]
            return CT.StructType(self.name, "struct " + self.name, self.name, members, contract_name, ref, None)

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            return {
                "type": "UserDefinedStruct",
                "structName": self.name,
                "structMembers": [{"name": x[0], "type": x[1].get_storage_type_descriptor()} for x in self.members],
                "containingContract": None,
                "astId": 0,
                "canonicalId": str(0)
            }

    class VyperTypeDynArray(VyperTypeStruct):
        def __init__(self, element_type: Any, max_num_elements: int):
            self.element_type = element_type
            self.max_num_elements = max_num_elements

            self.count_type = CompilerLangVy.VyperTypeBoundedInteger(
                CompilerLangVy.primitive_types['uint256'], 0, max_num_elements)
            self.array_type = CompilerLangVy.VyperTypeStaticArray(element_type, int(max_num_elements))
            super().__init__(self.get_canonical_vyper_name(), [('count', self.count_type), ('data', self.array_type)])

        def get_canonical_vyper_name(self) -> str:
            return f"DynArray[{self.element_type.get_canonical_vyper_name()}, {self.max_num_elements}]"

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            element_type = self.element_type.get_certora_type(contract_name, ref)
            return CT.ArrayType(self.name, element_type, None, contract_name, ref)

        def resolve_forward_declared_types(self, resolution_dict: Dict[str, Any]) -> Any:
            super().resolve_forward_declared_types(resolution_dict)
            self.element_type = self.element_type.resolve_forward_declared_types(resolution_dict)
            return self

    class VyperTypeString(VyperTypeDynArray):
        def __init__(self, max_num_elements: int):
            super().__init__(CompilerLangVy.primitive_types['byte'], max_num_elements)

        def get_canonical_vyper_name(self) -> str:
            return 'String[' + str(self.array_type.max_num_elements) + ']'

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            return CT.StringType()

    class VyperTypeBytes(VyperTypeDynArray):
        def __init__(self, max_num_elements: int):
            super().__init__(CompilerLangVy.primitive_types['byte'], max_num_elements)

        def get_canonical_vyper_name(self) -> str:
            return 'Bytes[' + str(self.array_type.max_num_elements) + ']'

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            return CT.PackedBytes()

    class VyperTypeHashMap(VyperType):
        def __init__(self, key_type: Any, value_type: Any):
            self.key_type = key_type
            self.value_type = value_type

        def size_in_bytes(self, storage: bool = False) -> int:
            return 32

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            return {
                'label': self.get_canonical_vyper_name(),
                'encoding': 'mapping',
                'key': self.key_type.get_canonical_vyper_name(),
                'value': self.value_type.get_canonical_vyper_name(),
                'numberOfBytes': '32'
            }

        def get_canonical_vyper_name(self) -> str:
            return 'HashMap[' + self.key_type.get_canonical_vyper_name() + ', ' + \
                self.value_type.get_canonical_vyper_name() + ']'

        def resolve_forward_declared_types(self, resolution_dict: Dict[str, Any]) -> Any:
            self.key_type = self.key_type.resolve_forward_declared_types(resolution_dict)
            self.value_type = self.value_type.resolve_forward_declared_types(resolution_dict)
            return self

        def get_used_types(self) -> List[Any]:
            return [self] + self.key_type.get_used_types() + self.value_type.get_used_types()

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            in_type = self.key_type.get_certora_type(contract_name, ref)
            out_type = self.value_type.get_certora_type(contract_name, ref)
            return CT.MappingType(out_type.type_string, in_type, out_type, contract_name, ref)

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            return {
                "type": "Mapping",
                "mappingKeyType": self.key_type.get_storage_type_descriptor(),
                "mappingValueType": self.value_type.get_storage_type_descriptor()
            }

    class VyperTypeContract(VyperType):
        def __init__(self, name: str):
            self.name = name

        def size_in_bytes(self, storage: bool = False) -> int:
            return 20

        def get_canonical_vyper_name(self) -> str:
            return self.name

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            return {
                'label': "contract " + self.get_canonical_vyper_name(),
                'encoding': 'inplace',
                'numberOfBytes': str(self.size_in_bytes(storage))
            }

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            return CT.ContractType(self.name, ref)

        def get_used_types(self) -> List[Any]:
            return [self]

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            return {"contractName": self.name, "type": "Contract"}

    class VyperTypePrimitive(VyperType):
        def __init__(self, name: str, size: int):
            self.name = name
            self.size = size

        def size_in_bytes(self, storage: bool = False) -> int:
            return self.size

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            return {
                'label': self.get_canonical_vyper_name(),
                'encoding': 'inplace',
                'numberOfBytes': str(self.size_in_bytes(storage))
            }

        def get_canonical_vyper_name(self) -> str:
            return self.name

        def get_used_types(self) -> List[Any]:
            return [self]

        def get_certora_type(self, contract_name: str, ref: int) -> CT.Type:
            if self.name not in CT.PrimitiveType.allowed_primitive_type_names:
                return CT.PrimitiveType('uint256', 'uint256')
            else:
                return CT.PrimitiveType(self.name, self.name)

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            # hack of hacks. no spaces in our primitive enums -> put an underscore
            return {"primitiveName": self.get_canonical_vyper_name().replace(" ", "_"),
                    "type": "Primitive"}

    class VyperTypePrimitiveAlias(VyperTypePrimitive):
        def __init__(self, basetype: Any, name: str):
            super().__init__(name, basetype.size)
            self.basetype = basetype

        def get_storage_type_descriptor(self) -> Dict[str, Any]:
            return {
                "type": "UserDefinedValueType",
                "valueTypeName": self.name,
                "containingContract": None,
                "valueTypeAliasedName": self.basetype.get_storage_type_descriptor(),
                "astId": 0,
                "canonicalId": str(0)
            }

    class VyperTypeBoundedInteger(VyperTypePrimitiveAlias):
        def __init__(self, basetype: Any, lower_bound: int, upper_bound: int):
            super().__init__(basetype, f'{basetype.get_canonical_vyper_name()}_bounded_{lower_bound}_{upper_bound}')
            self.lower_bound = lower_bound
            self.upper_bound = upper_bound

        def generate_types_field(self, storage: bool = False) -> Dict[str, Any]:
            return {
                'label': self.get_canonical_vyper_name(),
                'encoding': 'inplace',
                'numberOfBytes': str(self.size_in_bytes(storage)),
                'lowerBound': str(self.lower_bound),
                'upperBound': str(self.upper_bound)
            }

    # this absolutely bizarre pattern is to work around the fact that
    # python does not make the types declared in this class (particularly VyperTypePrimitive)
    # within the scope of this generator (the dictionary comprehension)
    # However! you can work around this by binding it using a default parameter
    # because python is a good, well-designed language.
    # See: https://stackoverflow.com/questions/35790692/
    # It's a good thing  we only have one line between declarations, otherwise this code might be unreadable! /s
    # nb this does **not** work if you make this a staticmethod
    # noinspection PyMethodParameters
    def build_sequence(fmt, sz, mk=VyperTypePrimitive):  # type: ignore
        # noinspection PyTypeChecker,PyCallingNonCallable
        return {fmt(i): mk(fmt(i), sz(i)) for i in range(1, 33)}  # type: ignore

    primitive_types = {
        **{
            'address': VyperTypePrimitive('address', 32),
            'bool': VyperTypePrimitive('bool', 1),
            'byte': VyperTypePrimitive('byte', 1),
            'decimal': VyperTypePrimitive('decimal', 32),
            'nonreentrant lock': VyperTypePrimitive('nonreentrant lock', 32)
        },

        **build_sequence(lambda i: f"int{i * 8}", lambda i: i),  # type: ignore
        **build_sequence(lambda i: f"uint{i * 8}", lambda i: i),  # type: ignore
        **build_sequence(lambda i: f"bytes{i}", lambda i: 32 + i)  # type: ignore
    }

    vyper_constructor_name = "__init__"

    @property
    def supports_typed_immutables(self) -> bool:
        return True

    @property
    def supports_ast_output(self) -> bool:
        return True

    @property
    def supports_srclist_output(self) -> bool:
        return True

    @staticmethod
    def extract_constant(ast_node: Dict[str, Any], named_constants: Dict[str, int]) -> int:
        """
        Attempts to compute the constant in [ast_node]. It may evaluate an expression of constant terms.
        It is done on a best-effort basis and recursively.
        """
        # TODO: there is some duplication between this and the [Collector] class below
        if ast_node['ast_type'] == 'Int':
            return ast_node['value']
        elif ast_node['ast_type'] == 'Name' and 'id' in ast_node and ast_node['id'] in named_constants:
            return named_constants[ast_node['id']]
        elif ast_node['ast_type'] == 'BinOp' and 'op' in ast_node:
            op = ast_node['op']
            op_name = op.get('ast_type')
            supported_functions = {
                'Div': operator.floordiv,
                'Sub': operator.sub,
                'Pow': operator.pow,
                'Mult': operator.mul,
                'Add': operator.add,
                'Mod': operator.mod
            }

            if op_name in supported_functions:
                func = supported_functions[op_name]
                left = CompilerLangVy.extract_constant(ast_node['left'], named_constants)
                right = CompilerLangVy.extract_constant(ast_node['right'], named_constants)
                return func(left, right)
            else:
                raise Exception(f"Unexpected op_name {op_name}, cannot evaluate constant")

            """
            omitted (based on https://github.com/vyperlang/vyper/blob/master/vyper/ast/nodes.py):
            USub, Not, Invert, BitAnd, BitOr, BitXor, LShift, RShift, BoolOp
            """
        else:
            raise Exception(f"Unexpected ast_node {ast_node}, cannot evaluate constant")

    @staticmethod
    def subscript_node(ast_node: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        ty = ast_node.get("ast_type")
        # pre-vyper 0.4.0, look deeper within
        if ty == "Index":
            nested = ast_node.get("value")
            if nested is None or not isinstance(nested, dict):
                return None
            return nested
        else:
            return ast_node

    @staticmethod
    def int_subscript(ast_node: Dict[str, Any], named_constants: Dict[str, int]) -> Optional[int]:
        node = CompilerLangVy.subscript_node(ast_node)
        if node is None:
            return None

        if "id" in node and node["id"] in named_constants:
            return named_constants[node["id"]]
        elif node.get("ast_type") == "Int":
            return node.get("value")
        else:
            return None

    @staticmethod
    def get_tuple_elements(ast_node: Dict[str, Any]) -> List[Dict[str, Any]]:
        node = CompilerLangVy.subscript_node(ast_node)
        if node is None or node.get("ast_type") != "Tuple":
            raise Exception("Couldn't get tuple elements from ast node")
        return node["elements"]

    @staticmethod
    def extract_type_from_subscript_node(ast_subscript_node: Dict[str, Any],
                                         named_constants: Dict[str, int]) -> VyperType:
        value_id = ast_subscript_node['value'].get('id', None)
        if value_id == 'String':
            max_bytes = CompilerLangVy.int_subscript(ast_subscript_node['slice'], named_constants)
            if max_bytes is None:
                raise Exception("Failed to find max length for string type declaration")
            return CompilerLangVy.VyperTypeString(max_bytes)
        elif value_id == 'Bytes':
            max_bytes = CompilerLangVy.int_subscript(ast_subscript_node['slice'], named_constants)
            if max_bytes is None:
                raise Exception("Failed to find max length for bytes type declaration")
            return CompilerLangVy.VyperTypeBytes(max_bytes)
        elif value_id == 'DynArray':
            tup_elems = CompilerLangVy.get_tuple_elements(ast_subscript_node["slice"])
            elem_type = CompilerLangVy.extract_type_from_type_annotation_node(tup_elems[0], named_constants)
            max_elements = CompilerLangVy.extract_constant(tup_elems[1], named_constants)
            return CompilerLangVy.VyperTypeDynArray(elem_type, max_elements)
        elif value_id == 'HashMap':
            elements_node = CompilerLangVy.get_tuple_elements(ast_subscript_node['slice'])
            key_type = CompilerLangVy.extract_type_from_type_annotation_node(elements_node[0], named_constants)
            value_type = CompilerLangVy.extract_type_from_type_annotation_node(elements_node[1], named_constants)
            return CompilerLangVy.VyperTypeHashMap(key_type, value_type)
        else:  # StaticArray key_type[size]
            key_type = CompilerLangVy.extract_type_from_type_annotation_node(ast_subscript_node['value'],
                                                                             named_constants)
            max_elem_value = CompilerLangVy.int_subscript(ast_subscript_node['slice'], named_constants)
            if max_elem_value is not None:
                return CompilerLangVy.VyperTypeStaticArray(key_type, max_elem_value)
            else:
                # this is very specific to curve code which has uint256[CONST/2] static array declaration.
                max_elements_node = CompilerLangVy.subscript_node(ast_subscript_node["slice"])
                if max_elements_node is None:
                    raise Exception("Couldn't find subscript node for array type")
                if 'ast_type' in max_elements_node:
                    if max_elements_node['ast_type'] == 'BinOp' or max_elements_node['ast_type'] in ('Int', 'Name'):
                        # good chance this will succeed
                        static_array_len = CompilerLangVy.extract_constant(max_elements_node, named_constants)
                        return CompilerLangVy.VyperTypeStaticArray(key_type, static_array_len)
                raise Exception(
                    f"Don't know how to deal with vyper static array declaration with length {max_elements_node}")

    @staticmethod
    def extract_type_from_type_annotation_node(ast_type_annotation: Dict[str, Any],
                                               named_constants: Dict[str, int]) -> VyperType:
        if ast_type_annotation['ast_type'] == 'Subscript':
            return CompilerLangVy.extract_type_from_subscript_node(ast_type_annotation, named_constants)
        elif ast_type_annotation['id'] in CompilerLangVy.primitive_types:
            return CompilerLangVy.primitive_types[ast_type_annotation['id']]
        elif 'value' in ast_type_annotation:
            value_id = ast_type_annotation['value']['id']
            return CompilerLangVy.VyperTypeNameReference(value_id)
        else:
            return CompilerLangVy.VyperTypeNameReference(ast_type_annotation['id'])

    @staticmethod
    def extract_type_from_variable_decl(ast_vardecl_node: Dict[str, Any],
                                        named_constants: Dict[str, int]) -> VyperType:
        return CompilerLangVy.extract_type_from_type_annotation_node(ast_vardecl_node['annotation'], named_constants)

    @staticmethod
    def extract_type_from_struct_def(ast_structdef_node: Dict[str, Any],
                                     named_constants: Dict[str, int]) -> VyperType:
        fields = [(n['target']['id'], CompilerLangVy.extract_type_from_type_annotation_node(n['annotation'],
                                                                                            named_constants))
                  for n in ast_structdef_node['body']]
        return CompilerLangVy.VyperTypeStruct(ast_structdef_node['name'], fields)

    @staticmethod
    def extract_type_from_enum_def(ast_enumdef_node: Dict[str, Any]) -> VyperType:
        fields = [n['target']['id'] if "target" in n else n["value"]["id"] for n in ast_enumdef_node['body']]
        return CompilerLangVy.VyperTypeEnum(ast_enumdef_node['name'], fields)

    @staticmethod
    def get_function_selector(f_name: str, input_types: List[CT.TypeInstance], is_lib: bool) -> str:
        """
        Return the abi function selector computed from f_name, input_types, and is_lib
        f_name : the name of the function
        input_types : the argument types
        is_lib : is the function
        """

        argnames  = [arg.get_abi_canonical_string(is_lib) for arg in input_types]
        signature = f_name + "(" + ",".join(argnames) + ")"

        f_hash = keccak.new(digest_bits=256)
        f_hash.update(str.encode(signature))

        return f_hash.hexdigest()[0:8]

    @staticmethod
    def extract_func_from_func_def(function_def: Dict[str, Any],
                                   contract_name: str,
                                   named_constants: Dict[str, int],
                                   types: Dict[str, VyperType]) -> List[CompilerLangFunc]:
        """
        Return [CompilerLangFunction]s describing the function given by the 'FunctionDef' vyper AST node; there may be
        multiples because Vyper generates extra external functions to allow args with default values
        """

        # collect function argument names and types
        args = []  # type: List[Tuple[str, CT.TypeInstance]] # (name, type)
        for arg in function_def['args']['args']:
            vyper_type = CompilerLangVy.extract_type_from_type_annotation_node(arg['annotation'], named_constants)
            vyper_type = vyper_type.resolve_forward_declared_types(types)
            # Note: Vyper only supports "memory", so location is "default":
            args.append((arg['arg'], CT.TypeInstance(vyper_type.get_certora_type(contract_name, 0))))

        optional_args = len(function_def['args']['defaults'])
        arg_variants  = [args[:n] for n in range(len(args) - optional_args, len(args) + 1)]

        # collect return types
        if not function_def['returns']:
            declared_returns = []
        elif function_def['returns']['ast_type'] == 'Tuple':
            declared_returns = function_def['returns']['elements']
        else:
            declared_returns = [function_def['returns']]

        ret_types = []  # type: List[CT.TypeInstance]
        for ret in declared_returns:
            ret_type = CompilerLangVy.extract_type_from_type_annotation_node(ret, named_constants)
            ret_type = ret_type.resolve_forward_declared_types(types)
            ret_types.append(CT.TypeInstance(ret_type.get_certora_type(contract_name, 0)))

        # collect annotations (e.g. @external)
        decorators = [dec['id'] for dec in function_def['decorator_list'] if 'id' in dec]  # ignores Call decorators

        result = []
        is_constructor = function_def['name'] == CompilerLangVy.vyper_constructor_name
        for args in arg_variants:
            arg_names, arg_types = [n for n, t in args], [t for n, t in args]  # python's not smart enough to type `zip`
            if not is_constructor:
                func_selector = CompilerLangVy.get_function_selector(function_def['name'], arg_types, is_lib=False)
            else:
                func_selector = "0"  # this is the sighash for constructors in Solidity too
            result.append(CompilerLangFunc(
                name            = function_def['name'],
                fullArgs        = arg_types,
                paramNames      = arg_names,
                returns         = ret_types,
                sighash         = func_selector,
                notpayable      = "payable" not in decorators,
                fromLib         = False,
                isConstructor   = is_constructor,
                stateMutability = first_of(decorators, ["pure", "view", "payable"], default="nonpayable"),
                implemented     = True,
                overrides       = False,
                visibility      = first_of(decorators, ["internal", "external"]),
                source_bytes    = None,
                ast_id          = function_def['node_id'],
                contractName    = contract_name,
                original_file   = None,
                body_location   = None,
            ))
        return result

    @staticmethod
    def extract_func_from_public_var(contract_name: str, var_name: str, var_type: VyperType) -> CompilerLangFunc:
        """
        :return: A CompilerLangFunc representing the getter defined by the (public) variable [var_def]
        """
        certora_type_instance = CT.TypeInstance(var_type.get_certora_type(contract_name, 0))
        arg_types, ret_types = certora_type_instance.compute_getter_signature()

        return CompilerLangFunc(
            name            = var_name,
            fullArgs        = arg_types,
            paramNames      = [],
            returns         = ret_types,
            sighash         = CompilerLangVy.get_function_selector(var_name, arg_types, is_lib=False),
            notpayable      = True,
            fromLib         = False,
            isConstructor   = False,
            stateMutability = "view",
            implemented     = True,
            overrides       = False,
            visibility      = "external",
            source_bytes    = None,
            ast_id          = None,
            contractName    = contract_name,
            original_file   = None,
            body_location   = None,
        )

    @staticmethod
    def resolve_extracted_types(extracted_types: List[VyperType]) -> List[VyperType]:
        """
        Removes all [VyperTypeNameReference]s in [extracted_types] by replacing them with references to the actual types
        :return: the updated list of types (not including references)
        """
        real_types = [t for t in extracted_types if not isinstance(t, CompilerLangVy.VyperTypeNameReference)]
        name_resolution_dict = {t.get_canonical_vyper_name(): t for t in real_types}
        return [t.resolve_forward_declared_types(name_resolution_dict) for t in real_types]

    @staticmethod
    def extract_ast_types_and_public_vardecls(ast_body_nodes: Dict[int, Dict[str, Any]]) -> \
            Tuple[List[VyperType], Dict[str, VyperType]]:
        """
        :param ast_body_nodes:
        :return: (types, vars) where `types` is a list of all user-defined types, and `vars` maps public variables to
          their output types.  Note that `types` has been fully resolved - all `VyperTypeNameReference` nodes have been
          dereferenced
        """
        def resolve_vardecl_types(
                vardecls: Dict[str, CompilerLangVy.VyperType],
                resolved_types: List[CompilerLangVy.VyperType]) -> Dict[str, CompilerLangVy.VyperType]:
            name_resolution_dict = {t.get_canonical_vyper_name(): t for t in resolved_types}
            return {x: vardecls[x].resolve_forward_declared_types(name_resolution_dict) for x in vardecls}

        result_types = []
        public_vardecls = {}
        named_constants: Dict[str, int] = {}

        # Process named constants ahead of time, as their use site in the source may precede
        # their definition site, e.g.
        for ast_node in ast_body_nodes.values():
            if ast_node['ast_type'] != 'VariableDecl':
                continue
            if ast_node['is_constant'] and ast_node['value'] is not None and \
               (ast_node['value']['ast_type'] == 'Int'):
                named_constants.update({ast_node['target']['id']: int(ast_node['value']['value'])})

        for ast_node in ast_body_nodes.values():
            if ast_node['ast_type'] == 'VariableDecl':
                decltype = CompilerLangVy.extract_type_from_variable_decl(ast_node, named_constants)
                result_types.append(decltype)
                if ast_node['is_public']:
                    public_vardecls[ast_node['target']['id']] = decltype
            elif ast_node['ast_type'] == 'StructDef':
                result_types.append(CompilerLangVy.extract_type_from_struct_def(ast_node, named_constants))
            # Not sure if `Import` is an actual ast type. It was already there, so I am not removing it.
            # I only fixed the implementation of this case to what I think it should be.
            elif ast_node['ast_type'] == 'Import':
                result_types.append(CompilerLangVy.VyperTypeContract(ast_node['name']))
            elif ast_node['ast_type'] == 'ImportFrom':
                result_types.append(CompilerLangVy.VyperTypeContract(ast_node['name']))
            elif ast_node['ast_type'] == 'InterfaceDef':
                result_types.append(CompilerLangVy.VyperTypeContract(ast_node['name']))
        resolved_result_types = CompilerLangVy.resolve_extracted_types(result_types)
        return resolved_result_types, resolve_vardecl_types(public_vardecls, resolved_result_types)

    @staticmethod
    def collect_storage_layout_info(file_abs_path: str,
                                    config_path: Path,
                                    compiler_cmd: str,
                                    compiler_version: Optional[CompilerVersion],
                                    data: Dict[str, Any]) -> Dict[str, Any]:
        # only Vyper versions 0.2.16 and up have the storage layout
        if compiler_version is None or not CompilerCollectorVy.supports_storage_layout(compiler_version):
            return data
        storage_layout_output_file_name = f'{get_certora_config_dir()}.storage.layout'
        storage_layout_stdout_name = storage_layout_output_file_name + '.stdout'
        storage_layout_stderr_name = storage_layout_output_file_name + '.stderr'
        args = [compiler_cmd, '-f', 'layout', '-o', storage_layout_output_file_name, file_abs_path]
        with Path(storage_layout_stdout_name).open('w+') as stdout:
            with Path(storage_layout_stderr_name).open('w+') as stderr:
                try:
                    ast_logger.debug(f"Running {' '.join(args)}")
                    subprocess.run(args, stdout=stdout, stderr=stderr)
                    # if the file does not exist, bail out
                    if not Path(storage_layout_output_file_name).exists():
                        ast_logger.warn(f"Could not find storage layout file {storage_layout_output_file_name}, "
                                        "giving up on storage layout info")
                        return data
                    with Path(storage_layout_output_file_name).open('r') as output_file:
                        storage_layout_dict = json.load(output_file)
                        # normalize this "declaration object" nonsense.
                        # https://github.com/vyperlang/vyper/blob/344fd8f36c7f0cf1e34fd06ec30f34f6c487f340/vyper/
                        # semantics/types/user.py#L555
                        # and also another piece of nonsense. from 0.2.16 until 0.3.6,
                        # we didn't have the 'storage_layout' key.
                        if 'storage_layout' in storage_layout_dict:
                            storage_layout_dict = storage_layout_dict['storage_layout']

                        for entry in storage_layout_dict.items():
                            if 'type' in entry[1] and " declaration object" in entry[1]['type']:
                                entry[1]['type'] = entry[1]['type'].replace(" declaration object", "")

                except Exception as e:
                    print(f'Error: {e}')
                    print_failed_to_run(compiler_cmd)
                    raise
        ast_output_file_name = f'{get_certora_config_dir()}.ast'
        ast_stdout_name = storage_layout_output_file_name + '.stdout'
        ast_stderr_name = storage_layout_output_file_name + '.stderr'
        args = [compiler_cmd, '-f', 'ast', '-o', ast_output_file_name, file_abs_path]
        with Path(ast_stdout_name).open('w+') as stdout:
            with Path(ast_stderr_name).open('w+') as stderr:
                try:
                    subprocess.run(args, stdout=stdout, stderr=stderr)
                    with Path(ast_output_file_name).open('r') as output_file:
                        ast_dict = json.load(output_file)
                except Exception as e:
                    print(f'Error: {e}')
                    print_failed_to_run(compiler_cmd)
                    raise

        # Depressing how many bugs old Vyper had. Example:
        # vyper 0.3.7: "userBalances": {"type": "HashMap[address, uint256]", "slot": 1}
        # vyper 0.3.0: "userBalances": {"type": "HashMap[address, uint256][address, uint256]",
        #                               "location": "storage", "slot": 2}
        # so we'll just gracefully exit
        try:

            extracted_types, _ = CompilerLangVy.extract_ast_types_and_public_vardecls(
                {x['node_id']: x for x in ast_dict['ast']['body']}
            )
            all_used_types = list(itertools.chain.from_iterable([e.get_used_types() for e in extracted_types])) + \
                list(CompilerLangVy.primitive_types.values())
            type_descriptors_by_name = {i.get_canonical_vyper_name(): i.get_storage_type_descriptor()
                                        for i in all_used_types}
            types_field = {i.get_canonical_vyper_name(): i.generate_types_field(storage=True) for i in all_used_types}

            def annotate_desc(desc: Dict[Any, Any], type_name: str, all_types: Dict[Any, Any], slot: Any = None,
                              offset: Any = None) -> Dict[Any, Any]:
                evm_type = all_types[type_name]
                annotation = CT.StorageAnnotation(evm_type["numberOfBytes"], slot, offset,
                                                  evm_type.get("lowerBound"), evm_type.get("upperBound"))
                desc["annotations"] = [annotation.as_dict()]

                # annotate descriptor recursively
                if evm_type.get("members") is not None:  # struct
                    for desc_member in desc["structMembers"]:
                        for struct_member in evm_type["members"]:
                            if desc_member["name"] == struct_member["label"]:
                                desc_member["type"] = annotate_desc(desc_member["type"],
                                                                    struct_member["type"], all_types,
                                                                    struct_member["slot"], struct_member["offset"])
                elif evm_type.get("key") is not None and evm_type.get("value") is not None:  # mapping
                    desc["mappingKeyType"] = annotate_desc(desc["mappingKeyType"], evm_type["key"], all_types)
                    desc["mappingValueType"] = annotate_desc(desc["mappingValueType"], evm_type["value"], all_types)
                elif evm_type.get("base") is not None:
                    if evm_type["encoding"] == "inplace":  # static array
                        desc["staticArrayBaseType"] = annotate_desc(desc["staticArrayBaseType"],
                                                                    evm_type["base"], all_types)
                    else:  # dynamic array
                        desc["DynamicArrayBaseType"] = annotate_desc(desc["DynamicArrayBaseType"],
                                                                     evm_type["base"], all_types)

                return desc

            storage_field = [{
                'label': v,
                'slot': str(storage_layout_dict[v]['slot']),
                'offset': 0,
                'type': storage_layout_dict[v]['type'],
                'descriptor': annotate_desc(type_descriptors_by_name[storage_layout_dict[v]['type']],
                                            storage_layout_dict[v]['type'], types_field)
            } for v in storage_layout_dict.keys()]

            contract_name = list(data['contracts'][file_abs_path].keys())[0]
            data['contracts'][file_abs_path][contract_name]['storageLayout'] = {
                'storage': storage_field,
                'types': types_field,
                'storageHashArgsReversed': True
            }
            data['contracts'][file_abs_path][contract_name]['storageHashArgsReversed'] = True
            return data
        except Exception as e:
            ast_logger.warning(f'Failed to get storage layout, continuing: {e}')
            return data

    @staticmethod
    def get_supports_imports() -> bool:
        return False

    # revived from the dead. why? old vyper versions didn't include getters in their AST.
    # credit: Ben Greenwald
    @staticmethod
    def legacy_collect_source_type_descriptions_and_funcs(asts: Dict[str, Dict[str, Dict[int, Any]]],
                                                          data: Dict[str, Any],
                                                          contract_file: str,
                                                          contract_name: str,
                                                          build_arg_contract_file: str) -> Tuple[List[CT.Type], List[CompilerLangFunc]]:  # noqa: E501
        parsed_types = {}  # type: Dict[str, CT.Type]

        def get_abi_type_by_name(type_name: str) -> CT.Type:
            if type_name == "bytes":
                return CT.PackedBytes()
            elif type_name == "string":
                return CT.StringType()
            elif type_name in CT.PrimitiveType.allowed_primitive_type_names:
                return CT.PrimitiveType(type_name, type_name)
            elif type_name in parsed_types:
                return parsed_types[type_name]
            else:
                ast_logger.fatal(f"unexpected AST Type Name Node: {type_name}")
                assert False, "get_type_by_name failed to resolve type name"

        def collect_funcs(getter_vars: Dict[str, CT.MappingType]) -> List[CompilerLangFunc]:
            def collect_array_type_from_abi_rec(type_str: str, dims: List[Optional[int]]) -> str:
                outer_dim = re.findall(r"\[\d*]$", type_str)
                if outer_dim:
                    type_rstrip_dim = re.sub(r"\[\d*]$", '', type_str)
                    if len(outer_dim[0]) == 2:
                        dims.append(None)  # dynamic array
                    else:
                        assert len(outer_dim[0]) > 2, f"Expected to find a fixed-size array, but found {type_str}"
                        dims.append(int(re.findall(r"\d+", outer_dim[0])[0]))
                    return collect_array_type_from_abi_rec(type_rstrip_dim, dims)
                return type_str

            # Returns (list of array dimensions' lengths, the base type of the array)
            def collect_array_type_from_abi(type_str: str) -> Tuple[List[Optional[int]], str]:
                dims = []  # type: List[Optional[int]]
                base_type = collect_array_type_from_abi_rec(type_str, dims)
                return dims, base_type

            def cons_array_type(base_ct_type: CT.Type, dims: List[Optional[int]]) -> CT.Type:
                if dims:
                    tn = base_ct_type.name + ''.join(['[' + str(x) + ']' for x in dims])
                    return CT.ArrayType(
                        type_string=tn,
                        elementType=cons_array_type(base_ct_type, dims[1:]),
                        length=dims[0],
                        contract_name=contract_name,
                        reference=0)  # We have no useful reference number because this is used to extract from abi_data
                else:
                    return base_ct_type

            # Gets the CT.TypeInstance of a function parameter (either input or output) from the ABI
            def get_solidity_type_from_abi(abi_param_entry: Dict[str, Any]) -> CT.TypeInstance:
                assert "type" in abi_param_entry, f"Invalid ABI function parameter entry: {abi_param_entry}"
                array_dims, base_type = collect_array_type_from_abi(abi_param_entry["type"])

                internal_type_exists = "internalType" in abi_param_entry
                if internal_type_exists:
                    array_dims_internal, internal_base_type = collect_array_type_from_abi(
                        abi_param_entry["internalType"])
                    assert array_dims_internal == array_dims
                    user_defined_type = CT.TypeInstance(get_abi_type_by_name(internal_base_type))
                else:
                    base_ct_type = get_abi_type_by_name(base_type)
                    user_defined_type = CT.TypeInstance(cons_array_type(base_ct_type, array_dims))

                return user_defined_type

            def compute_signature(name: str, args: List[CT.TypeInstance], signature_getter: Any) -> str:
                return name + "(" + ",".join([signature_getter(x) for x in args]) + ")"

            def get_function_selector(f_entry: Dict[str, Any], f_name: str,
                                      input_types: List[CT.TypeInstance], is_lib: bool) -> str:
                if "functionSelector" in f_entry:
                    return f_entry["functionSelector"]

                f_base = compute_signature(f_name, input_types, lambda x: x.get_abi_canonical_string(is_lib))

                assert f_base in data["evm"]["methodIdentifiers"], \
                    f"Was about to compute the sighash of {f_name} based on the signature {f_base}.\n" \
                    f"Expected this signature to appear in \"methodIdentifiers\"."

                f_hash = keccak.new(digest_bits=256)
                f_hash.update(str.encode(f_base))

                result = f_hash.hexdigest()[0:8]
                expected_result = data["evm"]["methodIdentifiers"][f_base]

                assert expected_result == CompilerLangVy.normalize_func_hash(result), \
                    f"Computed the sighash {result} of {f_name} " \
                    f"based on a (presumably) correct signature ({f_base}), " \
                    f"but got an incorrect result. Expected result: {expected_result}"

                return result

            def flatten_getter_domain(in_type: CT.Type) -> List[CT.Type]:
                if isinstance(in_type, CT.MappingType):
                    return [in_type.domain] + flatten_getter_domain(in_type.codomain)
                else:
                    return []

            funcs = list()
            base_contract_files = [(contract_file, contract_name, False)]  # type: List[Tuple[str, str, bool]]
            ast_logger.debug(
                f"build arg contract file {build_arg_contract_file} and base contract files {base_contract_files}")
            c_is_lib = False
            for c_file, c_name, c_is_lib in base_contract_files:
                for abi_data in data["abi"]:
                    if abi_data["type"] == "function":
                        name = abi_data["name"]
                        if name in getter_vars:
                            solidity_type_args = [CT.TypeInstance(x) for x in flatten_getter_domain(getter_vars[name])]
                            solidity_type_outs = [CT.TypeInstance(getter_vars[name].codomain)]
                        else:
                            params = [p for p in abi_data["inputs"]]
                            out_params = [p for p in abi_data["outputs"]]
                            solidity_type_args = [get_solidity_type_from_abi(p) for p in params]
                            solidity_type_outs = [get_solidity_type_from_abi(p) for p in out_params]

                        is_constructor = name == CompilerLangVy.vyper_constructor_name
                        if not is_constructor:
                            func_selector = get_function_selector({}, name, solidity_type_args, True)
                        else:
                            func_selector = "0"  # this is the sighash for constructors in Solidity too
                        state_mutability = abi_data["stateMutability"]

                        funcs.append(
                            CompilerLangFunc(
                                name=name,
                                fullArgs=solidity_type_args,
                                paramNames=[],
                                returns=solidity_type_outs,
                                sighash=func_selector,
                                notpayable=state_mutability in ["nonpayable", "view", "pure"],
                                fromLib=False,
                                isConstructor=is_constructor,
                                stateMutability=state_mutability,
                                implemented=True,
                                overrides=False,
                                # according to Solidity docs, getter functions have external visibility
                                visibility="external",
                                source_bytes=None,
                                ast_id=None,
                                contractName=contract_name
                            )
                        )

            # TODO: merge this and the implementation from certoraBuild
            def verify_collected_all_abi_funcs(
                abi_funcs: List[Dict[str, Any]], collected_funcs: List[CompilerLangFunc], is_lib: bool
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

                    def compareTypes(ct_type: CT.Type, i: Dict[str, Any]) -> bool:
                        # check that there is exactly one collected function with the same argument types
                        # as the ABI function
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
                            elif isinstance(ct_type, CT.ArrayType):
                                it = ct_type
                                dims = ""
                                while isinstance(it, CT.ArrayType):
                                    dims = f"[{'' if it.length is None else it.length}]" + dims
                                    it = it.elementType  # type: ignore
                                ret = f"{it.type_string}{dims}" == solc_type
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
                        f"function collected for {fabi['name']} has the wrong types of return values: " \
                        f"{f.returns} vs. {fabi['outputs']}"

            verify_collected_all_abi_funcs(
                [f for f in data["abi"] if f["type"] == "function"],
                [f for f in funcs if f.visibility in ("external", "public") and f.name != "constructor"],
                c_is_lib
            )

            return funcs

        vyper_types, public_vardecls = \
            CompilerLangVy.extract_ast_types_and_public_vardecls(asts[build_arg_contract_file][contract_file])
        ct_types = [x.get_certora_type(contract_name, 0) for x in vyper_types]
        getter_vars_list = [(v, public_vardecls[v].get_certora_type(contract_name, 0))
                            for v in public_vardecls if isinstance(public_vardecls[v], CompilerLangVy.VyperTypeHashMap)]
        getter_vars = {k: v for (k, v) in getter_vars_list if isinstance(v, CT.MappingType)}
        parsed_types = {x.name: x for x in ct_types}
        return list(parsed_types.values()), collect_funcs(getter_vars)

    @staticmethod
    def collect_source_type_descriptions_and_funcs(asts: Dict[str, Dict[str, Dict[int, Any]]],
                                                   data: Dict[str, Any],
                                                   contract_file: str,
                                                   contract_name: str,
                                                   build_arg_contract_file: str) -> Tuple[List[CT.Type], List[CompilerLangFunc]]:  # noqa: E501
        # legacy version did the verification against data['abi']. so we're going to run it too and use
        # it if it contains more functions
        # some of the limitations/problems of the ABI-based method (thanks Mike):
        #     the ABI-based method discovery does not work for user-defined types
        #     having both ABI- and AST-based method discovery introduces a maintenance burden
        #     there are not tests that demonstrate what was broken and how this fixes it
        try:
            legacy_type_descriptions_and_funcs: Optional[Tuple[List[CT.Type], List[
                CompilerLangFunc]]] = CompilerLangVy.legacy_collect_source_type_descriptions_and_funcs(asts,
                                                                                                       data,
                                                                                                       contract_file,
                                                                                                       contract_name,
                                                                                                       build_arg_contract_file)  # noqa: E501
        except Exception as e:
            ast_logger.info(f"Could not fetch legacy type descriptions and functions using the AST: {e}")
            legacy_type_descriptions_and_funcs = None

        try:
            # TODO: verify that the collected functions matches the information in data['abi']
            collector = Collector(contract_name, asts[build_arg_contract_file][contract_file])
            type_descriptions_and_funcs = [t.get_certora_type(contract_name, 0) for t in
                                           collector.types.values()], collector.funcs

            if legacy_type_descriptions_and_funcs is not None and \
               len(legacy_type_descriptions_and_funcs[1]) > len(type_descriptions_and_funcs[1]):
                ast_logger.info(
                    "Legacy collector returned more functions. Using legacy results instead. "
                    "(You may use an older Vyper version)")
                return legacy_type_descriptions_and_funcs

            return type_descriptions_and_funcs
        except Exception as e:
            if legacy_type_descriptions_and_funcs is None:
                msg = "Both legacy and AST-based method for fetching type descriptions and functions failed"
                ast_logger.fatal(msg)
                raise Exception(msg)
            else:
                ast_logger.info(
                    f"Could not compute type descriptions and functions using the AST, falling back to legacy: {e}")
                return legacy_type_descriptions_and_funcs


# TODO: consolidate this with CompilerCollectorVy
class Collector:
    """
    Utility class for extracting the type declarations and functions from a Vyper AST.  After construction, the
    types, funcs, and consts fields are populated.
    """

    types  : Dict[str, CompilerLangVy.VyperType]
    """Maps type names to the type object"""

    funcs  : List[CompilerLangFunc]
    """List of all top-level functions"""

    consts : Dict[str, int]
    """Mapping from variable name to value for all integer constants"""

    _contract_name : str

    def __init__(self, contract_name : str, asts: Dict[int, Dict[str, Any]]):
        """Collect the types and functions from the top-level 'AST' node in [ast]."""
        self.types = {}
        self.funcs = []
        self.consts = {}
        self._contract_name = contract_name
        for node in asts.values():
            if node['ast_type'] == 'Module':
                self._collect_module(node)

    def _collect_module(self, module_node: Dict[str, Any]) -> None:
        """Populate [self.types] and [self.funcs] base on 'Module' AST node in [node]."""
        assert module_node['ast_type'] == "Module"

        # Extract constants (needs to happen before any type resolution since array sizes can contain constants)

        # Note that in Vyper, constant definitions can refer to constants that were previously defined.  Therefore,
        # we need to collect the constants in source order, so that the previously defined constants are available
        # while evaluating new constants
        var_decls = [e for e in module_node['body'] if e['ast_type'] == 'VariableDecl']
        for v in var_decls:
            self._collect_const(v)

        # Extract and resolve types
        type_asts = {'EnumDef', 'StructDef', 'InterfaceDef', 'Import', 'Import', 'ImportFrom', 'FlagDef'}
        types = [e for e in module_node['body'] if e['ast_type'] in type_asts]
        for t in types:
            self._collect_type_decl(t)

        for n, t in self.types.items():
            self.types[n] = t.resolve_forward_declared_types(self.types)

        # Extract functions (needs to happen after type resolution because we can't build func types with forward refs)
        funs = [e for e in module_node['body'] if e['ast_type'] == 'FunctionDef']
        for f in funs:
            self._collect_func(f)

        # Add getters for public variables (also needs to happen after type resolution)
        for v in var_decls:
            self._collect_getter(v)

    def _collect_const(self, var_node: Dict[str, Any]) -> None:
        """Update self.consts to add constant defined by var_node, if appropriate"""
        assert var_node['ast_type'] == 'VariableDecl'
        if var_node['is_constant']:
            value = self._eval_to_int(var_node['value'])
            if value:
                self.consts[var_node['target']['id']] = value

    def _collect_type_decl(self, type_decl_node: Dict[str, Any]) -> None:
        """Update self.types to add type definition from `type_decl_node`.  Expects self.consts to be initialized"""
        if type_decl_node['ast_type'] in ('EnumDef', 'FlagDef'):
            vy_type = CompilerLangVy.extract_type_from_enum_def(type_decl_node)
        elif type_decl_node['ast_type'] == 'StructDef':
            vy_type = CompilerLangVy.extract_type_from_struct_def(type_decl_node, self.consts)

        # TODO: this is probably wrong, since you can probably import constants and things too...
        #   although in practice it appears that people only import constants
        elif type_decl_node['ast_type'] in ('InterfaceDef', 'Import', 'ImportFrom'):
            vy_type = CompilerLangVy.VyperTypeContract(type_decl_node['name'])
        else:
            raise AssertionError("Unexpected type definition")
        self.types[type_decl_node['name']] = vy_type

    def _collect_getter(self, var_node: Dict[str, Any]) -> None:
        """
        Add a getter for VariableDecl node `var_node` to self.funcs if appropriate.  Expects `self.types` and
        `self.consts` to be initialized
        """
        assert var_node['ast_type'] == 'VariableDecl'
        if var_node['is_public']:
            var_type = CompilerLangVy.extract_type_from_variable_decl(var_node, self.consts)
            var_type = var_type.resolve_forward_declared_types(self.types)
            var_name = var_node['target']['id']
            getter = CompilerLangVy.extract_func_from_public_var(self._contract_name, var_name, var_type)
            self.funcs.append(getter)

    def _collect_func(self, func_node: Dict[str, Any]) -> None:
        """
        Update self.funcs to include FunctionDef node `func_node`.  Expects `self.types` and `self.consts` to be
        initialized
        """
        funcs = CompilerLangVy.extract_func_from_func_def(func_node, self._contract_name, self.consts, self.types)
        self.funcs += funcs

    def _eval_to_int(self, expr_node: Dict[str, Any]) -> Optional[int]:
        """
        Try to interpret AST node [node] as an integer constant (after evaluating binary operators and constant
        references).  Note that this isn't as complete as the Vyper evaluation; see the implementation of
        `VyperNode.evaluate()` in the vyper source code.

        Returns None if the node cannot be evaluated as an integer constant
        """
        def _apply_op(op_node: Dict[str, Any], left_val: int, right_val: int) -> Optional[int]:
            ops = {'Add': operator.add, 'Sub': operator.sub, 'Mult': operator.mul,
                   'Div': operator.floordiv, 'Mod': operator.mod}
            if op_node['ast_type'] in ops:
                return ops[op_node['ast_type']](left_val, right_val)
            else:
                return None

        if expr_node['ast_type'] == 'Int':
            return expr_node['value']

        elif expr_node['ast_type'] == 'BinOp':
            op = expr_node['op']
            left, right = self._eval_to_int(expr_node['left']), self._eval_to_int(expr_node['right'])
            if left and right:
                return _apply_op(op, left, right)
            else:
                return None

        elif expr_node['ast_type'] == 'Name':
            # Note: in Vyper, constants can only refer to previously defined constants; therefore as long as we parse
            # constants in source order, we don't need to do a topological sort or anything
            return self.consts.get(expr_node['id'], None)

        else:
            return None


class CompilerCollectorVy(CompilerCollector):
    def __init__(self, version: CompilerVersion):
        self.__compiler_version = version

    @property
    def compiler_name(self) -> str:
        return self.smart_contract_lang.compiler_name

    @property
    def smart_contract_lang(self) -> CompilerLangVy:
        return CompilerLangVy()

    @property
    def compiler_version(self) -> CompilerVersion:
        return self.__compiler_version

    @staticmethod
    def supports_storage_layout(version: CompilerVersion) -> bool:
        return (version[1] > 2 or (
                version[1] == 2 and version[2] >= 16))


def first_of(elements: List[Any], options: List[Any], default: Any = None) -> Any:
    """Return the first element of [elements] that is in [options], or [default]"""
    return next(filter(lambda it: it in options, elements), default)
