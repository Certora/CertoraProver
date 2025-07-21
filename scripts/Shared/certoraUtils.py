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

import csv
import json
import os
import subprocess
from abc import ABCMeta
from enum import Enum, unique, auto
import sys
import platform
import shlex
import shutil
import re
import queue
import math
import urllib3.util
from collections import defaultdict
from types import SimpleNamespace

from typing import Any, Callable, Dict, List, Optional, Set, Union, Generator, Tuple, Iterable, Sequence, TypeVar
from pathlib import Path

scripts_dir_path = Path(__file__).parent.parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))
from contextlib import contextmanager
from Shared.ExpectedComparator import ExpectedComparator
import logging
import random
import time
import tempfile
from datetime import datetime
from rich.console import Console
from rich.text import Text

CONSOLE = Console()

io_logger = logging.getLogger("file")
# logger for issues calling/shelling out to external functions
process_logger = logging.getLogger("rpc")
# messages from the verification results
verification_logger = logging.getLogger("verification")
# errors handling csvs (???)
csv_logger = logging.getLogger("csv")
# logger for issues regarding type checking
typecheck_logger = logging.getLogger("type_check")
context_logger = logging.getLogger("context")
LEGAL_CERTORA_KEY_LENGTHS = [32, 40]

# bash colors
BASH_ORANGE_COLOR = "\033[33m"
BASH_END_COLOR = "\033[0m"
BASH_GREEN_COLOR = "\033[32m"
BASH_RED_COLOR = "\033[31m"
BASH_PURPLE_COLOR = "\033[35m"

VERIFICATION_ERR_MSG_PREFIX = "Prover found violations:"
VERIFICATION_SUCCESS_MSG = "No errors found by Prover!"

DEFAULT_SOLC_COMPILER = "solc"
DEFAULT_VYPER_COMPILER = 'vyper'

ENVVAR_CERTORA = "CERTORA"
KEY_SIGNUP_URL = "https://www.certora.com/signup"
CERTORA_INTERNAL_ROOT = Path(".certora_internal")
CERTORA_BUILD_CACHE_DIR_NAME = "build_cache"
PRODUCTION_PACKAGE_NAME = "certora-cli"
BETA_PACKAGE_NAME = "certora-cli-beta"
BETA_MIRROR_PACKAGE_NAME = "certora-cli-beta-mirror"
DEV_PACKAGE_NAME_PREFIX = f"{PRODUCTION_PACKAGE_NAME}-"
CERTORA_BUILD_DIRECTORY = Path("")
CERTORA_JARS = Path("certora_jars")
CERTORA_BINS = Path("certora_bins")
CERTORA_CLI_VERSION_METADATA = Path("CERTORA-CLI-VERSION-METADATA.json")
PRE_AUTOFINDER_BACKUP_DIR = Path(".pre_autofinders")
POST_AUTOFINDER_BACKUP_DIR = Path(".post_autofinders")
CERTORA_RUN_SCRIPT = "certoraRun.py"
CERTORA_RUN_APP = "certoraRun"
PACKAGE_FILE = Path("package.json")
REMAPPINGS_FILE = Path("remappings.txt")
RECENT_JOBS_FILE = Path(".certora_recent_jobs.json")
LAST_CONF_FILE = Path("run.conf")
EMV_JAR = Path("emv.jar")
CERTORA_SOURCES = Path(".certora_sources")
SOLANA_INLINING = "solana_inlining"
SOLANA_SUMMARIES = "solana_summaries"
CARGO_TOML_FILE = "cargo.toml"

ALPHA_PACKAGE_NAME = 'certora-cli-alpha'
ALPHA_PACKAGE_MASTER_NAME = ALPHA_PACKAGE_NAME + '-master'
# contract names in Solidity consists of alphanums, underscores and dollar signs
# https://docs.soliditylang.org/en/latest/grammar.html#a4.SolidityLexer.Identifier
SOLIDITY_ID_SUBSTRING_RE = r"[a-zA-Z_$][a-zA-Z0-9_$]*"  # string *contains* a valid solidity ID
SOLIDITY_ID_STRING_RE = rf"^{SOLIDITY_ID_SUBSTRING_RE}$"  # string *is* a valid solidity ID
CWD_FILE = '.cwd'
PROJECT_DIR_FILE = '.project_directory'
NEW_LINE = '\n'  # for new lines in f strings
VY_EXT = '.vy'
SOL_EXT = '.sol'
YUL_EXT = '.yul'
EVM_SOURCE_EXTENSIONS = (SOL_EXT, VY_EXT, YUL_EXT)
EVM_EXTENSIONS = EVM_SOURCE_EXTENSIONS + ('.tac', '.json')
SOLANA_EXEC_EXTENSION = '.so'
SOROBAN_EXEC_EXTENSION = '.wasm'
VALID_EVM_EXTENSIONS = list(EVM_EXTENSIONS) + ['.conf']
VALID_FILE_EXTENSIONS = VALID_EVM_EXTENSIONS + [SOLANA_EXEC_EXTENSION, SOROBAN_EXEC_EXTENSION]
# Type alias definition, not a variable
CompilerVersion = Tuple[int, int, int]
MAP_SUFFIX = '_map'
SUPPRESS_HELP_MSG = "==SUPPRESS=="
MAX_FLAG_LENGTH = 31
HELP_TABLE_WIDTH = 97
DEFAULT_RANGER_RANGE = '5'
DEFAULT_RANGER_LOOP_ITER = '3'
DEFAULT_RANGER_FAILURE_LIMIT = '1'

T = TypeVar('T')

class CallOnce:
    def __init__(self, func: Callable):
        self.func = func
        self.called = False

    def __call__(self, *args, **kwargs):  # type: ignore[no-untyped-def]
        if not self.called:
            self.called = True
            return self.func(*args, **kwargs)


@unique
class SupportedServers(Enum):
    """
    mapping between servers and their url
    """
    STAGING = 'https://vaas-stg.certora.com'
    PRODUCTION = 'https://prover.certora.com'


def get_build_dir() -> Path:
    return CERTORA_BUILD_DIRECTORY


def get_random_build_dir() -> Path:
    for tries in range(3):
        build_uuid = f"{datetime.now().strftime('%y_%m_%d_%H_%M_%S')}_{random.randint(0, 999):03d}"
        build_dir = CERTORA_INTERNAL_ROOT / Path(build_uuid)
        if not build_dir.exists():
            return build_dir
        time.sleep(0.5)
    raise Exception('Unable to generate random build directory')


