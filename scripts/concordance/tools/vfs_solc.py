#      The Certora Prover
#      Copyright (C) 2025  Certora Ltd.
#
#      This program is free software: you can redistribute it and/or modify
#      it under the terms of the GNU General Public License as published by
#      the Free Software Foundation, version 3 of the License.
#
#      This program is distributed in the hope that it will be useful,
#      but WITHOUT ANY WARRANTY; without even the implied warranty of
#      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
#      GNU General Public License for more details.
#
#      You should have received a copy of the GNU General Public License
#      along with this program.  If not, see <https://www.gnu.org/licenses/>.

from typing import Annotated

from langgraph.prebuilt import InjectedState
from langchain_core.tools import tool

from concordance.state import RewriterState
from concordance.solc import solidity_compiler_impl, SolidityCompilerInputBase

class VFSSolidityCompiler(SolidityCompilerInputBase):
    """
    Compiles the "rewrite harness" using a standard solidity compiler
    The compiler also performs typechecking and will flag any syntax errors. The compiler comes from the official
    distribution channels for Solidity and understands all the Solidity language and features.

    IMPORTANT: The rewrite harness MUST be placed in the VFS via the put_file tool first.
    """
    state: Annotated[RewriterState, InjectedState]

@tool(args_schema=VFSSolidityCompiler)
def vfs_solidity_compiler(
    compiler_version: str,
    state: Annotated[RewriterState, InjectedState]
) -> str:
    if "curr_rewrite" not in state:
        return "Rewrite harness is missing from the VFS, use the put_file tool first."
    return solidity_compiler_impl(source=state["curr_rewrite"], compiler_version=compiler_version)
