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

# ============================================================================
# IMPORTS
# ============================================================================

from langchain_anthropic import ChatAnthropic
from typing import Optional, List, TypedDict, Annotated, Literal, Required, TypeVar, Type, Protocol, Union, Any
from langchain_core.messages import ToolMessage, AnyMessage, SystemMessage, HumanMessage, BaseMessage
from langchain_core.tools import tool, InjectedToolCallId, BaseTool
from langchain_core.language_models.base import LanguageModelInput
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.runnables import Runnable, RunnableConfig
from langgraph.graph import StateGraph, START, MessagesState
from langgraph.graph.state import CompiledStateGraph
from langgraph._internal._typing import StateLike
from langgraph.graph.message import add_messages
from langgraph.types import Command, interrupt
from langgraph.prebuilt import ToolNode
from langgraph.checkpoint.memory import MemorySaver
from pydantic import BaseModel, Field
import os
import tempfile
import json
import subprocess
import sys
import logging
import argparse

# ============================================================================
# LOGGING SETUP
# ============================================================================

logger = logging.getLogger("concordance")
response_logger = logger.getChild("response")
tool_logger = logger.getChild("tools")


# ============================================================================
# SHARED UTILITIES
# ============================================================================


class GraphInput(TypedDict):
    code_input: str


class WithToolCallId(BaseModel):
    tool_call_id: Annotated[str, InjectedToolCallId]


def tool_return(
    tool_call_id: str,
    content: str
) -> Command:
    """
    Create a LangGraph Command for tool responses that need to continue processing.

    Used by tools that want to return a result and continue the workflow by routing
    back to the tool_result node for LLM processing.

    Args:
        tool_call_id: The ID of the tool call being responded to
        content: The response content from the tool execution

    Returns:
        Command that updates messages and continues workflow
    """
    return Command(
        update={
            "messages": [ToolMessage(tool_call_id=tool_call_id, content=content)]
        }
    )


def tool_output(tool_call_id: str, res: dict) -> Command:
    """
    Create a LangGraph Command for final tool outputs that update workflow state.

    Used by completion tools (like harness_output, rewrite_output) to set final
    results in the workflow state. The workflow's conditional edge will detect
    these state updates and route to completion.

    Args:
        tool_call_id: The ID of the tool call being responded to
        res: Dictionary containing the final workflow results to merge into state

    Returns:
        Command that updates state with final results and a success message
    """
    return Command(update={
        **res,
        "messages": [ToolMessage(
            tool_call_id=tool_call_id,
            content="Success"
        )]
    })

def pretty_print_messages(messages: list[AnyMessage]) -> str:
    """Format a list of AnyMessage objects for readable debug output."""
    formatted_lines = []
    for i, msg in enumerate(messages):
        msg_type = type(msg).__name__

        # Get message role if available
        role = getattr(msg, 'role', 'unknown')

        role = getattr(msg, 'type', 'unknown')
        # Get content preview (handle both string and list content)
        if hasattr(msg, 'content') and msg.content:
            if isinstance(msg.content, list):
                # For list content, show count and first item preview
                content_preview = \
                    f"[{len(msg.content)} items: {str(msg.content[0])[:50] if msg.content else 'empty'}...]"
            else:
                content_preview = str(msg.content)[:100]
                if len(str(msg.content)) > 100:
                    content_preview += "..."
        else:
            content_preview = "<empty>"

        # Format tool calls if present
        tool_info = ""
        if tool_calls := getattr(msg, 'tool_calls', None):
            tool_names = [tc.get('name', 'unknown') for tc in tool_calls]
            tool_info = f" | Tools: {', '.join(tool_names)}"

        formatted_lines.append(f"  [{i}] {msg_type} (role: {role}): {content_preview}{tool_info}")

    return "\n" + "\n".join(formatted_lines) if formatted_lines else " <no messages>"


class InitNodeFunction(Protocol):
    """Protocol defining the signature for LangGraph node functions."""
    def __call__(self, state: GraphInput) -> dict[str, List[BaseMessage]]:
        ...


