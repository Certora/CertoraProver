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
from pydantic import BaseModel, Field, create_model
from langchain_core.tools import tool, BaseTool, InjectedToolCallId
from langgraph.types import Command
from graphcore.graph import tool_output


def result_tool_generator(
    outkey: str,
    result_schema: type[BaseModel] | tuple[type, str],
    doc: str,
) -> BaseTool:
    if isinstance(result_schema, type) and issubclass(result_schema, BaseModel):
        # Case 1: result_schema is a Pydantic BaseModel
        # Copy all fields from result_schema with their metadata
        field_definitions = {}
        for field_name, field_info in result_schema.model_fields.items():
            field_definitions[field_name] = (field_info.annotation, field_info)

        # Add tool_call_id field
        field_definitions['tool_call_id'] = (Annotated[str, InjectedToolCallId], Field())

        # Create the new schema dynamically
        schema = create_model(
            'ResultToolSchema',
            __doc__=doc,
            **field_definitions
        )

        @tool(args_schema=schema)
        def result(**kwargs) -> Command:
            # Filter out tool_call_id and create instance of original BaseModel
            data = {k: v for k, v in kwargs.items() if k != "tool_call_id"}
            instance = result_schema(**data)
            return tool_output(tool_call_id=kwargs["tool_call_id"], res={outkey: instance})
        return result
    else:
        # Case 2: result_schema is a tuple[type, str]
        field_type, field_description = result_schema

        field_definitions = {
            outkey: (field_type, Field(description=field_description)),
            'tool_call_id': (Annotated[str, InjectedToolCallId], Field())
        }

        schema = create_model(
            'ResultToolSchema',
            __doc__=doc,
            **field_definitions
        )

        @tool(args_schema=schema)
        def result(**kwargs) -> Command:
            return tool_output(tool_call_id=kwargs["tool_call_id"], res={outkey: kwargs[outkey]})
        return result
