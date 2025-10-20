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

from typing import Optional, Annotated, List, NotRequired
from typing_extensions import TypedDict

from langchain_core.language_models.chat_models import BaseChatModel
from langgraph.graph.message import add_messages, AnyMessage

from pydantic import BaseModel, Field

from graphcore.graph import WithToolCallId


class HarnessedOutput(TypedDict):
    harness_definition: Optional[str]


class HarnessingState(TypedDict):
    harness_definition: Optional[str]
    messages: Annotated[list[AnyMessage], add_messages]


class RewriteContext(TypedDict):
    llm: BaseChatModel
    original_function: str

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


class RewriterState(TypedDict):
    messages: Annotated[list[AnyMessage], add_messages]
    result: NotRequired[RewriteResultSchema]
    original_harness: str
    curr_rewrite: NotRequired[str]