def reset_certora_internal_dir(build_dir_str: Optional[str] = None) -> None:
    """
    build_dir_str constraints are defined in type_build_dir (basically not an existing file/dir and open for creating
    a new directory
    """
    global CERTORA_BUILD_DIRECTORY
    if build_dir_str is None:
        build_dir = get_random_build_dir()
        safe_create_dir(CERTORA_INTERNAL_ROOT)  # create, allow generating symlink to latest when directory is empty
        if is_windows():
            build_dir = Path(".")
    else:
        build_dir = Path(build_dir_str)

    CERTORA_BUILD_DIRECTORY = Path(build_dir)

    # Always remove the "latest" symlink, if the build path changed then it might be pointing to garbage.
    last_build = CERTORA_INTERNAL_ROOT / 'latest'
    last_build.unlink(missing_ok=True)
    if build_dir_str is None:
        # We are using the default dir, with the BUILD_UUID. Add a symlink to the last one to run, for ease of use.
        # Note that when running concurrently 'latest' may not be well-defined, but for local usage it could be useful.

        try:
            last_build.symlink_to(build_dir.relative_to(build_dir.parent), target_is_directory=True)
        except Exception as e:
            # This is a nice-to-have thing, so if we fail for some reason (e.g. permission error)
            # we'll just continue without it.
            io_logger.warning(f"Failed to create the '{last_build}' symlink. {e}")


def path_in_build_directory(path: Path) -> Path:
    return path if (path.parent == get_build_dir()) else get_build_dir() / path


def path_in_build_or_internal(path: Path) -> Path:
    if get_build_dir() != Path(""):
        return path_in_build_directory(path)
    elif CERTORA_INTERNAL_ROOT.exists():
        return CERTORA_INTERNAL_ROOT / path
    else:
        return path


def get_certora_build_cache_dir() -> Path:
    cache_dir = CERTORA_INTERNAL_ROOT / CERTORA_BUILD_CACHE_DIR_NAME
    safe_create_dir(cache_dir)
    return cache_dir


def get_certora_config_dir() -> Path:
    return path_in_build_directory(Path(".certora_config"))


def get_certora_sources_dir() -> Path:
    return path_in_build_directory(CERTORA_SOURCES)


def get_certora_build_file() -> Path:
    return path_in_build_directory(Path(".certora_build.json"))


def get_certora_verify_file() -> Path:
    return path_in_build_directory(Path(".certora_verify.json"))


def get_built_output_props_file() -> Path:
    """
    build output props file will hold artifacts of build output,
    e.g. whether autofinders instrumentation and compilation failed or not
    """
    return path_in_build_directory(Path(".certora_build_output_props.json"))


def get_build_cache_indicator_file() -> Path:
    """
    build_cache indicator file to mark whether build cache gave a hit or a miss
    """
    return path_in_build_directory(Path(".certora_build_cache_indicator.json"))


def get_certora_metadata_file() -> Path:
    return path_in_build_directory(Path(".certora_metadata.json"))


def get_configuration_layout_data_file() -> Path:
    return path_in_build_directory(Path(".configuration_layout.json"))


def get_resource_errors_file() -> Path:
    return path_in_build_or_internal(Path("resource_errors.json"))


def get_debug_log_file() -> Path:
    return path_in_build_or_internal(Path("certora_debug_log.txt"))


def get_extension_info_file() -> Path:
    return path_in_build_directory(Path(".vscode_extension_info.json"))


def get_zip_output_url_file() -> Path:
    return CERTORA_INTERNAL_ROOT / '.zip-output-url.txt'


def get_recent_jobs_file() -> Path:
    return CERTORA_INTERNAL_ROOT / RECENT_JOBS_FILE


# for both files and directories
def get_from_certora_internal(name: str) -> Path:
    return CERTORA_INTERNAL_ROOT / name


def get_last_conf_file() -> Path:
    return path_in_build_directory(LAST_CONF_FILE)


class SolcCompilationException(Exception):
    pass


class CertoraUserInputError(Exception):
    def __init__(self, message: str, orig: Optional[Exception] = None, more_info: str = '') -> None:
        super().__init__(message)
        self.orig = orig
        self.more_info = more_info


# Internal exceptions that are due to bugs in our implementation
class ImplementationError(Exception):
    pass

# Prover failed on generated mutation
class BadMutationError(Exception):
    pass

class ExitException(Exception):
    def __init__(self, message: str, exit_code: int):
        super().__init__(message)
        self.exit_code = exit_code  # Store the integer data

MIN_JAVA_VERSION = 11  # minimal java version to run the local type checker jar


def text_style(txt: str, style: str) -> str:
    if not CONSOLE.is_terminal:
        return txt
    text = Text()
    text.append(txt, style=style)
    return text.markup


def text_blue(txt: str) -> str:
    return text_style(txt, 'bold blue')


def __colored_text(txt: str, color: str) -> str:
    return color + txt + BASH_END_COLOR


def orange_text(txt: str) -> str:
    return __colored_text(txt, BASH_ORANGE_COLOR)


def purple_text(txt: str) -> str:
    return __colored_text(txt, BASH_PURPLE_COLOR)


def red_text(txt: str) -> str:
    return __colored_text(txt, BASH_RED_COLOR)


def green_text(txt: str) -> str:
    return __colored_text(txt, BASH_GREEN_COLOR)


def print_completion_message(txt: str, flush: bool = False) -> None:
    print(green_text(txt), flush=flush)


def print_rich_link(link: str) -> str:
    return f"[link={link}]{link}[/link]"


def print_progress_message(txt: str, flush: bool = False) -> None:
    if not is_ci_or_git_action():
        print(txt, flush=flush)


def is_ci_or_git_action() -> bool:
    if os.environ.get("GITHUB_ACTIONS", False) or os.environ.get("CI", False):
        return True
    return False


def get_certora_ci_name() -> Union[str, None]:
    return os.environ.get('CERTORA_CI_CLIENT', None)


def remove_file(file_path: Union[str, Path]) -> None:  # TODO - accept only Path
    if isinstance(file_path, str):
        try:
            os.remove(file_path)
        except OSError:
            pass
    else:
        file_path.unlink(missing_ok=True)


def abs_norm_path(file_path: Union[str, Path]) -> Path:
    """
    This functions returns normalized version of the path which is absolute path without . and .. path parts
    Unlike th pathlib function resolve() this function does not resolve symbolic links since Solidity imports
    and package info refers to paths that may include symbolic links
    """
    path_str = str(file_path) if isinstance(file_path, Path) else file_path
    norm_path = os.path.normpath(os.path.abspath(path_str))
    return Path(norm_path)


