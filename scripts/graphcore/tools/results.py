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

from typing import Annotated, TypeVar, Callable, overload, Any, cast
from pydantic import BaseModel, Field, create_model
from langchain_core.tools import tool, BaseTool, InjectedToolCallId
from langgraph.types import Command
from langgraph.prebuilt import InjectedState
from graphcore.graph import tool_output, tool_return

ST = TypeVar("ST")
R = TypeVar("R")
M = TypeVar("M", bound=BaseModel)

ValidationResult = Command | str | None
ResultValidator = tuple[type[ST], Callable[[ST, R, str], ValidationResult]] | Callable[[R, str], ValidationResult]

@overload
def result_tool_generator(
    outkey: str,
    result_schema: type[M],
    doc: str,
    validator: tuple[type[ST], Callable[[ST, M, str], ValidationResult]]
) -> BaseTool:
    """
    Generates a tool that can be used to complete a workflow
    Args:
        outkey (str): The name of the key in the state which holds the result, and whose presence signals
          completion
        result_schema (type[M]): A BaseModel type which is the type of the completed state. Each field of this
           basemodel becomes a field in the generated tool schema, and so these fields SHOULD have string descriptions.
        doc (str): The documentation to use for the generated tool
        validator (tuple[type[ST], Callable[[ST, M, str], ValidationResult]]): The type of the state in which this
           tool should be used, and a callback that takes the current state, the generated result,
           and the current tool id. The validator returns None to indicate there is no issue, otherwise it may return
           a string (which is returned as the result of the tool call WITHOUT setting outkey), or it may return a Command.
    Returns:
        BaseTool: The generated result tool
    """
    ...

@overload
def result_tool_generator(
    outkey: str,
    result_schema: type[M],
    doc: str,
    validator: Callable[[M, str], ValidationResult]
) -> BaseTool:
    """
    Generates a tool that can be used to complete a workflow
    Args:
        outkey (str): The name of the key in the state which holds the result, and whose presence signals
          completion
        result_schema (type[M]): A BaseModel type which is the type of the completed state. Each field of this
           basemodel becomes a field in the generated tool schema, and so these fields SHOULD have string descriptions.
        doc (str): The documentation to use for the generated tool
        validator (Callable[[M, str], ValidationResult]): A validator which simply accepts the resultant basemodel
          and the current tool call id, and return None if there is no issue, otherwise it may return a string
          (which is returned as the result of the tool call WITHOUT setting outkey), or it may return an arbitrary command.

    Returns:
        BaseTool: The generated result tool
    """
    ...


@overload
def result_tool_generator(
    outkey: str,
    result_schema: tuple[type[R], str],
    doc: str,
    validator: Callable[[R, str], ValidationResult]
) -> BaseTool:
    """
    Generates a tool that can be used to complete a workflow
    Args:
        outkey (str): The name of the key in the state which holds the result, and whose presence signals
          completion
        result_schema (tuple[type[R], str]): A tuple of the desired result type, and a description of what the output
          should be.
        doc (str): The documentation to use for the generated tool
        validator (Callable[[R, str], ValidationResult]): A validator which simply accepts the resultant value
          and the current tool call id, and return None if there is no issue, otherwise it may return a string
          (which is returned as the result of the tool call WITHOUT setting outkey), or it may return an arbitrary command.

    Returns:
        BaseTool: The generated result tool
    """
    ...

@overload
def result_tool_generator(
    outkey: str,
    result_schema: tuple[type[R], str],
    doc: str,
    validator: tuple[type[ST], Callable[[ST, R, str], ValidationResult]]
) -> BaseTool:
    """
    Generates a tool that can be used to complete a workflow
    Args:
        outkey (str): The name of the key in the state which holds the result, and whose presence signals
          completion
        result_schema (tuple[type[R], str]): A tuple of the desired result type, and a description of what the output
          should be.
        doc (str): The documentation to use for the generated tool
        validator (tuple[type[ST], Callable[[ST, R, str], ValidationResult]]): The type of the state in which this
           tool should be used, and a callback that takes the current state, the generated result,
           and the current tool id. The validator returns None to indicate there is no issue, otherwise it may return
           a string (which is returned as the result of the tool call WITHOUT setting outkey), or it may return a Command.

    Returns:
        BaseTool: The generated result tool
    """
    ...

@overload
def result_tool_generator(
    outkey: str,
    result_schema: type[BaseModel] | tuple[type, str],
    doc: str,
) -> BaseTool:
    """
    Generates a tool that can be used to complete a workflow
    Args:
        outkey (str): The name of the key in the state which holds the result, and whose presence signals
          completion
        result_schema (type[BaseModel] | tuple[type, str]): Either a BaseModel type (where each field becomes
           a field in the generated tool schema) or a tuple of the desired result type and description.
        doc (str): The documentation to use for the generated tool

    Returns:
        BaseTool: The generated result tool
    """
    ...

_magic_state_name = "graphcore_injected_state"

def result_tool_generator(
    outkey: str,
    result_schema: type[BaseModel] | tuple[type, str],
    doc: str,
    validator: ResultValidator | None = None
) -> BaseTool:
    def maybe_inject_state(
        tgt: dict[str, Any]
    ):
        if validator is not None and isinstance(validator, tuple):
            tgt[_magic_state_name] = (Annotated[validator[0], InjectedState], Field())

    def run_checks(
        kwargs: dict,
        instance: Any
    ) -> Command | str:
        check : ValidationResult
        match validator:
            case None:
                check = None
            case (_, v):
                st_field = kwargs[_magic_state_name]
                check = v(st_field, instance, kwargs["tool_call_id"])
            case single_call:
                l = cast(Callable[[Any, str], ValidationResult], single_call)
                check = l(instance, kwargs["tool_call_id"])
        if check is None:
            return tool_output(tool_call_id=kwargs["tool_call_id"], res={outkey: instance})
        elif isinstance(check, str):
            return tool_return(tool_call_id=kwargs["tool_call_id"], content=check)
        else:
            return check

    if isinstance(result_schema, type) and issubclass(result_schema, BaseModel):
        # Case 1: result_schema is a Pydantic BaseModel
        # Copy all fields from result_schema with their metadata
        field_definitions = {}
        for field_name, field_info in result_schema.model_fields.items():
            field_definitions[field_name] = (field_info.annotation, field_info)

        # Add tool_call_id field
        field_definitions['tool_call_id'] = (Annotated[str, InjectedToolCallId], Field())

        maybe_inject_state(field_definitions)
        # Create the new schema dynamically
        schema = create_model(
            'ResultToolSchema',
            __doc__=doc,
            **field_definitions
        )

        @tool(args_schema=schema)
        def result(**kwargs) -> Command | str:
            # Filter out tool_call_id and create instance of original BaseModel
            data = {k: v for k, v in kwargs.items() if k != "tool_call_id" and k != _magic_state_name}
            instance = result_schema(**data)
            return run_checks(kwargs, instance)
        return result
    else:
        # Case 2: result_schema is a tuple[type, str]
        field_type, field_description = result_schema

        field_definitions = {
            outkey: (field_type, Field(description=field_description)),
            'tool_call_id': (Annotated[str, InjectedToolCallId], Field())
        }
        maybe_inject_state(field_definitions)
        schema = create_model(
            'ResultToolSchema',
            __doc__=doc,
            **field_definitions
        )

        @tool(args_schema=schema)
        def result(**kwargs) -> Command | str:
            return run_checks(kwargs, kwargs[outkey])
        return result
