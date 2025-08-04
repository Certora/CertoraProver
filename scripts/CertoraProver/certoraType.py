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

from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Callable, Sequence, Tuple

from CertoraProver.certoraNodeFilters import NodeFilters
from abc import ABC, abstractmethod
from enum import Enum
import logging

from Shared.certoraUtils import find_filename_in, get_certora_sources_dir

ast_logger = logging.getLogger("ast")


class Annotation(ABC):
    @abstractmethod
    def as_dict(self) -> Dict[str, Any]:
        ...


@dataclass
class StorageAnnotation(Annotation):
    number_of_bytes: int
    slot: Optional[int] = None
    offset: Optional[int] = None
    lowerBound: Optional[int] = None
    upperBound: Optional[int] = None

    def as_dict(self) -> Dict[str, Any]:

        as_dict = {
            "type": "StorageAnnotation",
            "numberOfBytes": self.number_of_bytes,
        }
        if self.slot is not None:
            as_dict.update({"slot": self.slot})
        if self.offset is not None:
            as_dict.update({"offset": self.offset})
        if self.lowerBound is not None:
            as_dict.update({"lowerBound": self.lowerBound})
        if self.upperBound is not None:
            as_dict.update({"upperBound": self.upperBound})

        return as_dict


class Type(ABC):
    def __init__(self, name: str, type_string: str, annotations: Optional[Sequence[Annotation]] = None):
        """
        not every instance of a type needs to have a name. TODO
        @param type_string: solidity associates a type_string with every type
        """
        self.name = name
        self.type_string = type_string
        self.annotations = annotations

    def annotate(self, annotations: Sequence[Annotation]) -> None:
        assert self.annotations is None
        self.annotations = annotations

    # I'm not messing with __eq__ right now
    def matches(self, other: Any) -> bool:
        if not isinstance(other, Type):
            # don't attempt to compare against unrelated types
            return NotImplemented
        if isinstance(other, UserDefinedType) and isinstance(self, UserDefinedType):
            return self.canonical_name == other.canonical_name
        elif isinstance(other, MappingType) and isinstance(self, MappingType):
            return self.type_string == other.type_string
        elif isinstance(other, ArrayType) and isinstance(self, ArrayType):
            return self.type_string == other.type_string
        else:
            # hope I got all cases, luv2python
            return self.type_string == other.type_string

    @abstractmethod
    def as_dict(self) -> Dict[str, Any]:
        return {"annotations": [annotation.as_dict() for annotation in self.annotations]} if \
            self.annotations is not None else {}

    @abstractmethod
    def get_abi_canonical_string(self, is_library: bool) -> str:
        ...

    @abstractmethod
    def get_source_str(self) -> str:
        ...

    def default_location(self) -> 'TypeLocation':
        return TypeLocation.STACK

    @staticmethod
    def from_primitive_name(name: str) -> 'Type':
        return PrimitiveType(name, name)

    @staticmethod
    def from_def_node(lookup_reference: Callable[[int], Dict[str, Any]],
                      lookup_containing_file: Callable[[int], Optional[str]], def_node: Dict[str, Any]) -> 'Type':
        if NodeFilters.is_enum_definition(def_node):
            ret = EnumType.from_def_node(lookup_reference, lookup_containing_file, def_node)  # type: Type
        elif NodeFilters.is_struct_definition(def_node):
            ret = StructType.from_def_node(lookup_reference, lookup_containing_file, def_node)
        elif NodeFilters.is_user_defined_value_type_definition(def_node):
            ret = UserDefinedValueType.from_def_node(lookup_reference, lookup_containing_file, def_node)
        elif NodeFilters.is_contract_definition(def_node):
            ret = ContractType.from_def_node(lookup_reference, lookup_containing_file, def_node)
        else:
            ast_logger.fatal(f"unexpected AST Type Definition Node {def_node}")
        return ret

    @staticmethod
    def from_type_name_node(lookup_reference: Callable[[int], Dict[str, Any]],
                            lookup_containing_file: Callable[[int], Optional[str]],
                            type_name: Dict[str, Any]) -> 'Type':
        node_type = type_name["nodeType"]
        if node_type == "ElementaryTypeName":
            if type_name["name"] in PrimitiveType.allowed_primitive_type_names:
                ret = PrimitiveType(type_name["name"], type_name["typeDescriptions"]["typeString"])  # type: Type
            else:
                ret = Type.get_non_primitive_elementary_type(type_name)
        elif node_type == "FunctionTypeName":
            # TODO what to do with FunctionTypes :[]
            name = type_name["typeDescriptions"]["typeString"]
            ret = FunctionType(name)
        elif node_type == "UserDefinedTypeName":
            ret = UserDefinedType.from_def_node(lookup_reference, lookup_containing_file, lookup_reference(
                type_name["referencedDeclaration"]))
        elif node_type == "Mapping":
            ret = MappingType.from_def_node(lookup_reference, lookup_containing_file, type_name)
        elif node_type == "ArrayTypeName":
            ret = ArrayType.from_def_node(lookup_reference, lookup_containing_file, type_name)
        else:
            ast_logger.fatal(f"unexpected AST Type Name Node: {type_name}")
        return ret

    @staticmethod
    def get_non_primitive_elementary_type(type_name: Dict[str, Any]) -> 'Type':
        name = type_name["name"]
        if name == "bytes":
            ret = PackedBytes()
        elif name == "string":
            return StringType()
        else:
            ast_logger.fatal(f"unexpected AST Type Name Node: {name}")
        return ret

    def contains_mapping(self) -> bool:
        if isinstance(self, MappingType):
            return True
        if isinstance(self, StructType):
            return any(member.contains_mapping() for member in self.members)
        if isinstance(self, ArrayType):
            return self.elementType.contains_mapping()
        return False


