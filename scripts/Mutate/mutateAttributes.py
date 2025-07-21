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
from dataclasses import dataclass
from pathlib import Path
from typing import List, Dict, Any
from rich.console import Console


scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

from Shared import certoraValidateFuncs as Vf
from Shared import certoraUtils as Util
from Shared import certoraAttrUtil as AttrUtil
from Mutate import mutateConstants as Constants


MUTATION_DOCUMENTATION_URL = 'https://docs.certora.com/en/latest/docs/gambit/mutation-verifier.html#cli-options'


@dataclass
class MutateAttributeDefinition(AttrUtil.AttributeDefinition):
    affects_build_cache_key: bool = False
    disables_build_cache: bool = False


class MutateAttributes(AttrUtil.Attributes):

    ORIG_RUN = MutateAttributeDefinition(
        help_msg="A link to a previous run of the Prover, will be used as the basis for the "
                 "generated mutations",
        default_desc="",
        attr_validation_func=Vf.validate_orig_run,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    MSG = MutateAttributeDefinition(
        help_msg="Add a message description to your run",
        attr_validation_func=Vf.validate_msg,
        default_desc="No message",
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    SERVER = MutateAttributeDefinition(
        attr_validation_func=Vf.validate_server_value,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    PROVER_VERSION = MutateAttributeDefinition(
        attr_validation_func=Vf.validate_prover_version,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    DEBUG = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        }
    )

    COLLECT_MODE = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        }
    )

    ORIG_RUN_DIR = MutateAttributeDefinition(
        help_msg="Download files from the original run link to the given folder",
        default_desc="Downloads files to the directory `.certora_mutate_sources`",
        # attr_validation_func=Vf.validate_writable_path,
        argparse_args={
            'type': Path,
            'action': AttrUtil.UniqueStore
        }
    )

    OUTDIR = MutateAttributeDefinition(
        help_msg="Specify the output directory for Gambit",
        default_desc=f"Gambit generates mutants inside the directory `{Constants.GAMBIT_OUT}`",
        # attr_validation_func=Vf.validate_writable_path,
        argparse_args={
            'type': Path,
            'action': AttrUtil.UniqueStore
        }
    )

    GAMBIT_ONLY = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Stop processing after generating mutations with Gambit",
        default_desc="Runs a verification job on each mutant and generates a test report from the results",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        }
    )

    DUMP_FAILED_COLLECTS = MutateAttributeDefinition(
        # This flag is hidden on purpose, the following code line help explain what it does
        # attr_validation_func=Vf.validate_writable_path,
        # help_msg="Path to the log file capturing mutant collection failures.",
        # default_desc="log will be stored at collection_failures.txt",
        argparse_args={
            'type': Path,
            'action': AttrUtil.UniqueStore
        }
    )

    # Sets a file that will store the object sent to mutation testing UI (useful for testing)
    UI_OUT = MutateAttributeDefinition(
        argparse_args={
            'type': Path,
            'action': AttrUtil.UniqueStore
        }
    )

    DUMP_LINK = MutateAttributeDefinition(
        # todo - validation can write the file
        argparse_args={
            'type': Path,
            'action': AttrUtil.UniqueStore
        }
    )

    DUMP_CSV = MutateAttributeDefinition(
        attr_validation_func=Vf.validate_writable_path,
        argparse_args={
            'type': Path,
            'action': AttrUtil.UniqueStore
        }
    )

    # Synchronous mode
    # Run the tool synchronously in shell
    SYNC = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        }
    )

    '''
    The file containing the links holding certoraRun report outputs.
    In async mode, run this tool with only this option.
    '''
    COLLECT_FILE = MutateAttributeDefinition(
        # attr_validation_func=Vf.validate_readable_file,
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'type': Path,
            'action': AttrUtil.UniqueStore
        }
    )

    '''
   The max number of minutes to poll after submission was completed,
    and before giving up on synchronously getting mutation testing results
   '''
    POLL_TIMEOUT = MutateAttributeDefinition(
        attr_validation_func=Vf.validate_positive_integer,
        arg_type=AttrUtil.AttrArgType.INT,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    # The maximum number of retries a web request is attempted
    MAX_TIMEOUT_ATTEMPTS_COUNT = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.INT,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    # The timeout in seconds for a web request
    REQUEST_TIMEOUT = MutateAttributeDefinition(
        attr_validation_func=Vf.validate_positive_integer,
        arg_type=AttrUtil.AttrArgType.INT,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    GAMBIT = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.OBJ_LIST,
        argparse_args={
            'nargs': '*',
            'action': AttrUtil.NotAllowed
        }
    )
    MANUAL_MUTANTS = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.OBJ_LIST,
        attr_validation_func=Vf.validate_manual_mutants,
        argparse_args={
            'nargs': '*',
            'action': AttrUtil.NotAllowed
        }
    )
    UNIVERSAL_MUTATOR = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.OBJ_LIST,
        attr_validation_func=Vf.validate_universal_mutator_mutants,
        argparse_args={
            'nargs': '*',
            'action': AttrUtil.NotAllowed
        }
    )

    '''
    Add this if you wish to wait for the results of the original verification.
    Reasons to use it:
    - Saves resources - all the mutations will be ignored if the original fails
    - The Prover will use the solver data from the original run to reduce the run time of the mutants
    Reasons to not use it:
    - Run time will be increased
    '''
    #
    WAIT_FOR_ORIGINAL_RUN = MutateAttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        }
    )

    TEST = MutateAttributeDefinition(
        attr_validation_func=Vf.validate_test_value,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    URL_VISIBILITY = MutateAttributeDefinition(
        attr_validation_func=Vf.validate_url_visibility,
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore,
            'default': None,  # 'default': when --url_visibility was not used
            # when --url_visibility was used without an argument its probably because the link should be public
            'const': str(Vf.UrlVisibilityOptions.PUBLIC)
        }
    )


