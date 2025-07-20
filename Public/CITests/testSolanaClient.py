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
import random
import unittest
from pathlib import Path
from typing import Set
import zipfile


# Add the path to the Test directory to the system path
test_dir_path = Path(__file__).parent.parent.resolve()
sys.path.insert(0, str(test_dir_path))

# Add the path to the scripts directory to the system path
scripts_dir_path = Path(__file__).parent.parent.parent / "scripts"
scripts_dir_path = scripts_dir_path.resolve()
sys.path.insert(0, str(scripts_dir_path))

TestEVM_path = ''
CITests_path = ''

if sys.argv and len(sys.argv) == 3:
    TestEVM_path = sys.argv[1]
    CITests_path = sys.argv[2]
else:
    # Allowing getting test directories from env var instead of passing in argv for local debugging
    test_paths = os.getenv("CERTORA_TEST_PATHS", None)
    if test_paths:
        TestEVM_path, CITests_path = test_paths.split(',')

if not TestEVM_path or not CITests_path:
    print(f"Usage: python {sys.argv[0]} <TestEVM path> <CITests path>")
    sys.exit(1)


os.environ["CERTORA_TEST_DATA_DIRECTORY"] = f"{CITests_path}/test_data"
import CITests.testCertoraUtils as TestUtil

from certoraSolanaProver import run_solana_prover
from certoraSorobanProver import run_soroban_prover
from certoraRun import run_certora
from Shared import certoraUtils as Util
from CertoraProver.Compiler.CompilerCollectorFactory import get_relevant_compiler
from Shared import certoraValidateFuncs as Vf
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraAttrUtil as AttrUtil

# short call so we can put all args in a single line
def _p(filename: str) -> str:
    return TestUtil.path_test_file(filename)
VAULT_CONF_DIR = f"{CITests_path}/test_data/certora-vault-tutorial/programs/vault/src/certora/confs"

remote_args = ["--server", "staging", "--prover_version", "master"]

def assert_context(context: CertoraContext, test_id: str) -> None:
    got = context.solana_inlining
    expected = ['../envs/cvlr_inlining.txt']
    assert got == expected, f"{test_id} solana_inlining: {got}, expected {expected}"

    got = context.solana_summaries
    expected = ['../envs/cvlr_summaries.txt']
    assert got == expected, f"{test_id} solana_summaries: {got}, expected {expected}"

    got = len(context.files)
    expected = 1
    assert got == expected, f"{test_id} files len: {got}, expected: {expected}"

    got = os.path.relpath(context.files[0], os.getcwd())
    expected = '../../../../../target/sbf-solana-solana/release/certora_vault.so'
    assert got == expected, f"{test_id} files: {got}, expected: {expected}"

def assert_jar_cmd(jar_cmd: str, test_id: str) -> None:
    expected_sub_strings = [
        'emv.jar ../../../../../target/sbf-solana-solana/release/certora_vault.so',
        '-solanaSummaries ../envs/cvlr_summaries.txt',
        '-solanaInlining ../envs/cvlr_inlining.txt'
    ]
    for expected_sub_string in expected_sub_strings:
        assert expected_sub_string in jar_cmd, f"{test_id} jar_cmd: {jar_cmd}, expected to contain: {expected_sub_string}"


common_expected_files_set = {
    'certora_debug_log.txt',
    '.certora_metadata.json',
    '.configuration_layout.json',
    'certora_vault.so',
    '.certora_sources/run.conf',
    '.certora_sources/programs/vault/src/certora/envs/cvlr_inlining.txt',
    '.certora_sources/programs/vault/src/certora/envs/cvlr_summaries.txt',
    '.certora_sources/target/sbf-solana-solana/release/certora_vault.so',
    '.certora_sources/programs/vault/src/certora/confs/.cwd'
}

