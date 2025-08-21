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

import os
import sys
import unittest
import shutil
import random
from pathlib import Path

# Add the path to the Test directory to the system path
test_dir_path = Path(__file__).parent.parent.resolve()
sys.path.insert(0, str(test_dir_path))

# Add the path to the scripts directory to the system path
scripts_dir_path = Path(__file__).parent.parent.parent / "scripts"
scripts_dir_path = scripts_dir_path.resolve()
sys.path.insert(0, str(scripts_dir_path))

if len(sys.argv) != 3:
    print(f"Usage: python {sys.argv[0]} <TestEVM path> <CITests path>")
    sys.exit(1)

TestEVM_path = sys.argv[1]
CITests_path = sys.argv[2]
os.environ["CERTORA_TEST_DATA_DIRECTORY"] = f"{CITests_path}/test_data"

from Shared import certoraUtils as Util
import CITests.testCertoraUtils as TestUtil
from certoraMutate import run_mutate_from_args
from typing import List, Any
from Mutate import mutateAttributes as MutAttrs
from Shared import certoraAttrUtil as AttrUtil
from Mutate import mutateConstants as Constants




orig_run_mul8_1_for_ci_production = "https://prover.certora.com/output/69614/bdc6fb51cdb14e3481a25fdd7e65fa7b"

# short call so we can put all args in a single line
def _p(filename: str) -> str:
    return TestUtil.path_test_file(filename)


class MutantTestSuite(TestUtil.TestSuite):
    def __init__(self, **kwargs: Any):
        super().__init__(run_mutate_from_args, **kwargs)
        if 'common_flags' not in kwargs:
            self.common_flags = ['--server', 'staging', '--prover_version', 'master']

    @staticmethod
    def conf_arg(conf: str) -> List[str]:
        return ['--conf', conf] if conf else []


def mutation_expr() -> str:
    return f'"manual_mutants": [{{ "file_to_mutate": "{_p("A.sol")}", "mutants_location": "{_p("A_mutations")}"}}]'


