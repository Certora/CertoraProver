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

# "sourceHints": {"localAssignments": self.local_assignments.items(), "branches": self.branches.items(),
#                 "requires": self.requires.items()}
from dataclasses import dataclass
from typing import Dict, Any, Optional, Tuple, List

from CertoraProver.Compiler.CompilerCollectorSol import CompilerCollectorSol
from CertoraProver.certoraBuildDataClasses import SDC, Instrumentation, instrumentation_logger, InsertAfter, SourceLoc, \
    UnspecializedSourceFinder
from CertoraProver.certoraType import PrimitiveType
from Shared import certoraUtils as Util


class LocalAssignmentSourceFinder(UnspecializedSourceFinder):
    def __init__(self, sourceLoc: SourceLoc, lhs: str):
        super().__init__(sourceLoc)
        self.lhs = lhs
        self.finderType = "LocalAssignmentSourceFinder"

    def as_dict(self) -> Dict[str, Any]:
        return {
            "sourceLoc": self.sourceLoc.as_dict(),
            "lhs": self.lhs
        }


def get_top_level_assignments(ast_nodes: Dict[int, Dict]) -> List[int]:
    """
    Get IDs of Assignment and VariableDeclarationStatement nodes that are direct children
    of block-like containers (function bodies, loops, if blocks, etc), whether wrapped
    in ExpressionStatement or not.

    Args:
        ast_nodes: Dictionary mapping node IDs to their AST nodes

    Returns:
        List of node IDs for top-level assignments/declarations
    """
    result = []
    for node in ast_nodes.values():
        # Check for nodes that can contain statements
        if node['nodeType'] in {
            'Block',
            'FunctionDefinition',
            'ModifierDefinition',
            'ForStatement',
            'WhileStatement',
            'DoWhileStatement',
            'IfStatement',
            'UncheckedBlock',
            'TryStatement'
        }:
            # Look at statements in the body
            statements = []

            if node['nodeType'] == 'TryStatement':
                # Get statements from try block
                body = node.get('body')
                if body:
                    statements.extend(body.get('statements', []))
                # Get statements from each catch clause
                for catch_clause in node.get('clauses', []):
                    body = catch_clause.get('body')
                    if body:
                        statements.extend(body.get('statements', []))
            elif node['nodeType'] == 'IfStatement':
                # Handle if statement bodies
                body = node.get('body')
                if body:
                    statements.extend(body.get('statements', []))
                false_body = node.get('falseBody')
                if false_body:
                    statements.extend(false_body.get('statements', []))
            else:
                # Handle regular blocks
                body = node.get('body')
                if body:
                    statements.extend(body.get('statements', []))

            for stmt in statements:
                if stmt['nodeType'] == 'ExpressionStatement':
                    expr = stmt.get('expression', {})
                    if expr.get('nodeType') == 'Assignment':
                        result.append(expr['id'])
                elif stmt['nodeType'] in ('Assignment', 'VariableDeclarationStatement'):
                    result.append(stmt['id'])

    return result


def filter_assignments(nodes: Dict[int, Any]) -> List[Any]:
    """
    Filter nodes with nodeType='Assignment' or 'VariableDeclarationStatement.

    Args:
        nodes: List of dictionaries that may contain 'nodeType' and 'src' keys

    Returns:
        List of assignment/variable declaration nodes (skipping missing src)
    """

    # we are not interested in 'inline' assignments, it is difficult to instrument finders for them
    top_level_assignments = get_top_level_assignments(nodes)

    return [
        node
        for node in nodes.values()
        if (node.get('nodeType') == 'Assignment' or node.get(
            'nodeType') == 'VariableDeclarationStatement') and 'src' in node and node.get('id') in top_level_assignments
    ]


def get_chars_range(filepath: str, offset: int, length: int) -> str:
    """
    Read characters from file at given byte offset and length, removing newlines.
    Returns "[READ ERROR]" if any error occurs.

    Args:
        filepath: Path to the file
        offset: Byte offset to start reading from
        length: Number of bytes to read

    Returns:
        String with characters from the range with newlines removed,
        or "[READ ERROR]" if any error occurs
    """
    try:
        with open(filepath, 'r') as f:
            f.seek(offset)
            text = f.read(length)
            return text.replace('\n', '').replace('\r', '')
    except OSError:
        return "[READ ERROR]"

