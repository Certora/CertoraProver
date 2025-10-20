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

from langgraph.types import Command
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.tools import tool, InjectedToolCallId
from langgraph.graph.state import CompiledStateGraph
from pydantic import Field

from graphcore.graph import tool_output, FlowInput, build_workflow, WithToolCallId

from concordance.state import HarnessingState, HarnessedOutput
from concordance.solc import SolidityCompilerInputBase, solidity_compiler_impl
from concordance.templates import load_template


# ============================================================================
# HARNESSING TOOLS
# ============================================================================


class SolidityCompilerExplicitInput(SolidityCompilerInputBase):
    """
    A Solidity compiler capable of compiling a single, Solidity file into EVM bytecode. The compiler
    also performs typechecking and will flag any syntax errors. The compiler comes from the official
    distribution channels for Solidity and understands all the Solidity language and features.
    """
    source: str = Field(description="The Solidity source to be compiled")


@tool(args_schema=SolidityCompilerExplicitInput)
def solidity_compiler(source: str, compiler_version: str) -> str:
    return solidity_compiler_impl(source, compiler_version)

class HarnessOutputSchema(WithToolCallId):
    """
    Used to communicate the results of harness generation, which is the minimal contract to exercise an internal
    function, along with the ABI signature of the method which is the external entry point and the name of the contract.
    Used only for successfully validated (type correct, syntax correct) harnesses.
    """
    source_code: str = \
        Field(description=
              "The self-contained Solidity source code which wraps the provided internal function"
              )

    contract_name: str = \
        Field(description=
              "The name of the Solidity contract containing the external method that wraps the internal function"
              )

    abi_signature: str = \
        Field(description=
              "The ABI signature of the external function generated as the internal function wrapper. "
              "Includes parameter types (but not return types)"
              )


@tool(args_schema=HarnessOutputSchema)
def harness_output(
    source_code: str,
    tool_call_id: Annotated[str, InjectedToolCallId],
    contract_name: str,
    abi_signature: str
) -> Command:
    return tool_output(tool_call_id=tool_call_id, res={"harness_definition": source_code})

HARNESS_TOOLS = [harness_output, solidity_compiler]

harness_system_prompt = load_template("harness_system_prompt.j2").render()
harnessing_prompt = load_template("harnessing_prompt.j2").render()

def generate_harness(harness_llm: BaseChatModel, input_func: str) -> str:
    """Generate harness for the input function."""
    runner: CompiledStateGraph[HarnessingState, None, FlowInput, HarnessedOutput] = build_workflow(
        state_class=HarnessingState,
        input_type=FlowInput,
        tools_list=HARNESS_TOOLS,
        sys_prompt=harness_system_prompt,
        initial_prompt=harnessing_prompt,
        output_key="harness_definition",
        output_schema=HarnessedOutput,
        unbound_llm=harness_llm
    )[0].compile()

    # Generate harness
    return runner.invoke(
        input=FlowInput(input=[input_func])
    )["harness_definition"]
