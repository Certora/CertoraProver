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

from typing import Optional, List, TypedDict, Annotated, Literal, TypeVar, Type, Protocol, cast, Any, Tuple, NotRequired
from langchain_core.messages import ToolMessage, AnyMessage, SystemMessage, HumanMessage, BaseMessage, AIMessage, RemoveMessage
from langchain_core.tools import InjectedToolCallId, BaseTool
from langchain_core.language_models.base import LanguageModelInput
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.runnables import Runnable
from langgraph.graph import StateGraph, MessagesState
from langgraph._internal._typing import StateLike
from langgraph.types import Command
from langgraph.prebuilt import ToolNode
from langchain_anthropic import ChatAnthropic
from pydantic import BaseModel
from .utils import cached_invoke
from .summary import SummaryConfig

"""
This provides the framework for building applications which loop with an LLM,
using tools to refine the LLM output.
"""

class WithToolCallId(BaseModel):
    """
    A common schema used for tools which need explicit access to the tool_id (usually
    for calling tool_output
    """

    tool_call_id: Annotated[str, InjectedToolCallId]

def tool_output(tool_call_id: str, res: dict) -> Command:
    """
    Create a LangGraph Command for final tool outputs that update workflow state.

    Used by "completion" tools to set final
    results in the workflow state. The workflow's conditional edge will detect
    these state updates and route to completion.

    Args:
        tool_call_id: The ID of the tool call being responded to
        res: Dictionary containing the final workflow results to merge into state

    Returns:
        Command that updates state with final results and a success message
    """
    return Command(update={
        **res,
        "messages": [ToolMessage(
            tool_call_id=tool_call_id,
            content="Success"
        )]
    })

def tool_return(
    tool_call_id: str,
    content: str
) -> Command:
    """
    Create a LangGraph Command for tool responses that need to continue processing.

    Used by tools that want to return a result and continue the workflow by routing
    back to the tool_result node for LLM processing.

    Usually this is unnecessary, and you can just return a "str" or dictionary from the
    tool body.

    Args:
        tool_call_id: The ID of the tool call being responded to
        content: The response content from the tool execution

    Returns:
        Command that updates messages and continues workflow
    """
    return Command(
        update={
            "messages": [ToolMessage(tool_call_id=tool_call_id, content=content)]
        }
    )

class FlowInput(TypedDict):
    """
    Upper bound on any type used as an input to a workflow.
    """

    """
    Any contents to be placed *before* the initial prompt but
    *after* the system prompt.
    """
    front_matter: NotRequired[list[HumanMessage]]
    """
    Any workflow specific data to add *after* the initial prompt.
    """
    input: list[str | dict]

# InputState <: FlowInput
InputState = TypeVar('InputState', bound=FlowInput)

InputType = TypeVar("InputType", contravariant=True)
OutputType = TypeVar("OutputType", covariant=True)

class InitNodeFunction(Protocol[InputType, OutputType]):
    """Protocol defining the signature for LangGraph input functions. All instantiations of InputType
    are expected to be a type bounded above by FlowInput, although this is not expressible."""
    def __call__(self, state: InputType) -> OutputType:
        ...

class ChatNodeFunction(Protocol[InputType]):
    """
    The protocol defining the signature for a langgraph node function.
    Takes a state which contains *at least* some messages (MessagesState), and returns
    an update to the messages state.
    """
    def __call__(self, state: InputType) -> dict[str, List[BaseMessage]]:
        ...

# TypeVars for generic typing
StateT = TypeVar('StateT', bound=MessagesState)
OutputT = TypeVar('OutputT', bound=StateLike)
ContextT = TypeVar("ContextT", bound=StateLike)

