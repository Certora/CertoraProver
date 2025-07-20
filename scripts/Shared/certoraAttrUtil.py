#     The Certora Prover
#     Copyright (C) 2025  Certora Ltd.
#
#     This program is free software: you can redistribute it and/or modify
#     it under the terms of the GNU General Public License as published by
#     the Free Software Foundation, version 3 of the License.
#
#     This program is distributed in the hope that it will be useful,
#     but WITHOUT ANY WARRANTY; without even the implied warranty of
#     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#     GNU General Public License for more details.
#
#     You should have received a copy of the GNU General Public License
#     along with this program.  If not, see <https://www.gnu.org/licenses/>.

import sys
import argparse
from typing import Any, NoReturn, Dict, Optional, Callable, List
from Shared import certoraUtils as Util
from CertoraProver.certoraCollectConfigurationLayout import AttributeJobConfigData
from enum import auto
from dataclasses import dataclass, field
from rich.console import Console
from rich.table import Table
from rich.text import Text


APPEND = 'append'
STORE_TRUE = 'store_true'
VERSION = 'version'
SINGLE_OR_NONE_OCCURRENCES = '?'
MULTIPLE_OCCURRENCES = '*'
ONE_OR_MORE_OCCURRENCES = '+'


def default_validation(x: Any) -> Any:
    return x


class UniqueStore(argparse.Action):
    """
    This class makes the argparser throw an error for a given flag if it was inserted more than once
    """

    def __call__(self, parser: argparse.ArgumentParser, namespace: argparse.Namespace, values: Any,  # type: ignore
                 option_string: str) -> None:
        if getattr(namespace, self.dest, self.default) is not self.default:
            parser.error(f"{option_string} appears several times.")
        setattr(namespace, self.dest, values)


class NotAllowed(argparse.Action):
    """
    This class makes the argparser throw an error for a given flag if it was set in CLI (can be set using conf file)
    """

    def __call__(self, parser: argparse.ArgumentParser, namespace: argparse.Namespace, values: Any,  # type: ignore
                 option_string: str) -> None:

        parser.error(f"{option_string} cannot be set in command line only in a conf file.")


class ContextAttributeParser(argparse.ArgumentParser):
    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)

    def error(self, message: str) -> NoReturn:
        prefix = 'unrecognized arguments: '
        is_single_dash_flag = False

        if message.startswith(prefix):
            flag = message[len(prefix):].split()[0]
            if len(flag) > 1 and flag[0] == '-' and flag[1] != '-':
                is_single_dash_flag = True
        self.print_help(sys.stderr)
        if is_single_dash_flag:
            Console().print(f"{Util.NEW_LINE}[bold red]Please remember, CLI flags should be preceded with "
                            f"double dashes!{Util.NEW_LINE}")
        raise Util.CertoraUserInputError(message)


class AttrArgType(Util.NoValEnum):
    STRING = auto()
    BOOLEAN = auto()
    LIST = auto()
    INT = auto()
    MAP = auto()
    OBJ_LIST = auto()

@dataclass
class AttributeDefinition:
    affects_build_cache_key: bool  # a context argument that should be hashed as part of cache key computation
    disables_build_cache: bool  # if set to true, setting this option means cache will be disabled no matter what
    attr_validation_func: Callable = default_validation
    help_msg: str = argparse.SUPPRESS
    # args for argparse's add_attribute passed as is
    argparse_args: Dict[str, Any] = field(default_factory=dict)
    arg_type: AttrArgType = AttrArgType.STRING
    deprecation_msg: Optional[str] = None
    default_desc: Optional[str] = None  # A description of the default behavior
    jar_flag: Optional[str] = None  # the flag that is sent to the jar (if attr is sent to the jar)
    jar_no_value: Optional[bool] = False  # if true, flag is sent with no value
    temporary_jar_invocation_allowed: bool = False  # If true we can call the jar flag without raising an error
    name: str = ''  # the name of the CONST will be set during set_attribute_list()
    # A description of the configuration data to be presented in the report
    config_data: Optional[AttributeJobConfigData] = AttributeJobConfigData()

    def get_conf_key(self) -> str:
        return self.name.lower()

    def get_flag(self) -> str:
        dashes = '' if self.name == 'FILES' else '--'
        return dashes + str(self.name.lower())

    def validate_value(self, value: str, cli_flag: bool = True) -> None:
        if self.attr_validation_func is not None:
            try:
                self.attr_validation_func(value)
            except Util.CertoraUserInputError as e:
                msg = f"attribute/flag '{self.name.lower()}': {e}"
                if cli_flag and isinstance(value, str) and value and value.strip()[0] == '-':
                    flag_error = f'{value}: Please remember, CLI flags should be preceded with double dashes. ' \
                                 f'{Util.NEW_LINE}For more help run the tool with the option --help'
                    msg = flag_error + msg
                raise Util.CertoraUserInputError(msg) from None