class ChatNodeFunction(Protocol):
    def __call__(self, state: MessagesState) -> dict[str, List[BaseMessage]]:
        ...


def tool_result_generator(llm: Runnable[LanguageModelInput, BaseMessage]) -> ChatNodeFunction:
    """
    Create a LangGraph node function that processes tool results by sending
    the current message history to the LLM for the next response.

    Args:
        llm: The LLM bound with tools to invoke for generating responses

    Returns:
        A node function that takes MessagesState and returns updated messages
    """
    def tool_result(state: MessagesState) -> dict[str, List[BaseMessage]]:
        logger.debug("Tool result state messages:%s", pretty_print_messages(state["messages"]))
        return {"messages": [llm.invoke(state["messages"])]}
    return tool_result

def initial_node(sys_prompt: str, initial_prompt: str, llm: Runnable[LanguageModelInput, BaseMessage]) -> InitNodeFunction:
    """
    Create a LangGraph node function that initializes a workflow with system and human messages,
    then gets the first LLM response.

    Args:
        sys_prompt: System message content to set the LLM's role and context
        initial_prompt: Human message template to start the conversation
        llm: The LLM bound with tools to invoke for generating the initial response

    Returns:
        A node function that takes GraphInput and returns initial message history
    """
    def to_return(state: GraphInput) -> dict[str, List[BaseMessage]]:
        initial_messages : List[BaseMessage] = [
            SystemMessage(
                sys_prompt
            ),
            HumanMessage(
                content=[initial_prompt, state["code_input"]]
            )
        ]
        initial_messages.append(
            llm.invoke(initial_messages)
        )
        return {"messages": initial_messages}
    return to_return


# TypeVars for generic typing
StateT = TypeVar('StateT', bound=StateLike)
OutputT = TypeVar('OutputT', bound=StateLike)


def build_workflow(
    state_class: Type[StateT],
    tools_list: List[BaseTool],
    sys_prompt: str,
    initial_prompt: str,
    output_key: str,
    unbound_llm: BaseChatModel,
    output_schema: Optional[Type[OutputT]] = None,
) -> StateGraph[StateT, None, GraphInput, OutputT]:
    """
    Build a standard workflow with initial node -> tools -> tool_result pattern.
    Uses fixed GraphInput schema and explicit LLM currying.
    """
    # Node name constants
    INITIAL_NODE = "initial"
    TOOLS_NODE = "tools"
    TOOL_RESULT_NODE = "tool_result"

    def should_end(state: StateT) -> Literal["__end__", "tool_result"]:
        """Check if workflow should end based on output key being defined."""
        assert isinstance(state, dict)
        if state.get(output_key, None) is not None:
            return "__end__"
        return TOOL_RESULT_NODE

    llm = unbound_llm.bind_tools(tools_list)

    # Create initial node and tool node with curried LLM
    init_node = initial_node(sys_prompt=sys_prompt, initial_prompt=initial_prompt, llm=llm)
    tool_node = ToolNode(tools_list)
    tool_result_node = tool_result_generator(llm)

    # Build the graph with fixed input schema, no context
    builder = StateGraph(
        state_class,
        input_schema=GraphInput,
        output_schema=output_schema
    )
    builder.add_node(INITIAL_NODE, init_node)
    builder.add_edge(START, INITIAL_NODE)
    builder.add_node(TOOLS_NODE, tool_node)
    builder.add_edge(INITIAL_NODE, TOOLS_NODE)
    builder.add_node(TOOL_RESULT_NODE, tool_result_node)
    builder.add_edge(TOOL_RESULT_NODE, TOOLS_NODE)

    # Add conditional edge from tools
    builder.add_conditional_edges(
        TOOLS_NODE,
        should_end
    )

    return builder


# ============================================================================
# SOLIDITY COMPILER TOOL (SHARED)
# ============================================================================


