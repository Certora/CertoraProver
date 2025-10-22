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

import uuid
import argparse
import sqlite3
from typing import Any, Union

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.runnables import RunnableConfig
from langgraph.types import Command, Checkpointer
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.checkpoint.memory import MemorySaver
from langgraph.graph import StateGraph

from graphcore.graph import FlowInput, build_workflow

from concordance.tools import *  # noqa: F403
from concordance.state import RewriteResultSchema, RewriteContext, RewriterState
from concordance.logger import logger
from concordance.templates import load_template

class InputState(FlowInput):
    original_harness: str

# define by above start import, flake8 sux
rewrite_tools = [rewrite_output, equivalence_check, human_in_the_loop, qualitative_feedback_tool,  # noqa: F405
                 vfs_solidity_compiler, put_file, differential_fuzz_tester]  # noqa: F405

simplification_system_prompt = load_template("simplification_system_prompt.j2").render()
rewriting_prompt = load_template("rewrite_prompt.j2").render()


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

def execute_rewrite_workflow(
    rewrite_llm: BaseChatModel,
    harness: str,
    args: argparse.Namespace,
    original_func: str
) -> int:
    """Execute the rewrite workflow with interrupt handling."""
    # Add checkpointer for interrupt functionality
    checkpointer: Checkpointer
    checkpointer = MemorySaver()
    if args.db is not None:
        sqlite = sqlite3.connect(args.db, check_same_thread=False)
        checkpointer = SqliteSaver(sqlite)

    print("Calling LLM...")
    to_compile : StateGraph[RewriterState, RewriteContext, InputState, Any]
    (to_compile, _) = build_workflow(
        state_class=RewriterState,
        input_type=InputState,
        tools_list=rewrite_tools,
        sys_prompt=simplification_system_prompt,
        initial_prompt=rewriting_prompt,
        output_key="result",
        unbound_llm=rewrite_llm,
        context_schema=RewriteContext
    )

    rewriter_exec = to_compile.compile(checkpointer=checkpointer)

    context : RewriteContext = {
        "llm": rewrite_llm,
        "original_function": original_func
    }

    # Execute rewrite workflow with interrupt handling
    thread_id: str
    if args.thread_id is not None:
        thread_id = args.thread_id
    else:
        thread_id = f"rewrite_session_{uuid.uuid1().hex}"
        print(f"Using thread id {thread_id}")

    config: RunnableConfig = {"configurable": {"thread_id": thread_id}}
    # Start with initial input
    current_input: Union[None, Command, InputState] = InputState(
        input=[harness],
        original_harness=harness
    )

    if args.checkpoint_id is not None:
        config["configurable"]["checkpoint_id"] = args.checkpoint_id
        current_input = None

    while True:
        # Stream execution
        interrupted = False
        r = current_input
        current_input = None
        for (ty, event) in rewriter_exec.stream(input=r, config=config, stream_mode=["updates", "checkpoints"], context=context):
            if ty == "checkpoints":
                assert isinstance(event, dict)
                print("current checkpoint: " + event["config"]["configurable"]["checkpoint_id"])
                continue

            logger.debug("Stream event: %s", event)

            # Check if we hit an interrupt
            if "__interrupt__" in event:
                assert isinstance(event, dict)
                del config["configurable"]["checkpoint_id"]
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
