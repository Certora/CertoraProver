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
from typing import Any

from CertoraProver.Compiler.CompilerCollectorSol import CompilerCollectorSol
from CertoraProver.castingInstrumenter import encode_type, iter_all_nodes_under
from CertoraProver.certoraBuildDataClasses import SDC, Instrumentation, Replace, InsertBefore, InsertAfter
from CertoraProver.certoraOffsetConverter import OffsetConverter
from CertoraProver.certoraSourceFinders import find_char
from Shared import certoraUtils as Util


def is_unchecked_block(node: Any) -> bool:
    return isinstance(node, dict) and node.get('nodeType') == 'UncheckedBlock'


def is_possibly_overflowing_op(node: Any) -> bool:
    return isinstance(node, dict) and node.get('operator') in {"*", "+", "-"}


def find_unchecked(ast: dict[int, Any]) -> list[dict]:
    function_nodes = [node for node in ast.values() if node.get('nodeType') == 'FunctionDefinition']
    result = []
    for func in function_nodes:
        for node in iter_all_nodes_under(func, is_unchecked_block):
            if is_possibly_overflowing_op(node):
                result.append(node)
    return result


def func_name(counter: int) -> str:
    return f"op_{counter}"


def char_at(filepath: str, offset: int) -> str:
    with open(filepath, 'r') as f:
        f.seek(offset)
        return f.read(1)


def instrumentations(filename: str, lib_name: str, op: dict, counter: int) -> dict[int, Instrumentation]:
    start_offset, src_len, file = op["src"].split(":")
    left = op["leftExpression"]
    operator = op["operator"]
    result: dict[int, Instrumentation] = {}

    start_offset_left, src_len_left, _ = left["src"].split(":")
    where = find_char(filename, int(start_offset_left) + int(src_len_left), operator)
    if where is None:
        raise Exception(f"Could not find {start_offset_left}:{src_len_left} in {filename}")
    result[where] = Instrumentation(expected=bytes(operator, 'utf-8'),
                                    to_ins=",",
                                    mut=Replace(1))
    before = int(start_offset_left)
    result[before] = Instrumentation(expected=bytes(char_at(filename, before), 'utf-8'),
                                     to_ins=f"{lib_name}.{func_name(counter)}(",
                                     mut=InsertBefore())
    after = int(start_offset) + int(src_len)
    result[after] = Instrumentation(expected=bytes(char_at(filename, after), 'utf-8'),
                                    to_ins=")",
                                    mut=InsertBefore())
    return result


def generate_overflow_function(offset_converter: OffsetConverter, assembly_prefix: str, op: dict, counter: int) -> str:
    res_type = op["typeDescriptions"]["typeString"]
    function_head = f"function {func_name(counter)}({res_type} x, {res_type} y) internal pure returns ({res_type})"
    start_offset, _, _ = op["src"].split(":")
    _, left_length, _ = op["leftExpression"]["src"].split(":")
    line, column = offset_converter.offset_to_line_column(int(start_offset) + int(left_length))
    encoded = ("0xffffff6e4604afefe123321beef1b04fffffffffffffffffff"
               f"{'%0.5x' % line}{'%0.5x' % column}{'%0.4x' % encode_type(res_type)}")
    return f"""
               {function_head} {{
                 unchecked {{
                   {res_type} z = x {op['operator']} y;
                   {assembly_prefix} {{
                     mstore({encoded}, z)
                   }}
                   return z;
                 }}
               }}
           """


def add_instrumentation(inst_dict: dict[int, Instrumentation], k: int, v: Instrumentation) -> None:
    if k in inst_dict:
        old = inst_dict[k]
        if isinstance(old.mut, InsertBefore) and isinstance(v.mut, InsertBefore):
            inst_dict[k] = Instrumentation(expected=old.expected, mut=InsertBefore(),
                                           to_ins=old.to_ins + v.to_ins)
        elif isinstance(old.mut, InsertAfter) and isinstance(v.mut, InsertAfter):
            inst_dict[k] = Instrumentation(expected=old.expected, mut=InsertAfter(),
                                           to_ins=old.to_ins + v.to_ins)
        elif isinstance(old.mut, Replace) and isinstance(v.mut, InsertBefore):
            inst_dict[k] = Instrumentation(expected=old.expected, mut=old.mut,
                                           to_ins=v.to_ins + old.to_ins)
        elif isinstance(old.mut, InsertBefore) and isinstance(v.mut, Replace):
            inst_dict[k] = Instrumentation(expected=old.expected, mut=v.mut,
                                           to_ins=old.to_ins + v.to_ins)
        else:
            print(f"GOT A PROBLEM at {k} ::::  {old}   {v}")
            # should warn here.
            inst_dict[k] = v
    else:
        inst_dict[k] = v


def generate_overflow_instrumentation(asts: dict[str, dict[str, dict[int, Any]]], contract_file: str, sdc: SDC,
                                      offset_converters: dict[str, OffsetConverter]) \
        -> tuple[dict[str, dict[int, Instrumentation]], dict[str, tuple[str, list[str]]]]:
    """
    Generates the instrumentation for uncheckedOverflow builtin rule.
    It replaces each of the possibly overflowing operations: `*, +, -`, with a function call to a new function
    we add in a library in the same file. This function does the exact same operation, but adds an mload instruction
    encoding the location of the operation and the expected resulting type.
    """
    overflow_instrumentation: dict[str, dict[int, Instrumentation]] = dict()
    op_funcs: dict[str, tuple[str, list[str]]] = dict()
    if not isinstance(sdc.compiler_collector, CompilerCollectorSol):
        raise Exception(f"Encountered a compiler collector that is not solc for file {contract_file}"
                        " when trying to add casting instrumentation")
    assembly_prefix = sdc.compiler_collector.gen_memory_safe_assembly_prefix()
    counter = 0

    original_files = sorted({Util.convert_path_for_solc_import(c.original_file) for c in sdc.contracts})
    for file_count, solfile in enumerate(original_files, start=1):
        main_ast = asts[contract_file]
        libname, per_file_funcs = op_funcs.setdefault(solfile, (f"CertoraOverflowLib{file_count}", []))
        curr_file_ast = main_ast.get(solfile, dict())
        per_file_inst = overflow_instrumentation.setdefault(solfile, dict())

        for op in find_unchecked(curr_file_ast):
            counter += 1
            for k, v in instrumentations(contract_file, libname, op, counter).items():
                add_instrumentation(per_file_inst, k, v)
            new_func = generate_overflow_function(offset_converters[solfile], assembly_prefix, op, counter)
            per_file_funcs.append(new_func)

    return overflow_instrumentation, op_funcs