class SolidityCompilerInput(BaseModel):
    """
    A Solidity compiler capable of compiling a single, Solidity file into EVM bytecode. The compiler
    also performs typechecking and will flag any syntax errors. The compiler comes from the official
    distribution channels for Solidity and understands all the Solidity language and features.
    """
    compiler_version: str = \
        Field(description=
              "The compiler version string to use for compilation. Compiler versions are taken from the known compiler "
              "releases (e.g., 0.8.2), but with the leading '0.' dropped (e.g., 8.2)."
              )

    source: str = Field(description="The Solidity source to be compiled")


@tool(args_schema=SolidityCompilerInput)
def solidity_compiler(source: str, compiler_version: str) -> str:
    compiler_input = {
        "language": "Solidity",
        "sources": {
            "harness.sol": {
                "content": source
            }
        },
        "settings": {
            "outputSelection": {
                "*": {
                    "*": []
                }
            }
        }
    }
    compile_result = subprocess.run(
        [f'solc{compiler_version}', "--standard-json"],
        input=json.dumps(compiler_input),
        text=True,
        encoding="utf-8",
        capture_output=True
    )
    res = f"Return code was: {compile_result.returncode}\nStdout:\n{compile_result.stdout}"
    return res


# ============================================================================
# HARNESSING WORKFLOW
# ============================================================================


harness_system_prompt = """
You are an expert Solidity developer, with several years of experience writing smart contracts. You also
have a deep understanding of the EVM and how the Solidity language is ultimately compiled to the EVM bytecode.
This lets you understand why certain programs written in Solidity language are invalid and rejected by the
compiler. For example, you know that an `external` function in a `contract` cannot accept a reference type
marked as `storage`: you know that this is only allowed in a `library` which is always accessed with a delegatecall.

You also understand the subtleties around ABI encoding and decoding, and the translation of high-level types to
an ABI signature. For example, you know that a struct with two uint fields is represented in an ABI signature as
`(uint256,uint256)`.
"""

harnessing_prompt = """
Create an external 'harness contract' which provides a minimal way to execute the given 'internal' Solidity function
via an external function wrapper.
The external function wrapper should simply pass its arguments to the internal function, and return the result back
to the caller. The internal function being harnessed
should be included in the contract. You MAY include extra type definitions, but only if absolutely necessary for the
code to compile; definitions solely for documentation or explanation
purposes should NOT be included."
The external harness should be type correct and syntax correct. To ensure this, use the Solidity compiler and
incorporate its feedback to fix any type/syntax errors.
"""


class HarnessedOutput(TypedDict):
    harness_definition: Optional[str]


class HarnessingState(TypedDict):
    GraphInput: str
    harness_definition: Optional[str]
    messages: Annotated[list[AnyMessage], add_messages]


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
def harness_output(source_code: str, tool_call_id: Annotated[str, InjectedToolCallId], contract_name: str, abi_signature: str) -> Command:
    return tool_output(tool_call_id=tool_call_id, res={"harness_definition": source_code})


# Harness workflow setup
HARNESS_TOOLS = [harness_output, solidity_compiler]

# ============================================================================
# REWRITE WORKFLOW
# ============================================================================

simplification_system_prompt = """
You are an expert Solidity developer with several years of experience writing smart contracts and
optimizing them for gas costs. You know all about low-level assembly and how to use it to directly
access EVM internal representations to optimize execution. This means you are also familiar with how
the EVM works on a deep, fundamental level. You are also intimately familiar with how Solidity
lays out memory, and its allocation pattern. Among other facts, you know that it uses a bump allocator
with a free pointer whose value resides in memory at slot 0x40. You also know that memory in the range 0x0 - 0x40
is considered "scratch" and is freely usable, as is all memory after the current value of the free pointer. You
know that arrays are allocated to include an extra word at the beginning of the allocated block to hold the length
of the memory, followed by elements of the array. `bytes` and `string` arrays pack their elements tightly (1 byte
per element), whereas all other arrays use 32 bytes per element.

You also hold a PhD in static analysis, and are an expert in the field of points to analyses and memory
safety analyses. You help maintain a static analysis which attempts to recover the pointer relationships
between stack values and memory locations in compiled EVM bytecode. For soundness, this analysis
must be able to prove that every access to memory is either in the scratch areas OR said access can be
attributed to a field of some object. Accesses to memory which cannot be proven to satisfy one of these two conditions
cause the entire analysis to fail. The analysis is partially path sensitive, and can understand that
`i < array.length` means that `i` is a valid index into `array`. The analysis uses these facts to prove
accesses are safe AND which object's fields are being accessed by each memory operation.
"""

