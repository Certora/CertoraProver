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

import json5
import logging
from pathlib import Path
from typing import Dict, Any
from collections import OrderedDict

import CertoraProver.certoraContext as Ctx
import CertoraProver.certoraContextAttributes as Attrs
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util

"""
This file is responsible for reading and writing configuration files.
"""

# logger for issues regarding the general run flow.
# Also serves as the default logger for errors originating from unexpected places.
run_logger = logging.getLogger("run")


def current_conf_to_file(context: CertoraContext) -> Dict[str, Any]:
    """
    Saves current command line options to a configuration file
    @param context: context object
    @:return the data that was written to the file (in json/dictionary form)

    We are not saving options if they were not provided (and have a simple default that cannot change between runs).
    Why?
    1. The .conf file is shorter
    2. The .conf file is much easier to read, easy to find relevant arguments when debugging
    3. Reading the .conf file is quicker
    4. Parsing the .conf file is simpler, as we can ignore the null case
    """
    def input_arg_with_value(k: Any, v: Any) -> Any:
        all_conf_names = Attrs.get_attribute_class().all_conf_names()
        return v is not None and v is not False and k in all_conf_names

    context_to_save = {k: v for k, v in vars(context).items() if input_arg_with_value(k, v)}
    all_conf_names = Attrs.get_attribute_class().all_conf_names()
    context_to_save = dict(sorted(context_to_save.items(), key=lambda x: all_conf_names.index(x[0])))
    context_to_save.pop('build_dir', None)  # build dir should not be saved, each run should define its own build_dir
    context_to_save.pop('mutation_test_id', None)  # mutation_test_id should be recreated for every run
    context_to_save.pop('test_condition', None)  # test_condition is only used internally
    context_to_save.pop('override_base_config', None)  # test_condition is only used internally

    out_file_path = Util.get_last_conf_file()
    run_logger.debug(f"Saving last configuration file to {out_file_path}")
    Ctx.write_output_conf_to_path(context_to_save, out_file_path)

    return context_to_save


def read_from_conf_file(context: CertoraContext) -> None:
    """
    If the file in the command line is a conf file, read data from the configuration file and add each key to the
    context namespace if the key was not set in the command line (command line shadows conf data).
    @param context: A namespace containing options from the command line
    """
    conf_file_path = Path(context.files[0])
    assert conf_file_path.suffix == ".conf", f"conf file must be of type .conf, instead got {conf_file_path}"

    try:
        with conf_file_path.open() as conf_file:
            context.conf_file_attr = json5.load(conf_file, allow_duplicate_keys=False, object_pairs_hook=OrderedDict)
            try:
                check_conf_content(context)
            except Util.CertoraUserInputError as e:
                raise Util.CertoraUserInputError(f"Error when reading {conf_file_path}: {e}") from None
            context.conf_file = str(conf_file_path)
    except FileNotFoundError:
        raise Util.CertoraUserInputError(f"read_from_conf_file: {conf_file_path}: not found") from None
    except PermissionError:
        raise Util.CertoraUserInputError(f"read_from_conf_file: {conf_file_path}: Permission denied") from None
    except Util.CertoraUserInputError:
        raise
    except Exception as e:
        raise Util.CertoraUserInputError(f"read_from_conf_file: {conf_file_path}: Failed\n{e}") from None

def handle_override_base_config(context: CertoraContext) -> None:
    """
    attributes that are not set by the CLI and the conf attribute will be set from the parent conf (if exist)
    """

    if context.override_base_config:
        try:
            with Path(context.override_base_config).open() as conf_file:
                override_base_config_attrs = json5.load(conf_file, allow_duplicate_keys=False,
                                                        object_pairs_hook=OrderedDict)
                context.conf_file_attr = {**override_base_config_attrs, **context.conf_file_attr}

                if 'override_base_config' in override_base_config_attrs:
                    raise Util.CertoraUserInputError("base config cannot include 'override_base_config'")
        except Exception as e:
            raise Util.CertoraUserInputError(f"Cannot load base config: {context.override_base_config}\n{e}")

        for attr in override_base_config_attrs:
            if hasattr(context, attr):
                value = getattr(context, attr, False)
                if not value:
                    setattr(context, attr, override_base_config_attrs.get(attr))
            else:
                raise Util.CertoraUserInputError(f"{attr} appears in the base conf file {context.override_base_config} but is not a known attribute.")


def check_conf_content(context: CertoraContext) -> None:
    """
    validating content read from the conf file
    Note: a command line definition trumps the definition in the file.
    If in the .conf file solc is 4.25 and in the command line --solc solc6.10 was given, sol6.10 will be used
    @param conf: A json object in the conf file format
    @param context: A namespace containing options from the command line, if any
    """

    for option in context.conf_file_attr:
        if hasattr(context, option):
            val = getattr(context, option)
            if val is None or val is False:
                setattr(context, option, context.conf_file_attr[option])
            elif option != 'files' and val != context.conf_file_attr[option]:
                cli_val = ' '.join(val) if isinstance(val, list) else str(val)
                conf_val = ' '.join(context.conf_file_attr[option]) \
                    if isinstance(context.conf_file_attr[option], list) else str(context.conf_file_attr[option])
                run_logger.warning(f"Note: attribute {option} value in CLI ({cli_val}) overrides value stored in conf"
                                   f" file ({conf_val})")
        else:
            raise Util.CertoraUserInputError(f"{option} appears in the conf file but is not a known attribute. ")

    handle_override_base_config(context)

    if Attrs.is_evm_app() and not context.files and not context.project_sanity and not context.foundry:
        raise Util.CertoraUserInputError("Mandatory 'files' attribute is missing from the configuration")
    context.files = context.conf_file_attr.get('files')
    if Attrs.is_soroban_app() and not context.files and not context.build_script:
        raise Util.CertoraUserInputError("'files' or 'build script' must be set for Soroban runs")

    context.files = context.conf_file_attr.get('files')