class PrimitiveType(Type):
    allowed_primitive_type_names = {
        "uint", "uint8", "uint16", "uint24", "uint32", "uint40", "uint48", "uint56", "uint64", "uint72", "uint80",
        "uint88", "uint96", "uint104", "uint112", "uint120", "uint128", "uint136", "uint144", "uint152", "uint160",
        "uint168", "uint176", "uint184", "uint192", "uint200", "uint208", "uint216", "uint224", "uint232", "uint240",
        "uint248", "uint256",
        "int", "int8", "int16", "int24", "int32", "int40", "int48", "int56", "int64", "int72", "int80",
        "int88", "int96", "int104", "int112", "int120", "int128", "int136", "int144", "int152", "int160",
        "int168", "int176", "int184", "int192", "int200", "int208", "int216", "int224", "int232", "int240",
        "int248", "int256",
        "bytes1", "bytes2", "bytes3", "bytes4", "bytes5", "bytes6", "bytes7", "bytes8", "bytes9", "bytes10", "bytes11",
        "bytes12", "bytes13", "bytes14", "bytes15", "bytes16", "bytes17", "bytes18", "bytes19", "bytes20", "bytes21",
        "bytes22", "bytes23", "bytes24", "bytes25", "bytes26", "bytes27", "bytes28", "bytes29", "bytes30", "bytes31",
        "bytes32",
        "byte",
        "bool",
        "address",
    }

    # I am not 100% convinced this is the right spot for this canonicalizing (perhaps in kotlin deserialization) but
    # I do like that everything is "cleaned" before the jar gets its hands on this information
    @staticmethod
    def canonical_primitive_name(name: str) -> str:
        if name == "uint":
            return "uint256"
        elif name == "int":
            return "int256"
        else:
            return name

    def __init__(self, name: str, type_string: str):
        if name not in self.allowed_primitive_type_names:
            raise Exception(f'bad primitive name {name}')
        canonical_name = PrimitiveType.canonical_primitive_name(name)
        Type.__init__(self, canonical_name, type_string)

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({"primitiveName": self.name, "type": "Primitive"})
        return as_dict

    def get_abi_canonical_string(self, is_library: bool) -> str:
        return PrimitiveType.canonical_primitive_name(self.name)

    def get_source_str(self) -> str:
        return self.type_string  # this is different from `self.name` in the case of a payable address


class StringType(Type):
    def __init__(self) -> None:
        Type.__init__(self, "string", "string")

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "StringType",
        })
        return as_dict

    def default_location(self) -> 'TypeLocation':
        return TypeLocation.MEMORY

    def get_abi_canonical_string(self, is_library: bool) -> str:
        return "string"

    def get_source_str(self) -> str:
        return "string"