rewriting_prompt = """
<context>
The following contract "harnesses" a problematic internal function which causes a pointer analysis on EVM bytecode to fail.
This may be due to non-standard operations on memory that occcurs in memory blocks, or due to imprecision in the
pointer analysis.
</context>

<task>
Rewrite the *internal* function so that it is semantically equivalent but is more amenable to static analysis.
Common problems include:
  - Inline assembly with direct memory manipulation
  - Unchecked array/memory access
  - Pointer arithmetic that the analyzer cannot track
  - Non-standard memory layout assumptions
Your rewrite should satisfy the following constraints:
 - It must be semantically equivalent to the original function.
 - Wherever possible, eschew the use of inline assembly in favor of more straightforward, standard Solidity
 - You may ignore the gas implications of any code you write: code that is accepted by the pointer analysis is
   preferable to gas efficient code. However, you should consider that the original code may by optimized for gas
   consumption, which should inform your understanding of its intent
"Semantic equivalence" means the following:
- Functions produce the same return value
- The functions have exactly the same observable effects. These external effects are:
  - Reverting (including the revert data)
  - EVM level returns (that is, the return opcode)
  - External calls
  - Changes to storage
  - emitted logs/events

In other words, if the original function reverts, the rewritten function must also revert with
the same buffer.
For the purposes of this rewrite, you can ignore the possibility of out-of-gas exceptions.
Similarly, the rewrite must emit the same log events (if any) and in the same order.
The rewrite must also make the same external calls, and make the same modifications to storage.
However, remember that if both functions revert, any other side effects (external calls, storage changes, etc.)
are mooted.
</task>

<algorithm>
   <input>An "original harness" around a "problematic internal function"</input>
   <output>The rewritten "better function"</output>
   <steps>
   1. Analyze the "problematic internal function" in the "original harness" to understand its behavior.
      Pay close attention to its revert conditions and side effects
   2. Generate a rewrite of the internal function called the "better function", which uses straight-forward
      solidity while preserving equivalence to the "problematic internal function"
      a. Keep track of and remember any extra definitions required for this "better function" rewrite.
   3. Adapt the "original harness" into a "rewrite harness" by replacing the "problematic internal function" with the
      "better function" generated in step 2 and changing the name of the "original harness" contract.
      Incorporate any definitions generated by step 2.a
   4. Check that the "rewrite harness" is type correct and syntax correct using the solidity compiler
   5. Check that the "rewrite harness" and "original harness" are semantically equivalent using the equivalence checker.
   6. Interpret the results of the equivalence checker:
      a. If the result is 'Equivalent', then go to step 7
      b. Otherwise, examine the explanation provided by the equivalence checker for why the two functions are not
         equivalent. Incorporating this feedback, adjust the definition of "better function" within the
         "rewrite harness", and go to step 5.
   7. Output the definition of the "better function" along with any of the extra definitions that are necessary.
   </steps>
</algorithm>

<guidance>
   <important>
     When invoking the equivalence checker, you *may not* change the external entry point of
     either the "original harness" or the "rewrite harness"
   </important>
   <important>
     You *MAY NOT* change the "original harness" in any way: you must pass it to the equivalence checker without
     modification.
   </important>
   <important>
     The task is complete only when the equivalence checker says the implementations are 'Equivalent'
   </important>
   <soft_requirement>4
     You should *not* add additional error/interface declarations unless absolutely necessary
     for your rewrite to compile.
   </soft_requirement>
   <soft_requirement>
     Inline assembly should be absolutely avoided unless you have no other option to preserve semantic equivalence.
     If you have no choice but to use inline assembly, the inline assembly should hew as closely as possible to
     standard Solidity memory access patterns; array accesses should be "guarded" by length checks, and so on.
   </soft_requirement>
   <reminder>
     When adapting the "original harness" to check equivalence, you **should** change the name of the harnessing
     contract.
   </reminder>
   <tool_advice>
      You **should** check that your rewrite harness is type and syntax correct using the solidity compiler.
   </tool_advice>
   <tool_advice>
      You are an automated tool, and should only use the the human_in_the_loop tool as a last resort to get "unstuck".
      Be sure to iterate on a particular issue a few times before asking the user for help.
    </tool_advice>
</guidance>
"""


