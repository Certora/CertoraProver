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

import logging

from typing import Generic, TypeVar

StateT = TypeVar("StateT")

logger = logging.getLogger(__name__)

class SummaryConfig(Generic[StateT]):
    def __init__(self, max_messages: int = 20, enabled: bool = True):
        self.max_messages = max_messages
        self.enabled = enabled

    def get_summarization_prompt(self, state: StateT) -> str:
        return """
For the purposes of history compaction, summarize the current state of your task.
The result of this summarization process will be fed back into you, the LLM model, so structure
your output in a way that is most suitable for your later consumption.

Your summary should include:
1. Your current progress on the task, what is done and what remains to be done
2. Any lessons you have learned from invoking the tools; what CVL syntax you've learned, and what lessons you've learned from verification failures (if any)
3. Any lessons you've learned about invoking the various tools; e.g., "using solc8.2 doesn't work because ...". Include any workarounds or advice when invoking the tools

IMPORTANT: your summary must accurately capture the current state of the task. Do NOT include commentary describing past failures, **unless** it is
in the context of describing how you overcame those failures or if said failure is unresolved.
        """

    def get_resume_prompt(self, state: StateT, summary: str) -> str:
        return f"""
IMPORTANT: You are resuming this task already in progress. A summary of your recent work is as follows:

{summary}
"""

    # default, do nothing, this is for auditing purposes
    def on_summary(self, state: StateT, summary: str, resume: str) -> None:
        pass