class PackedBytes(Type):
    def __init__(self) -> None:
        Type.__init__(self, "bytes", "bytes")

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "PackedBytes",
        })
        return as_dict

    def default_location(self) -> 'TypeLocation':
        return TypeLocation.MEMORY

    def get_abi_canonical_string(self, is_library: bool) -> str:
        return "bytes"

    def get_source_str(self) -> str:
        return "bytes"


class MappingType(Type):
    def __init__(self, type_string: str, domain: Type, codomain: Type, contract_name: str, reference: int):
        Type.__init__(self, f"mapping({domain.name} => {codomain.name})", type_string)
        self.domain = domain
        self.codomain = codomain
        self.contract_name = contract_name
        self.reference = reference

    @staticmethod
    def from_def_node(lookup_reference: Callable[[int], Dict[str, Any]],
                      lookup_containing_file: Callable[[int], Optional[str]],
                      def_node: Dict[str, Any]) -> 'MappingType':
        domain = Type.from_type_name_node(lookup_reference, lookup_containing_file,
                                          def_node["keyType"])
        codomain = Type.from_type_name_node(lookup_reference, lookup_containing_file,
                                            def_node["valueType"])
        type_string = def_node["typeDescriptions"]["typeString"]
        return MappingType(
            type_string=type_string,
            domain=domain,
            codomain=codomain,
            contract_name=def_node.get(NodeFilters.CERTORA_CONTRACT_NAME(), None),
            reference=def_node["id"],
        )

    def default_location(self) -> 'TypeLocation':
        return TypeLocation.STORAGE

    def get_abi_canonical_string(self, is_library: bool) -> str:
        return f"mapping({self.domain.get_abi_canonical_string(is_library)} => \
            {self.codomain.get_abi_canonical_string(is_library)})"

    def get_source_str(self) -> str:
        return f"mapping({self.domain.get_source_str()} => {self.codomain.get_source_str()})"

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "Mapping",
            "mappingKeyType": self.domain.as_dict(),
            "mappingValueType": self.codomain.as_dict(),
        })
        return as_dict


class ArrayType(Type):
    def __init__(self, type_string: str, elementType: Type, length: Optional[int], contract_name: Optional[str],
                 reference: int):
        Type.__init__(self, type_string, type_string)
        self.elementType = elementType
        self.length = length  # a length of None indicates a dynamic array
        self.contract_name = contract_name
        self.reference = reference

    @staticmethod
    def from_def_node(lookup_reference: Callable[[int], Dict[str, Any]],
                      lookup_containing_file: Callable[[int], Optional[str]],
                      def_node: Dict[str, Any]) -> 'ArrayType':
        type_string = def_node["typeDescriptions"]["typeString"]
        element_type = Type.from_type_name_node(lookup_reference, lookup_containing_file,
                                                def_node["baseType"])
        if "length" in def_node and def_node["length"] is not None:
            length_object = def_node["length"]
            if "value" in length_object:
                length = int(length_object["value"], 10)  # type: Optional[int]
            elif "nodeType" in length_object and length_object["nodeType"] == "Identifier" \
                 and "referencedDeclaration" in length_object:
                constant_ref = lookup_reference(length_object["referencedDeclaration"])
                if "constant" in constant_ref and constant_ref["constant"] and "value" in constant_ref \
                   and isinstance(constant_ref["value"], dict) and "value" in constant_ref["value"]:
                    length = int(constant_ref["value"]["value"], 10)
                else:
                    length = None
            else:
                """
                This happens if we have something like:
                uint256 internal constant TREE_DEPTH = 32;

                struct Tree {
                    bytes32[TREE_DEPTH] branch;
                    uint256 count;
                }

                I guess we could resolve TREE_DEPTH, but taking a more straight forward approach now

                0 means that we currently don't know what is the real size
                """
                length = 0
        else:
            length = None
        return ArrayType(
            type_string=type_string,
            elementType=element_type,
            length=length,
            contract_name=def_node.get(NodeFilters.CERTORA_CONTRACT_NAME(), None),
            reference=def_node["id"],
        )

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        if self.is_static_array():
            as_dict.update({
                "type": "StaticArray",
                "staticArrayBaseType": self.elementType.as_dict(),
                "staticArraySize": f"{self.length}",
            })
        else:
            as_dict.update({
                "type": "Array",
                "dynamicArrayBaseType": self.elementType.as_dict(),
            })
        return as_dict

    def is_static_array(self) -> bool:
        return self.length is not None

    def default_location(self) -> 'TypeLocation':
        return TypeLocation.MEMORY

    def get_abi_canonical_string(self, is_library: bool) -> str:
        return self.elementType.get_abi_canonical_string(is_library) + \
            f"[{self.length if self.is_static_array() else ''}]"

    def get_source_str(self) -> str:
        return self.elementType.get_source_str() + \
            f"[{self.length if self.is_static_array() else ''}]"