class EquivalenceCheckerSchema(BaseModel):
    """
    A formal verification tool that is able to compare the behavior of two external methods in two different contracts
    on all possible inputs, and judges whether they have the same side effects.
    A side effect includes: changes to storage, external calls, logs, and returns/reverts.

    If the equivalence checker thinks the external contracts exhibit different behaviors, it will respond with
    a concrete example demonstrating the difference in behaviors. Otherwise it will respond with just 'Equivalent'.

    IMPORTANT: The name of the two contracts containing the external methods *must* be different and the external
    methods *must* have the same ABI signature.
    """

    contract1: str = \
        Field(description=
              "Solidity source code of the first contract to compare for equivalence. This source code must be s"
              "elf-contained, and must be compilable with a standard solidity compiler. It must be type correct and "
              "syntactically correct."
              )

    contract1_name: str = \
        Field(description=
              "The name of the contract defined in the `contract1` param. For example, if `contract1` contains the "
              "source `contract Foo { ... }` this parameter should be `Foo`"
              )

    contract2: str = \
        Field(description=
              "Solidity source code of the second contract to compare for equivalence. The source code must be "
              "self-contained, and must be compilable with a standard solidity compiler. It must therefore be type "
              "correct and syntactically correct."
              )

    contract2_name: str = \
        Field(description=
              "The name of the contract defined in the `contract2` param. MUST be different from the value of "
              "`contract1-name`. For example, if `contract2` contains the source code "
              "`contract Bar { ... }` this parameter should be `Bar`."
              )

    abi_signature: str = \
        Field(description=
              "The ABI signature (name and parameter types) of the external method to compare between "
              "contract1 and contract2"
              )

    compiler_version: str = \
        Field(description=
              "The compiler version string to use for compiling contract1 and contract2. Compiler versions are taken "
              "from the known compiler releases (e.g., 0.8.2), but with the leading '0.' dropped (e.g., 8.2)."
              )

    loop_bound: int = \
        Field(description=
              "When verifying equivalence of looping code, how many times to unroll the loop for bounded verification. "
              "For performance reasons, this should be set as small as possible while still demonstrating non-trivial "
              "behavior. While values above 3 are supported, performance gets exponentially worse above these values, "
              "and they should be avoided if possible."
              )


