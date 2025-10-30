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

from typing import List, cast
from langchain_core.messages import BaseMessage, AnyMessage, HumanMessage, ToolMessage
from langchain_core.language_models.base import LanguageModelInput
from langchain_core.runnables import Runnable


def canonicalize_message(s: AnyMessage) -> AnyMessage:
    """
    Canonicalize the `content` of `s` if it is a `HumanMessage` or `ToolMessage`, otherwise
    leave it unchanged. The canonical representation of `content` for human and tool messages
    is a list of dicts of the form `{ "type": "text", "text": t }` where `t` is the textual content
    of the message. Note that the type of `content` also allows a regular old string or a
    list of strings, which this function transforms into the dict representation described above.

    NB: this function is *pure*; the original object `s` is *unchanged* by this function.
    """
    match s:
        case HumanMessage(content=cont) | ToolMessage(content=cont):
            cont_list: List[str | dict] = cont if isinstance(cont, list) else [cont]
            new_cont: List[str | dict] = [
                d if isinstance(d, dict) else {
                    "type": "text",
                    "text": d
                } for d in cont_list
            ]
            s_copy = s.model_copy()
            s_copy.content = new_cont
            return s_copy
        case _:
            return s


def canonicalize(s: List[AnyMessage]) -> List[AnyMessage]:
    """
    Canonicalize all messages in `s` via `canonicalize_message`.
    `s` is unchanged by this function, the list returned is a fresh list,
    and any canonicalization is performed on copies of the original messages in `s`.
    """
    return [canonicalize_message(m) for m in s]


def add_cache_control(s: List[AnyMessage]) -> List[AnyMessage]:
    """
    Add a `cache_control` directive to the last human/tool message in
    the canonical representation of `s` (as determined by `canonicalize`).
    This function returns a copy of `s` with the canonicalization & cache control
    directives applied.
    """
    canon = canonicalize(s)
    for i in range(len(canon) - 1, -1, -1):
        curr = canon[i]
        match curr:
            case HumanMessage(content=cont) | ToolMessage(content=cont):
                cont_list = cast(List[dict], cont)
                if not cont_list:
                    continue
                new_cont_list = cont_list.copy()
                final_elem = new_cont_list[-1]
                new_cont_list[-1] = {
                    **final_elem,
                    "cache_control": {"type": "ephemeral"}
                }
                mutated = curr.model_copy()
                mutated.content = cast(List[str | dict], new_cont_list)
                to_ret = canon.copy()
                to_ret[i] = mutated
                return to_ret
            case _:
                continue
    return s

def cached_invoke(b: Runnable[LanguageModelInput, BaseMessage], s: List[AnyMessage]) -> BaseMessage:
    """
    Send messages `s` to the llm `b` after adding caching instructions.
    """
    canon = add_cache_control(s)
    to_ret = b.invoke(canon)
    return to_ret
