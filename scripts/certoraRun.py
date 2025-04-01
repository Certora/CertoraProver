#!/usr/bin/env python3
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
import time
import logging
from dataclasses import dataclass
from typing import List, Optional, Type
from pathlib import Path
from rich.console import Console

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))
from Shared import certoraUtils as Util

from CertoraProver.certoraCloudIO import CloudVerification, validate_version_and_branch

from CertoraProver.certoraCollectRunMetadata import collect_run_metadata
from CertoraProver.certoraCollectConfigurationLayout import collect_configuration_layout
from Shared.certoraLogging import LoggingManager
from CertoraProver.certoraBuild import build
from CertoraProver.certoraBuildRust import build_rust_app
import CertoraProver.certoraContext as Ctx

from CertoraProver import certoraContextValidator as Cv
import CertoraProver.certoraContextAttributes as Attrs
from Shared import certoraAttrUtil as AttrUtil
import CertoraProver.splitRules as splitRules

BUILD_SCRIPT_PATH = Path("CertoraProver/certoraBuild.py")
VIOLATIONS_EXIT_CODE = 100

# logger for issues regarding the general run flow.
# Also serves as the default logger for errors originating from unexpected places.
run_logger = logging.getLogger("run")


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


class ExitException(Exception):
    def __init__(self, message: str, exit_code: int):
        super().__init__(message)
        self.exit_code = exit_code  # Store the integer data


def run_certora(args: List[str], attrs_class: Optional[Type[AttrUtil.Attributes]] = None,
                prover_cmd: Optional[str] = None) -> Optional[CertoraRunResult]:
    """
    The main function that is responsible for the general flow of the script.
    The general flow is:
    1. Parse program arguments
    2. Run the necessary steps (type checking/ build/ cloud verification/ local verification)

    """

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

    if not attrs_class:
        attrs_class = Attrs.detect_application_class(args)
    Attrs.set_attribute_class(attrs_class)

    Ctx.handle_flags_in_args(args)
    context = Ctx.get_args(args)  # Parse arguments
    if prover_cmd:
        context.prover_cmd = prover_cmd
    assert logging_manager, "logging manager was not set"
    logging_manager.set_log_level_and_format(is_quiet=Ctx.is_minimal_cli_output(context),
                                             debug=context.debug,
                                             debug_topics=context.debug_topics,
                                             show_debug_topics=context.show_debug_topics)

    timings = {}
    exit_code = 0  # The exit code of the script. 0 means success, any other number is an error.
    return_value = None

    metadata = (
        collect_run_metadata(wd=Path.cwd(), raw_args=sys.argv, context=context))

    if context.test == str(Util.TestValue.CHECK_METADATA):
        raise Util.TestResultsReady(metadata)
    metadata.dump()

    configuration_layout = collect_configuration_layout()

    if context.test == str(Util.TestValue.CHECK_CONFIG_LAYOUT):
        raise Util.TestResultsReady(configuration_layout)
    configuration_layout.dump()

    if Attrs.is_evm_app() and context.split_rules:
        context.build_only = True
        build(context)
        context.build_only = False
        rule_handler = splitRules.SplitRulesHandler(context)
        exit_code = rule_handler.generate_runs()
        CloudVerification(context).print_group_id_url()
        raise ExitException(f"Split Rules {'succeeded' if exit_code == 0 else 'failed'}", exit_code)

    if Attrs.is_rust_app():
        build_rust_app(context)

        if context.local:
            check_cmd = Ctx.get_local_run_cmd(context)
            print(f"Verifier run command:\n {check_cmd}", flush=True)

            compare_with_tool_output = False
            run_result = Util.run_jar_cmd(check_cmd, compare_with_tool_output, logger_topic="verification",
                                          print_output=True)
            # For solana and wasm, we don't check types so build time is zero.
            timings["buildTime"] = 0.0
            if run_result != 0:
                exit_code = 1
            else:
                Util.print_completion_message("Finished running verifier:")
                print(f"\t{check_cmd}")
        else:
            validate_version_and_branch(context)
            context.key = Cv.validate_certora_key()
            cloud_verifier = CloudVerification(context, timings)
            # Wrap strings with space with ' so it can be copied and pasted to shell
            pretty_args = [f"'{arg}'" if ' ' in arg else arg for arg in args]
            cl_args = ' '.join(pretty_args)
            logging_manager.remove_debug_logger()
            result = cloud_verifier.cli_verify_and_report(cl_args, context.wait_for_results)
            if cloud_verifier.statusUrl:
                return_value = CertoraRunResult(cloud_verifier.statusUrl, False,
                                                Util.get_certora_sources_dir(), cloud_verifier.reportUrl)
            if not result:
                exit_code = 1
    else:

        if not context.local and not context.build_only and not context.compilation_steps_only:
            """
            Before running the local type checker, we see if the current package version is compatible with
            the latest. We check it before running the local type checker, because local type checking
            errors could be simply a result of syntax introduced in the newest version.
            The line below will raise an exception if the local version is incompatible.
            """
            validate_version_and_branch(context)

        # When a TAC file is provided, no build arguments will be processed
        if not context.is_tac:
            run_logger.debug(f"There is no TAC file. Going to script {BUILD_SCRIPT_PATH} to main_with_args()")
            build_start = time.perf_counter()

            # If we are not in CI, we also check the spec for Syntax errors.
            build(context, ignore_spec_syntax_check=False)
            build_end = time.perf_counter()

            timings["buildTime"] = round(build_end - build_start, 4)
            if context.test == str(Util.TestValue.AFTER_BUILD):
                raise Util.TestResultsReady(True)

        if not context.build_only and exit_code == 0:
            # either we skipped building (TAC MODE) or build succeeded
            if context.local:
                compare_with_expected_file = Path(context.expected_file).exists()

                check_cmd = Ctx.get_local_run_cmd(context)

                # In local mode, this is reserved for Certora devs, so let the script print it
                print(f"Verifier run command:\n {' '.join(check_cmd)}", flush=True)
                run_result = \
                    Util.run_jar_cmd(check_cmd, compare_with_expected_file,
                                     logger_topic="verification", print_output=True)
                emv_dir = latest_emv_dir()
                return_value = CertoraRunResult(str(emv_dir) if emv_dir else None, True,
                                                Util.get_certora_sources_dir(), None)
                if run_result != 0:
                    exit_code = run_result
                else:
                    Util.print_completion_message("Finished running verifier:")
                    print(f"\t{check_cmd}")
                    if compare_with_expected_file:
                        print("Comparing tool output to the expected output:")
                        output_path = (context.tool_output if context.tool_output else
                                       ('tmpOutput.json' if emv_dir is None
                                           else str(emv_dir / 'Reports/output.json')))
                        result = Util.check_results_from_file(output_path, context.expected_file)
                        if not result:
                            exit_code = 1
            else:  # Remote run
                # Syntax checking and typechecking
                if Cv.mode_has_spec_file(context):
                    if context.disable_local_typechecking:
                        run_logger.warning(
                            "Local checks of CVL specification files disabled. It is recommended to enable "
                            "the checks.")
                    else:
                        typechecking_start = time.perf_counter()
                        Ctx.run_local_spec_check(True, context)
                        typechecking_end = time.perf_counter()
                        timings['typecheckingTime'] = round(typechecking_end - typechecking_start, 4)

                if exit_code == 0:
                    if context.compilation_steps_only:
                        # Give a non-None value for the overall result, but with links set to None
                        return_value = CertoraRunResult(None, False, Util.get_certora_sources_dir(), None)
                    else:
                        # typechecking either succeeded or skipped

                        context.key = Cv.validate_certora_key()
                        cloud_verifier = CloudVerification(context, timings)

                        # Wrap strings with space with ' so it can be copied and pasted to shell
                        pretty_args = [f"'{arg}'" if ' ' in arg else arg for arg in args]
                        cl_args = ' '.join(pretty_args)

                        logging_manager.remove_debug_logger()
                        if not cloud_verifier.cli_verify_and_report(cl_args, context.wait_for_results):
                            exit_code = VIOLATIONS_EXIT_CODE
                        if cloud_verifier.statusUrl:
                            return_value = CertoraRunResult(cloud_verifier.statusUrl, False,
                                                            Util.get_certora_sources_dir(), cloud_verifier.reportUrl)

    if exit_code == VIOLATIONS_EXIT_CODE:
        raise CertoraFoundViolations("violations were found", return_value)
    if exit_code != 0:
        raise Util.CertoraUserInputError(f"run_certora failed (code {exit_code})")
    return return_value