@tool(args_schema=EquivalenceCheckerSchema)
def equivalence_check(
    contract1: str,
    contract1_name: str,
    contract2: str,
    contract2_name: str,
    abi_signature: str,
    loop_bound: int,
    compiler_version: str
) -> str:
    print("Running the equivalence checker...")

    # Create temporary files - result in current directory, trace anywhere
    with tempfile.NamedTemporaryFile(mode='w', dir=".", suffix='.sol') as f1, \
            tempfile.NamedTemporaryFile(mode='w', dir=".", suffix='.sol') as f2, \
            tempfile.NamedTemporaryFile(mode='w') as trace, \
            tempfile.NamedTemporaryFile(mode='w', dir='.', suffix=".json") as result:

        # Write contract bodies to files
        f1.write(contract1)
        f1.flush()

        f2.write(contract2)
        f2.flush()

        # Build the command
        command = [
            'certoraRun.py',
            f'{f1.name}:{contract1_name}',
            f'{f2.name}:{contract2_name}',
            '--equivalence_contracts', f'{contract1_name}={contract2_name}',
            '--method', abi_signature,
            '--prover_args', f'-equivalenceCheck true -maxHeuristicFoldingDepth 5 -equivTraceFile {trace.name}',
            '--tool_output', os.path.basename(result.name),
            '--loop_iter', str(loop_bound),
            "--optimistic_hashing",
            "--optimistic_loop",
            '--solc', 'solc8.29'
        ]

        # Run the command without assuming success
        result_process = subprocess.run(command,
                                        capture_output=True,
                                        text=True,
                                        env={**os.environ, "DONT_USE_VERIFICATION_RESULTS_FOR_EXITCODE": "1"}
                                        )

        # If non-zero exit, just return
        if result_process.returncode != 0:
            return f"The equivalence checker failed with returncode {result_process.returncode}. " \
                   "It's possible something in your code wasn't handled. " \
                   "Try a few more times, and then ask for assistance"

        # Load and parse result JSON
        with open(result.name, 'r') as result_file:
            result_data = json.load(result_file)

        # Extract the rules dictionary
        rules_dict = result_data['rules']

        # Get the single key-value pair (since it's a singleton)
        _, rule_value = next(iter(rules_dict.items()))

        # Check if SUCCESS
        if rule_value == "SUCCESS":
            print("Equivalence check passed")
            return "Equivalent"
        else:
            print("Divergent behavior found; returning for refinement")
            # Read and return trace contents
            with open(trace.name, 'r') as trace_file:
                to_return = trace_file.read()
                tool_logger.info("Trace was:\n%s", to_return)
                return to_return


class ExtraDefinition(BaseModel):
    definition: str = \
        Field(description=
              "A snippet of Solidity that defines some type/error/interface etc. that is needed for the rewrite to work"
              )

    where: str = \
        Field(description=
              "Human readable description of where this definition should be placed. If there is no strong "
              "guidance/requirement for where the definition lives, 'Nearby' is an acceptable answer"
              )

    justification: str = \
        Field(description=
              "Explanation for why this additional definition is necessary."
              )


class RewriteResultSchema(WithToolCallId):
    """
    Used to communicate the successful rewrite to the client. Should only be invoked once the problematic rewritten function has been
    successfully validated using the equivalence checker; that is, it has returned "Equivalent".
    """
    rewrite: str = \
        Field(description=
              "The validated; rewritten function. Should consist only of the internal function definition; "
              "the surrounding external harness should NOT be included."
              )

    extra_definitions: List[ExtraDefinition] = \
        Field(description="Any extra definitions that are necessary for the rewrite.")

    remarks: str = \
        Field(description=
              "Any explanation of the rewrite. In particular, be sure to justify the use of any inline assembly or "
              "extra type definitions included"
              )


@tool(args_schema=RewriteResultSchema)
def rewrite_output(rewrite: str, extra_definitions: List[ExtraDefinition], remarks: str,
                   tool_call_id: Annotated[str, InjectedToolCallId]) -> Command:
    return tool_output(
        tool_call_id=tool_call_id,
        res={
            "result": RewriteResultSchema(
                tool_call_id=tool_call_id,
                extra_definitions=extra_definitions,
                remarks=remarks,
                rewrite=rewrite
            )
        }
    )


class HumanInTheLoopSchema(WithToolCallId):
    """
    A tool that allows the LLM agent to request human assistance when encountering divergent behaviors
    during the rewriting process. This tool should be used when the equivalence checker reports
    differences between the original and rewritten functions that the agent cannot resolve automatically.
    """
    question: str = Field(description="The specific question or problem the agent needs help with")

    context: str = \
        Field(description=
              "Relevant context about the divergent behavior, including equivalence checker output, "
              "and what has been tried before (and what didn't work)"
              )

    original_function: str = Field(description="The original problematic function being rewritten")
    attempted_rewrite: str = Field(description="The current attempted rewrite that shows divergent behavior")