def get_package_and_version() -> Tuple[bool, str, str]:
    """
    @return: A tuple (is insatlled package, package name, version)
    is installed package - True if we run an installed package, false if we run as a local script
    package name - either certora-cli / certora-cli-beta, or certora-cli-alpha-master and others
    version - the python package version in format X.Y.Z if found
    """
    # Note: the most common reason not to have an installed package is in circleci
    version_metadata_file = get_package_resource(CERTORA_JARS / CERTORA_CLI_VERSION_METADATA)
    if not version_metadata_file.exists():
        return False, "", ""

    try:
        with open(version_metadata_file) as version_metadata_handle:
            version_metadata = json.load(version_metadata_handle)
            if "name" in version_metadata and "version" in version_metadata:
                return True, version_metadata["name"], version_metadata["version"]
            else:
                raise Exception(f"Invalid format for {version_metadata_file}, got {version_metadata}")
    except OSError as e:  # json errors - better to just propagate up
        raise Exception(f"Failed to open {version_metadata_file}: {e.strerror}")


def check_results_from_file(output_path: str, expected_filename: str) -> bool:
    with open(output_path) as output_file:
        actual = json.load(output_file)
        return check_results(actual, expected_filename)


def check_results(actual: Dict[str, Any], expected_filename: str) -> bool:
    actual_results = actual
    if os.path.exists(expected_filename):  # compare actual results with expected
        with open(expected_filename) as expected_file:
            expected = json.load(expected_file)
        if 'rules' in actual_results and 'rules' in expected:
            comparator = ExpectedComparator(actual_results["rules"], expected["rules"], {}, {})
            if comparator.has_violations:
                verification_logger.error(f'{VERIFICATION_ERR_MSG_PREFIX}')
                print(comparator.get_violations_table())
                return False
        if ('rules' not in actual_results) ^ ('rules' not in expected):
            verification_logger.error(f'{VERIFICATION_ERR_MSG_PREFIX}')
            return False

        print_completion_message(f"{VERIFICATION_SUCCESS_MSG} (based on {expected_filename})")
        return True
    # if expected results are not defined
    # traverse results and look for violation
    errors: Dict[str, List[str]] = defaultdict(list)

    if "rules" not in actual_results:
        errors["no_results"].append("No rules in results")
    elif len(actual_results["rules"]) == 0:
        errors["no_results"].append("No rule results found."
                                    "Please make sure you wrote the rule and method names correctly.")
    else:
        for rule, res in actual_results["rules"].items():
            if isinstance(res, str):
                if res != "SUCCESS":
                    errors[rule].append(res)
            else:
                check_violation_recursively(rule, res, errors)

    if len(errors) > 0:
        to_print = print_error_dict(errors)
        verification_logger.error(f"{VERIFICATION_ERR_MSG_PREFIX}\n{to_print}")
        return False

    print_completion_message(VERIFICATION_SUCCESS_MSG)
    return True


def print_error_dict(errors: Dict[str, List[str]]) -> str:
    if "no_results" in errors:
        return errors["no_results"][0]
    result = ""
    for rule, error in errors.items():
        result += f"\n[rule] {rule}: " + "".join(error)
    return result


def check_violation_recursively(rule: str, actual_results: Dict[str, Any], errors: Dict[str, List[str]]) -> None:
    for key, val in actual_results.items():
        if isinstance(val, list):
            if key != "SUCCESS":
                for func in val:
                    errors[rule].append(f'\n    [func] {func}')
        elif isinstance(val, str):
            if val != "SUCCESS":
                errors[rule].append(f'\n    [func] {key}: {val}')
        else:
            check_violation_recursively(rule, val, errors)


def is_windows() -> bool:
    return platform.system() == 'Windows'


def replace_file_name(old_file: str, new_file_name: str) -> str:
    """
    :param old_file: the full original path
    :param new_file_name: the new base name of the file
    :return: file_with_path with the base name of the file replaced with new_file_name,
             preserving the file extension and the base path
    """
    old_file_path = Path(old_file)
    return str(old_file_path.parent / f'{new_file_name}')


def safe_create_dir(path: Path) -> None:
    if path.is_dir():
        io_logger.debug(f"directory {path} already exists")
        return
    path.mkdir(parents=True, exist_ok=True)


def safe_copy_folder(source: Path, dest: Path, ignore_patterns: Callable[[str, List[str]], Iterable[str]]) -> None:
    """
    Safely copy source to dest. Assume dest does not exists.
    On certain OS/kernels/FS, copying a folder f into a subdirectory of f will
    send copy tree into an infinite loop. This sidesteps the problem by first copying through a temporary folder.
    """
    copy_temp = tempfile.mkdtemp()
    shutil.copytree(source, copy_temp, ignore=ignore_patterns, dirs_exist_ok=True)
    shutil.copytree(copy_temp, dest)
    shutil.rmtree(copy_temp, ignore_errors=True)


def as_posix(path: str) -> str:
    """
    Converts path from windows to unix
    :param path: Path to translate
    :return: A unix path
    """
    return path.replace("\\", "/")


def normalize_double_paths(path: str) -> str:
    """
    Handles an oddity of paths from absolutePath nodes in solc AST,
    specifically "//" instead of just "/"
    """
    return path.replace("//", "/")


def abs_posix_path(path: Union[str, Path]) -> str:
    """
    Returns the absolute path, unix style
    :param path: Path to change
    :return: A posix style absolute path string
    """
    return as_posix(str(abs_posix_path_obj(path)))


def abs_posix_path_obj(path: Union[str, Path]) -> Path:
    """
    This function should be replaced with abs_norm_path once we clean up all references to windows paths
    https://certora.atlassian.net/browse/CERT-7589
    """
    sanitized_path = as_posix(str(path))  # Windows works with / as file separator, so we always convert
    return abs_norm_path(sanitized_path)

def abs_posix_path_relative_to_root_file(rel_path: Path, root_file: Path) -> Path:
    """
     Returns the absolute path, unix style
     :param rel_path: Relative path to change.
     :param root_file: rel_path is assumed to be relative to the directory of the file root_file.
     :return: A posix style absolute path
    """
    root_dir = root_file.parent
    file_path = root_dir / rel_path
    return Path(abs_posix_path(file_path))


