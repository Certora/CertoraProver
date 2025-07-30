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

"""
Shared helpers for certoraRun entry points (Solana & Soroban & EVM).

Placing the context_build environment preparation logic and verification helpers in one module
reduces duplication from the dedicated entry scripts.
"""

from __future__ import annotations

import sys
import logging
import functools
from pathlib import Path
from dataclasses import dataclass
from rich.console import Console
from typing import List, Tuple, Type, Optional, Dict, NoReturn, Callable

scripts_dir_path = Path(__file__).parent.parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

from CertoraProver.certoraCloudIO import validate_version_and_branch
from Shared.certoraLogging import LoggingManager
from Shared import certoraUtils as Util
import CertoraProver.certoraContext as Ctx
from CertoraProver.certoraContextClass import CertoraContext
import CertoraProver.certoraContextAttributes as Attrs
from CertoraProver.certoraCollectRunMetadata import collect_run_metadata
from CertoraProver.certoraCollectConfigurationLayout import collect_configuration_layout
from CertoraProver import certoraContextValidator as Cv
from CertoraProver.certoraCloudIO import CloudVerification
import CertoraProver.certoraApp as App

log = logging.getLogger(__name__)


VIOLATIONS_EXIT_CODE = 100

@dataclass
class CertoraRunResult:
    link: Optional[str]  # Path to emv_dir if running locally, or the link to the job status page
    is_local_link: bool
    src_dir: Path
    rule_report_link: Optional[str]

class CertoraFoundViolations(Exception):
    def __init__(self, message: str, results: Optional[CertoraRunResult] = None) -> None:
        super().__init__(message)
        self.results = results

# --------------------------------------------------------------------------- #
# Setup Environment
# --------------------------------------------------------------------------- #
def build_context(args: List[str], app: Type[App.CertoraApp]) -> Tuple[CertoraContext, LoggingManager]:
    """
    Build the context for the Certora Prover.
    This function is responsible for setting up the context and logging manager
    for the Certora Prover. It handles the following tasks:
    1. Setting up the logging manager
    2. Parsing the command line arguments
    3. Setting up the context

    Args:
        args: The command line arguments to parse.
        app: The application
    Returns:
        A tuple containing the CertoraContext object and the LoggingManager object.
    """
    Attrs.set_attribute_class(app.attr_class)
    non_str_els = [x for x in args if not isinstance(x, str)]
    if non_str_els:
        print(f"args for run_certora that are not strings: {non_str_els}")
        exit(1)

    # If we are not in debug mode, we do not want to print the traceback in case of exceptions.
    if '--debug' not in args:  # We check manually, because we want no traceback in argument parsing exceptions
        sys.tracebacklimit = 0

    # creating the default internal dir, files may be copied to user defined build directory after
    # parsing the input

    if not ('--help' in args or '--version' in args):
        Util.reset_certora_internal_dir()
        Util.safe_create_dir(Util.get_build_dir())
        logging_manager = LoggingManager()

    Ctx.handle_flags_in_args(args, app)
    context = Ctx.get_args(args, app)  # Parse arguments

    assert logging_manager, "logging manager was not set"
    logging_manager.set_log_level_and_format(is_quiet=Ctx.is_minimal_cli_output(context),
                                             debug=context.debug,
                                             debug_topics=context.debug_topics,
                                             show_debug_topics=context.show_debug_topics)

    return context, logging_manager


def collect_and_dump_metadata(context: CertoraContext) -> None:
    """
    Collect and validate run metadata.

    Args:
        context: The Certora context containing verification settings

    Raises:
        Util.TestResultsReady: If this is a metadata test run
    """
    metadata = collect_run_metadata(wd=Path.cwd(), raw_args=sys.argv, context=context)

    if context.test == str(Util.TestValue.CHECK_METADATA):
        raise Util.TestResultsReady(metadata)

    metadata.dump()


def collect_and_dump_config_layout(context: CertoraContext) -> None:
    """
    Collect and dump the configuration layout.

    Args:
        context: The Certora context containing verification settings

    Raises:
        Util.TestResultsReady: If this is a configuration layout test run
    """
    configuration_layout = collect_configuration_layout()

    if context.test == str(Util.TestValue.CHECK_CONFIG_LAYOUT):
        raise Util.TestResultsReady(configuration_layout)

    configuration_layout.dump()


def ensure_version_compatibility(context: CertoraContext) -> None:
    if not (context.local or context.build_only or context.compilation_steps_only):
        """
        Before running the local type checker, we see if the current package version is compatible with
        the latest. We check it before running the local type checker, because local type checking
        errors could be simply a result of syntax introduced in the newest version.
        The line below will raise an exception if the local version is incompatible.
        """
        validate_version_and_branch(context)