def latest_emv_dir() -> Optional[Path]:
    """
    Returns the latest emv-... directory.
    This is known to be highly unreliable _unless_ we know that in the current work dir only one jar
    is invoked every time, and that we do not pass arguments to the jar that change the output directory.
    The current use case is for the local-and-sync'd dev-mode for mutation testing.
    """
    cwd = Path.cwd()
    candidates = list(cwd.glob(r"emv-[0-9]*-certora-*"))
    max = None
    max_no = -1
    for candidate in candidates:
        if candidate.is_dir():
            index = int(str(candidate.stem).split("-")[1])
            if index > max_no:
                max = candidate
                max_no = index
    return max


def entry_point() -> None:
    """
    This function is the entry point of the certora_cli customer-facing package, as well as this script.
    It is important this function gets no arguments!
    """
    try:
        run_certora(sys.argv[1:], prover_cmd=sys.argv[0])
        sys.exit(0)
    except KeyboardInterrupt:
        Console().print("[bold red]\nInterrupted by user")
        sys.exit(1)
    except Util.TestResultsReady:
        print("reached checkpoint")
        sys.exit(0)
    except CertoraFoundViolations as e:
        try:
            assert e.results
            print(f"report url: {e.results.rule_report_link}")
        except Exception:
            pass
        Console().print("[bold red]\nViolations were found\n")
        sys.exit(1)
    except Util.CertoraUserInputError as e:
        if e.orig:
            print(f"\n{str(e.orig).strip()}")
        if e.more_info:
            print(f"\n{e.more_info.strip()}")
        Console().print(f"[bold red]\n{e}\n")
        sys.exit(1)

    except ExitException as e:
        Console().print(f"[bold red]{e}")
        sys.exit(e.exit_code)

    except Exception as e:
        Console().print(f"[bold red]{e}")
        sys.exit(1)


if __name__ == '__main__':
    entry_point()