class UserDefinedType(Type):
    # TODO: what should we do if file is None? we may want to not let file be optional and error if we can't get it
    def __init__(self, name: str, type_string: str, canonical_name: str, contract_name: str, reference: int,
                 file: Optional[str]):
        Type.__init__(self, name, type_string)
        self.canonical_name = canonical_name
        self.contract_name = contract_name
        self.reference = reference
        self.file_for_canonical_id = file

    def generate_canonical_id(self) -> str:
        """
        We don't want canonical ids to contain absolute paths, because it can interfere with caching.
        I.e. if the canonical_id contains a part of an absolute path, and then we run the same job
        in a different machine (i.e. different user name), we'll get a failure to merge type descriptors
        because that part of the string representing a user name is different.
        This is absurd, so we try to resolve with respect to .certora_sources.
        If not, we just take whatever we got from the constructor of the type.
        Hopefully the lazy computation of the final canonical_id going to the part consumed by the
        typechecker in the cloud is late enough so that the resolution actually succeeds.
        Is it bullet-proof? Not at all - but works for now.
        """
        if self.file_for_canonical_id is None:  # happened in Vyper
            return self.canonical_name

        resolve_file = find_filename_in(get_certora_sources_dir(), self.file_for_canonical_id)
        if resolve_file is None:
            return f"{self.file_for_canonical_id}|{self.canonical_name}"
        return f"{resolve_file}|{self.canonical_name}"

    @abstractmethod
    def as_dict(self) -> Dict[str, Any]:
        ...

    @abstractmethod
    def get_abi_canonical_string(self, is_library: bool) -> str:
        ...

    @abstractmethod
    def get_source_str(self) -> str:
        ...


class EnumType(UserDefinedType):
    def __init__(self, name: str, type_string: str, canonical_name: str, members: List[str], contract_name: str,
                 reference: int, file: Optional[str]):
        UserDefinedType.__init__(self, name, type_string, canonical_name, contract_name, reference, file)
        self.members = tuple(members)

    @staticmethod
    def get_type_string_from_def_node(def_node: Dict[str, Any]) -> str:
        if "typeDescriptions" in def_node.keys():
            return def_node["typeDescriptions"]["typeString"]  # was'nt included in solidity 4ish
        else:
            canonical_name = def_node["canonicalName"]
            return f"enum {canonical_name}"

    @staticmethod
    def from_def_node(lookup_reference: Callable[[int], Dict[str, Any]],
                      lookup_containing_file: Callable[[int], Optional[str]],
                      def_node: Dict[str, Any], _: Optional[str] = None) -> 'EnumType':
        members = map(
            lambda member: member["name"],
            def_node["members"]
        )

        return EnumType(
            name=def_node["name"],
            type_string=EnumType.get_type_string_from_def_node(def_node),
            canonical_name=def_node["canonicalName"],
            members=list(members),
            contract_name=def_node.get(NodeFilters.CERTORA_CONTRACT_NAME(), None),
            reference=def_node["id"],
            file=lookup_containing_file(def_node["id"])
        )

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "UserDefinedEnum",
            "enumName": self.name,
            "enumMembers": [{"name": x} for x in self.members],
            "containingContract": self.contract_name,  # null means it wasn't declared in a contract but at file-level
            "astId": self.reference,
            "canonicalId": self.generate_canonical_id(),
        })
        return as_dict

    def get_abi_canonical_string(self, is_library: bool) -> str:
        if is_library:
            return f"{self.contract_name}.{self.name}"
        return "uint8"

    def get_source_str(self) -> str:
        if self.contract_name is None:
            return self.name
        return f"{self.contract_name}.{self.name}"