# --------------------------------------------------------------------------- #
# Verification helpers
# --------------------------------------------------------------------------- #

def run_local(context: CertoraContext, timings: Dict, additional_commands: Optional[List[str]] = None,
              compare_with_expected_file: bool = False) -> int:
    """
    Run the verifier locally and return its exit code (0 = success).
    Args:
        context: The CertoraContext object containing the configuration.
        timings: A dictionary to store timing information.
        additional_commands: A list of additional commands to pass to the verifier.
    Returns:
        An integer representing the exit code of the verifier run.
    """
    cmd: List[str] = Ctx.get_local_run_cmd(context)

    if additional_commands:
        cmd.extend(additional_commands)

    print(f'Verifier run command:\n {" ".join(cmd)}')
    if context.test == str(Util.TestValue.BEFORE_LOCAL_PROVER_CALL):
        raise Util.TestResultsReady(' '.join(cmd))
    rc = Util.run_jar_cmd(
        cmd,
        override_exit_code=compare_with_expected_file,
        logger_topic="verification",
        print_output=True,
    )

    if rc == 0:
        Util.print_completion_message("Finished running verifier:")
        print(f'\t{" ".join(cmd)}')
        timings.setdefault("buildTime", 0.0)  # ensure key exists
        return 0

    return 1


def run_remote(
    context: CertoraContext,
    args: List[str],
    timings: Dict,
) -> Tuple[int, Optional[CertoraRunResult]]:
    """
    Run verification in Certora Cloud.

    Args:
        context: The CertoraContext object containing the configuration.
        args: The command line arguments to pass to the cloud verification.
        timings: A dictionary to store timing information.
    Returns:
        A tuple containing the exit code (0 = success) and an optional CertoraRunResult object.
    """
    if context.compilation_steps_only:
        return 0, CertoraRunResult(None, False, Util.get_certora_sources_dir(), None)

    context.key = Cv.validate_certora_key()
    cloud = CloudVerification(context, timings)

    pretty_args = [f"'{a}'" if " " in a else a for a in args]

    ok = cloud.cli_verify_and_report(" ".join(pretty_args), context.wait_for_results)

    exit_code = 0 if ok else VIOLATIONS_EXIT_CODE
    result: Optional[CertoraRunResult] = None
    if cloud.statusUrl:
        result = CertoraRunResult(
            cloud.statusUrl,
            False,
            Util.get_certora_sources_dir(),
            cloud.reportUrl,
        )
    return exit_code, result


def handle_exit(exit_code: int, return_value: Optional[CertoraRunResult]) -> Optional[CertoraRunResult]:
    """
    Handle the exit code of the verification run.
    Args:
        exit_code: The exit code of the verification run.
        return_value: The CertoraRunResult object containing the results of the verification run.
    Raises:
        CertoraFoundViolations: If violations were found during the verification run.
        Util.CertoraUserInputError: If there was an error with the user input.
    Returns:
        The CertoraRunResult object containing the results of the verification run.
    """
    if exit_code == VIOLATIONS_EXIT_CODE:
        raise CertoraFoundViolations("violations were found", return_value)
    if exit_code != 0:
        raise Util.CertoraUserInputError(f"run_certora failed (code {exit_code})")
    return return_value


# --------------------------------------------------------------------------- #
# Entry point decorator
# --------------------------------------------------------------------------- #

console = Console()

def catch_exits(fn: Callable[..., None]) -> Callable[..., NoReturn]:
    """
    Wrap any entry-point in a standard try/except + sys.exit logic.
    The wrapped function should do its work and then return normally;
    this decorator will exit(0) on success or exit(1/other) on failure.
    """
    @functools.wraps(fn)
    def wrapper(*args, **kwargs) -> NoReturn:   # type: ignore
        try:
            fn(*args, **kwargs)
            sys.exit(0)

        except KeyboardInterrupt:
            console.print("[bold red]\nInterrupted by user")
            sys.exit(1)

        except Util.TestResultsReady:
            print("reached checkpoint")
            sys.exit(0)

        except CertoraFoundViolations as e:
            link = getattr(e.results, "rule_report_link", None)
            if link:
                print(f"report url: {link}")
            console.print("[bold red]\nViolations were found\n")
            sys.exit(1)

        except Util.CertoraUserInputError as e:
            if e.orig:
                print(f"\n{str(e.orig).strip()}")
            if e.more_info:
                print(f"\n{e.more_info.strip()}")
            console.print(f"[bold red]\n{e}\n")
            sys.exit(1)

        except Util.ExitException as e:
            console.print(f"[bold red]{e}")
            sys.exit(e.exit_code)

        except Exception as e:
            console.print(f"[bold red]{e}")
            sys.exit(1)

    return wrapper