@tool(args_schema=HumanInTheLoopSchema)
def human_in_the_loop(
    question: str,
    context: str,
    original_function: str,
    attempted_rewrite: str,
    tool_call_id: Annotated[str, InjectedToolCallId]
) -> Command[Literal["tool_result", "error"]]:
    """
    Request human assistance to resolve divergent behaviors during rewriting.
    """
    # Use LangGraph's interrupt mechanism to pause execution and request human input
    human_guidance = interrupt({
        "question": question,
        "context": context,
        "original_function": original_function,
        "attempted_rewrite": attempted_rewrite
    })

    return tool_return(
        tool_call_id=tool_call_id,
        content=f"Human guidance: {human_guidance}"
    )


class ToolError(TypedDict, total=False):
    error_message: Required[str]
    tool_stdout: str
    tool_stderr: str


class RewriterState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    code_input: str
    error: Optional[ToolError]
    result: Optional[RewriteResultSchema]


# Rewrite workflow setup
rewrite_tools = [
    rewrite_output,
    solidity_compiler,
    equivalence_check,
    human_in_the_loop
]


# ============================================================================
# APPLICATION ORCHESTRATION AND CLI INTERFACE
# ============================================================================

def setup_argument_parser() -> argparse.ArgumentParser:
    """Configure command line argument parser."""
    parser = argparse.ArgumentParser(description="Certora Concordance Tool for Solidity Function Rewriting")
    parser.add_argument("input_file", help="Input Solidity file containing the function to process")
    parser.add_argument("--harness-model", default="claude-sonnet-4-20250514",
                        help="Model to use for harness generation (default: claude-sonnet-4-20250514)")
    parser.add_argument("--rewrite-model", default="claude-opus-4-20250514",
                        help="Model to use for function rewriting (default: claude-opus-4-20250514)")
    parser.add_argument("--harness-tokens", type=int, default=1024,
                        help="Token budget for harness generation (default: 1024)")
    parser.add_argument("--rewrite-tokens", type=int, default=4096,
                        help="Token budget for function rewriting (default: 4096)")
    parser.add_argument("--thinking-tokens", type=int, default=2048,
                        help="Token budget for thinking in rewriting (default: 2048)")
    parser.add_argument("--debug", action="store_true",
                        help="Enable debug logging output")
    return parser


def setup_logging(debug: bool) -> None:
    """Configure logging based on debug flag."""
    if debug:
        logger.setLevel(logging.DEBUG)
        if not logger.handlers:
            handler = logging.StreamHandler()
            handler.setFormatter(logging.Formatter('%(asctime)s - %(name)s - %(levelname)s - %(message)s'))
            logger.addHandler(handler)


def create_harness_llm(args: argparse.Namespace) -> BaseChatModel:
    """Create and configure the harness generation LLM."""
    return ChatAnthropic(
        model_name=args.harness_model,
        max_tokens_to_sample=args.harness_tokens,
        temperature=0,
        timeout=None,
        max_retries=2,
        stop=None
    )


def create_rewrite_llm(args: argparse.Namespace) -> BaseChatModel:
    """Create and configure the rewrite LLM."""
    return ChatAnthropic(
        model_name=args.rewrite_model,
        max_tokens_to_sample=args.rewrite_tokens,
        temperature=1,
        timeout=None,
        max_retries=2,
        stop=None,
        thinking={"type": "enabled", "budget_tokens": args.thinking_tokens}
    )