class StructType(UserDefinedType):
    def __init__(self, name: str, type_string: str, canonical_name: str, members: List[Any], contract_name: str,
                 reference: int, file: Optional[str]):
        UserDefinedType.__init__(self, name, type_string, canonical_name, contract_name, reference, file)
        self.members = members

    class StructMember:
        def __init__(self, name: str, type: Type):
            self.name = name
            self.type = type

        @staticmethod
        def from_member_node(lookup_reference: Callable[[int], Dict[str, Any]],
                             lookup_containing_file: Callable[[int], Optional[str]],
                             member_node: Dict[str, Any]) -> 'StructType.StructMember':
            name = member_node["name"]
            type_name = member_node["typeName"]
            type = Type.from_type_name_node(lookup_reference, lookup_containing_file,
                                            type_name)
            assert type is not None
            return StructType.StructMember(name, type)

        def as_dict(self) -> Dict[str, Any]:
            return {
                "name": self.name,
                "type": self.type.as_dict()
            }

    @staticmethod
    def from_def_node(lookup_reference: Callable[[int], Dict[str, Any]],
                      lookup_containing_file: Callable[[int], Optional[str]],
                      def_node: Dict[str, Any]) -> 'StructType':
        canonical_name = def_node["canonicalName"]
        return StructType(
            name=def_node["name"],
            type_string=f"struct {canonical_name}",
            canonical_name=canonical_name,
            members=[StructType.StructMember.from_member_node(lookup_reference,
                                                              lookup_containing_file,
                                                              member_node) for member_node in def_node["members"]],
            contract_name=def_node.get(NodeFilters.CERTORA_CONTRACT_NAME(), None),
            reference=def_node["id"],
            file=lookup_containing_file(def_node["id"]),
        )

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "UserDefinedStruct",
            "structName": self.name,
            "structMembers": [x.as_dict() for x in self.members],
            "containingContract": self.contract_name,
            # ^ null means it wasn't declared in a contract but at file-level (is this possible for structs?)
            "astId": self.reference,
            "canonicalId": self.generate_canonical_id(),
        })
        return as_dict

    def default_location(self) -> 'TypeLocation':
        return TypeLocation.MEMORY

    def get_abi_canonical_string(self, is_library: bool) -> str:
        if is_library:
            return f"{self.contract_name}.{self.name}"
        members = ",".join(m.type.get_abi_canonical_string(False) for m in self.members)
        return f"({members})"

    def get_source_str(self) -> str:
        return f"{self.canonical_name}"  # again, flailing here


# Solidity Name for a Type Alias
class UserDefinedValueType(UserDefinedType):
    def __init__(self, name: str, canonical_name: str, contract_name: str, reference: int,
                 underlying: Type, file: Optional[str]):
        UserDefinedType.__init__(self, name, canonical_name, canonical_name, contract_name, reference, file)
        self.underlying = underlying

    @staticmethod
    def from_def_node(lookup_reference: Callable[[int], Dict[str, Any]],
                      lookup_containing_file: Callable[[int], Optional[str]],
                      def_node: Dict[str, Any], _: Optional[str] = None) -> 'UserDefinedValueType':
        underlying_node = def_node["underlyingType"]
        assert underlying_node["nodeType"] == "ElementaryTypeName", \
            f"Unexpected underlying type {underlying_node}"
        assert underlying_node["name"] in PrimitiveType.allowed_primitive_type_names, \
            f"Unexpected underlying type name {underlying_node['name']}"
        return UserDefinedValueType(
            name=def_node["name"],
            canonical_name=def_node["canonicalName"],
            contract_name=def_node.get(NodeFilters.CERTORA_CONTRACT_NAME(), None),
            reference=def_node["id"],
            underlying=PrimitiveType(underlying_node["name"], underlying_node["typeDescriptions"]["typeString"]),
            file=lookup_containing_file(def_node["id"])
        )

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "UserDefinedValueType",
            "valueTypeName": self.name,
            "containingContract": self.contract_name,
            "valueTypeAliasedName": self.underlying.as_dict(),
            "astId": self.reference,
            "canonicalId": self.generate_canonical_id(),
        })
        return as_dict

    def get_abi_canonical_string(self, is_library: bool) -> str:
        return self.underlying.get_abi_canonical_string(is_library)

    def get_source_str(self) -> str:
        if self.contract_name is None:
            return self.name
        return f"{self.contract_name}.{self.name}"