class TestMutatedClient(unittest.TestCase):
    def test_conf_file_inputs(self) -> None:
        suite = MutantTestSuite(conf_file_template=_p('mutation_conf_top_level.conf'),
                                test_attribute=Util.TestValue.CHECK_ARGS)
        suite.expect_success(description='placeholder is replaced with empty string',
                             replacements=TestUtil.replace_x(''))
        suite.expect_failure(description="inject '2'", replacements=TestUtil.replace_x('2'),
                             expected='Failed to parse')
        suite = MutantTestSuite(conf_file_template=_p('gambit_template.conf'), test_attribute=Util.TestValue.CHECK_ARGS)
        suite.expect_failure(description="No solc flags inside gambit object",
                             replacements=TestUtil.replace_x(f'"filename": "{_p("A.sol")}", "solc": "6.1"'),
                             expected='flags to Solidity should be set from the original run')
        suite.expect_failure(description="No outdir flags inside gambit object",
                             replacements=TestUtil.replace_x(f'"filename": "{_p("A.sol")}", "outdir": "."'),
                             expected='outdir not allowed inside embedded gambit')
        suite.expect_failure(description="illegal use of skip_validate",
                             replacements=TestUtil.replace_x(f'"filename": "{_p("A.sol")}", "skip_validate": true'),
                             expected='skip_validate not allowed inside embedded gambit object')
        suite = MutantTestSuite(conf_file_template=_p('mutation_template.conf'),
                                test_attribute=Util.TestValue.CHECK_ARGS)
        suite.expect_failure(description="outdir without gambit",
                             replacements=TestUtil.replace_x(f'{mutation_expr()}, "outdir": "new_dir"'),
                             expected="Invalid configuration: 'outdir' should not be set")

    def test_invalid_runs(self) -> None:
        suite = MutantTestSuite(conf_file_template=_p('mutation_conf_top_level.conf'),
                                test_attribute=Util.TestValue.CHECK_ARGS)
        suite.expect_failure(description="prover_conf", run_flags=["--prover_conf"],
                             expected='unrecognized arguments: --prover_conf')

        suite.expect_failure(description="--conf with no value", run_flags=["--conf"],
                             expected='argument --conf: expected one argument')

        suite.expect_failure(description="--gambit in CLI", run_flags=["--gambit"],
                             expected='--gambit cannot be set in command line only in a conf file')

    def test_all_cli_attr(self) -> None:

        suite = MutantTestSuite(conf_file_template=_p('mutation_conf_top_level.conf'),
                                test_attribute=Util.TestValue.CHECK_ARGS, common_flags=[])

        for attr in MutAttrs.MutateAttributes.attribute_list():
            flag = attr.get_flag()
            # we already call with conf and test flag, adding the flag will cause an error
            # map attributes cannot be set as flags only in a conf file
            if (flag in ['--conf', '--test', 'conf_no_flag'] or attr.arg_type in [AttrUtil.AttrArgType.MAP,
                                                                                  AttrUtil.AttrArgType.OBJ_LIST]):
                continue
            run_flags = [flag]
            if attr.arg_type != AttrUtil.AttrArgType.BOOLEAN:
                run_flags.append(TestUtil.get_valid_value(attr))
            suite.expect_checkpoint(description="check valid flags", run_flags=run_flags)

    def test_compile_mutation(self) -> None:
        suite = MutantTestSuite(conf_file_template=_p('mutation_template.conf'))

        mutation_attrs = {
            'manual_mutants': [
                {
                    "file_to_mutate": _p("A.sol"),
                    "mutants_location": _p("A_mutations")
                }
            ]
        }
        # this test compiles the mutant does not stop on a checkpoint
        suite.expect_success(description="single manual mutation",
                             replacements=TestUtil.replace_x(TestUtil.json_to_str(mutation_attrs)))

        prover_attr = {
            "solc_optimize": "20",
            "solc_via_ir": True,
            "solc_evm_version": "berlin"
        }

        result = suite.expect_checkpoint(description="single manual mutation",
                                         replacements=TestUtil.replace_x_y(TestUtil.json_to_str(mutation_attrs),
                                                                           TestUtil.json_to_str(prover_attr) + ','),
                                         test_attribute=Util.TestValue.CHECK_MANUAL_COMPILATION)
        assert result
        mutation_attrs['manual_mutants'][0]['mutants_location'] = "does_not_exist"
        suite.expect_failure(description="single manual mutation - bad location",
                             replacements=TestUtil.replace_x(TestUtil.json_to_str(mutation_attrs)),
                             expected="not a valid file or directory")

    def test_mutations_flags(self) -> None:

        suite = MutantTestSuite(conf_file_template=_p('mutation_conf_top_level.conf'))
        result = suite.expect_checkpoint(description="--debug: valid input", run_flags=["--debug"],
                                         test_attribute=Util.TestValue.CHECK_ARGS)
        assert result.debug

    def test_flags_invalid(self) -> None:
        # conf file can be the first argument without a flag, or can be set with the flag --conf
        # But it is not allowed to use both methods even if the conf file is the same
        # e.g.  certoraMutate my_conf.json --conf my_conf.json

        suite = MutantTestSuite()
        c_file = _p('mutation_conf_top_level.conf')
        suite.expect_failure(description="conf file should be set only once", run_flags=[c_file, '--conf', c_file],
                             test_attribute=Util.TestValue.CHECK_ARGS, expected="was set twice")

    def test_conf_file_arg(self) -> None:
        # conf file can only be set in CLI not inside a conf file
        # can be set with the --conf flag or without the flag if the conf file is the first argument
        # To run in collect mode the flag --collect_mode must be set
        # not setting the conf file and the --collect_mode flag is an error
        c_file = _p('mutation_conf_top_level.conf')
        suite = MutantTestSuite(test_attribute=Util.TestValue.CHECK_ARGS)

        test_description = "conf file without flag"
        result = suite.expect_checkpoint(description=test_description, run_flags=[c_file])
        assert str(result.conf) == c_file, test_description

        test_description = "conf file with flag"
        result = suite.expect_checkpoint(description=test_description, run_flags=['--conf', c_file])
        assert str(result.conf) == c_file, test_description

        suite = MutantTestSuite(conf_file_template=c_file, test_attribute=Util.TestValue.CHECK_ARGS)
        suite.expect_failure(description="'conf' in conf file", replacements=TestUtil.replace_x(f'"conf": "{c_file}",'),
                             expected="Failed to parse")
        suite.expect_failure(description="'conf' in mutation", replacements=TestUtil.replace_y(f'"conf": "{c_file}",'),
                             expected="Unknown key, conf, under 'Mutations'")

    def test_calling_from_test_directory(self) -> None:
        suite = MutantTestSuite(conf_file_template=f'{TestEVM_path}/mulSolc8/mutation_confs/mul8_1_for_ci.conf',
                                test_attribute=Util.TestValue.AFTER_BUILD_MUTANTS_DIRECTORY)

        mutateApp = suite.expect_checkpoint(description='successful run from test directory')
        assert mutateApp.prover_context.verify == f'C:{TestEVM_path}/mulSolc8/c.spec', \
            "test_calling_from_test_directory - mul8_1_for_ci"
        suite = MutantTestSuite(conf_file_template=f'{TestEVM_path}/mulSolc8/mutation_confs/mul8_4_only_mutations_for_ci.conf',
                                test_attribute=Util.TestValue.AFTER_BUILD_MUTANTS_DIRECTORY)
        mutateApp = suite.expect_checkpoint(
            description='successful run from test directory',
            run_flags=['--orig_run', orig_run_mul8_1_for_ci_production])
        assert mutateApp.prover_context.verify == f'C:{TestEVM_path}/mulSolc8/c.spec', \
            "test_calling_from_test_directory - mul8_4_only_mutations_for_ci"

    def test_collect_mode(self) -> None:

        suite = MutantTestSuite(test_attribute=Util.TestValue.AFTER_COLLECT)

        # no conf file without --collect_mode is an error
        suite.expect_failure(description="executing collect", expected="No conf file was set")

        Path(Constants.DEFAULT_COLLECT_FILE).unlink(missing_ok=True)
        # failure - no default conf file
        suite.expect_failure(description=f"{Constants.DEFAULT_COLLECT_FILE} does not exist",
                             run_flags=["--collect_mode"],
                             expected=f"file {Constants.DEFAULT_COLLECT_FILE} not found")

        # copy collect.json to temp file
        copy_1 = f"temp_{random.randint(0, 99999):05d}.json"
        copy_2 = f"temp_{random.randint(0, 99999):05d}.json"
        copy_3 = f"temp_{random.randint(0, 99999):05d}.json"

        try:
            shutil.copy(_p('collect_files/empty_collect.json'), Constants.DEFAULT_COLLECT_FILE)
            shutil.copy(Constants.DEFAULT_COLLECT_FILE, copy_1)
            shutil.copy(Constants.DEFAULT_COLLECT_FILE, copy_2)
            shutil.copy(Constants.DEFAULT_COLLECT_FILE, copy_3)

        except IOError as e:
            raise Util.ImplementationError(f"failed to move/copy to {copy_1} and {copy_2}\n{e}")

        # no conf file  --collect_mode is not an error
        result = suite.expect_checkpoint(description=f"running default {Constants.DEFAULT_COLLECT_FILE}",
                                         run_flags=["--collect_mode"])
        assert result['original'] and result['mutants'], "collect_mode was not set"

        suite.expect_failure(description=f"{Constants.DEFAULT_COLLECT_FILE} does not exist",
                             run_flags=["--collect_mode", "--collect_file", '.'],
                             expected="is not a readable file")

        # succeed with the collect.json copy
        result = suite.expect_checkpoint(description=f"run with non-default {Constants.DEFAULT_COLLECT_FILE}",
                                         run_flags=["--collect_mode", "--collect_file", copy_1])
        assert result['original'] and result['mutants'], f"collect failed on {copy_1}"

        # change copy of collect.json
        TestUtil.replace_in_file(Path(copy_2), 'mutants', 'bad')
        suite.expect_failure(description=f"{Constants.DEFAULT_COLLECT_FILE} does not exist",
                             run_flags=["--collect_mode", "--collect_file", copy_2],
                             expected=f"Could not find mutants in {copy_2}")

        # restore collect.json
        try:
            os.remove(Constants.DEFAULT_COLLECT_FILE)
            os.remove(copy_1)
            os.remove(copy_2)
            os.remove(copy_3)
        except IOError as e:
            raise Util.ImplementationError(f"failed to delete {Constants.DEFAULT_COLLECT_FILE} files from CWD\n{e}")

    def test_collect_bad_origin(self) -> None:
        suite = MutantTestSuite(test_attribute=Util.TestValue.AFTER_GENERATE_COLLECT_REPORT)
        suite.expect_failure(description="original halted",
                             run_flags=["--collect_mode", "--collect_file",
                                        _p('collect_files/collect_original_halted.json')],
                             expected="Original run timed out, link -")

        suite.expect_failure(description="no valid rules in original",
                             run_flags=["--collect_mode", "--collect_file",
                                        _p('collect_files/collect_original_no_valid_rules.json')],
                             expected="No valid rules in original report")


if __name__ == '__main__':
    test_argv = [f"{sys.argv[1]}, {sys.argv[2]}"]
    runner = unittest.main(argv=test_argv, exit=False)
    if not runner.result.wasSuccessful():
        exit(1)