def convert_path_for_solc_import(path: Union[Path, str]) -> str:
    """
    Converts a path to a solc-compatible import.
    Solc paths only accept / as a file separator, and do not accept drives in path
    :param path: A path to convert
    :return: the converted path
    """
    unix_file_sep_path = abs_norm_path(path)
    driveless_path = re.sub("^[a-zA-Z]:", "", str(unix_file_sep_path))
    return as_posix(os.path.abspath(driveless_path))


def remove_and_recreate_dir(path: Path) -> None:
    if path.is_dir():
        shutil.rmtree(path)
    safe_create_dir(path)


def prepare_call_args(cmd: str) -> List[str]:
    """
    Takes a command line as a string and returns a list of strings that consist that line.
    Importantly, does not interpret forward slashes used for newline continuation as a word.
    We replace a call to a Python script with a call to the Python executable first.
    We also fix the path to the certora root directory
    :param cmd - the command line we split. We assume it contains no comments!
    :return - a list of words that make up the command line given
    """
    if is_windows():
        """
        There is no good shlex alternative to Windows, but quoting works well, and spaces should work too
        see https://stackoverflow.com/questions/33560364/python-windows-parsing-command-lines-with-shlex
        """
        split = cmd.split()
    else:
        # Using shlex here is necessary, as otherwise quotes are not handled well especially in lists like "a/path",.
        split = shlex.split(cmd)
    if split[0].endswith('.py'):
        # sys.executable returns a full path to the current running python, so it's good for running our own scripts
        certora_root = get_certora_root_directory()
        args = [sys.executable, (certora_root / split[0]).as_posix()] + split[1:]
    else:
        args = split
    return args


def get_certora_root_directory() -> Path:
    return Path(os.getenv(ENVVAR_CERTORA, os.getcwd()))


def get_certora_envvar() -> str:
    return os.getenv(ENVVAR_CERTORA, "")


def which(filename: str) -> Optional[str]:
    if is_windows() and not filename.endswith(".exe"):
        filename += ".exe"

    # TODO: find a better way to iterate over all directories in $Path
    for dirname in os.environ['PATH'].split(os.pathsep) + [os.getcwd()]:
        candidate = os.path.join(dirname, filename)
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return filename

    return None


def read_json_file(file_name: Path) -> Dict[str, Any]:
    with file_name.open() as json_file:
        json_obj = json.load(json_file)
        return json_obj


def write_json_file(data: Union[Dict[str, Any], List[Dict[str, Any]]], file_name: Path) -> None:
    with file_name.open("w+") as json_file:
        json.dump(data, json_file, indent=4)


def output_to_csv(filename: str, fieldnames: List[str], row: Dict[str, Any]) -> bool:
    """
        Creates and appends the row to csv file

        @param filename: csv filename without the extension
        @param fieldnames: headers of the csv file
        @param row: data to append (as a row) to the csv file

        @return: true if completed successfully
    """
    try:
        csv_path = Path(f'{filename}.csv')
        if csv_path.exists():
            with csv_path.open("a") as f:
                writer = csv.DictWriter(f, fieldnames=fieldnames)
                writer.writerow(row)
        else:
            with csv_path.open('a+') as f:
                writer = csv.DictWriter(f, fieldnames=fieldnames)
                writer.writeheader()
                writer.writerow(row)
        return True
    except ValueError as e:  # when the row contains fields not in fieldnames (file header)
        csv_logger.error("value conversion failed", exc_info=e)
        return False


class NoValEnum(Enum):
    """
    A class for an enum where the numerical value has no meaning.
    """

    def __repr__(self) -> str:
        """
        Do not print the value of this enum, it is meaningless
        """
        return f'<{self.__class__.__name__}.{self.name}>'

    @classmethod
    def values(cls) -> List[str]:
        return list(map(lambda c: str(c), cls))  # type: ignore

    def __str__(self) -> str:
        return self.name.lower()


def is_hex_or_dec(s: str) -> bool:
    """
    @param s: A string
    @return: True if it is a decimal or hexadecimal number
    """
    try:
        int(s, 16)
        return True
    except ValueError:
        return False


def is_hex(number: str) -> bool:
    """
    @param number: A string
    @return: True if the number is a hexadecimal number:
        - Starts with 0
        - Second character is either x or X
        - All other characters are digits 0-9, or letters a-f or A-F
    """
    match = re.search(r'^0[xX][0-9a-fA-F]+$', number)
    return match is not None


def hex_str_to_cvt_compatible(s: str) -> str:
    """
    @param s: A string representing a number in base 16 with '0x' prefix
    @return: A string representing the number in base 16 but without the '0x' prefix
    """
    assert is_hex(s)
    return re.sub(r'^0[xX]', '', s)


def decimal_str_to_cvt_compatible(s: str) -> str:
    """
    @param s: A string representing a number in base 10
    @return: A string representing the hexadecimal representation of the number, without the '0x' prefix
    """
    assert s.isnumeric()
    return re.sub(r'^0[xX]', '', hex(int(s)))


def split_by_delimiter_and_ignore_character(input_str: str, delimiter: str, ignore_splitting_char: str,
                                            last_delimiter_chars_to_include: int = 0) -> List[str]:
    """
    Splits a string by a given delimiter, ignoring anything between a special pair of characters.

    For example, if the delimiter is a comma, and the ignore splitting character is an asterisk, then the input:
    hello,we,dislike*splitting,if,*it,is,complex
    Will return:
    ['hello', 'we', 'dislike*splitting,if,*it', 'is', 'complex']

    If we want to include some of the last characters of the delimiter in the preceding substring, we should specify a
    positive number for the parameter last_delimiter_chars_to_include. A negative number will not include that amount
    of characters after the delimiter in the substrings.

    A more complex example, for delimiter ", -", ignore character ", the input string:

    "-b=2, -assumeUnwindCond, -rule=bounded_supply, -m=withdrawCollateral(uint256, (bool, bool)), -regressionTest,
         -solvers=bitwuzla, yices"

    will return:
    ['-b=2',
    '-assumeUnwindCond',
    '-rule=bounded_supply',
    '-m=withdrawCollateral(uint256, (bool, bool))',
    '-regressionTest',
    '-solvers=bitwuzla, yices']

    Assumptions:
    - We do not check for the validity of the last_delimiter_chars_to_include parameter. If it is too large or too
    small, we will get an out-of-bounds error.

    Notes:
    - We currently do not support a different character to start and end an ignored section, like an opening and
    closing parenthesis.

    @param input_str a string we want to split to substrings
    @param delimiter a sequence of characters by which we split
    @param ignore_splitting_char a character that must appear an even amount of times in the string. Between every
           pair of appearances, we skip splitting
    @param last_delimiter_chars_to_include a number of characters from the end of the delimeter to include in the
           following substring. See above.
    @returns a list of strings that represents individual settings to pass to the jar. They might have illegal syntax.
    """

    if input_str.count(ignore_splitting_char) % 2 != 0:
        raise ValueError(f'Uneven number of {ignore_splitting_char} in {input_str}')

    substrings = []  # type: List[str]

    i = 0
    substring_start_index = 0
    ignore_splitting = False  # if we are between the two ignore characters, we skip splitting

    while i < len(input_str):
        if input_str[i] == ignore_splitting_char:
            ignore_splitting = not ignore_splitting
        elif not ignore_splitting:
            if i + len(delimiter) < len(input_str):
                if input_str[i:i + len(delimiter)] == delimiter:
                    substrings.append(input_str[substring_start_index:i])
                    i += len(delimiter)
                    substring_start_index = i - last_delimiter_chars_to_include
                    continue
        i += 1

    if substring_start_index < len(input_str):
        substrings.append(input_str[substring_start_index:])

    return substrings