# unclear if this belongs in certoraBuild or not (it may only bet that CodeContracts come from the scene)
class ContractType(UserDefinedType):
    def __init__(self, name: str, reference: int):
        # TODO: should we allow contract_name for inner/nested contract declarations?
        UserDefinedType.__init__(self, name, f"contract {name}", name, name,  # is name right for typeString?
                                 reference, None)

    @staticmethod
    def from_def_node(lookup_reference: Callable[[int], Dict[str, Any]],
                      lookup_containing_file: Callable[[int], Optional[str]],
                      def_node: Dict[str, Any], _: Optional[str] = None) -> 'ContractType':
        name = def_node["name"]
        reference = def_node["id"]
        return ContractType(name, reference)

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "Contract",
            "contractName": self.name,
        })
        return as_dict

    def get_abi_canonical_string(self, is_library: bool) -> str:
        if is_library:
            return self.name
        return "address"

    def get_source_str(self) -> str:
        return self.name  # yolo


class FunctionType(Type):
    def __init__(self, name: str):
        Type.__init__(self, name, name)

    def as_dict(self) -> Dict[str, Any]:
        as_dict = Type.as_dict(self)
        as_dict.update({
            "type": "Function",
            "name": self.name,
        })
        return as_dict

    def get_abi_canonical_string(self, is_library: bool) -> str:
        return "function"

    def get_source_str(self) -> str:
        return f"function {self.name}"  # this is wrong, fix is function types are ever really supported


class TypeLocation(Enum):
    STACK = "stack"
    MEMORY = "memory"
    CALLDATA = "calldata"
    STORAGE = "storage"
    TRANSIENT = "transient"

    @property
    def abi_str(self) -> str:
        if self == TypeLocation.STORAGE:
            return f' {self.value}'
        return ""


class TypeInstance:
    def __init__(self, type: Type, location: str = "default"):
        self.type = type
        self.location = TypeLocation(location) if location != "default" else type.default_location()

    def as_dict(self) -> Dict[str, Any]:
        return {
            "typeDesc": self.type.as_dict(),
            "location": self.location.value
        }

    def get_abi_canonical_string(self, is_library: bool) -> str:
        ret = self.type.get_abi_canonical_string(is_library)
        if is_library:
            ret += self.location.abi_str if self.location == TypeLocation.STORAGE else ''
        return ret

    def compute_getter_signature(self) -> Tuple[List['TypeInstance'], List['TypeInstance']]:
        """
        Computes the signature of a contract's (public) state-variable getter function
        :return: The param types and return types for a getter of a storage variable of type variable_type
        """
        curr = self.type
        params: List[TypeInstance] = []
        while True:
            if isinstance(curr, MappingType):
                params.append(TypeInstance(curr.domain))
                curr = curr.codomain
            elif isinstance(curr, ArrayType):
                params.append(TypeInstance(PrimitiveType("uint256", "uint256")))
                curr = curr.elementType
            else:
                break

        # In case of the variable returned is a struct, the getter skips any struct members that are
        # of mapping or array type.
        # See https://docs.soliditylang.org/en/v0.8.17/contracts.html#getter-functions
        if isinstance(curr, StructType):
            returns = [TypeInstance(t.type)
                       for t in curr.members if not isinstance(t.type, (MappingType, ArrayType))]
        else:
            returns = [TypeInstance(curr)]
        return params, returns

    def get_source_str(self) -> str:
        return self.type.get_source_str()

    def get_source_and_location_str(self) -> str:
        return self.get_source_str() + (f" {self.location.value}" if self.location != TypeLocation.STACK else "")

    def matches(self, other: Any) -> bool:
        if not isinstance(other, TypeInstance):
            return False

        if not self.type.matches(other.type):
            return False

        if not self.location == other.location:
            return False

        return True
