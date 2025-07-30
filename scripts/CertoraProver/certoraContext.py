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

import argparse
import hashlib
import json
import os
import re
import sys
import logging
import fnmatch
from wcmatch import glob


from pathlib import Path
from typing import Dict, List, Optional, Any, Union, Type
from rich.console import Console

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util
from CertoraProver.certoraConfigIO import read_from_conf_file, current_conf_to_file
import CertoraProver.certoraContextValidator as Cv
import CertoraProver.certoraContextAttributes as Attrs

from Shared import certoraValidateFuncs as Vf
from Shared import certoraAttrUtil as AttrUtil
import CertoraProver.certoraApp as App


context_logger = logging.getLogger("context")

CLI_DOCUMENTATION_URL = 'https://docs.certora.com/en/latest/docs/prover/cli/options.html'


def escape_string(string: str) -> str:
    """
    enclose string with double quotes if the string contains char that is not alphanum
    """
    pattern = re.compile('^[0-9a-zA-Z]+$')
    if pattern.match(string):
        return string
    else:
        return f"\"{string}\""

def is_evm_app_class(context: CertoraContext) -> bool:
    return issubclass(context.app, App.EvmAppClass)

def is_rust_app_class(context: CertoraContext) -> bool:
    return issubclass(context.app, App.RustAppClass)

def get_attr_value(context: CertoraContext, attr: AttrUtil.AttributeDefinition) -> Any:
    conf_key = attr.get_conf_key()
    return getattr(context, conf_key, None)


def collect_args_with_jar_flags(context: CertoraContext) -> List[AttrUtil.AttributeDefinition]:
    attribute_list = context.app.attr_class.attribute_list()
    return [attr for attr in attribute_list if get_attr_value(context, attr) and attr.jar_flag]


def collect_args_build_cache_affecting(context: CertoraContext) -> List[AttrUtil.AttributeDefinition]:
    attribute_list = context.app.attr_class.attribute_list()
    return [attr for attr in attribute_list if get_attr_value(context, attr) and
            attr.affects_build_cache_key]


def collect_args_build_cache_disabling(context: CertoraContext) -> List[AttrUtil.AttributeDefinition]:
    attribute_list = context.app.attr_class.attribute_list()
    return [attr for attr in attribute_list if get_attr_value(context, attr) and
            attr.disables_build_cache]


def create_jar_flag_list(context: CertoraContext, args: List[AttrUtil.AttributeDefinition]) -> List[str]:
    return_list = []
    for arg in args:
        attr = get_attr_value(context, arg)
        jar_flag = arg.jar_flag
        assert jar_flag is not None
        return_list.append(jar_flag)
        if not arg.jar_no_value:
            if isinstance(attr, list):
                return_list.append(','.join(attr))
            elif isinstance(attr, bool):
                return_list.append('true')
            elif isinstance(attr, str):
                return_list.append(escape_string(attr))
            else:
                raise RuntimeError(f"{arg.name}: {type(attr)} - unknown arg type")
    return return_list


def collect_jar_args(context: CertoraContext) -> List[str]:
    """
    construct the jar flags. For each attribute with non-empty value in the context, we check if a jar flag was
    declared for that attribute. The jar command is a list of strings the first string is the flag (jar_flag). If
    the flag comes with a value we construct the value as the second string, based on the type of the attribute
    (Boolean, String or List of Strings)
    """
    return_list = create_jar_flag_list(context, collect_args_with_jar_flags(context))

    prover_args_values = getattr(context, Attrs.EvmProverAttributes.PROVER_ARGS.get_conf_key(), None)
    if prover_args_values:
        for value in prover_args_values:
            return_list.extend(value.split())

    return return_list


