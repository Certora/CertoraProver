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

from typing import List, Annotated

from langgraph.types import Command
from langchain_core.tools import tool, InjectedToolCallId

from graphcore.graph import tool_output

from concordance.state import RewriteResultSchema, ExtraDefinition

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
