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
from typing import Dict, Any, Optional, Generator

from CertoraProver.Compiler.CompilerCollectorSol import CompilerCollectorSol
from CertoraProver.certoraBuildDataClasses import SDC, Instrumentation, Replace
from Shared import certoraUtils as Util


@dataclass
class IntType:
    is_signed: bool
    bitwidth: int

    def encode(self) -> Optional[int]:
        """
        Encodes into a 9 bit number: lowest bit is on if [IntType] is true, and upper 8 bits are the width.
        returns none if width is out of bounds.
        """
        if self.bitwidth <= 0 or self.bitwidth > 256:
            return None
        return self.bitwidth * 2 + int(self.is_signed)


def parse_int_type(type_string: str) -> Optional[IntType]:
    """
    Parse an integer type string of the form 'int<bitwidth>' or 'uint<bitwidth>'.
    Returns IntType or None if the string doesn't match the pattern.
    """
    if type_string == "int":
        return IntType(True, 256)
    elif type_string == "uint":
        return IntType(False, 256)
    elif type_string.startswith("int"):
        is_signed = True
        bitwidth_str = type_string[3:]
    elif type_string.startswith("uint"):
        is_signed = False
        bitwidth_str = type_string[4:]
    else:
        return None

    try:
        bitwidth = int(bitwidth_str)
        return IntType(is_signed, bitwidth)
    except ValueError:
        return None


def is_int_type(type_string: str) -> bool:
    return parse_int_type(type_string) is not None


def encode_type(type_string: str) -> int:
    int_type = parse_int_type(type_string)
    if int_type is None:
        raise Exception(f"Invalid type string: {type_string}")
    encoded = int_type.encode()
    if encoded is None:
        raise Exception(f"Invalid type string: {type_string}")
    return encoded


@dataclass
class CastInfo:
    arg_type_str: str
    res_type_str: str
    expr_id: int


def find_casts(ast: Dict[int, Any]) -> list[CastInfo]:
    """
    Search all nodes that are in functions for kind "typeConversion" with one argument.
    Returns a list of CastInfo instances.
    """
    conversions = []
    function_nodes = [node for node in ast.values() if node.get('nodeType') == 'FunctionDefinition']

    for func in function_nodes:
        for node in iter_all_nodes(func):
            if isinstance(node, dict) and node.get("kind") == "typeConversion":
                arguments = node.get("arguments", [])
                if len(arguments) == 1 and isinstance(arguments[0], dict):
                    expression = node["expression"]
                    expr_node_id = expression["id"]
                    arg_type_str = expression["argumentTypes"][0]["typeString"]
                    if not is_int_type(arg_type_str):
                        continue
                    if "typeName" not in expression:
                        continue
                    expr_type_node = expression["typeName"]
                    if isinstance(expr_type_node, str):
                        expr_type_str = expr_type_node
                    else:
                        expr_type_str = expr_type_node["name"]
                    if not is_int_type(expr_type_str):
                        continue
                    conversions.append(CastInfo(arg_type_str, expr_type_str, expr_node_id))

    return conversions


def casting_func_name(counter: int) -> str:
    return f"cast_{counter}"


def generate_casting_function(assembly_prefix: str, cast_info: CastInfo, counter: int) -> str:
    """
    returns the text of a solidity function that does casting according to CastInfo. It also has an encoded mload
    call, to be decoded later on the kotlin side if we run the `safeCasting` builtin rule.
    """
    conversion_string = assembly_prefix + \
        "{ mstore(0xffffff6e4604afefe123321beef1b03fffffffffffffffffffff" + \
        f'{"%0.4x" % counter}{"%0.4x" % encode_type(cast_info.arg_type_str)}{"%0.4x" % encode_type(cast_info.res_type_str)}, x)' + "}"
    function_head = f"function {casting_func_name(counter)}({cast_info.arg_type_str} x) internal pure returns ({cast_info.res_type_str})"
    return function_head + "{\n" + conversion_string + f"return {cast_info.res_type_str}(x);\n" "}\n"


def generate_casting_instrumentation(asts: Dict[str, Dict[str, Dict[int, Any]]], contract_file: str, sdc: SDC) \
        -> tuple[Dict[str, Dict[int, Instrumentation]], Dict[str, tuple[str, list[str]]]]:
    """
    Generate instrumentation for integer type casts in Solidity code.

    Returns a tuple of:
    - casting_instrumentation: Maps file paths to offset->Instrumentation mappings that replace type names with
      library calls to casting functions we generate.
    - casting_types: Maps file paths to library_name and the text for our new casting functions. This library is added
      to the end of the file.

    It's not hundred precent sure this works well in combination with `compiler_map`, because the counter for library
    names may reset?
    """
    casting_instrumentation: Dict[str, Dict[int, Instrumentation]] = dict()

    if not isinstance(sdc.compiler_collector, CompilerCollectorSol):
        raise Exception(f"Encountered a compiler collector that is not solc for file {contract_file}"
                        " when trying to add casting instrumentation")
    assembly_prefix = sdc.compiler_collector.gen_memory_safe_assembly_prefix()

    casting_funcs : Dict[str, tuple[str, list[str]]] = dict()
    counter = 0
    original_files = sorted({Util.convert_path_for_solc_import(c.original_file) for c in sdc.contracts})
    for file_count, solfile in enumerate(original_files, start=1):
        main_ast = asts[contract_file]
        curr_file_ast = main_ast.get(solfile, dict())

        per_file_inst = casting_instrumentation.setdefault(solfile, dict())
        libname, per_file_casts = casting_funcs.setdefault(solfile, (f"CertoraCastingLib{file_count}", []))

        casts = find_casts(curr_file_ast)
        for cast_info in casts:
            start_offset, src_len, file = curr_file_ast[cast_info.expr_id]["src"].split(":")
            counter += 1
            per_file_inst[int(start_offset)] = Instrumentation(expected=bytes(cast_info.res_type_str[0], 'utf-8'),
                                                               to_ins=f"{libname}.{casting_func_name(counter)}",
                                                               mut=Replace(len(cast_info.res_type_str)))
            new_func = generate_casting_function(assembly_prefix, cast_info, counter)
            per_file_casts.append(new_func)

    return casting_instrumentation, casting_funcs


def iter_all_nodes(node: Any) -> Generator[Any, Optional[Any], None]:
    """
    Yield a node and all its subnodes in depth-first order.
    Works with dict nodes that may contain nested dicts and lists.
    """
    yield node

    if isinstance(node, dict):
        for value in node.values():
            if isinstance(value, (dict, list)):
                yield from iter_all_nodes(value)
    elif isinstance(node, list):
        for item in node:
            if isinstance(item, (dict, list)):
                yield from iter_all_nodes(item)