def string_distance_function(input_str: str, dictionary_str: str) -> float:
    """
    Calculates a modified levenshtein distance between two strings. The distance function is modified to penalize less
    for more common user mistakes.
    Each subtraction, insertion or replacement of a character adds 1 to the distance of the two strings, unless:
    1. The input string is a prefix of the dictionary string or vice versa - the distance is 0.1 per extra letter.
    2. The replacement is between two equal letter except casing - adds nothing to the distance
    3. The subtraction/addition is of an underscore, adds 0.1 to the distance
    4. Repeated characters cost nothing, e.g. 'balloon', 'baloon' and 'balllllloooonn' have distance 0 from each other

    :param input_str: the string the user gave as input, error-prone
    :param dictionary_str: a legal string we compare the wrong input to
    :return a distance measure between the two string. A low number indicates a high probably the user to give the
            dictionary string as input
    """
    # treat special cases first:

    input_str = input_str.lower()
    dictionary_str = dictionary_str.lower()

    if input_str == dictionary_str:
        return 0
    if dictionary_str.startswith(input_str) or input_str.startswith(dictionary_str):
        diff = abs(len(input_str) - len(dictionary_str))
        return 0.1 * diff

    """
    We are calculating the Levenshtein distance with a dynamic programming algorithm based on
    https://en.wikipedia.org/wiki/Levenshtein_distance

    Each matrix value distance_matrix[row][col] we calculate represent the distance between the two prefix substrings
    input_str[0..row-1] and dictionary_str[0..col-1]

    NOTE: our implementation differs from the classic implementation in that the cost of deletions/insertions is not
    constant
    """

    # Initialize matrix of zeros
    rows = len(input_str) + 1
    cols = len(dictionary_str) + 1

    distance_matrix = []
    for row in range(rows):
        column = []
        for j in range(cols):
            column.append(0.0)
        distance_matrix.append(column)

    # Populate matrix of zeros with the indices of each character of both strings
    for i in range(1, rows):
        distance_matrix[i][0] = i
    for k in range(1, cols):
        distance_matrix[0][k] = k

    # Calculate modified Levenshtein distance
    for col in range(1, cols):
        for row in range(1, rows):
            if input_str[row - 1] == dictionary_str[col - 1]:
                # No cost if the characters are the same up to casing in the two strings
                cost: float = 0
            elif input_str[row - 1] == '_' or dictionary_str[col - 1] == '_':
                # common mistake
                cost = 0.1
            else:
                # full cost
                cost = 1
            distance_matrix[row][col] = min(distance_matrix[row - 1][col] + cost,  # Cost of deletions
                                            distance_matrix[row][col - 1] + cost,  # Cost of insertions
                                            distance_matrix[row - 1][col - 1] + cost)  # Cost of substitutions

    return distance_matrix[rows - 1][cols - 1]


def get_closest_strings(input_word: str, word_dictionary: List[str],
                        distance: Callable[[str, str], float] = string_distance_function,
                        max_dist: float = 4, max_dist_ratio: float = 0.5, max_suggestions: int = 2,
                        max_delta: float = 0.2) -> List[str]:
    """
    Gets an input word, which doesn't belong to a dictionary of predefined words, and returns a list of the closest
    words from the dictionary, with respect to a distance function.

    :param input_word: The word we look for closest matches of.
    :param word_dictionary: A collection of words to suggest matches from.
    :param distance: The distance function we use to measure proximity of words.
    :param max_dist: The maximal distance between words, over which no suggestions will be made.
    :param max_dist_ratio: A maximal ratio between the distance and the input word's length. No suggestions will be made
                           over this ratio.
    :param max_suggestions: The maximal number of suggestions to return.
    :param max_delta: We stop giving suggestions if the next best suggestion is worse than the one before it by more
                      than the maximal delta.
    :return: A list of suggested words, ordered from the best match to the worst.
    """
    if (len(input_word) == 0):
        # empty input word, nothing is close to that.
        return []

    distance_queue: queue.PriorityQueue = queue.PriorityQueue()  # Ordered in a distance ascending order

    for candidate_word in word_dictionary:
        dist = distance(input_word, candidate_word)
        distance_queue.put((dist, candidate_word))

    all_suggestions: List[str] = []
    last_dist = None

    while not distance_queue.empty() and len(all_suggestions) <= max_suggestions:
        suggested_dist, suggested_rule = distance_queue.get()
        if suggested_dist > max_dist or suggested_dist / len(input_word) > max_dist_ratio:
            break  # The distances are monotonically increasing
        if (last_dist is None) or (suggested_dist - last_dist <= max_delta):
            all_suggestions.append(suggested_rule)
            last_dist = suggested_dist

    return all_suggestions


def get_readable_time(milliseconds: int) -> str:
    # calculate (and subtract) whole hours
    milliseconds_in_hour = 3600000  # 1000 * 60 * 60
    hours = math.floor(milliseconds / milliseconds_in_hour)
    milliseconds -= hours * milliseconds_in_hour

    # calculate (and subtract) whole minutes
    milliseconds_in_minute = 60000  # 1000 * 60
    minutes = math.floor(milliseconds / milliseconds_in_minute)
    milliseconds -= minutes * milliseconds_in_minute

    # seconds
    seconds = math.floor(milliseconds / 1000)

    milliseconds -= seconds * 1000
    duration = ""

    if hours > 0:
        duration += f"{hours}h "
    duration += f"{minutes}m {seconds}s {milliseconds}ms"
    return duration