def get_node_from_ast(ast: Dict[str, Dict[int, Any]], node_id: int) -> Any:
    for contract_file in ast:
        node = ast.get(contract_file, {}).get(node_id)
        if node is not None:
            return node  # Found the ast node of the given node_id
    return {}  # an ast node with the given node_id was not found


def is_state_variable_or_function_call(node: Dict[str, Any], asts: Dict[str, Dict[int, Any]]) -> bool:
    if node.get("nodeType") == "FunctionCall":
        return True
    left_hand_side_referenced_declaration_id = node.get("referencedDeclaration")
    if left_hand_side_referenced_declaration_id is None:
        assert False, "Should not happen that a reference declaration field does not exist"
    left_hand_side_decl = get_node_from_ast(asts, int(left_hand_side_referenced_declaration_id))
    if left_hand_side_decl is None:
        assert False, "Should not happen that the referenced declaration does not exist"
    return left_hand_side_decl.get("stateVariable") is True


@dataclass
class LocalAssignmentFinderData:
    finder_prefix: str
    lhs_for_inline_assembly_read: str
    lhs_for_display: str
    """
    See https://www.notion.so/certora/Logging-Solidity-in-calltrace-135fe5c14fd380eb8453cfd3c8629449?pvs=4#135fe5c14fd3804fa47cfd0acc9b614c
    for documentation of the finder types.
    Currently:
    0 - local assignment of primitive
    1 - local declaration that cannot have its value presented
    2 - local assignment that cannot have its value presented
    """
    finder_type: int
    skip_assignment: bool


def handle_multi_decl(source_snippet: str, assignment: Dict[str, Any]) -> LocalAssignmentFinderData:
    return handle_unsupported_decl_value(source_snippet, assignment)

# https://docs.soliditylang.org/en/latest/yul.html#evm-dialect
SOLIDITY_BUILTINS = {"caller", "timestamp", "exp", "balance", "blockhash", "gasleft", "msg", "tx", "gas", "address",
                     "callvalue", "chainid", "basefee", "origin", "blobbasefee", "blobhash", "coinbase", "number",
                     "difficulty", "prevrandao"}

def handle_single_decl(source_snippet: str, assignment: Dict[str, Any], finder_running_id: int) -> LocalAssignmentFinderData:
    left_hand_side = assignment["declarations"][0]
    type_string = left_hand_side["typeDescriptions"]["typeString"]

    if is_unsupported_type(type_string):
        return handle_unsupported_decl_value(source_snippet, assignment)
    else:
        lhs_for_display = left_hand_side["name"]
        if lhs_for_display in SOLIDITY_BUILTINS:
            lhs_for_inline_assembly_read = f"certora_local{finder_running_id}"
            finder_prefix = f"{type_string} {lhs_for_inline_assembly_read} = {lhs_for_display};"
        else:
            lhs_for_inline_assembly_read = lhs_for_display
            finder_prefix = ""

        finder_type = 0

        return LocalAssignmentFinderData(finder_prefix, lhs_for_inline_assembly_read, lhs_for_display, finder_type,
                                         False)


def handle_unsupported_decl_value(source_snippet: str,
                                  assignment: Dict[str, Any]) -> LocalAssignmentFinderData:
    instrumentation_logger.debug(f"The declaration {source_snippet} does not assign to a supported lhs")
    return LocalAssignmentFinderData("", "0", ','.join(
        [x.get("name", "_") if x is not None else "_" for x in assignment["declarations"]]), 1,
        False)


def handle_multi_def(source_snippet: str, assignment: Dict[str, Any]) -> LocalAssignmentFinderData:
    return handle_unsupported_def_value(source_snippet, assignment)


