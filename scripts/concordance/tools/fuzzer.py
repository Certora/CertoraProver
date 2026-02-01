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
import tempfile
import pathlib
import subprocess

from langgraph.runtime import get_runtime
from langgraph.prebuilt import InjectedState
from langchain_core.messages import HumanMessage, AIMessage
from langchain_core.tools import tool
from pydantic import BaseModel, Field

from concordance.templates.loader import load_template
from concordance.state import RewriteContext, RewriterState


class DifferentialFuzzTester(BaseModel):
    """
    Do a lightweight check for equivalence by performing differential fuzz testing on the original and rewrite
    harnesses.

    This tool is only appropriate if the functions definitely DO NOT make external calls. In the presence
    of external calls, it is likely to return false positives, and should not be used.

    In addition, like all fuzz testing, the success of the differential fuzz testing is NOT a guarantee the programs are equivalent.
    However, a failure in the fuzz testing is a gaurantee of non-equivalence.
    """
    original_harness_name: str = Field(description="The name of the \"original harness\" contract")
    rewrite_harness_name: str = Field(description="The name of the \"rewrite harness\" on the VFS")
    abi_signature: str = Field(description="The ABI signature of the external entry point used by both harnesses.")
    internal_param_types: list[str] = \
        Field(description="The types of the internal function parameters "
              "in the rewrite and original functions, in their declaration order. IMPORTANT: for aggregate types, "
              "the types must include the data location; e.g., `bytes memory`. Do NOT include the parameter name, just the type.")

    state: Annotated[RewriterState, InjectedState]


@tool(args_schema=DifferentialFuzzTester)
def differential_fuzz_tester(
    original_harness_name: str,
    rewrite_harness_name: str,
    abi_signature: str,
    internal_param_types: list[str],
    state: Annotated[RewriterState, InjectedState]
) -> str:
    """
    Run differential fuzz testing between original and rewritten harness contracts.

    Args:
        original_harness_name: Name of the original harness contract
        rewrite_harness_name: Name of the rewrite harness contract on the VFS
        abi_signature: ABI signature of the external entry point used by both harnesses
        internal_param_types: List of internal function parameter types in declaration order
        state: Current rewriter state containing harness code

    Returns:
        Status message indicating test results or error message if rewrite is missing from VFS
    """
    if "curr_rewrite" not in state:
        return "Current rewrite missing from the VFS: please add it using put_file."

    ctx = get_runtime(RewriteContext)
    build_buffer = generate_adapter_harness(original_harness_name, abi_signature, ctx.context)
    if build_buffer is None:
        return "The fuzz tester failed to run, proceed to next step."
    return build_and_run_fuzz_test(original_harness_name, rewrite_harness_name, internal_param_types, state, build_buffer)

def build_and_run_fuzz_test(original_harness_name: str, rewrite_harness_name: str, internal_param_types: list[str], state: RewriterState, build_buffer: str) -> str:
    """
    Build and execute a Foundry-based differential fuzz test in a temporary directory.

    Creates a temporary Foundry project with the original and rewrite harnesses,
    generates a differential test, and runs it using forge test.

    Args:
        original_harness_name: Name of the original harness contract
        rewrite_harness_name: Name of the rewrite harness contract
        internal_param_types: List of internal function parameter types
        state: Rewriter state containing harness code
        build_buffer: Generated adapter harness code for building test input

    Returns:
        Formatted string containing forge test results including return code, stdout, and stderr
    """
    diff_tester = build_fuzz_test(original_harness_name, rewrite_harness_name, internal_param_types, build_buffer)
    assert "curr_rewrite" in state
    with tempfile.TemporaryDirectory() as working_dir:
        dir_path = pathlib.Path(working_dir)
        with open(dir_path / "foundry.toml", 'wt') as toml:
            toml.write("""
[profile.default]
src = "src"
out = "out"
libs = ["lib"]
            """)
        test_path = (dir_path / "test")
        test_path.mkdir(exist_ok=True, parents=True)
        (test_path / "DiffTest.t.sol").write_text(diff_tester)

        src_path = (dir_path / "src")
        src_path.mkdir(exist_ok=True, parents=True)
        (src_path / "Orig.sol").write_text(state["original_harness"])
        (src_path / "Rewrite.sol").write_text(state["curr_rewrite"])
        lib_dir = (dir_path / "lib")
        lib_dir.mkdir(parents = True, exist_ok = True)
        subprocess.run([
            "git", "clone", "--branch", "v1.10.0", "https://github.com/foundry-rs/forge-std", "forge-std",
        ], cwd=str(lib_dir), capture_output=True)

        res = subprocess.run([
            "forge", "test"
        ], cwd=str(working_dir), capture_output=True)
        return f"""
Forge exited with returncode: {res.returncode},
Stdout:
{res.stdout.decode("utf-8")}
Stderr:
{res.stderr.decode("utf-8")}
"""

def build_fuzz_test(original_harness_name: str, rewrite_harness_name: str, internal_param_types: list[str], build_buffer: str) -> str:
    """
    Generate a Solidity differential fuzz test contract from a template.

    Creates parameter declarations and function call arguments from the internal parameter types,
    then renders the DiffTest template with the appropriate values.

    Args:
        original_harness_name: Name of the original harness contract
        rewrite_harness_name: Name of the rewrite harness contract
        internal_param_types: List of internal function parameter types
        build_buffer: Adapter harness code for building test input

    Returns:
        Rendered Solidity test contract code
    """
    param_type_and_names = [(ty, f"ty{i}") for ty, i in enumerate(internal_param_types)]
    param_decl = ",\n".join([
        f"{ty} {p}" for (ty, p) in param_type_and_names
    ])
    args = ", ".join([
        p for (_, p) in param_type_and_names
    ])
    build_buffer_call = f"buildBuffer({args})"
    diff_tester = load_template("DiffTest.t.sol.j2").render(
        build_buffer_call=build_buffer_call,
        build_buffer=build_buffer,
        orig_name=original_harness_name,
        rewrite_name=rewrite_harness_name,
        args=param_decl
    )

    return diff_tester

def generate_adapter_harness(original_harness_name: str, abi_signature: str, ctx: RewriteContext) -> str | None:
    """
    Generate an adapter harness using an LLM to convert function parameters to ABI-encoded input.

    Prompts an LLM to generate Solidity code that builds an ABI-encoded buffer from the internal
    function parameters. Attempts up to 2 times and handles markdown code fence removal.

    Args:
        original_harness_name: Name of the original harness contract
        abi_signature: ABI signature of the function to adapt
        ctx: Rewrite context containing the original function code and LLM instance

    Returns:
        Generated adapter harness code, or None if generation fails after 2 attempts
    """
    adapater_prompt = load_template("adapter_prompt.j2").render(
        code=ctx["original_function"],
        sig=abi_signature,
        harness=original_harness_name
    )
    llm = ctx["llm"]
    attempts = 0
    while attempts < 2:
        res = llm.invoke([
            HumanMessage(content=adapater_prompt)
        ])
        assert isinstance(res, AIMessage)
        code = res.text
        if code:
            # despite my *insistent* urgings in the prompt, claude will still include markdown
            # just strip it off...
            bad_start = "```solidity"
            if code.startswith(bad_start):
                code = code[len(bad_start):]
            if code.endswith("```"):
                code = code[:-3]
            if "```" not in code:
                return code
            break
        attempts += 1
    return None