def flush_stdout() -> None:
    print("", flush=True)


def flatten_nested_list(nested_list: List[list]) -> list:
    """
    @param nested_list: A list of lists: [[a], [b, c], []]
    @return: a flat list, in our example [a, b, c]. If None was entered, returns None
    """
    return [item for sublist in nested_list for item in sublist]


def flatten_set_list(set_list: List[Set[Any]]) -> List[Any]:
    """
    Gets a list of sets, returns a list that contains all members of all sets without duplicates
    :param set_list: A list containing sets of elements
    :return: A list containing all members of all sets. There are no guarantees on the order of elements.
    """
    ret_set = set()
    for _set in set_list:
        for member in _set:
            ret_set.add(member)
    return list(ret_set)


def find_jar(jar_name: str) -> Path:
    # if we are a dev running certoraRun.py (local version), we want to get the local jar
    # if we are a dev running an installed version of certoraRun, we want to get the installed jar
    # how would we know? if $CERTORA is not empty, __file__ is relative to $CERTORA,
    # and we have a local jar, then we need the local jar. Otherwise we take the installed one.
    # A regular user should not have $CERTORA enabled, or the local jar doesn't exist.
    # if $CERTORA is set to site-packages, it should be fine too. (but let's hope nobody does that.)
    certora_home = get_certora_envvar()

    if certora_home != "":
        local_certora_path = Path(certora_home) / CERTORA_JARS / jar_name
        if Path(__file__).is_relative_to(Path(certora_home)) and local_certora_path.is_file():
            return local_certora_path

    return get_package_resource(CERTORA_JARS / jar_name)


def get_package_resource(resource: Path) -> Path:
    """
    Returns a resource installed in the package. Since we are in
    `site-packages/certora_cli/Shared/certoraUtils.py`, we go 3 parents up, and then can access, e.g.,
    - certora_jars (sibling to certora_cli)
    """
    return Path(__file__).parents[2] / resource


def get_java_version() -> str:
    """
    Retrieve installed java version
    @return installed java version on success or empty string
    """
    # Check if java exists on the machine
    java = which("java")
    if java is None:
        return ''

    try:
        java_version_str = subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT).decode()
        java_version = re.search(r'version \"([\d\.]+)\"', java_version_str).groups()[0]  # type: ignore[union-attr]

        return java_version
    except (subprocess.CalledProcessError, AttributeError):
        typecheck_logger.debug("Couldn't find the installed Java version.")
        return ''


def is_java_installed(java_version: str) -> bool:
    """
    Check that java is installed and with a version that is suitable for running certora jars
    @return True on success
    """
    if not java_version:
        typecheck_logger.warning(
            f"`java` is not installed. Installing Java version {MIN_JAVA_VERSION} or later will enable faster "
            f"CVL specification syntax checking before uploading to the cloud.")
        return False

    else:
        major_java_version = java_version.split('.')[0]
        if int(major_java_version) < MIN_JAVA_VERSION:
            typecheck_logger.warning("Installed Java version is too old to check CVL specification files locally. "
                                     f" Java version should be at least {MIN_JAVA_VERSION} to allow local java-based "
                                     "type checking")

            return False

    return True


def run_jar_cmd(jar_cmd: List[str], override_exit_code: bool, custom_error_message: Optional[str] = None,
                logger_topic: Optional[str] = "run", print_output: bool = False, print_err: bool = True) -> int:
    """
    @return: 0 on success, an error exit code otherwise
    @param override_exit_code if true, always returns 0 (ignores/overrides non-zero exit codes of the jar subprocess)
    @param custom_error_message if specified, determines the header of the error message printed for non-zero exit codes
    @param logger_topic the topic of the logger being used
    @param print_output If True, the process' standard output will be printed on the screen
    @param print_err If True, the process' standard error will be printed on the screen
    @param jar_cmd a command line that runs a jar file (CertoraProver or Typechecker)

    One may be confused why we need both override_exit_code and print_err, that have a similar effect:
    logs are not printed if either override_exit_code is enabled or print_err is disabled.

    The difference is that override_exit_code also controls the return value of this function and print_err
    only affects the logging.

    The use case for setting override_exit_code is the comparison of expected files instead of the Prover's default
    exit code which is failure in any case of not all-successful rules.

    """
    logger = logging.getLogger(logger_topic)
    try:
        if print_output:
            stdout_stream = None
        else:
            stdout_stream = subprocess.DEVNULL

        run_result = \
            subprocess.run(jar_cmd, shell=False, universal_newlines=True, stderr=subprocess.PIPE, stdout=stdout_stream)

        return_code = run_result.returncode
        if return_code:

            default_msg = f"Execution of command \"{' '.join(jar_cmd)}\" terminated with exitcode {return_code}."
            err_msg_header = custom_error_message if custom_error_message is not None else default_msg
            if print_err:
                logger.error(err_msg_header)
            else:
                logger.info(err_msg_header)

            # We log all lines in stderr, as they contain useful information we do not want the
            # Python loggers to miss
            # specifically, the errors go only to the log if we disabled printing of errors or exit code override is on
            log_level = logging.INFO if (override_exit_code or not print_err) else logging.CRITICAL
            stderr_lines = run_result.stderr.splitlines()
            for line in stderr_lines:
                logger.log(log_level, line)

            if not override_exit_code:  # else, we return 0
                return return_code

        return 0
    except Exception as e:
        logger.error(e)
        return 1


def print_failed_to_run(cmd: str) -> None:
    print()
    print(f"Failed to run {cmd}")
    if is_windows() and cmd.find('solc') != -1 and cmd.find('exe') == -1:
        print("did you forget the .exe extension for solcXX.exe??")
    print()