class Attributes:

    _attribute_list: List[AttributeDefinition] = []
    _all_conf_names: List[str] = []
    _all_map_attrs: List[str] = []

    @classmethod
    def attribute_list(cls) -> List[AttributeDefinition]:
        if not cls._attribute_list:
            cls.set_attribute_list()
        return cls._attribute_list

    @classmethod
    def all_conf_names(cls) -> List[str]:
        if not cls._attribute_list:
            cls.set_attribute_list()
        return cls._all_conf_names

    @classmethod
    def all_map_attrs(cls) -> List[str]:
        if not cls._attribute_list:
            cls.set_attribute_list()
        return cls._all_map_attrs

    @classmethod
    def print_attr_help(cls) -> None:

        type_col_header = "Type"
        type_col_width = len(type_col_header)
        desc_col_width = 37
        """
        At the time of writing this, the default_desc column width is 25 and is the minimum because the default value
        of --orig_run_dir in certoraMutate is `.certora_mutate_sources`
        """
        default_col_width = Util.HELP_TABLE_WIDTH - Util.MAX_FLAG_LENGTH - type_col_width - desc_col_width

        table = Table(padding=(0, 0), show_lines=True, header_style="bold")
        table.add_column(Text("Flag"), no_wrap=True, width=Util.MAX_FLAG_LENGTH)
        table.add_column(Text(type_col_header), width=type_col_width)
        table.add_column(Text("Description"), width=desc_col_width)
        table.add_column(Text("Default"), width=default_col_width)

        unsupported_attribute_names = [attr.name for attr in cls.unsupported_attributes()]
        for name in dir(cls):
            if name in cls.hide_attributes():
                continue
            if name in unsupported_attribute_names:
                continue
            if name.isupper():
                attr = getattr(cls, name, None)
                assert isinstance(attr, AttributeDefinition), "print_attr_help: type(attr) == Attribute"

                if attr.help_msg != Util.SUPPRESS_HELP_MSG and attr.get_flag().startswith('--') \
                   and not attr.deprecation_msg:
                    default = attr.default_desc if attr.default_desc else ""
                    type_str = str(attr.arg_type).upper()[0]  # We show boolean as B etc
                    flag_name = Text(attr.get_conf_key(), style="bold")
                    table.add_row(flag_name, type_str, attr.help_msg, default)
        console = Console()
        console.print(table)

    @classmethod
    def set_attribute_list(cls) -> None:
        def set_name(name: str) -> AttributeDefinition:
            v = getattr(cls, name)
            v.name = name
            return v

        cls._attribute_list = [set_name(name) for name in dir(cls) if name.isupper()]
        cls._all_conf_names = [attr.name.lower() for attr in cls.attribute_list()]
        cls._all_map_attrs = [attr for attr in cls._all_conf_names if attr.endswith(Util.MAP_SUFFIX)]

    @classmethod
    def hide_attributes(cls) -> List[str]:
        """
        This function is used to hide attributes from the help message.
        :return: A list of attribute names to be hidden.
        """
        return []

    @classmethod
    def unsupported_attributes(cls) -> list:
        # Return a list of AttributeDefinition objects that are unsupported
        return []