def get_local_run_cmd(context: CertoraContext) -> List[str]:
    """
    Assembles a jar command for local run
    @param context: A namespace including all command line input arguments
    @return: A command for running the prover locally
    """
    run_args = []

    if context.app == App.SuiApp:
        # For local runs, we want path to be relative to cwd instead of zip root.
        move_rel_path = os.path.relpath(Path(context.move_path), os.getcwd())
        run_args.extend(['-movePath', move_rel_path])
    elif is_rust_app_class(context):
        # For local runs, we want path to be relative to cwd instead of zip root.
        rust_rel_path = os.path.relpath(Path(context.files[0]), os.getcwd())
        run_args.append(rust_rel_path)
    elif context.is_tac:
        # For Rust app we assume the files holds the executable for the prover, currently we support a single file
        try:
            run_args.append(context.files[0])
        except Exception:
            raise RuntimeError("get_local_run_cmd: cannot find context.files[0]")

    if is_evm_app_class(context) and context.cache is not None:
        run_args.extend(['-cache', context.cache])
    if context.app == App.ConcordApp:
        run_args.extend(['-equivalenceCheck', 'true'])
    jar_args = collect_jar_args(context)
    run_args.extend(jar_args)

    run_args.extend(['-buildDirectory', str(Util.get_build_dir())])
    if context.jar is not None:
        jar_path = context.jar
    else:
        certora_root_dir = Util.get_certora_root_directory().as_posix()
        jar_path = f"{certora_root_dir}/{Util.EMV_JAR}"

    java_cmd = ["java"]

    if context.java_args:
        java_cmd.extend(context.java_args.strip().split(' '))

    cmd = java_cmd + ["-jar", jar_path] + run_args
    return cmd


