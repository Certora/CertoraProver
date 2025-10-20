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

from langgraph.types import Command, interrupt
from langchain_core.tools import tool, InjectedToolCallId
from pydantic import Field

from graphcore.graph import tool_return, WithToolCallId

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
) -> Command:
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