def generate_harness(harness_llm: BaseChatModel, input_file: str) -> str:
    """Generate harness for the input function."""
    runner = build_workflow(
        state_class=HarnessingState,
        tools_list=HARNESS_TOOLS,
        sys_prompt=harness_system_prompt,
        initial_prompt=harnessing_prompt,
        output_key="harness_definition",
        output_schema=HarnessedOutput,
        unbound_llm=harness_llm
    ).compile()

    # Read input file
    with open(input_file, "r") as f:
        f_def = f.read()

    # Generate harness
    return runner.invoke(
        input=GraphInput(code_input=f_def),
    )["harness_definition"]

def handle_human_interrupt(interrupt_data: dict) -> str:
    """Handle human-in-the-loop interrupts and get user input."""
    print("\n" + "=" * 80)
    print("HUMAN ASSISTANCE REQUESTED")
    print("=" * 80)
    print(f"Question: {interrupt_data.get('question', 'N/A')}")
    print(f"Context: {interrupt_data.get('context', 'N/A')}")
    print(f"Original Function:\n{interrupt_data.get('original_function', 'N/A')}")
    print(f"Attempted Rewrite:\n{interrupt_data.get('attempted_rewrite', 'N/A')}")
    print("-" * 80)
    return input("Please provide guidance: ")

def display_rewrite_result(result: RewriteResultSchema) -> None:
    """Display the final rewrite results to the user."""
    print("\n" + "=" * 80)
    print("REWRITE COMPLETED")
    print("=" * 80)
    print(f"Rewritten Function:\n{result.rewrite}")

    # Format extra definitions nicely
    if result.extra_definitions:
        print("\nExtra Definitions:")
        for i, extra_def in enumerate(result.extra_definitions, 1):
            print(f"  {i}. {extra_def.definition}")
            print(f"     Where: {extra_def.where}")
            print(f"     Justification: {extra_def.justification}")
            if i < len(result.extra_definitions):  # Add spacing between definitions
                print()

    print(f"\nRemarks: {result.remarks}")

def execute_rewrite_workflow(rewrite_llm: BaseChatModel, harness: str) -> int:
    """Execute the rewrite workflow with interrupt handling."""
    # Add checkpointer for interrupt functionality
    checkpointer = MemorySaver()
    rewriter_exec: CompiledStateGraph[RewriterState, None, GraphInput, Any] = build_workflow(
        state_class=RewriterState,
        tools_list=rewrite_tools,
        sys_prompt=simplification_system_prompt,
        initial_prompt=rewriting_prompt,
        output_key="result",
        unbound_llm=rewrite_llm
    ).compile(checkpointer=checkpointer)

    # Execute rewrite workflow with interrupt handling
    thread_id = "rewrite_session"
    config: RunnableConfig = {"configurable": {"thread_id": thread_id}}

    # Start with initial input
    current_input: Union[None, Command, GraphInput] = GraphInput(code_input=harness)

    while True:
        assert current_input is not None
        # Stream execution
        interrupted = False
        r = current_input
        current_input = None
        for event in rewriter_exec.stream(input=r, config=config):
            logger.debug("Stream event: %s", event)

            # Check if we hit an interrupt
            if "__interrupt__" in event:
                interrupt_data = event["__interrupt__"][0].value
                human_response = handle_human_interrupt(interrupt_data)

                # Set up for resumption
                current_input = Command(resume=human_response)
                interrupted = True
                break

        # If we were interrupted, continue the loop to resume
        if interrupted:
            continue

        state = rewriter_exec.get_state(config)
        result = state.values.get("result", None)
        if result is None or not isinstance(result, RewriteResultSchema):
            return 1

        display_rewrite_result(result)
        return 0  # Success

def main() -> int:
    """Main entry point for the concordance tool."""
    parser = setup_argument_parser()
    args = parser.parse_args()

    setup_logging(args.debug)

    # Create configured LLMs
    harness_llm = create_harness_llm(args)
    rewrite_llm = create_rewrite_llm(args)

    # Generate harness
    harness = generate_harness(harness_llm, args.input_file)

    # Execute rewrite workflow
    return execute_rewrite_workflow(rewrite_llm, harness)

if __name__ == "__main__":
    sys.exit(main())