class ProverParser(AttrUtil.ContextAttributeParser):
    def __init__(self, app: Type[App.CertoraApp], *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.app = app

    def format_help(self) -> str:
        console = Console()
        if self.app == App.ConcordApp:
            console.print("\n\nConcord - Certora’s equivalence checker for smart contracts")
        elif self.app == App.RangerApp:
            console.print("\n\nRanger - Certora’s bounded model checker for smart contracts")
        else:
            console.print("\n\nThe Certora Prover - A formal verification tool for smart contracts")
        # Using sys.stdout.write() as print() would color some of the strings here
        sys.stdout.write(f"\n\nUsage: {sys.argv[0]} <Files> <Flags>\n\n")
        if self.app == App.ConcordApp:
            sys.stdout.write("Concord supports only Solidity (.sol/.yul) and configuration (.conf) files.\n"
                             "Rust and Vyper contracts are not currently supported.\n\n")
        if self.app == App.RangerApp:
            sys.stdout.write("Ranger supports only Solidity (.sol) and configuration (.conf) files.\n"
                             "Rust and Vyper contracts are not currently supported.\n\n")
        elif isinstance(self.app, App.EvmAppClass):
            sys.stdout.write("Acceptable files for EVM projects are Solidity files (.sol suffix), "
                             "Vyper files (.vy suffix), or conf files (.conf suffix)\n\n")
        elif self.app == App.SolanaApp:
            sys.stdout.write("Acceptable files for Solana projects are RUST executables ('.so' suffix) or conf "
                             "files ('.conf' suffix')\n\n")
        elif self.app == App.SorobanApp:
            sys.stdout.write("Acceptable files for Soroban projects are WASM executables ('.wasm' suffix) or conf "
                             "files ('.conf' suffix')\n\n")
        else:
            RuntimeError("Unrecognized Application Type")

        console.print("Flag Types\n", style="bold underline")

        console.print("1. boolean (B): gets no value, sets flag value to true (false is always the default)")
        console.print("2. string (S): gets a single string as a value, note also numbers are of type string")
        console.print("3. list (L): gets multiple strings as a value, flags may also appear multiple times")
        console.print("4. map (M): collection of key, value pairs\n\n")

        Attrs.get_attribute_class().print_attr_help()
        console.print("\n\nYou can find detailed documentation of the supported flags here: "
                      f"{Util.print_rich_link(CLI_DOCUMENTATION_URL)}\n\n")

        return ''

def __get_argparser(app: Type[App.CertoraApp]) -> argparse.ArgumentParser:
    def formatter(prog: Any) -> argparse.HelpFormatter:
        return argparse.HelpFormatter(prog, max_help_position=100, width=200)

    parser = ProverParser(app, prog="certora-cli arguments and options", allow_abbrev=False,
                          formatter_class=formatter,
                          epilog="  -*-*-*   You can find detailed documentation of the supported options in "
                                 f"{CLI_DOCUMENTATION_URL}   -*-*-*")

    for arg in app.attr_class.attribute_list():
        flag = arg.get_flag()
        parser.add_argument(flag, help=arg.help_msg, **arg.argparse_args)
    return parser

def get_args(args_list: List[str], app: Type[App.CertoraApp]) -> CertoraContext:
    """
    Compiles an CertoraContext object from the given list of command line arguments.

    Why do we handle --version before argparse?
    Because on some platforms, mainly CI tests, we cannot fetch the installed distribution package version of
    certora-cli. We want to calculate the version lazily, only when --version was invoked.
    We do it pre-argparse, because we do not care bout the input validity of anything else if we have a --version flag
    """

    if args_list is None:
        args_list = sys.argv

    handle_version_flag(args_list)

    pre_arg_fetching_checks(args_list)
    parser = __get_argparser(app)

    # if there is a --help flag, we want to ignore all parsing errors, even those before it:
    if any(string in [arg.strip() for arg in args_list] for string in ['--help', '-h']):
        parser.print_help()
        exit(0)

    try:
        import argcomplete
        argcomplete.autocomplete(parser)
    except ImportError:
        pass

    args = parser.parse_args(args_list)
    context = CertoraContext(**vars(args))
    context.app = app
    context.args_list = args_list

    __remove_parsing_whitespace(args_list)
    format_input(context)
    Cv.pre_validation_checks(context)

    if context.is_conf:
        read_from_conf_file(context)
    context.process = None
    context.local = Util.is_local(context)
    context.is_tac = context.files and context.files[0].endswith('.tac')
    context.is_vyper = context.files and context.files[0].endswith('.vy')

    if is_evm_app_class(context):
        Cv.check_mode_of_operation(context)  # Here boolean run characteristics are set

    validator = Cv.CertoraContextValidator(context)

    validator.validate()
    if is_evm_app_class(context) or is_rust_app_class(context):
        current_build_directory = Util.get_build_dir()
        if context.build_dir is not None and current_build_directory != context.build_dir:
            Util.reset_certora_internal_dir(context.build_dir)
            os.rename(current_build_directory, context.build_dir)

    # Store current options (including the ones read from .conf file)
    context.conf_options = current_conf_to_file(context)

    if context.java_args is not None:
        context.java_args = ' '.join(context.java_args).replace('"', '').replace("'", '')

    if is_evm_app_class(context) or context.app == App.ConcordApp:
        validator.check_args_post_argparse()
        setup_cache(context)  # Here context.cache, context.user_defined_cache are set
        validator.handle_ranger_attrs()
        validator.handle_concord_attrs()
    if is_rust_app_class(context):
        validator.check_rust_args_post_argparse()

    attrs_to_relative(context)
    # Setup defaults (defaults are not recorded in conf file)
    context.expected_file = context.expected_file or "expected.json"
    context.run_source = context.run_source or Vf.RunSources.COMMAND.name.upper()
    context.java_version = Util.get_java_version()

    context_logger.debug("parsed args successfully.")
    context_logger.debug(f"args= {context}")

    if context.test == str(Util.TestValue.CHECK_ARGS):
        raise Util.TestResultsReady(context)
    return context


def get_client_version() -> str:
    installed, package_name, version = Util.get_package_and_version()
    if installed:
        return f"{package_name} {version}"
    else:
        return "local script version"


def handle_version_flag(args_list: List[str]) -> None:
    for arg in args_list:
        if arg == "--version":
            print(get_client_version())
            exit(0)


def __remove_parsing_whitespace(arg_list: List[str]) -> None:
    """
    Removes all whitespaces added to args by __alter_args_before_argparse():
    1. A leading space before a dash (if added)
    2. space between commas
    :param arg_list: A list of options as strings.
    """
    for idx, arg in enumerate(arg_list):
        arg_list[idx] = arg.strip().replace(', ', ',')


def __alter_args_before_argparse(args_list: List[str]) -> None:
    """
    some args value accept flags as value (e.g. java_args). The problem is that argparse treats this values
    as CLI arguments. The fix is to add a space before the dash artificially.

    NOTE: remove_parsing_whitespace() removes the added space
    :param args_list: A list of CLI options as strings
    """
    for idx, arg in enumerate(args_list):
        if isinstance(arg, str):
            pattern = r"^[\"\']*-[^-]"  # a string that starts 0 or more qoutes followed by a single hyphen
            if re.match(pattern, arg):
                arg = re.sub('-', " -", arg, count=1)
                args_list[idx] = arg


def pre_arg_fetching_checks(args_list: List[str]) -> None:
    """
    This function runs checks on the raw arguments before we attempt to read them with argparse.
    We also replace certain argument values so the argparser will accept them.
    NOTE: use remove_parsing_whitespace() on argparse.ArgumentParser.parse_args() output!
    :param args_list: A list of CL arguments
    :raises CertoraUserInputError if there are errors (see individual checks for more details):
        - There are wrong quotation marks “ in use
    """
    Cv.check_no_pretty_quotes(args_list)
    __alter_args_before_argparse(args_list)


def format_input(context: CertoraContext) -> None:
    """
    Formats the input as it was parsed by argParser. This allows for simpler reading and treatment of context
    * Removes whitespace from input
    * Flattens nested lists
    * Removes duplicate values in link
    :param context: Namespace containing all command line arguments, generated by get_args()
    """
    flatten_arg_lists(context)
    if is_evm_app_class(context):
        __dedup_link(context)


def flatten_arg_lists(context: CertoraContext) -> None:
    """
    Flattens lists of lists arguments in a given namespace.
    For example,
    [[a], [b, c], []] -> [a, b, c]

    This is applicable to all options that can be used multiple times, and each time get multiple arguments.
    For example: --assert and --link
    @param context: Namespace containing all command line arguments, generated by get_args()
    """
    for arg_name in vars(context):
        arg_val = getattr(context, arg_name)
        # We assume all list members are of the same type
        if isinstance(arg_val, list) and len(arg_val) > 0 and isinstance(arg_val[0], list):
            flat_list = Util.flatten_nested_list(arg_val)
            flat_list.sort()
            setattr(context, arg_name, flat_list)


def __dedup_link(context: CertoraContext) -> None:
    try:
        if hasattr(context, 'link'):
            context.link = list(set(context.link))
    except TypeError:
        pass


def setup_cache(context: CertoraContext) -> None:
    """
    Sets automatic caching up, unless it is disabled (only relevant in VERIFY and ASSERT modes).
    The list of contracts, optimistic loops and loop iterations are determining uniquely a cache key.
    If the user has set their own cache key, we will not generate an automatic cache key, but we will also mark it
    as a user defined cache key.

    This function first makes sure to set user_defined_cache to either True or False,
    and then if necessary, sets up the cache key value.
    """

    # we have a user defined cache key if the user provided a cache key
    context.user_defined_cache = context.cache is not None
    if not context.disable_auto_cache_key_gen and not os.environ.get("CERTORA_DISABLE_AUTO_CACHE") is not None:
        if context.is_verify or context.is_conf:
            # in local mode we don't want to create a cache key if not such is given
            if (context.cache is None) and (not context.local):
                optimistic_loop = context.optimistic_loop
                loop_iter = context.loop_iter
                files = sorted(context.files)
                context.cache = hashlib.sha256(bytes(str(files), 'utf-8')).hexdigest() + \
                    f"-optimistic{optimistic_loop}-iter{loop_iter}"

                """
                We append the cloud env and the branch name (or None) to the cache key to make it different across
                branches to avoid wrong cloud cache collisions.
                """
                branch = context.prover_version if context.prover_version else ''
                context.cache += f'-{context.server}-{branch}'
                is_installed, package, version = Util.get_package_and_version()
                if is_installed:
                    context.cache += f'-{package}-{version}'
                # sanitize so we don't create nested "directories" in s3
                context.cache = context.cache.replace("/", "-").replace(" ", "-")
                context_logger.debug(f"setting cache key to {context.cache}")


def write_output_conf_to_path(json_content: Dict[str, Any], path: Path) -> None:
    """
    Write the json object to the path
    @param json_content: the json object
    @param path: the location of the output path
    @:return: None
    """
    with path.open("w+") as out_file:
        json.dump(json_content, out_file, indent=4, sort_keys=True)


def handle_flags_in_args(args: List[str], app: Type[App.CertoraApp]) -> None:
    """
    For argparse flags are strings that start with a dash. Some arguments get flags as value.
    The problem is that argparse will not treat the string as a value but rather as a new flag. There are different ways
    to prevent this. One way that was used in the past in certoraRun was to surround the string value with single
    quotes, double quotes or both. This technique complicates the value syntax and is error prune. A different technique
    is to precede the dash with a white space. That is something the tool can do for the user. In addition, if the user
    did add quotes (single or double) around a value they will be removed. Examples:

        --java_args '-d'
        --java_args "-d"
        --java_args '"-d"'

    Will all be converted to " -d"

    """
    all_flags = list(map(lambda member: member.get_flag(), app.attr_class.attribute_list()))

    def surrounded(string: str, char: str) -> bool:
        if len(string) < 2:
            return False
        return string[0] == char and string[-1] == char

    for index, arg in enumerate(args):
        if arg in all_flags:
            continue

        while True:
            if arg and (surrounded(arg, '\'') or surrounded(arg, '\"')):
                arg = arg[1:-1]
            else:
                break
        if len(arg) > 0 and arg[0] == '-' and (args[index - 1] == Attrs.EvmProverAttributes.JAVA_ARGS.get_flag()):
            arg = f" {arg}"
        if arg != args[index]:
            args[index] = arg


def is_staging(context: CertoraContext) -> bool:
    if context.server is None:
        return False
    return context.server.upper() == Util.SupportedServers.STAGING.name


def is_supported_server(context: CertoraContext) -> bool:
    if context.server is None:
        return False
    return context.server.upper() in Util.SupportedServers.__members__


def is_minimal_cli_output(context: CertoraContext) -> bool:
    """
    Returns true if we are configured to emit minimal logs in the stdout of certora-cli.
    In particular, either of these cases:
    - if we run in ci or github action
    - context has short_output enabled
    """
    return Util.is_ci_or_git_action() or context.short_output


def __rename_key(context: CertoraContext, old_key: str, new_key: str) -> None:
    if old_key in vars(context):
        value = getattr(context, old_key)
        setattr(context, new_key, value)
        context.delete_key(old_key)


def should_run_local_speck_check(context: CertoraContext) -> bool:
    return not (context.disable_local_typechecking or Util.is_ci_or_git_action())


def run_typechecker(typechecker_name: str, with_typechecking: bool, args: List[str], print_errors: bool) -> None:
    """
    Runs a spec typechecker or syntax checker

    @param typechecker_name: the name of the jar that we should run for running typechecking
    @param with_typechecking: True if we want full typechecking including build (Solidity outputs) file,
                                False if we run only the leaner syntax checking.
    @param args: A list of strings of additional arguments to the typechecker jar.
    @param print_errors: Toggles printing the typechecker's errors to stderr.
    """
    # Find path to typechecker jar
    path_to_typechecker = Util.find_jar(typechecker_name)

    context_logger.debug(f"Path to typechecker is {path_to_typechecker}")
    if not path_to_typechecker.is_file():
        raise Util.CertoraUserInputError(f"Could not run type checker locally: file not found {path_to_typechecker}")

    cmd_str_list = ['java', '-jar', str(path_to_typechecker), '-buildDirectory', str(Util.get_build_dir())] + args
    if with_typechecking:
        cmd_str_list.append('-typeCheck')

    context_logger.debug(f"typechecking cmd: {' '.join(cmd_str_list)}")

    exit_code = Util.run_jar_cmd(cmd_str_list, False,
                                 custom_error_message="Failed to run Certora Prover locally. Please check the errors "
                                                      "below for problems in the specifications (.spec files) or the "
                                                      "prover_args defined in the .conf file.",
                                 logger_topic="type_check",
                                 print_err=print_errors)
    if exit_code != 0:
        raise Util.CertoraUserInputError(
            "CVL syntax or type check failed.\n Please fix the issue. "
            "Using --disable_local_typechecking to skip this check is strongly discouraged, "
            "as simple syntax errors will only be detected during the cloud run."
            "with --disable_local_typechecking is not recommended is not recommended as "
            "simple syntax failures will be visible only on the cloud run.")


def run_local_spec_check(with_typechecking: bool, context: CertoraContext, extra_args: List[str] = list(), print_errors: bool = True) -> None:
    """
    Runs the local type checker

    @param with_typechecking: If `True` will perform a full typecheck pass which requires Solidity build information.
                              Otherwise performs only a syntax check.

    @param context: The `CertoraContext`.

    @param extra_args: a list of additional arguments that should be sent to the typechecker.

    @param print_errors: Toggles printing the typechecker's errors to stderr. Default: True
    """

    args = collect_jar_args(context)
    if Util.is_java_installed(context.java_version):
        run_typechecker("Typechecker.jar", with_typechecking, args + extra_args, print_errors)
    else:
        raise Util.CertoraUserInputError("Cannot run local checks because of missing a suitable java installation. "
                                         "To skip local checks run with the --disable_local_typechecking flag")

def attrs_to_relative(context: CertoraContext) -> None:
    def to_relative(input_path: Union[str, Path]) -> Union[str, Path]:
        path = Path(input_path) if isinstance(input_path, str) else input_path
        if path.is_absolute():
            relative_path = os.path.relpath(path, cwd)   # Path.relative_to only works for subdirectories
            return str(relative_path) if isinstance(input_path, str) else relative_path
        return input_path

    def str_attr_to_relative(attr_name: str) -> None:
        attr_value = getattr(context, attr_name, None)
        if attr_value:
            setattr(context, attr_name, to_relative(attr_value))

    def list_attr_to_relative(attr_name: str) -> None:
        attr_value = getattr(context, attr_name, None)
        relative_paths = []
        if not attr_value:
            return
        for input_path in attr_value:
            path = Path(input_path) if isinstance(input_path, str) else input_path
            if path.is_absolute():
                relative_path = to_relative(path)
            else:
                relative_path = path
            relative_paths.append(str(relative_path) if isinstance(input_path, str) else relative_path)
        setattr(context, attr_name, relative_paths)

    def map_attr_to_relative(attr_name: str, separator: str) -> None:
        attr_value = getattr(context, attr_name, None)
        relative_paths = []
        if not attr_value:
            return
        for str_vall in attr_value:
            key, value = str_vall.split(separator)
            if Path(value).is_absolute():
                relative_paths.append(separator.join([key, str(to_relative(value))]))
            else:
                relative_paths.append(str_vall)
        setattr(context, attr_name, relative_paths)

    def packages_to_relative() -> None:
        map_attr_to_relative('packages', '=')

    def prover_resource_file_to_relative() -> None:
        map_attr_to_relative('prover_resource_files', ':')

    def verify_to_relative() -> None:
        attr_value = getattr(context, 'verify', None)
        if attr_value:
            contract, spec_file = attr_value.split(':')
            if Path(spec_file).is_absolute():
                context.verify = ':'.join([contract, str(to_relative(spec_file))])

    cwd = Path.cwd()
    str_attr_to_relative('packages_path')
    str_attr_to_relative('bytecode_spec')
    str_attr_to_relative('yul_abi')
    list_attr_to_relative('files')
    list_attr_to_relative('bytecode_jsons')
    packages_to_relative()
    prover_resource_file_to_relative()
    verify_to_relative()

def get_map_attribute_value(context: CertoraContext, path: Path, attr_name: str) -> Optional[Union[str, bool]]:

    value = getattr(context, attr_name, None)
    if value:
        return value

    map_value = getattr(context, f"{attr_name}_map", None)
    if not map_value:
        return None  # No map value defined
    for key, entry_value in map_value.items():
        # Split key to handle contract:field syntax
        key_parts = key.split(':')
        pattern = key_parts[0]

        if Path(pattern).suffix == "":  # This is a contract pattern
            # Find contracts that match the pattern
            for contract_name, contract_file_path in context.contract_to_file.items():
                if fnmatch.fnmatch(contract_name, pattern):
                    # Check if this contract's file matches our target path
                    if Path(contract_file_path) == path:
                        return entry_value
        else:  # This is a file pattern
            # Match the file pattern against the path
            if glob.globmatch(str(path), pattern, flags=glob.GLOBSTAR):
                return entry_value
    return None  # No match
