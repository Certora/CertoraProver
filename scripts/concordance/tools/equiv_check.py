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

import tempfile
import subprocess
import json
import os
from typing import Annotated

from langgraph.prebuilt import InjectedState
from langchain_core.tools import tool
from pydantic import BaseModel, Field

from concordance.state import RewriterState
from concordance.logger import tool_logger, logger

class EquivalenceCheckerSchema(BaseModel):
    """
    A formal verification tool that is able to compare the behavior of two external methods in two different contracts
    on all possible inputs, and judges whether they have the same side effects.
    A side effect includes: changes to storage, external calls, logs, and returns/reverts.

    If the equivalence checker thinks the external contracts exhibit different behaviors, it will respond with
    a concrete example demonstrating the difference in behaviors. Otherwise it will respond with just 'Equivalent'.

    IMPORTANT: The name of the two contracts containing the external methods *must* be different and the external
    methods *must* have the same ABI signature.

    IMPORTANT: The source code for the "rewrite harness" *MUST* be put onto the VFS before invoking this tool.

    IMPORTANT: The "original harness" is already on the VFS.
    """

    original_harness_name: str = \
        Field(description=
              "The name of the contract defined in the \"original harness\"."
              )

    rewrite_harness_name: str = \
        Field(description=
              "The name of the contract defined in the \"rewrite harness\" stored on the VFS. MUST be different from the value of "
              "`original_harness_name`."
              )

    abi_signature: str = \
        Field(description=
              "The ABI signature (name and parameter types) of the external method to compare between "
              "original_harness_name and rewrite_harness_name. This *SHOULD* be the signature of the external "
              "entry point. For example, for the declaration `function foo(uint[] memory x,uint bar) return (uint)`"
              " the abi_signature is `foo(uint256[],uint256)`"
              )

    compiler_version: str = \
        Field(description=
              "The compiler version string to use for compiling contract1 and contract2. Compiler versions are taken "
              "from the known compiler releases (e.g., 0.8.2), but with the leading '0.' dropped (e.g., 8.2). "
              "For example, to use solc version 0.8.2 this argument should be '8.2'. "
              "When possible, use the latest solidity compiler version, which is currently 8.29"
              )

    loop_bound: int = \
        Field(description=
              "When verifying equivalence of looping code, how many times to unroll the loop for bounded verification. "
              "For performance reasons, this should be set as small as possible while still demonstrating non-trivial "
              "behavior. While values above 3 are supported, performance gets exponentially worse above these values, "
              "and they should be avoided if possible."
              )
    state: Annotated[RewriterState, InjectedState]


@tool(args_schema=EquivalenceCheckerSchema)
def equivalence_check(
    original_harness_name: str,
    rewrite_harness_name: str,
    abi_signature: str,
    loop_bound: int,
    compiler_version: str,
    state: RewriterState
) -> str:
    print("Running the equivalence checker...")

    # Create temporary files - result in current directory, trace anywhere
    with tempfile.NamedTemporaryFile(mode='w', dir=".", suffix='.sol') as f1, \
            tempfile.NamedTemporaryFile(mode='w', dir=".", suffix='.sol') as f2, \
            tempfile.NamedTemporaryFile(mode='w') as trace, \
            tempfile.NamedTemporaryFile(mode='w', dir='.', suffix=".json") as result:

        # Write contract bodies to files
        f1.write(state["original_harness"])
        f1.flush()
        if "curr_rewrite" not in state:
            return "The \"rewrite harness\" is missing from the VFS."
        f2.write(state["curr_rewrite"])
        f2.flush()

        # Build the command
        command = [
            'certoraRun.py',
            f'{f1.name}:{original_harness_name}',
            f'{f2.name}:{rewrite_harness_name}',
            '--equivalence_contracts', f'{original_harness_name}={rewrite_harness_name}',
            '--method', abi_signature,
            '--prover_args', f'-equivalenceCheck true -maxHeuristicFoldingDepth 5 -equivTraceFile {trace.name}',
            '--tool_output', os.path.basename(result.name),
            '--loop_iter', str(loop_bound),
            "--optimistic_hashing",
            "--optimistic_loop",
            '--solc', f"solc{compiler_version}"
        ]

        # Run the command without assuming success
        result_process = subprocess.run(command,
                                        capture_output=True,
                                        text=True,
                                        env={**os.environ, "DONT_USE_VERIFICATION_RESULTS_FOR_EXITCODE": "1"}
                                        )

        # If non-zero exit, just return
        if result_process.returncode != 0:
            logger.debug(f"Failed to run equivalence checker: {result_process.stderr}")
            logger.debug(f"stdout: {result_process.stdout}")
            return f"The equivalence checker failed with returncode {result_process.returncode}. " \
                   "It's possible something in your code wasn't handled. " \
                   "Try a few more times, and then ask for assistance. " \
                   f"The stdout was {result_process.stdout}"

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