common_build_files_set = {
    '.certora_sources/programs/vault/src/processor.rs',
    '.certora_sources/programs/vault/src/loaders.rs',
    '.certora_sources/programs/vault/src/lib.rs',
    '.certora_sources/programs/vault/src/state.rs',
    '.certora_sources/programs/vault/src/errors.rs',
    '.certora_sources/programs/vault/src/instruction.rs',
    '.certora_sources/programs/vault/src/operations.rs',
    '.certora_sources/programs/vault/src/utils/guards.rs',
    '.certora_sources/programs/vault/src/utils/mod.rs',
    '.certora_sources/programs/vault/src/utils/math.rs',
    '.certora_sources/programs/vault/src/certora/constants.rs',
    '.certora_sources/programs/vault/src/certora/log.rs',
    '.certora_sources/programs/vault/src/certora/mod.rs',
    '.certora_sources/programs/vault/src/certora/nondet.rs',
    '.certora_sources/programs/vault/src/certora/utils.rs',
    '.certora_sources/programs/vault/src/certora/mocks/processor.rs',
    '.certora_sources/programs/vault/src/certora/mocks/mod.rs',
    '.certora_sources/programs/vault/src/certora/specs/solvency_processor.rs',
    '.certora_sources/programs/vault/src/certora/specs/no_dilution.rs',
    '.certora_sources/programs/vault/src/certora/specs/props_processor.rs',
    '.certora_sources/programs/vault/src/certora/specs/fees.rs',
    '.certora_sources/programs/vault/src/certora/specs/vault_consistency.rs',
    '.certora_sources/programs/vault/src/certora/specs/base.rs',
    '.certora_sources/programs/vault/src/certora/specs/mod.rs',
    '.certora_sources/programs/vault/src/certora/specs/solvency.rs',
    '.certora_sources/programs/vault/src/certora/specs/props.rs',
    '.certora_sources/programs/vault/src/certora/specs/access_control.rs',
    '.certora_sources/programs/vault/src/certora/specs/no_dilution_processor.rs',
    '.certora_sources/programs/vault/src/certora/specs/base_processor.rs',
    '.certora_sources/.project_directory'
}


def assert_zip_file(zip_path: str, test_id: str, expected_files_set: Set[str]) -> None:
    with zipfile.ZipFile(zip_path, 'r') as zip_ref:
        zip_contents_set = set(zip_ref.namelist())

    diff = zip_contents_set - expected_files_set
    assert not diff, f"test_solana_zip: {test_id} zip contains unexpected files: {diff}"
    diff = expected_files_set - zip_contents_set
    assert not diff, f"test_solana_zip: {test_id} zip is missing expected files: {diff}"