# TODO move to CompilerCollectorFactory.py
def run_compiler_cmd(compiler_cmd: str, output_file_name: str, wd: Path,
                     compiler_input: Optional[bytes] = None) -> None:
    """
    @param compiler_cmd The command that runs the smart contract compiler, i.e. solc/vyper executable
    @param output_file_name the name of the .stdout and .stderr file
    @param config_path the directory of the generated files
    @param wd the working directory for the compiler
    @param compiler_input input to the compiler subprocess
    """
    process_logger.debug(f"Running cmd {compiler_cmd}")
    build_start = time.perf_counter()

    output_file_path = Path(output_file_name)
    if output_file_path.suffix == ".json" :
        stderr_fname = str(output_file_path.with_suffix(".stderr.json"))
        stdout_fname = str(output_file_path.with_suffix(".stdout.json"))
    else:
        stdout_fname = f"{output_file_name}.stdout"
        stderr_fname = f"{output_file_name}.stderr"

    stdout_name = get_certora_config_dir() / stdout_fname
    stderr_name = get_certora_config_dir() / stderr_fname
    process_logger.debug(f"stdout, stderr = {stdout_name}, {stderr_name}")

    with stdout_name.open('w+') as stdout:
        with stderr_name.open('w+') as stderr:
            try:
                args = prepare_call_args(compiler_cmd)
                exitcode = subprocess.run(args, stdout=stdout, stderr=stderr, input=compiler_input, cwd=wd).returncode
                if exitcode:
                    msg = f"Failed to run {compiler_cmd}, exit code {exitcode}"
                    with open(stderr_name, 'r') as stderr_read:
                        for line in stderr_read:
                            print(line)
                    raise Exception(msg)
                else:
                    process_logger.debug(f"Exitcode {exitcode}")
            except Exception as e:
                print(f"Error: {e}")
                print_failed_to_run(compiler_cmd)
                raise

    build_end = time.perf_counter()
    time_run = round(build_end - build_start, 4)
    process_logger.debug(f"Solc run {compiler_cmd} time: {time_run}")


@contextmanager
def change_working_directory(path: Union[str, os.PathLike]) -> Generator[None, None, None]:
    """
    Changes working directory and returns to previous on exit.
    Note: the directory will return to the previous even if an exception is thrown, for example: if path does not exist
    """
    prev_cwd = Path.cwd()
    os.chdir(path)
    try:
        yield
    finally:
        os.chdir(prev_cwd)


def file_is_not_empty(file_path: Path) -> bool:
    return file_path.exists() and file_path.stat().st_size != 0


class Singleton(type):
    """
    This is intended to be used as a metaclass to enforce only a single instance of a class can be created
    """
    __instancesinstances: Dict[Any, Any] = {}  # Mapping from a class type to its instance

    def __call__(cls, *args: Any, **kwargs: Any) -> Any:
        """
        returns the instance of a class if exists, otherwise constructs it
        """
        if cls not in cls.__instancesinstances:
            cls.__instancesinstances[cls] = super(Singleton, cls).__call__(*args, **kwargs)
        return cls.__instancesinstances[cls]

class AbstractAndSingleton(Singleton, ABCMeta):
    pass

def find_in(dir_path: Path, file_to_find: Path) -> Optional[Path]:
    """
    Given a directory dir_path and a file we wish to find within that directory,
    we iterate by trimming the prefix of file_to_find.
    Use case: since .certora_sources is a common root of paths we copy, we wish to resolve
    the original files inside .certora_sources.
    Note that file_to_find should not have directory traversals.
    Also, the result must not be an absolute path.

    @param dir_path: A path to root the new file in
    @param file_to_find: The file to re-root
    @return The path of file_to_find rooted in dir_path, and None if it is not there
    """

    num_parts = len(file_to_find.parts)
    if file_to_find.is_absolute():
        start = 1  # we must trim the `/` so that we do not return absolute paths
    else:
        start = 0

    for i in range(start, num_parts):
        candidate_path = Path(*file_to_find.parts[i:])
        if (dir_path / candidate_path).is_file():
            return candidate_path

    return None


def find_filename_in(dir_path: Path, filename_to_find: str) -> Optional[str]:
    res = find_in(dir_path, Path(filename_to_find))
    if res is not None:
        return str(res)
    else:
        return None


def resolve_original_file(original_file: str) -> Optional[str]:
    """
    resolves the original source file, given as an absolute path,
    to its path relative to the Certora sources dir.
    important note on timing - this function will fail to resolve
    if used before the appropriate files have been copied to the
    sources dir by the build script
    """
    return find_filename_in(get_certora_sources_dir(), original_file)


def get_trivial_contract_name(contract: str) -> str:
    """
    Gets a path to a .sol file and returns its trivial contract name. The trivial contract name is the basename of the
    path of the file, without file type suffix.
    For example: for 'file/Test/opyn/vault.sol', the trivial contract name is 'vault'.
    @param contract: A path to a .sol file
    @return: The trivial contract name of a file
    """
    return abs_posix_path_obj(contract).stem


def version_triplet_regex(prefix: str = "", suffix: str = "") -> str:
    """
    @return: the regex pattern for a version triplet (xx.yy.zz)
    """
    return fr'^{prefix}(\d+)\.(\d+)\.(\d+){suffix}'


class TestValue(NoValEnum):
    """
    valid values for the --test flag. The values in command line are in lower case (e.g. --test local_jar). The value
    determines the chekpoint where the execution will halt. The exception TestResultsReady will be thrown. The value
    will also determine what object will be attached to the exception for inspection by the caller
    """
    BEFORE_LOCAL_PROVER_CALL = auto()
    CHECK_ARGS = auto()
    AFTER_BUILD = auto()
    CHECK_SOLC_OPTIONS = auto()
    CHECK_AUTH_DATA = auto()
    CHECK_MANUAL_COMPILATION = auto()
    CHECK_METADATA = auto()
    CHECK_CONFIG_LAYOUT = auto()
    AFTER_COLLECT = auto()
    AFTER_BUILD_MUTANTS_DIRECTORY = auto()
    AFTER_GENERATE_COLLECT_REPORT = auto()
    AFTER_BUILD_RUST = auto()
    AFTER_RULE_SPLIT = auto()
    SOLANA_BUILD_CMD = auto()
    CHECK_ZIP = auto()
    STORAGE_EXTENSION_LAYOUT = auto()

class FeValue(NoValEnum):
    PRODUCTION = auto()
    LATEST = auto()


class TestResultsReady(Exception):
    def __init__(self, data: Any):
        super().__init__()
        self.data = data


def is_valid_url(parsed_url: urllib3.util.Url) -> bool:
    """
    Thanks stackoverflow. This returns true if the given URL string is a valid URL, and false otherwise.
    """
    try:
        return all([parsed_url.scheme, parsed_url.netloc])
    except Exception:
        return False