def get_args(args_list: List[str]) -> Dict:

    def formatter(prog: Any) -> argparse.HelpFormatter:
        return argparse.HelpFormatter(prog, max_help_position=100, width=96)  # TODO - use the constant!

    parser = MutationParser(prog="certora-cli arguments and options", allow_abbrev=False,
                            formatter_class=formatter,
                            epilog="  -*-*-*   You can find detailed documentation of the supported options in "
                                   f"{MUTATION_DOCUMENTATION_URL}   -*-*-*")
    attrs = MutateAttributes.attribute_list()

    parser.add_argument("conf_no_flag", nargs='?', type=Path)
    parser.add_argument("--conf", type=Path)

    for attr in attrs:
        flag = attr.get_flag()
        if attr.arg_type == AttrUtil.AttrArgType.INT:
            parser.add_argument(flag, help=attr.help_msg, type=int, **attr.argparse_args)
        else:
            parser.add_argument(flag, help=attr.help_msg, **attr.argparse_args)
    return vars(parser.parse_args(args_list))


class MutationParser(AttrUtil.ContextAttributeParser):
    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)

    def format_help(self) -> str:
        console = Console()
        console.print("\n\nThe Certora Mutate - A tool for generating and verifying mutations")
        sys.stdout.write("\n\nUsage: certoraMutate <flags>\n\n")  # Print() would color the word <flags> here

        console.print("[bold][underline]Flag Types[/bold][/underline]\n")

        console.print("[bold]1. boolean (B):[/bold] gets no value, sets flag value to true "
                      "(false is always the default)")
        console.print("[bold]2. string (S):[/bold] gets a single string as a value, "
                      "note also numbers are of type string\n\n")

        MutateAttributes.print_attr_help()
        console.print("\n\nYou can find detailed documentation of the supported flags "
                      f"{Util.print_rich_link(MUTATION_DOCUMENTATION_URL)}\n\n")
        return ''
