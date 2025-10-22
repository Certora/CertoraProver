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

from typing import Literal, Annotated

from langgraph.runtime import get_runtime
from langgraph.prebuilt import InjectedState
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.tools import tool
from pydantic import BaseModel, Field

from concordance.state import RewriterState, RewriteContext
from concordance.templates import load_template

class QualitativeFeedbackArgSchema(BaseModel):
    """
    Invoke this tool to receive qualitative feedback on the rewrite harness once equivalence checking has passed.

    This tool will return an XML document with the following structure:
    ```
    <result>$status</result>
    <feedback>$feedback</feedback>
    ```

    where $status is one of "REJECTED", "ACCEPTED", or "ADVICE" describing the overall result of the feedback.
    "REJECTED" means there was a qualitative problem with the code; i.e., it somehow did not conform to the requirements
    of the prompt while still being equivalent. "ACCEPTED" means that the rewrite is good, and should be returned to the user.
    ADVICE indicates that there is potential room for improvement, but the rewrite can still be returned to the user as-is.
    $feedback is a natural language explanation of why `$status` was chosen, with advice on how to fix the issue.
    """
    state: Annotated[RewriterState, InjectedState]

FeedbackStatus = Literal["REJECTED", "ACCEPTED", "ADVICE"]

class QualitativeFeedbackSchema(BaseModel):
    status: FeedbackStatus = Field(
        description="The overall feedback result. REJECTED means that there is some glaring violation of the qualitative "
        "requirements. ACCEPTED means the rewrite is good, and satisfies the requirements. ADVICE means "
        "the rewrite is acceptable as-is, but could still be improved"
    )
    feedback: str = Field(
        description="The results of your analysis, explaining the choice of `status`. If the status is"
        "`REJECTED` or `ADVICE` provide specific explanations of what can/should be improved, referencing"
        "specific line numbers and proposed code changes. REMINDER: this feedback should be phrased in the 2nd person."
    )

simplification_system_prompt = load_template("simplification_system_prompt.j2").render()

@tool(args_schema=QualitativeFeedbackArgSchema)
def qualitative_feedback_tool(
    state: Annotated[RewriterState, InjectedState]
) -> str:
    if "curr_rewrite" not in state:
        return "Missing rewrite harness from VFS. Use the put tool first"
    cont = get_runtime(RewriteContext)
    bound = cont.context["llm"].with_structured_output(QualitativeFeedbackSchema)
    res = bound.invoke(
        input=[
            SystemMessage(content=simplification_system_prompt),
            HumanMessage(content=[
                load_template("feedback_prompt.j2").render(),
                state["curr_rewrite"]
            ])
        ]
    )
    assert isinstance(res, QualitativeFeedbackSchema)
    return f"""
<result>{res.status}</result>
<feedback>{res.feedback}</feedback>
"""