def handle_single_def(source_snippet: str, finder_running_id: int, assignment: Dict[str, Any],
                      ast: Dict[str, Dict[int, Any]]) -> LocalAssignmentFinderData:
    left_hand_side = assignment["leftHandSide"]
    # if we deal with an assignment not to a variable, skip (for now)
    left_hand_side_node_type = left_hand_side.get("nodeType")
    if left_hand_side_node_type == "Identifier":
        # if we deal with an assignment to a storage variable, skip (calltrace will show it anyway)
        if is_state_variable_or_function_call(left_hand_side, ast):
            instrumentation_logger.debug(
                f"The assignment {source_snippet} assigns to a storage variable, skipping")
            return handle_to_skip()

        type_string = left_hand_side["typeDescriptions"]["typeString"]

        if is_unsupported_type(type_string):
            return handle_unsupported_def_value(source_snippet, assignment)
        else:
            lhs_for_display = left_hand_side["name"]
            if lhs_for_display in SOLIDITY_BUILTINS:
                lhs_for_inline_assembly_read = f"certora_local{finder_running_id}"
                finder_prefix = f"{type_string} {lhs_for_inline_assembly_read} = {lhs_for_display};"
            else:
                lhs_for_inline_assembly_read = lhs_for_display
                finder_prefix = ""
            return LocalAssignmentFinderData(finder_prefix, lhs_for_inline_assembly_read, lhs_for_display, 0, False)
    elif left_hand_side_node_type == "MemberAccess" or left_hand_side_node_type == "IndexAccess":
        # we access a struct or array. let's make sure the inner-most identifier is not a state variable
        base = left_hand_side.get("expression") if left_hand_side_node_type == "MemberAccess" else left_hand_side.get(
            "baseExpression")
        while base["nodeType"] == "MemberAccess" or base["nodeType"] == "IndexAccess":
            base = base.get("expression") if base["nodeType"] == "MemberAccess" else base.get("baseExpression")
        if is_state_variable_or_function_call(base, ast):
            instrumentation_logger.debug(
                f"The assignment {source_snippet} assigns to a storage variable, skipping")
            return handle_to_skip()

        type_string = left_hand_side["typeDescriptions"]["typeString"]

        if is_unsupported_type(type_string):
            return handle_unsupported_def_value(source_snippet, assignment)
        else:
            # this kind of instrumentation allows embedding in inline-assembly but can fail the compiler.
            # xxx include only in most-inclusive mode
            lhs_for_display = source_snippet.split(assignment["operator"])[0].strip()
            lhs_for_inline_assembly_read = f"certora_local{finder_running_id}"
            finder_prefix = f"{type_string} {lhs_for_inline_assembly_read} = {lhs_for_display};"
            finder_type = 0
            return LocalAssignmentFinderData(finder_prefix, lhs_for_inline_assembly_read, lhs_for_display, finder_type,
                                             False)
    else:
        return handle_unsupported_def_value(source_snippet, assignment)


def handle_unsupported_def_value(source_snippet: str,
                                 assignment: Dict[str, Any]) -> LocalAssignmentFinderData:
    instrumentation_logger.debug(f"The assignment {source_snippet} does not assign to a supported lhs")
    return LocalAssignmentFinderData("", "0", source_snippet.split(assignment["operator"])[0].strip(), 2, False)


def handle_to_skip() -> LocalAssignmentFinderData:
    return LocalAssignmentFinderData("", "0", "", 0, True)


def is_unsupported_type(type_string: str) -> bool:
    return type_string not in PrimitiveType.allowed_primitive_type_names


def find_char(filepath: str, offset: int, c : str) -> Optional[int]:
    """
    given offset, skip whitespace until finding the input character `c`.
    Returns None if any non-whitespace character other than `c` is encountered.

    Args:
        filepath: Path to the file
        offset: Byte offset where we expect to find 'c' or whitespace

    Returns:
        Offset of 'c' if found after only whitespace
        None if any other non-whitespace character is encountered
    """
    try:
        with open(filepath, 'r') as f:
            f.seek(offset)
            while True:
                char = f.read(1)
                if not char:  # EOF
                    return None

                if char == c:
                    return f.tell() - 1

                if char not in ' \t\n\r':  # not whitespace
                    return None

    except (IOError, OSError, UnicodeDecodeError):
        # Handle file-related errors and encoding issues
        return None


