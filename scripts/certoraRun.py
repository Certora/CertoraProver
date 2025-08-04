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
from typing import List, Optional, Type
from pathlib import Path

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))
from Shared import certoraUtils as Util

from CertoraProver.certoraCloudIO import CloudVerification

from CertoraProver.certoraBuild import build
import CertoraProver.certoraContext as Ctx
import CertoraProver.certoraApp as App

from CertoraProver import certoraContextValidator as Cv

from Shared.proverCommon import (
    build_context,
    collect_and_dump_metadata,
    collect_and_dump_config_layout,
    ensure_version_compatibility,
    run_local,
    run_remote,
    CertoraRunResult,
    handle_exit,
    catch_exits
)
import CertoraProver.splitRules as splitRules

BUILD_SCRIPT_PATH = Path("CertoraProver/certoraBuild.py")

# logger for issues regarding the general run flow.
# Also serves as the default logger for errors originating from unexpected places.
run_logger = logging.getLogger("run")

def run_certora(args: List[str], app: Type[App.CertoraApp] = App.EvmApp,
                prover_cmd: Optional[str] = None) -> Optional[CertoraRunResult]:
    """
    The main function that is responsible for the general flow of the script.
    The general flow is:
    1. Parse program arguments
    2. Run the necessary steps (type checking/ build/ cloud verification/ local verification)

    """
    context, logging_manager = build_context(args, app)

    if prover_cmd:
        context.prover_cmd = prover_cmd

    timings = {}
    exit_code = 0  # The exit code of the script. 0 means success, any other number is an error.
    return_value = None

    # Collect and validate metadata
    collect_and_dump_metadata(context)
    # Collect and dump configuration layout
    collect_and_dump_config_layout(context)

    if context.split_rules:
        context.build_only = True
        build(context)
        context.build_only = False
        rule_handler = splitRules.SplitRulesHandler(context)
        exit_code = rule_handler.generate_runs()
        CloudVerification(context).print_group_id_url()
        if exit_code == 0:
            print("Split rules succeeded")
        else:
            raise Util.ExitException("Split rules failed", exit_code)

    # Version validation
    ensure_version_compatibility(context)

    # When a TAC file is provided, no build arguments will be processed
    if not context.is_tac:
        run_logger.debug(f"There is no TAC file. Going to script {BUILD_SCRIPT_PATH} to main_with_args()")
        build_start = time.perf_counter()

        # If we are not in CI, we also check the spec for Syntax errors.
        build(context)
        build_end = time.perf_counter()

        timings["buildTime"] = round(build_end - build_start, 4)
        if context.test == str(Util.TestValue.AFTER_BUILD):
            raise Util.TestResultsReady(context)

    if context.build_only:
        return return_value

    # either we skipped building (TAC MODE) or build succeeded
    if context.local:
        compare_with_expected_file = Path(context.expected_file).exists()

        run_result = run_local(context, timings, compare_with_expected_file=compare_with_expected_file)
        emv_dir = latest_emv_dir()
        return_value = CertoraRunResult(str(emv_dir) if emv_dir else None, True,
                                        Util.get_certora_sources_dir(), None)
        if run_result != 0:
            exit_code = run_result
        elif compare_with_expected_file:
            print("Comparing tool output to the expected output:")
            output_path = context.tool_output or (
                'tmpOutput.json' if emv_dir is None else
                str(emv_dir / 'Reports/output.json')
            )
            result = Util.check_results_from_file(output_path, context.expected_file)
            if not result:
                exit_code = 1
    else:  # Remote run
        # Syntax checking and typechecking
        if Cv.mode_has_spec_file(context):
            if Ctx.should_run_local_speck_check(context):
                typechecking_start = time.perf_counter()
                Ctx.run_local_spec_check(True, context)
                typechecking_end = time.perf_counter()
                timings['typecheckingTime'] = round(typechecking_end - typechecking_start, 4)
            else:
                run_logger.warning(
                    "Local checks of CVL specification files disabled. It is recommended to enable "
                    "the checks.")

        # Remove debug logger and run remote verification
        logging_manager.remove_debug_logger()
        exit_code, return_value = run_remote(context, args, timings)

    # Handle exit codes and return
    return handle_exit(exit_code, return_value)


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


@catch_exits
def entry_point() -> None:
    """
    This function is the entry point of the certora_cli customer-facing package, as well as this script.
    It is important this function gets no arguments!
    """
    run_certora(sys.argv[1:], prover_cmd=sys.argv[0])


if __name__ == '__main__':
    entry_point()
