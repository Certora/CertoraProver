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
from langchain_core.tools import tool, InjectedToolCallId
from pydantic import Field

from graphcore.graph import tool_output, WithToolCallId

class PutFile(WithToolCallId):
    """
    Put or update the "rewrite harness" on the VFS.
    """

    rewrite_harness: str = \
        Field(description='The Solidity source code of the "rewrite harness" contract to compare for equivalence. The source code must be '
              "self-contained, and must be compilable with a standard solidity compiler.")

@tool(args_schema=PutFile)
def put_file(
    rewrite_harness: str,
    tool_call_id: Annotated[str, InjectedToolCallId],
) -> Command:
    return tool_output(
        tool_call_id=tool_call_id,
        res={
            "curr_rewrite": rewrite_harness
        }
    )