def check_packages_arguments(context: SimpleNamespace) -> None:
    """
    Performs checks on the --packages_path and --packages options.
    @param context: A namespace including all CLI arguments provided
    @raise an CertoraUserInputError if:
        1. both options --packages_path and --packages options were used
        2. in --packages the same name was given multiples paths
    """
    if getattr(context, 'packages_path', None) is None:
        setattr(context, 'packages_path', os.getenv("NODE_PATH", f"{Path.cwd() / 'node_modules'}"))
        context_logger.debug(f"context.packages_path is {context.packages_path}")

    if getattr(context, 'packages', None) and len(context.packages) > 0:
        context.package_name_to_path = dict()
        for package_str in context.packages:
            package = package_str.split("=")[0]
            path = package_str.split("=")[1]
            if package in context.package_name_to_path:
                raise CertoraUserInputError(
                    f"package {package} was given two paths: {context.package_name_to_path[package]}, {path}")
            if path.endswith("/"):
                # emitting a warning here because here loggers are already initialized
                context_logger.warning(
                    f"Package {package} is given a path ending with a `/`, which could confuse solc: {path}")
            context.package_name_to_path[package] = path

        context.packages = sorted(context.packages, key=str.lower)

    else:
        packages_to_path_list = []
        if PACKAGE_FILE.exists():
            try:
                with PACKAGE_FILE.open() as package_json_file:
                    package_json = json.load(package_json_file)
                    dict1 = package_json.get("dependencies", {})
                    dict2 = package_json.get("devDependencies", {})
                    dep_conflicts = {key: value for key, value in dict1.items() if key in dict2 and dict2[key] != value}
                    deps = {**dict1, **dict2}.keys()
                    # deps = (dict1 | dict2).keys() when we use pyton >= 3.9 we can use |
                    if dep_conflicts:
                        raise CertoraUserInputError(f"key(s) ({', '.join(dep_conflicts)}) "
                                                    "appear(s) multiple times in package.json with different values")

                    packages_to_path_list = [f"{package}={context.packages_path}/{package}" for package in deps]
            except CertoraUserInputError as e:
                raise e from None
            except Exception as e:
                raise CertoraUserInputError(f"Invalid package file: {PACKAGE_FILE}", e)

        packages_to_path_list += handle_remappings_file(context)
        if len(packages_to_path_list) > 0:
            keys = [s.split('=')[0] for s in packages_to_path_list]
            if len(set(keys)) < len(keys):
                raise CertoraUserInputError(f"package.json and remappings.txt include duplicated keys in: {keys}")
            context.packages = sorted(packages_to_path_list, key=str.lower)


def is_local(object: Any) -> bool:
    # if object has the attribute 'jar' - local
    # if object has the attribute 'server' - remote
    # if the object does not have the attributes 'jar' or 'server' then checking for local installation

    certora_root_dir = get_certora_root_directory().as_posix()
    default_jar_path = Path(certora_root_dir) / EMV_JAR
    return getattr(object, 'jar', None) or (default_jar_path.is_file() and not getattr(object, 'server', None))


def handle_remappings_file(context: SimpleNamespace) -> List[str]:
    """"
    Tries to reach packages from remappings.txt
    :return:
    """
    if REMAPPINGS_FILE.exists():
        try:
            with REMAPPINGS_FILE.open() as remappings_file:
                remappings = list(filter(lambda x: x != "", map(lambda x: x.strip(), remappings_file.readlines())))
                keys = [s.split('=')[0] for s in remappings]
                if len(set(keys)) < len(keys):
                    raise CertoraUserInputError(f"remappings.txt includes duplicated keys in: {keys}")
                return remappings
        except CertoraUserInputError as e:
            raise e from None
        except Exception as e:
            raise CertoraUserInputError(f"Invalid remappings file: {REMAPPINGS_FILE}", e)
    return []


def get_ir_flag(solc: str) -> str:
    """

    :param solc: solc command
    :return: the ir flag supported by solc (if exist): --via-ir, --experimental-via-ir or ''
    """

    command = f"{solc} --help"
    process = subprocess.Popen(command, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    output, _ = process.communicate()
    for flag in ["--via-ir", "--experimental-via-ir"]:
        if output.find(flag) != -1:
            return flag
    return ''


def run_shell_command(cmd: Union[str, List[str]], env: Dict[str, str] = os.environ.copy(), cwd: Path = Path.cwd()) -> None:
    try:
        print(f"Executing:  {cmd}")
        result = subprocess.run(cmd, shell=True, capture_output=True, text=True, env=env, cwd=cwd)
    except Exception as e:
        raise RuntimeError(f"Failed to execute {cmd}\n{e}")
    if result.returncode != 0:
        stderr_lines = result.stderr.splitlines()
        raise RuntimeError(f"Failed to execute {cmd}, return code - {result.returncode}\n{stderr_lines}")

def is_yul_file(file: str) -> bool:
    return file.lower().endswith(YUL_EXT)

def is_sol_file(file: str) -> bool:
    return file.lower().endswith(SOL_EXT)

def is_solidity_file(file: str) -> bool:
    return is_yul_file(file) or is_sol_file(file)

def is_vyper_file(file: str) -> bool:
    return file.lower().endswith(VY_EXT)

def get_backup_path(origin_path: Path) -> Path:
    return origin_path.with_name(origin_path.name + '.backup')

def create_backup(origin_path: Path) -> Optional[Path]:
    # copy file xx.yy to xx.yy.backup
    backup_path = None
    if origin_path.exists():
        backup_path = get_backup_path(origin_path)
        if backup_path.exists():
            raise FileExistsError(f"Cannot create backup to {str(origin_path)} since {str(backup_path)} already exists")
        shutil.copy2(origin_path, backup_path)
        return backup_path
    return backup_path

def restore_backup(backup_path: Path) -> Optional[Path]:
    # move backup file back to origin, if exists, origin will be overwrittern
    original_path = None
    if backup_path.exists():
        original_path = backup_path.with_name(backup_path.stem)
        backup_path.rename(original_path)
    return original_path

def eq_by(f: Callable[[T, T], bool], a: Sequence[T], b: Sequence[T]) -> bool:
    """
    check if Sequences a and b are equal according to function f.
    """
    return len(a) == len(b) and all(map(f, a, b))

def find_nearest_cargo_toml() -> Optional[Path]:
    current = Path.cwd()
    for parent in [current] + list(current.parents):
        candidate = parent / CARGO_TOML_FILE
        if candidate.is_file():
            return candidate
    return None

def file_in_source_tree(file_path: Path) -> bool:
    # if the file is under .certora_source, return True
    file_path = Path(file_path).absolute()
    parent_dir = get_certora_sources_dir().absolute()

    try:
        file_path.relative_to(parent_dir)
        return True
    except ValueError:
        return False