def tool_result_generator(
    t: type[StateT],
    llm: Runnable[LanguageModelInput, BaseMessage],
) -> ChatNodeFunction[StateT]:
    """
    Create a LangGraph node function that processes tool results by sending
    the current message history to the LLM for the next response.

    Args:
        llm: The LLM bound with tools to invoke for generating responses

    Returns:
        A node function that takes MessagesState and returns updated messages
    """
    def tool_result(state: MessagesState) -> dict[str, List[BaseMessage]]:
        # logger.debug("Tool result state messages:%s", pretty_print_messages(state["messages"]))
        result = cached_invoke(llm, state["messages"])
        return {"messages": [result]}
    return tool_result

def get_summarizer(
    llm: BaseChatModel,
    system_prompt: str,
    initial_prompt: str,
    state_type: type[StateT],
    context: SummaryConfig[StateT]
) -> ChatNodeFunction[StateT]:
    def to_return(state: StateT) -> dict[str, list[BaseMessage]]:
        config = context
        assert config is not None

        summary_prompt = config.get_summarization_prompt(state)

        messages = state["messages"].copy()
        assert len(messages) >= config.max_messages

        try:
            msg = llm.invoke(messages + [HumanMessage(content=summary_prompt)])
            assert isinstance(msg, AIMessage)
            summary = msg.text()
            resume_message = config.get_resume_prompt(state, summary)
            config.on_summary(state, summary, resume_message)
            return {
                "messages": [
                    RemoveMessage(id="__remove_all__"),
                    SystemMessage(content=system_prompt),
                    HumanMessage(content=initial_prompt),
                    HumanMessage(content=summary),
                    HumanMessage(content=resume_message),
                ]
            }
        except Exception:
            return {}

    return to_return


def initial_node(
    t: Type[InputState],
    output_state: Type[StateT],
    sys_prompt: str,
    initial_prompt: str,
    llm: Runnable[LanguageModelInput, BaseMessage]
) -> InitNodeFunction[InputState, StateT]:
    """
    Create a LangGraph node function that initializes a workflow with system and human messages,
    then gets the first LLM response.

    Args:
        t: unused argument to parameterize over the input state
        output_state: unused argument to parameterize over the output state
        sys_prompt: System message content to set the LLM's role and context
        initial_prompt: Human message template to start the conversation
        llm: The LLM bound with tools to invoke for generating the initial response

    Returns:
        A node function that takes an InputState and returns initial message history.
    """
    def to_return(state: InputState) -> StateT:
        """
        Any fields in state that are not specified by the FlowInput bound (i.e.,
        are not front_matter or input) are automatically added to the main state.
        """
        initial_messages : List[AnyMessage] = [
            SystemMessage(
                sys_prompt
            )
        ]
        front = state.get("front_matter", None)
        if front is not None:
            app = cast(List[HumanMessage], front)
            initial_messages.extend(app)

        prompt_and_input_message: List[str | dict] = [initial_prompt]
        prompt_and_input_message.extend(state["input"])

        initial_messages.append(
            HumanMessage(
                content=prompt_and_input_message
            )
        )
        # The format of the initial message is:
        # [system_prompt, front_matter?, initial_prompt, input]
        # front_matter is for variable inputs that provide context to the initial prompt
        # (e.g., reference material)
        # this cast is fine because we don't write into initial_messages
        res = cached_invoke(llm, initial_messages)

        initial_messages.append(
            cast(AnyMessage, res)
        )
        to_ret : dict[str, Any] = {"messages": initial_messages}
        for (k, v) in state.items():
            if k == "front_matter" or k == "input":
                continue
            to_ret[k] = v
        return cast(StateT, to_ret)

    return to_return

INITIAL_NODE = "initial"
TOOLS_NODE = "tools"
TOOL_RESULT_NODE = "tool_result"
SUMMARIZE_NODE = "summarize"

BoundLLM = Runnable[LanguageModelInput, BaseMessage]