def add_source_finders(asts: Dict[str, Dict[str, Dict[int, Any]]], contract_file: str,
                       sdc: SDC) -> Tuple[Dict[str, UnspecializedSourceFinder], Dict[str, Dict[int, Instrumentation]]]:
    source_finder_map: Dict[str, UnspecializedSourceFinder] = dict()
    # contract file -> byte offset -> to insert
    source_finder_instrumentation: Dict[str, Dict[int, Instrumentation]] = dict()
    if not isinstance(sdc.compiler_collector, CompilerCollectorSol):
        raise Exception(f"Encountered a compiler collector that is not solc for file {contract_file}"
                        " when trying to add source autofinders")

    instrumentation_logger.debug(f"Using {sdc.compiler_collector} compiler to "
                                 f"add source finders to contract {sdc.primary_contract}")

    finder_counter = 1

    # Go over all contracts' files
    for c in sdc.contracts:
        solfile = c.original_file

        instrumentation_path = Util.convert_path_for_solc_import(solfile)
        if instrumentation_path not in source_finder_instrumentation:
            instrumentation_logger.debug(f"New instrumentation for location {solfile}, " +
                                         f"instrumentation path {instrumentation_path}")
            source_finder_instrumentation[instrumentation_path] = dict()
        else:
            instrumentation_logger.debug(f"Using existing instrumentation for location {solfile}, " +
                                         f"instrumentation path {instrumentation_path}")

        per_file_inst = source_finder_instrumentation[instrumentation_path]

        main_ast = asts[contract_file]
        curr_file_ast = asts[contract_file].get(solfile, dict())

        """
        Find ASTs we want to instrument:
        include VariableDeclarationStatement-s and Assignment-s nodeTypes
        """
        assignment_nodes = filter_assignments(curr_file_ast)
        for assignment in assignment_nodes:
            src = assignment["src"]
            start_offset, src_len, file = src.split(":")
            # no need to -1 as the source mapping does not include the ';', and we want to find it...
            # i.e., the end_offset should point at ';'
            end_offset = int(start_offset) + int(src_len)  # this is the original end offset
            end_offset_with_semicolon = find_char(solfile, end_offset, ";")
            if end_offset_with_semicolon is None:
                # we are dealing with Solidity code with unexpected format, let's skip this assignment
                continue
            if end_offset_with_semicolon in per_file_inst:
                # skip if we already instrumented this position for some reason
                continue

            source_snippet = get_chars_range(solfile, int(start_offset), int(src_len))
            # setting the finder_running_id, note that finder_counter is incremented at the end
            finder_running_id = finder_counter

            # Declaration of multiple variables is not supported at the moment. Let's at least print that we reached it!
            if assignment["nodeType"] == "VariableDeclarationStatement" and len(assignment["declarations"]) > 1:
                lafd = handle_multi_decl(source_snippet, assignment)
            elif assignment["nodeType"] == "Assignment" and assignment["typeDescriptions"]["typeString"] == "tuple()":
                lafd = handle_multi_def(source_snippet, assignment)
            # Now deal with single decls/defs
            elif assignment["nodeType"] == "VariableDeclarationStatement":
                lafd = handle_single_decl(source_snippet, assignment, finder_running_id)

                if lafd.skip_assignment:
                    continue

            elif assignment["nodeType"] == "Assignment":
                lafd = handle_single_def(source_snippet, finder_running_id, assignment, main_ast)

                if lafd.skip_assignment:
                    continue

            else:
                instrumentation_logger.warning(f"Should be unreachable {assignment}")
                continue

            finder_counter += 1
            assembly_prefix = sdc.compiler_collector.gen_memory_safe_assembly_prefix()

            finder_prefix = lafd.finder_prefix
            finder_type = lafd.finder_type
            lhs_for_inline_assembly_read = lafd.lhs_for_inline_assembly_read
            lhs_for_display = lafd.lhs_for_display

            source_finder_string = finder_prefix + assembly_prefix + "{mstore(" + \
                f'0xffffff6e4604afefe123321beef1b02fffffffffffffffffffffffff' \
                f'{"%0.4x" % finder_type}{"%0.4x" % finder_running_id},' \
                f'{lhs_for_inline_assembly_read})' + "}"
            per_file_inst[end_offset_with_semicolon] = Instrumentation(expected=b';', to_ins=source_finder_string,
                                                                       mut=InsertAfter())
            source_finder_map["%d" % finder_running_id] = LocalAssignmentSourceFinder(
                SourceLoc(file, start_offset, src_len), lhs_for_display)

    return source_finder_map, source_finder_instrumentation
