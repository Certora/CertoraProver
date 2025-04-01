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

from pathlib import Path
import argparse
from typing import List

from ConfRunnerInfra.TestTable import TestTable  # type: ignore
from ConfRunnerInfra.Checks.VerificationCheck import VerificationCheck  # type: ignore
from ConfRunnerInfra.Checks.RegressionCheck import RegressionCheck  # type: ignore
from ConfRunnerInfra.Checks.AnalysisCheck import AnalysisCheck  # type: ignore
from ConfRunnerInfra.LocalExecutor import LocalExecutor  # type: ignore
import ConfRunnerInfra.TestTableQueries as Queries  # type: ignore

# Add the Test directory to the path
import sys
test_dir = Path(__file__).parent.parent / "Test"
test_dir = test_dir.resolve()
sys.path.append(str(test_dir))

from ConfTester.RegTestDispatch import BASE_FLAGS


def filter_confs(confs: List[Path], ignore_confs: Path) -> List[Path]:
    """
    Filter a list of configuration files based on specified criteria.
    Args:
        confs (List[Path]): List of configuration file paths.
        ignore_confs (Path): Path to a file containing a  list of .conf files to skip.
    Returns:
        List[Path]: Filtered list of configuration file paths.
    """
    # List of configuration files to skip
    with open(ignore_confs, "r") as f:
        confs_to_skip = [c.strip() for c in f.readlines()]

    # Remove files that should be skipped
    confs = [conf for conf in confs if str(conf) not in confs_to_skip]
    return confs


def parse_args() -> argparse.Namespace:
    """
    Parse command line arguments.
    Returns:
        argparse.Namespace: Parsed command line arguments.
    """
    parser = argparse.ArgumentParser(formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    parser.add_argument('--path', nargs='?', type=Path, default='Test',
                        help="""The relative path or the specific configuration file to run.
                            If a directory is provided, it will search for configuration files.
                            If a file path is provided, it will run only that specific configuration file.""")
    parser.add_argument("--additional_prover_args", type=str, default=None,
                        help="Additional prover arguments to pass to the prover")
    parser.add_argument("--additional_java_args", type=str, default=None,
                        help="Additional Java arguments to pass to the prover")
    parser.add_argument('--recursive', action="store_true", help='Recursively search for conf files in directories')
    parser.add_argument('--clean_output', action="store_true", help='Delete the test output after running the tests')
    parser.add_argument('--jacoco', action="store_true", help='Decide if execute with Jacoco data collection')
    parser.add_argument('--ignore_confs', default=Path(__file__).parent / "conf_tester/conf_ignore.txt", type=Path,
                        help="A file containing a list of .conf files to skip")

    args = parser.parse_args()

    return args


def main() -> None:
    args = parse_args()

    if args.recursive:
        confs = [conf for conf in args.path.rglob("*.conf") if ".certora_internal" not in str(conf)]
    elif args.path.is_dir():
        confs = [conf for conf in args.path.glob("*.conf") if ".certora_internal" not in str(conf)]
    else:
        confs = [args.path]

    confs = filter_confs(confs, args.ignore_confs)
    confs = [conf.resolve() for conf in confs]

    checks = [VerificationCheck(), RegressionCheck(), AnalysisCheck()]

    test_table = TestTable(confs, checks)
    local_executor = LocalExecutor(test_table, clean_test_dir = args.clean_output)

    if args.additional_prover_args:
        if 'prover_args' in BASE_FLAGS:
            BASE_FLAGS['prover_args'] += f" {args.additional_prover_args}"  # type: ignore
        else:
            BASE_FLAGS['prover_args'] = args.additional_prover_args  # type: ignore

    if args.additional_java_args:
        if 'java_args' in BASE_FLAGS:
            BASE_FLAGS['java_args'] += f" {args.additional_java_args}"  # type: ignore
        else:
            BASE_FLAGS['java_args'] = args.additional_java_args  # type: ignore

    if args.jacoco:
        if 'java_args' in BASE_FLAGS:
            BASE_FLAGS['java_args'] += " -Djacoco.report.dir=$JACOCO_REPORT"  # type: ignore
        else:
            BASE_FLAGS['java_args'] = "-Djacoco.report.dir=$JACOCO_REPORT"  # type: ignore

    print(BASE_FLAGS)

    local_executor.execute_tests({conf: BASE_FLAGS for conf in confs})
    test_table.export_csv("Report.csv")

    exit(
        Queries.query_failed_tests(test_table, "Verification") or
        Queries.query_failed_tests(test_table, "Regression") or
        Queries.query_failed_tests(test_table, "Analysis")
    )


if __name__ == "__main__":
    main()