def build_workflow(
    state_class: Type[StateT],
    input_type: Type[InputState],
    tools_list: List[BaseTool],
    sys_prompt: str,
    initial_prompt: str,
    output_key: str,
    unbound_llm: BaseChatModel,
    output_schema: Optional[Type[OutputT]] = None,
    context_schema: Optional[Type[ContextT]] = None,
    summary_config: SummaryConfig[StateT] | None = None
) -> Tuple[StateGraph[StateT, ContextT, InputState, OutputT], BoundLLM]:
    """
    Build a standard workflow with initial node -> tools -> tool_result pattern.
    More specifically, the initial_prompt should instruct the LLM to use any of
    the tools available. The results of these tools are then returned back to the LLM,
    which will continue to call tools. One of these tools should be a distinguished "output"
    tool; this tool should be called when the llm's work is complete, and set
    the `output_key` field of the running state to be non-None. The graph created
    by this function will detect this field becoming non-null, and break the loop with the LLM,
    indicating the computation is complete.

    Args:
        state_class: The type of the "main" state, bounded by `MessagesState`
        input_type: The type of the "input" state, bounded by `FlowInput`. The `input` field of \
            this type should be used for task specific inputs
        tools_list: A list of tools that the LLM can call during iteration.
        sys_prompt: The system prompt sent to start the conversation
        initial_prompt: The static prompt sent describing the task and how to use the tools
        output_key: The designated "output" tool should set this key to be non-None in the current state. \
            When this happens, the computation is considered complete, and the graph exits
        unbound_llm: The llm to use for the computation and looping
        output_schema: (Optional) if non-none, describes the output format of the computation.
        context_schema: (Optional) if non-none, the type of contexts passed through the computation
        summary_config: if non-none, the parameters and prompts for history summarization.

    Returns:
        The state graph compiled to execute the workflow above, and the llm wth the tools bound
        and configured.
    """

    def should_end(state: StateT) -> Literal["__end__", "tool_result"]:
        """Check if workflow should end based on output key being defined."""
        assert isinstance(state, dict)
        if state.get(output_key, None) is not None:
            return "__end__"
        return "tool_result"

    if isinstance(unbound_llm, ChatAnthropic) and (beta_attr := getattr(unbound_llm, "betas", [])) is not None and "context-management-2025-06-27" in beta_attr:
        llm = unbound_llm.bind_tools([{
            "type": "memory_20250818",
            "name": "memory"
        } if t.name == "memory" else t for t in tools_list])
    else:
        llm = unbound_llm.bind_tools(tools_list)

    # Create initial node and tool node with curried LLM
    init_node = initial_node(input_type, state_class, sys_prompt=sys_prompt, initial_prompt=initial_prompt, llm=llm)
    tool_node = ToolNode(tools_list, handle_tool_errors=False)
    tool_result_node = tool_result_generator(state_class, llm)

    # Build the graph with fixed input schema, no context
    builder = StateGraph(
        state_schema=state_class,
        input_schema=input_type,
        output_schema=output_schema,
        context_schema=context_schema
    )

    builder.set_entry_point(INITIAL_NODE)
    builder.add_node(INITIAL_NODE, init_node, input_schema=input_type)
    builder.add_node(TOOLS_NODE, tool_node)
    builder.add_node(TOOL_RESULT_NODE, tool_result_node)
    builder.add_edge(INITIAL_NODE, TOOLS_NODE)
    builder.add_edge(TOOL_RESULT_NODE, TOOLS_NODE)

    if summary_config is not None:
        def routing(state: StateT) -> Literal["summarize", "tool_result", "__end__"]:
            if state.get(output_key, None) is not None:
                return "__end__"
            elif len(state["messages"]) > summary_config.max_messages:
                return "summarize"
            else:
                return "tool_result"

        summarizer = get_summarizer(
            unbound_llm, sys_prompt, initial_prompt, state_class, summary_config
        )
        builder.add_node(SUMMARIZE_NODE, summarizer)
        builder.add_edge(SUMMARIZE_NODE, TOOL_RESULT_NODE)
        builder.add_conditional_edges(TOOLS_NODE, routing)
    else:
        builder.add_conditional_edges(TOOLS_NODE, should_end)
    return builder, llm