class TestClient(unittest.TestCase):

    def test_solana_context(self) -> None:
        with Util.change_working_directory(VAULT_CONF_DIR):

            # solana run from cargo
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_cargo.conf",
                test_attribute=str(Util.TestValue.AFTER_BUILD)
            )

            context = suite.expect_checkpoint(description="solana context: locally from cargo")
            assert_context(context, "cargo local")
            context = suite.expect_checkpoint(description="solana context: remotely from cargo", run_flags=remote_args)
            assert_context(context, "cargo remote")

            # solana run from a build script
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_script.conf",
                test_attribute=str(Util.TestValue.AFTER_BUILD)
            )

            context = suite.expect_checkpoint(description="solana context: locally from script")
            assert_context(context, "script local")
            context = suite.expect_checkpoint(description="solana context: remotely from script", run_flags=remote_args)
            assert_context(context, "script remote")

            # solana run no build
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_no_build.conf",
                test_attribute=str(Util.TestValue.AFTER_BUILD)
            )

            context = suite.expect_checkpoint(description="solana context: locally without build")
            assert_context(context, "no build local")
            context = suite.expect_checkpoint(description="solana context: remotely without build",
                                              run_flags=remote_args)
            assert_context(context, "no build remote")

    def test_solana_jar_cmd(self) -> None:

        with Util.change_working_directory(VAULT_CONF_DIR):

            # solana run from cargo
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_cargo.conf",
                test_attribute=str(Util.TestValue.BEFORE_LOCAL_PROVER_CALL)
            )

            jar_cmd = suite.expect_checkpoint(description="solana jar: locally from cargo")
            assert_jar_cmd(jar_cmd, "cargo local")

            # solana run from a build script
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_script.conf",
                test_attribute=str(Util.TestValue.BEFORE_LOCAL_PROVER_CALL)
            )

            jar_cmd = suite.expect_checkpoint(description="solana jar: locally from script")
            assert_jar_cmd(jar_cmd, "script local")

            # solana run no build
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_no_build.conf",
                test_attribute=str(Util.TestValue.BEFORE_LOCAL_PROVER_CALL)
            )

            jar_cmd = suite.expect_checkpoint(description="solana jar: locally without build")
            assert_jar_cmd(jar_cmd, "no build local")

    def test_solana_zip(self) -> None:

        with Util.change_working_directory(VAULT_CONF_DIR):

            conf_file_template = "conf_cargo.conf"
            expected_files_set = common_expected_files_set.union(
                {f'.certora_sources/programs/vault/src/certora/confs/{conf_file_template}'},
                common_build_files_set
            )

            # solana run from cargo
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_cargo.conf",
                test_attribute=str(Util.TestValue.CHECK_ZIP)
            )

            zip_path = suite.expect_checkpoint(description="solana jar: locally from cargo", run_flags=remote_args)
            assert_zip_file(zip_path, "cargo local", expected_files_set)

            # solana run from a build script

            conf_file_template = "conf_script.conf"
            expected_files_set = common_expected_files_set.union(
                {
                    f'.certora_sources/programs/vault/src/certora/confs/{conf_file_template}',
                    '.certora_sources/programs/vault/certora_build.py'
                },
                common_build_files_set
            )
            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template="conf_script.conf",
                test_attribute=str(Util.TestValue.CHECK_ZIP)
            )

            zip_path = suite.expect_checkpoint(description="solana jar: locally from script", run_flags=remote_args)
            assert_zip_file(zip_path,  "script local", expected_files_set)

            # solana run no build

            conf_file_template = "conf_no_build.conf"
            expected_files_set = common_expected_files_set.union({
                f'.certora_sources/programs/vault/src/certora/confs/{conf_file_template}'
            })

            suite = TestUtil.SolanaProverTestSuite(
                conf_file_template=conf_file_template,
                test_attribute=str(Util.TestValue.CHECK_ZIP)
            )

            zip_path = suite.expect_checkpoint(description="solana jar: locally without build", run_flags=remote_args)
            assert_zip_file(zip_path, "no build local", expected_files_set)

    @staticmethod
    def get_card_object(parent, nested):
        return next((section for section in parent if section.card_title == nested), None)

    @staticmethod
    def get_inner_object(parent, nested):
        return next((section for section in parent if section.inner_title == nested), None)


    def test_solana_runs(self) -> None:

        temp_dir = f"temp_{random.randint(0, 99999):05d}"

        os.makedirs(temp_dir, exist_ok=True)

        suite = TestUtil.SolanaProverTestSuite(
            conf_file_template=str(Path.cwd() / _p("rust.conf")),
            test_attribute=str(Util.TestValue.AFTER_BUILD),
            build_script_template=str(Path.cwd() / _p("rust_build_script_template.py.j2")),
        )

        suite.expect_checkpoint(
            description="run solana from subdir",
            build_script_context={
                'argument': 'json',
                'output_data':
                    {
                        "success": True,
                        "project_directory": temp_dir,
                        "sources": ["a.rs"],
                        "executables": str(Path('..') / _p('empty.so'))
                    }
            }
        )
        assert Path('./.certora_internal/latest/.certora_sources/.cwd').is_file(), \
            "test_solana_runs: .cwd does not exist (1)"
        assert Path(f'./.certora_internal/latest/.certora_sources/{temp_dir}/.project_directory').is_file(), \
            "test_solana_runs: .project_directory does not exist (1)"

        with (((Util.change_working_directory(temp_dir)))):
            suite.expect_checkpoint(
                description="run solana from subdir with change dir",
                build_script_context={
                    'argument': 'json',
                    'output_data':
                        {
                            "success": True,
                            "project_directory": '..',
                            "sources": ["a.rs"],
                            "executables": _p('empty.so')
                        }
                }
            )

            print(f"cwd: {os.getcwd()}")
            p = f"../{_p('empty.so')}"
            print(f"p: {p}")
            print(f"exists: {Path(p).exists()}")

            assert Path(f'./.certora_internal/latest/.certora_sources/{temp_dir}/.cwd').is_file(), \
                "test_solana_runs: .cwd does not exist (2)"
            assert Path('./.certora_internal/latest/.certora_sources/.project_directory').is_file(), \
                "test_solana_runs: .project_directory does not exist (2)"

        suite = TestUtil.SolanaProverTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))
        suite.expect_success(description='calling solana with .so',
                             run_flags=[_p('empty.so')])
        suite.expect_failure(description='calling solana with solc flag',
                             run_flags=[_p('empty.so'), '--solc', 'solc4.25'],
                             expected="unrecognized arguments: --solc")

        suite = TestUtil.SolanaProverTestSuite(
            conf_file_template=_p("rust.conf"),
            test_attribute=str(Util.TestValue.AFTER_BUILD_RUST),
            build_script_template=_p("rust_build_script_template.py.j2"),
        )

        result = suite.expect_checkpoint(
            description="run soroban with correct build script schema",
            build_script_context={
                'argument': 'json',
                'output_data':
                    {
                        "success": True,
                        "project_directory": temp_dir,
                        "sources": ["a.rs"],
                        "solana_inlining": "inline.txt",
                        "executables": _p('empty.so'),
                    }
            }
        )

        assert result.solana_inlining == [f'{temp_dir}/inline.txt'], "setting solana_inlining from CLI"

    def test_solana_configuration_layout_data(self) -> None:
        """
        Tests that the configuration_layout is as expected.
        """
        args = [_p('empty.so'), '--server', 'production', '--rule', 'dummy_rule']
        args = TestUtil.add_test_flag(args, Util.TestValue.CHECK_CONFIG_LAYOUT)
        try:
            run_solana_prover(args)
            raise AssertionError(f"__test_main_spec: No Test Result for {args}")
        except Util.TestResultsReady as e:
            layout = e.data.configuration_layout

            # Validating files section in RunConfigurationLayout
            files = self.get_card_object(layout, 'files')
            assert files, f"Error! files section is expected to exist in configuration layout data!\n{layout}"
            assert _p('empty.so') in files.content[0].content, \
                f"Error! files in configuration layout is {files.content[0].content}, expected {_p('empty.so')}"
            assert 'solana' in files.content[0].doc_link and 'files' in files.content[0].doc_link, \
                f"Error! doc_link in configuration layout is {files.content[0].doc_link}\n" \
                f"expected 'solana' and '#files' in link!"

            # Validating general section exists in RunConfigurationLayout
            # Note: we do not have Git or CLI version info on CI
            assert self.get_card_object(layout, 'general'), \
                   f"Error! General section is expected to exist in configuration layout data!\n{layout}"

            # Validating options section
            options_data = self.get_card_object(layout, 'options')
            assert options_data, \
                f"Error! Options section is expected to exist in configuration layout data!\n{layout}"

            server_data = self.get_inner_object(options_data.content, 'server')
            assert server_data and "production" == server_data.content, \
                f"Error! server flag in general section is incorrect!\n" \
                f"expected: 'production', actual: '{server_data.content}'"

            rule_data = self.get_inner_object(options_data.content, 'rule')
            assert rule_data and "dummy_rule" in rule_data.content and "SIMPLE" == rule_data.content_type \
                   and "prover/cli" in rule_data.doc_link, \
                   f"Error! rule subsection in general section is incorrect!\n" \
                   f"expected: dummy_rule in content, content_type: SIMPLE and 'prover/cli' in doc_link,\n" \
                   f"actual: '{rule_data}'"


if __name__ == '__main__':
    test_argv = [f"{sys.argv[1]}, {sys.argv[2]}"]
    runner = unittest.main(argv=test_argv, exit=False)
    if not runner.result.wasSuccessful():
        exit(1)
