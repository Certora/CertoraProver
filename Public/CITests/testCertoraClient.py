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

import copy
import json
import os
import shutil
import sys
import unittest
from pathlib import Path
from typing import List, Any, Dict, Optional
from collections import OrderedDict


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

import CertoraProver.certoraContextAttributes as Attrs
from Mutate import mutateAttributes as MutAttrs
import CITests.testCertoraUtils as TestUtil
from certoraSorobanProver import run_soroban_prover
from certoraRun import run_certora
from Shared import certoraUtils as Util
from CertoraProver.Compiler.CompilerCollectorFactory import get_relevant_compiler
from Shared import certoraValidateFuncs as Vf
from CertoraProver.certoraContextClass import CertoraContext
import CertoraProver.certoraContext as Ctx
from Shared import certoraAttrUtil as AttrUtil

# short call so we can put all args in a single line
def _p(filename: str) -> str:
    return TestUtil.path_test_file(filename)


def test_simple_args(attr: AttrUtil.AttributeDefinition = Attrs.EvmProverAttributes.FILES) -> List[str]:
    if attr in [Attrs.EvmProverAttributes.BYTECODE_JSONS,
                Attrs.EvmProverAttributes.BYTECODE_SPEC]:

        args = ['--bytecode_jsons', _p('erc20.json'), '--bytecode_spec',
                _p('erc20.spec')]
    else:
        args = [f"{_p('_simple$.sol')}:_Simple$", '--verify',
                f"_Simple$:{_p('_simple$.spec')}", '--solc', 'solc4.25',
                '--disable_local_typechecking']
    return args

class TestClient(unittest.TestCase):

    def test_auth_data(self) -> None:
        def auth_data_run(more_args: List[str] = []) -> List[str]:
            return ([_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--server', 'production',
                     '--msg', msg, '--disable_local_typechecking',
                     '--fe_version', fe_version, '--loop_iter', loop_iter, '--cache', cache, '--commit_sha1', '012345',
                     '--protocol_name', protocol_name, '--protocol_author', protocol_author,
                     '--mutation_test_id', mutation_test_id, '--java_args', java_args
                     ] + more_args)

        def check_auth_key(auth_key: str, value: Any, auth_data: Dict[str, Any]) -> None:
            if auth_key in auth_data:
                assert value == auth_data[auth_key], f"'{auth_key}' should be {auth_data[auth_key]} not {value}"
            else:
                assert False, f"'{auth_key}' is not in auth_data"

        branch = 'master'
        run_source = str(Vf.RunSources.COMMAND).upper()
        fe_version = 'latest'
        msg = 'message1'
        commit_sha1 = '012345'
        loop_iter = '5'
        cache = 'cache_id'
        protocol_author = 'pauthor'
        protocol_name = 'pname'
        mutation_test_id = 'test5'
        java_args = '-ea'

        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.CHECK_AUTH_DATA))
        args = auth_data_run(['--prover_version', branch, '--test', str(Util.TestValue.CHECK_AUTH_DATA)])
        result = suite.expect_checkpoint(description="check valid flags",
                                         run_flags=auth_data_run(['--prover_version', branch]))

        # simple verifications (comparing input to auth_data key)
        check_auth_key('branch', branch, result)
        check_auth_key('run_source', run_source, result)
        check_auth_key('msg', msg, result)
        check_auth_key('git_hash', commit_sha1, result)
        check_auth_key('jarSettings', ['-b', loop_iter], result)
        check_auth_key('ci', os.environ.get('CI', '') == 'true', result)
        check_auth_key('toolSceneCacheKey', cache, result)
        check_auth_key('protocolName', protocol_name, result)
        check_auth_key('protocolAuthor', protocol_author, result)
        check_auth_key('primaryContract', 'A', result)
        check_auth_key('useLatestFe', True, result)
        check_auth_key('mutationTestId', mutation_test_id, result)
        check_auth_key('javaArgs', java_args, result)

        # not simple value verifications (auth_data key is not a simple copy of the input)
        if 'runName' in result:
            run_name = result['runName']
            assert len(run_name) == 32, f"'runName' {run_name} length is not 32, got {len(run_name)}"
        else:
            assert False, "'runName' is not in auth_data"

        if 'useLatestFe' in result:
            assert result['useLatestFe'], f"'useLatestFe' is {result['useLatestFe']} not True"
        else:
            assert False, "'useLatestFe' is not in auth_data"

        if 'buildTime' in result:
            assert isinstance(result['buildTime'], float), "'buildTime' is not of type float"
        else:
            assert False, "'buildTime' is not in auth_data"

        if 'buildArgs' in result:
            assert sorted(result['buildArgs'].split()) == sorted(args), \
                f"'buildArgs' is {result['buildArgs']} not {args}"
        else:
            assert False, "'buildArgs' is not in auth_data"

    def test_conf_file_inputs(self) -> None:
        suite = TestUtil.ProverTestSuite(conf_file_template=_p('mutation_conf_top_level.conf'),
                                test_attribute=str(Util.TestValue.CHECK_ARGS))
        suite.expect_success(description='placeholder is replaced with empty string',
                             replacements=TestUtil.replace_x(''))
        suite.expect_failure(description="inject '2'", replacements=TestUtil.replace_x('2'), expected='Unexpected')
        suite.expect_failure(description="inject ','", replacements=TestUtil.replace_x(','), expected='Unexpected')
        suite.expect_success(description="valid string attribute", replacements=TestUtil.replace_x('"msg": "msg",'))
        suite.expect_failure(description="inject ','", replacements=TestUtil.replace_x('"msgc": "msg",'),
                             expected='Error when reading')
        suite.expect_failure(description="list value instead of string",
                             replacements=TestUtil.replace_x('"msg": ["msg"],'), expected='is not a string')
        suite.expect_failure(description="dictionary value instead of string",
                             replacements=TestUtil.replace_x('"msg": {},'), expected='is not a string')
        suite.expect_success(description="valid list attribute",
                             replacements=TestUtil.replace_x('"rule": ["rule1", "rule2"],'))
        suite.expect_failure(description="dictionary value instead of a list",
                             replacements=TestUtil.replace_x('"rule": {},'), expected='is not a list')
        suite.expect_success(description="valid dictionary attribute",
                             replacements=TestUtil.replace_x('"solc_map": {"A": "solc6.1","B": "solc6.1"},'))
        suite.expect_failure(description="superfluous column sign",
                             replacements=TestUtil.replace_x('"solc_map": {"A": "solc6.1","B":: "solc6.1"},'),
                             expected='Unexpected')
        suite.expect_failure(description="Duplicate key specified",
                             replacements=TestUtil.replace_x('"solc_map": {"A": "solc6.1"},'
                                                             '"solc_map": {"B": "solc4.25"},'),
                             expected='Duplicate key')
        suite.expect_success(description="valid dictionary attribute",
                             replacements=TestUtil.replace_x('"solc_map": {"A": "solc6.1","B": "solc6.1"},'))
        suite.expect_success(description="valid dictionary attribute (full paths)",
                             replacements=TestUtil.replace_x(f"'solc_map': {{'{_p('A.sol')}': 'solc6.1',"
                                                             f"'{_p('B.sol')}': 'solc6.1'}},"))
        suite.expect_success(description="valid dictionary attribute (compiler_map)",
                             replacements=TestUtil.replace_x('"compiler_map": {"A": "solc6.1","B": "solc6.1"},'))
        suite.expect_failure(description=":: instead of : in compiler_map",
                             replacements=TestUtil.replace_x('"compiler_map": {"A": "solc6.1","B":: "solc6.1"},'),
                             expected='Unexpected')
        suite.expect_failure(description="Duplicate key specified (compiler_map)",
                             replacements=TestUtil.replace_x('"compiler_map": {"A": "solc6.1"},'
                                                             '"compiler_map": {"B": "solc4.25"},'),
                             expected='Duplicate key')
        suite.expect_success(description="valid dictionary attribute (compiler_map)",
                             replacements=TestUtil.replace_x('"compiler_map": {"A": "solc6.1","B": "solc6.1"},'))
        suite.expect_success(description="valid dictionary attribute (full paths) (compiler_map)",
                             replacements=TestUtil.replace_x(f"'compiler_map': {{'{_p('A.sol')}': 'solc6.1',"
                                                             f"'{_p('B.sol')}': 'solc6.1'}},"))
        suite.expect_failure(description="Duplicate key specified (rule)",
                             replacements=TestUtil.replace_x('"rule": ["rule1"], "rule": ["rule2"],'),
                             expected='Duplicate key')
        suite.expect_failure(description="Duplicate key specified (full path)",
                             replacements=TestUtil.replace_x(f"'solc_map': {{'{_p('A.sol')}': 'solc6.1',"
                                                             f"'{_p('A.sol')}': 'solc6.1'}},"),
                             expected='Duplicate key')
        suite.expect_failure(description="string instead of map",
                             replacements=TestUtil.replace_x("'solc_map': 'solc6.1',"),
                             expected='should be stored as a map')
        suite.expect_failure(description="string instead of map",
                             replacements=TestUtil.replace_x("'solc_map': 'solc6.1',"),
                             expected='should be stored as a map')
        suite = TestUtil.ProverTestSuite(conf_file_template=_p('conf_in_a_conf.conf'),
                                test_attribute=str(Util.TestValue.CHECK_ARGS))
        suite.expect_failure(description="Cannot use conf files inside a conf file",
                             expected='Cannot use conf files inside a conf file')

    def test_valid_runs(self) -> None:

        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))
        suite.expect_success(description='valid assert on file name',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}"])
        suite.expect_success(description='valid assert on redundant name:contract',
                             run_flags=[f"{_p('A.sol')}:A", '--verify', f"A:{_p('spec1.spec')}"])
        suite.expect_success(description='valid assert on non-redundant name:contract',
                             run_flags=[f"{_p('A.sol')}:B", '--verify', f"B:{_p('spec1.spec')}"])
        suite.expect_success(description='multiple files single assert',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}"])
        suite.expect_success(description='file is repeated',
                             run_flags=[f"{_p('A.sol')}:B", f"{_p('A.sol')}:B", '--verify', f"B:{_p('spec1.spec')}"])
        suite.expect_success(description='2 contracts in a single file',
                             run_flags=[f"{_p('A.sol')}:B", _p('A.sol'), '--verify', f"B:{_p('spec1.spec')}"])
        suite.expect_success(description='--verify with spec file',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}"])
        suite.expect_success(description='--verify with cvl file',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('erc20.cvl')}"])
        suite.expect_success(description='--link ',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:a=B'])
        suite.expect_success(description='--link: self linking',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:a=A'])

        suite.expect_success(description='slot names can have alphanumeric characters and underscores',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:a_2=B'])
        suite.expect_success(description='defining the same link twice (legal)',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:a=B',
                                        'A:a=B'])
        suite.expect_success(description='slot names can have alphanumeric characters and underscores',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:a1=B',
                                        'B:b_1=A'])
        suite.expect_success(description='a link can accept a number',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:12=A'])
        suite.expect_success(description='a link can accept the number 0',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:0=A'])
        suite.expect_success(description='a link can accept the number hex',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:0Xd3c=A'])
        suite.expect_success(description='--solc valid',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc', 'solc6.10'])
        suite.expect_success(description='--solc_optimize valid',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_optimize'])
        suite.expect_success(description='--solc_optimize with runs valid',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_optimize', '300'])
        suite.expect_success(description='--solc_map valid',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_map',
                                        f"{_p('A.sol')}=solc6.10"])
        suite.expect_success(description='--solc_map 2 entries valid',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('A.sol')}=solc6.10,{_p('B.sol')}=solc4.25"])
        suite.expect_success(description='solc_map can get contracts',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_map',
                                        f"A=solc6.10,{_p('B.sol')}=solc4.25"])
        suite.expect_success(description='solc_map duplicated entries (valid)',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_map',
                                        f"{_p('A.sol')}=solc6.10,{_p('A.sol')}=solc6.10"])
        suite.expect_success(description='spaces inside solc_map are trimmed',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('A.sol')} =solc6.10 , {_p('A.sol')} = solc6.10"])
        suite.expect_success(description='--solc_map with equivalent paths',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_map',
                                        f"{_p('dir1/../A.sol')}=solc6.10"])

        suite.expect_success(description='--solc_via_ir_map valid',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_via_ir_map', f"{_p('A.sol')}=true"])
        suite.expect_success(description='--solc_via_ir_map 0 valid value',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_via_ir_map', f"{_p('A.sol')}=false"])
        suite.expect_success(description='--solc_via_ir_map 2 entries',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_via_ir_map', f"{_p('A.sol')}=false,{_p('B.sol')}=true"])
        suite.expect_success(description='--solc_via_ir_map with duplicates',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_via_ir_map',
                                        f"{_p('A.sol')}=true,{_p('A.sol')}=true"])
        suite.expect_success(description='--solc_via_ir_map keys are contracts or contract files',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_via_ir_map',
                                        f"A=true,{_p('B.sol')}=false"])
        suite.expect_success(description='--solc_via_ir_map keys are contracts',
                             run_flags=[f"{_p('A.sol')}:A", f"{_p('A.sol')}:B", '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_via_ir_map', "A=true,B=true"])
        suite.expect_success(description='--jar valid run',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--jar', _p('empty.jar')])
        suite.expect_success(description='--tool_output valid run',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--tool_output', _p('empty.json')])
        suite.expect_success(description='--tool_output files do not require a suffix of .json',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--tool_output', _p('empty')])
        suite.expect_success(description='valid tac file', run_flags=[_p('empty.tac')])
        suite.expect_success(description='valid conf file', run_flags=[_p('tac_file.conf')])
        suite.expect_success(description='valid --bytecode_jsons',
                             run_flags=['--bytecode_jsons', _p('erc20.json'), '--bytecode_spec', _p('spec1.spec')])
        suite.expect_success(description='valid --packages_path',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--packages_path', '.'])
        suite.expect_success(description='valid --packages',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--packages', 'a=.'])
        suite.expect_success(description='valid --server',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--server', 'production'])
        suite.expect_success(description='valid --server and --prover_version',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--server', 'production',
                                        '--prover_version', 'hotfix'])
        suite.expect_success(description='valid --java_args',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--java_args', '-ea'])
        suite.expect_success(description='valid --java_args multiple args',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--java_args', '"-Xmx8g -Dcvt.default.parallelism=2"'])
        suite.expect_success(description='valid --prover_args',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--prover_args', '-a 88'])
        suite.expect_success(description='valid --address',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--address', "A:1"])
        suite.expect_success(description='valid --address slot 0 is legal',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--address', "A:0"])
        suite.expect_success(description='valid --debug_topics ',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--debug', '--debug_topics', 'rome'])
        suite.expect_success(description='valid --debug_topics  without --debug',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--debug_topics', 'rome'])
        suite.expect_success(description='valid --show_debug_topics',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--debug_topics', 'rome',
                                        '--show_debug_topics'])
        suite.expect_success(description='valid --debug_topics and --show_debug_topics',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--debug_topics', 'rome',
                                        '--show_debug_topics'])
        suite.expect_success(description='valid --no_compare ',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--no_compare'])
        suite.expect_success(description='valid --expected_file',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--expected_file', _p('empty.json')])
        suite.expect_success(description='valid --wait_for_results',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--wait_for_results'])
        suite.expect_success(description='valid --struct_link',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--struct_link', 'A:0=A'])
        suite.expect_success(description='valid --struct_link accepts slot as hexadecimal numbers',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--struct_link', 'A:0=A'])
        suite.expect_success(description='valid --struct_link multiple entries',
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--struct_link',
                                        'A:1=B', 'A:2=B', 'A:1009=A'])
        suite.expect_success(description='valid --struct_link duplicate entries',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--struct_link', 'A:0XC=A', 'A:0XC=A'])
        suite.expect_success(description='valid --build_only',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--build_only'])
        suite.expect_success(description='valid --build_only duplicate entries',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--build_only', '--build_only'])
        suite.expect_success(description='valid --compilation_steps_only',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--server', 'staging',
                                        '--compilation_steps_only'])
        suite.expect_success(description='valid --disable_local_typechecking',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--disable_local_typechecking'])
        suite.expect_success(description='valid --queue_wait_minutes',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--queue_wait_minutes', '1'])
        suite.expect_success(description='valid --queue_wait_minutes accepts the argument zero',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--queue_wait_minutes', '0'])
        suite.expect_success(description='valid --max_poll_minutes',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--max_poll_minutes', '1'])
        suite.expect_success(description='valid --max_poll_minutes accepts the argument zero',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--max_poll_minutes', '0'])
        suite.expect_success(description='valid --log_query_frequency_seconds',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--log_query_frequency_seconds', '1'])
        suite.expect_success(description='valid --log_query_frequency_seconds accepts the argument zero',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--log_query_frequency_seconds', '0'])
        suite.expect_success(description='valid --max_attempts_to_fetch_output',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--max_attempts_to_fetch_output', '1'])
        suite.expect_success(description='valid --max_attempts_to_fetch_output accepts the argument zero',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--max_attempts_to_fetch_output', '0'])
        suite.expect_success(description='valid --delay_fetch_output_seconds',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--delay_fetch_output_seconds', '1'])
        suite.expect_success(description='valid --delay_fetch_output_seconds accepts the argument zero',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--delay_fetch_output_seconds', '0'])
        suite.expect_success(description='valid --msg',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--msg', '1'])
        suite.expect_success(description='valid --msg with double quotes',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--msg', '"rio de janeiro"'])
        suite.expect_success(description='valid --msg with space',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--msg', 'rio de janeiro'])
        suite.expect_success(description='valid --protocol_name',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--protocol_name', 'protocol1'])
        suite.expect_success(description='valid --protocol_author',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--protocol_author', 'Joe'])
        suite.expect_success(description='valid --solc_evm_version',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_evm_version', 'Istanbul'])
        suite.expect_success(description='valid --solc_via_ir',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_via_ir'])
        suite.expect_success(description='valid --solc_experimental_via_ir',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--solc_experimental_via_ir'])
        suite.expect_success(description='valid --rule',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--rule', 'always_true'])
        suite.expect_success(description='valid --loop_iter',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--loop_iter', '1000'])
        suite.expect_success(description='valid --smt_timeout',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--smt_timeout', '100000'])
        suite.expect_success(description='valid --max_graph_depth',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--max_graph_depth', '100000'])
        suite.expect_success(description='valid --max_graph_depth accept 0',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--max_graph_depth', '0'])
        suite.expect_success(description='valid --global_timeout',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--global_timeout', '1800'])
        suite.expect_success(description='valid --global_timeout accept 0',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--global_timeout', '0'])
        suite.expect_success(description='valid --commit_sha1',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--server', 'staging',
                                        '--commit_sha1', '123'])
        suite.expect_success(description='valid --commit_sha1 max length',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--server', 'staging',
                                        '--commit_sha1', '3' * 40])
        suite.expect_success(description='valid --internal_funcs',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--internal_funcs', _p('erc20.json')])
        suite.expect_success(description='valid --run_source',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--run_source', 'COMMAND'])
        suite.expect_success(description='valid --parametric_contracts',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--parametric_contracts', 'A'])
        ctx = suite.expect_checkpoint(description='dynamic_bound sets disable_source_finders',
                                      run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--dynamic_bound', '4'])
        if not ctx.disable_source_finders:
            self.fail("dynamic_bound should set disable_source_finders")

    def test_invalid_runs(self) -> None:
        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))

        suite.expect_failure(description='--compiler_map file not found',
                             run_flags=[_p('A.sol'), _p('V.vy'), '--verify', f"A:{_p('spec1.spec')}", '--compiler_map',
                                        f"{_p('A.sol')}=solc6.10"],
                             expected="The following files are not matched in compiler_map: ['Test/CITests/test_data/V.vy']")
        suite.expect_failure(description='--compiler_map and --vyper',
                             run_flags=[_p('A.sol'), _p('V.vy'), '--verify', f"A:{_p('spec1.spec')}", '--compiler_map',
                                        f"{_p('A.sol')}=solc6.10",
                                        '--vyper', 'vyper0.3.7'],
                             expected='compiler map flags cannot be set with other compiler flags')
        suite.expect_failure(description='--solc_map and --vyper',
                             run_flags=[_p('A.sol'), _p('V.vy'), '--verify', f"A:{_p('spec1.spec')}", '--solc_map',
                                        f"{_p('A.sol')}=solc6.10",
                                        '--vyper', 'vyper0.3.7'],
                             expected='compiler map flags cannot be set with other compiler flags')
        suite.expect_failure(description='--compiler_map with --solc',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--compiler_map',
                                        f"{_p('A.sol')}=solc6.10",
                                        '--solc', 'solc4.25'],
                             expected='compiler map flags cannot be set with other compiler flags')
        suite.expect_failure(description='--compiler_map with --vyper',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--compiler_map',
                                        f"{_p('A.sol')}=solc6.10",
                                        '--vyper', 'vyper0.3.7'],
                             expected='compiler map flags cannot be set with other compiler flags')
        suite.expect_failure(description='not valid contract name',
                             run_flags=[f"{_p('A.sol')}:El-Paso", '--verify', f"A:{_p('spec1.spec')}"],
                             expected='El-Paso should be a valid contract name')
        suite.expect_failure(description="--bytecode_spec' instead of 'prover_args' with -spec",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--prover_args', '-spec XXX'],
                             expected='Use CLI flag ')
        suite.expect_failure(description='bad link',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A::1=2'],
                             expected='must be of the form contractA:slot=contractB or contractA:slot=<number>')
        suite.expect_failure(description='double address',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--address', 'A:2', 'A:3'],
                             expected='contract A was given two different addresses')
        suite.expect_failure(description="unrecognized contract in 'address'",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--address', 'C:2', 'A:3'],
                             expected="unrecognized contract in 'address'")
        suite.expect_failure(description="Must use 'bytecode' together with 'bytecode_spec'",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--bytecode_jsons',
                                        f"{_p('empty.json')}"],
                             expected="Must use 'bytecode' together with 'bytecode_spec'")
        suite.expect_failure(description="Must use 'bytecode' together with 'bytecode_spec' - 2",
                             run_flags=[_p('A.sol'), '--bytecode_spec', f"A:{_p('spec1.spec')}"],
                             expected="Must use 'bytecode' together with 'bytecode_spec'")
        suite.expect_failure(description="Must use 'bytecode' together with 'bytecode_spec' - 3",
                             run_flags=[_p('A.sol'), '--bytecode_jsons', f"{_p('empty.json')}"],
                             expected="Must use 'bytecode' together with 'bytecode_spec'")
        suite.expect_failure(description="must use either 'verify' or 'bytecode_jsons'",
                             run_flags=[_p('A.sol')],
                             expected="You must use 'verify' when running the Certora Prover")
        suite.expect_failure(description="package a was given two paths",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--packages',
                                        f"a={_p('dir1')}", '--packages', f"a={_p('.')}"],
                             expected='package a was given two paths')
        suite.expect_failure(description="cannot use both 'solc' and 'solc_map' arguments",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('A.sol')}=solc6.10", '--solc', 'solc4.25'],
                             expected="compiler map flags cannot be set with other compiler flags")
        suite.expect_failure(description="bad --solc_map",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('B.sol')}=solc6.10"],
                             expected="The following files are not matched in compiler_map: ['Test/CITests/test_data/A.sol']")
        suite.expect_failure(description="not a source file",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('spec1.spec')}=solc6.10,{_p('A.sol')}=solc5.11"],
                             expected="does not end with ('.sol', '.vy', '.yul')")
        suite.expect_failure(description="wrong Solidity executable given",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('A.sol')}=solc6.sol"],
                             expected='wrong Solidity executable given')
        suite.expect_failure(description="solc not found in path",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('A.sol')}=solc5.1"],
                             expected='solc5.1 not found in path')
        suite.expect_failure(description="Some source files do not appear in solc_map",
                             run_flags=[_p('A.sol'), _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--solc_map', f"{_p('A.sol')}=solc5.11"],
                             expected="The following files are not matched in compiler_map: ['Test/CITests/test_data/B.sol']")
        suite.expect_failure(description="A contract named B was declared twice",
                             run_flags=[f"{_p('A.sol')}:B", _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}"],
                             expected='A contract named B was declared twice')
        suite.expect_failure(description="cannot use both 'compilation_steps_only' and 'build_only'",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--compilation_steps_only',
                                        '--build_only'],
                             expected="cannot use both 'compilation_steps_only' and 'build_only'")
        suite.expect_failure(description='“ instead of "',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--msg', '“msg“'],
                             expected='Please replace “ with " quotation marks')
        suite.expect_failure(description="bytecode' together with 'bytecode_spec'",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--bytecode_jsons',
                                        f"{_p('empty.json')}"],
                             expected="Must use 'bytecode' together with 'bytecode_spec'")
        suite.expect_failure(description="'bytecode_jsons' with other files",
                             run_flags=[_p('A.sol'), '--bytecode_jsons', _p('erc20.json'), '--bytecode_spec',
                                         _p('spec1.spec'), '--verify', f"A:{_p('spec1.spec')}"],
                             expected="Cannot use 'bytecode_jsons' with other files")
        suite.expect_failure(description="using both branch and commit",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--commit_sha1', '123', '--server',
                                        'staging', '--prover_version', 'production'],
                             expected="Cannot run on both a specific branch production and a specific commit")
        suite.expect_failure(description="--cloud_global_timeout not allowed",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--cloud_global_timeout', "60"],
                             expected="Cannot set the global timeout for the cloud. Use 'global_timeout' instead")
        suite.expect_failure(description="--rule with tac",
                             run_flags=[_p('empty.tac'), '--rule', 'always_true'],
                             expected="rules flag/attributes such as --rule, --exclude_rule or --split_rules are only")
        suite.expect_failure(description="compilation_steps_only with build_only",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--compilation_steps_only',
                                        '--build_only'],
                             expected="cannot use both 'compilation_steps_only' and 'build_only'")
        suite.expect_failure(description="illegal struct_link",
                             run_flags=[_p('tac_file.conf'), '--struct_link', 'tac_file:0=tac_file'],
                             expected="'struct_link' argument tac_file:0=tac_file is illegal")
        suite.expect_failure(description="illegal struct_link 2",
                             run_flags=[_p('empty.tac'), '--struct_link', 'empty:0=empty'],
                             expected="'struct_link' argument empty:0=empty is illegal")
        suite.expect_failure(description="illegal struct_link 3",
                             run_flags=[f"{_p('A.sol')}:B", _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                        '--struct_link', 'A:0=A', 'A:0=B'],
                             expected="A contract named B was declared twice ")
        suite.expect_failure(description="illegal struct_link 4",
                             run_flags=
                             [f"{_p('A.sol')}:B", _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--struct_link', 'C:0=C'],
                             expected="A contract named B was declared twice")
        suite.expect_failure(description="unrecognized contract in 'address'",
                             run_flags=[_p('tac_file.conf'), '--address', 'tac_file:1'],
                             expected="unrecognized contract in 'address' argument")
        suite.expect_failure(description="unrecognized contract in 'address' 2",
                             run_flags=[_p('empty.tac'), '--address', 'tac_file:1'],
                             expected="unrecognized contract in 'address' argument")
        suite.expect_failure(description="two different addresses",
                             run_flags=[f"{_p('A.sol')}:A", _p('B.sol'), '--verify', f"A:{_p('spec1.spec')}", '--address',
                                        'A:2', 'A:3'],
                             expected="contract A was given two different addresses")
        suite.expect_failure(description="package a was given two paths",
                             run_flags=[f"{_p('A.sol')}:A", '--verify', f"A:{_p('spec1.spec')}", '--packages', 'a=.', 'a=..'],
                             expected="package a was given two paths")
        suite.expect_failure(description="packages_path'spath does not exist",
                             run_flags=[f"{_p('A.sol')}:A", '--verify', f"A:{_p('spec1.spec')}", '--packages_path', 'KFC'],
                             expected="path KFC does not exist")
        suite.expect_failure(description="use 'bytecode_jsons' with other files",
                             run_flags=[f"{_p('A.sol')}:A", '--verify', f"A:{_p('spec1.spec')}",
                                        '--bytecode_jsons', _p('erc20.json'), '--bytecode_spec', _p('spec1.spec')],
                             expected="Cannot use 'bytecode_jsons' with other files")
        suite.expect_failure(description="other files with conf file",
                             run_flags=[_p('tac_file.conf'), _p('empty.tac')],
                             expected="No other files are allowed when using a config file")
        suite.expect_failure(description="illegal contract in verify",
                             run_flags=[_p('empty.tac'), '--verify', f"A:{_p('spec1.spec')}"],
                             expected="'verify' argument, A, doesn't match any contract name")
        suite.expect_failure(description="'bytecode_jsons' with other files",
                             run_flags=[_p('empty.tac'), '--bytecode_jsons', _p('erc20.json'),
                                        '--bytecode_spec', _p('spec1.spec')],
                             expected="Cannot use 'bytecode_jsons' with other files")
        suite.expect_failure(description="illegal contract in verify (conf file)",
                             run_flags=[_p('tac_file.conf'), '--verify', f"A:{_p('spec1.spec')}"],
                             expected="'verify' argument, A, doesn't match any contract name")
        suite.expect_failure(description="'bytecode_jsons' with other files (conf file)",
                             run_flags=[_p('tac_file.conf'), '--bytecode_jsons', _p('erc20.json'),
                                        '--bytecode_spec', _p('spec1.spec')],
                             expected="Cannot use 'bytecode_jsons' with other files")
        suite.expect_failure(description="'bytecode_jsons' with other files (sol file)",
                             run_flags=[f"{_p('A.sol')}:A", '--verify', f"A:{_p('spec1.spec')}",
                                        '--bytecode_jsons', _p('erc20.json'), '--bytecode_spec', _p('spec1.spec')],
                             expected="Cannot use 'bytecode_jsons' with other files")
        suite.expect_failure(description="2 conf files",
                             run_flags=[_p('tac_file.conf'), _p('tac_file.conf')],
                             expected="No other files are allowed when using a config file")
        suite.expect_failure(description="2 tag files",
                             run_flags=[_p('empty.tac'), _p('empty.tac')],
                             expected="No other files are allowed with a file of type .tac")
        suite.expect_failure(description="Some source files do not appear in 'solc_optimize_map'",
                             run_flags=[f"{_p('A.sol')}", f"{_p('B.sol')}", '--verify', f"A:{_p('spec1.spec')}",
                                         '--solc_optimize_map', 'A=1'],
                             expected="The following files are not matched in solc_optimize_map: ['Test/CITests/test_data/B.sol']")
        suite.expect_failure(description=f"No matching for {CITests_path}/test_data/A.sol in solc_optimize_map",
                             run_flags=[f"{_p('A.sol')}", '--verify', f"A:{_p('spec1.spec')}", '--solc_optimize_map', 'C=1'],
                             expected="The following files are not matched in solc_optimize_map: ['Test/CITests/test_data/A.sol']")
        suite.expect_failure(description="both 'solc_optimize' and 'solc_optimize_map'",
                             run_flags=[f"{_p('A.sol')}", '--verify', f"A:{_p('spec1.spec')}", '--solc_optimize_map',
                                        'A=1', '--solc_optimize'],
                             expected="You cannot use both 'solc_optimize' and 'solc_optimize_map' arguments")
        suite.expect_failure(description="illegal contract in solc_map",
                             run_flags=[f"{_p('A.sol')}", '--verify', f"A:{_p('spec1.spec')}", '--solc_map', 'C=solc6.10'],
                             expected="The following files are not matched in compiler_map: ['Test/CITests/test_data/A.sol']")
        suite.expect_failure(description="'solc' and 'solc_map'",
                             run_flags=[f"{_p('A.sol')}", '--verify', f"A:{_p('spec1.spec')}", '--solc', 'solc6.10', '--solc_map',
                                        'C=solc6.10'],
                             expected="compiler map flags cannot be set with other compiler flags")
        suite.expect_failure(description="missing sources in solc_map",
                             run_flags=[f"{_p('A.sol')}:A", f"{_p('B.sol')}", '--verify', f"A:{_p('spec1.spec')}", '--solc_map',
                                        'A=solc6.10'], expected="The following files are not matched in compiler_map: ['Test/CITests/test_data/B.sol']")
        suite.expect_failure(description="illegal target contract in link",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:b=BB'],
                             expected="Error in linkage: link `A:b=BB`, contract BB does not exist")
        suite.expect_failure(description="illegal contract in link",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'AA:b=B'],
                             expected="Error in linkage: link `AA:b=B`, contract AA does not exist")
        suite.expect_failure(description="multiple slots in link",
                             run_flags=[f"{_p('A.sol')}:A", f"{_p('B.sol')}", '--verify', f"A:{_p('spec1.spec')}",
                                        '--link', 'A:0=A', 'A:0=B'],
                             expected="slot A:0 was defined multiple times")
        suite.expect_failure(description="bad contract in verify",
                             run_flags=[f"{_p('A.sol')}:A", '--verify', f"D:{_p('spec1.spec')}"],
                             expected="doesn't match any contract name")
        suite.expect_failure(description="contract with conf file",
                             run_flags=[_p('tac_file.conf'), f"{_p('A.sol')}:A", '--verify', f"A:{_p('spec1.spec')}"],
                             expected="No other files are allowed when using a config file")
        suite.expect_failure(description="duplicate contracts with assert",
                             run_flags=[f"{_p('A.sol')}:B", f"{_p('B.sol')}", '--verify', f"B:{_p('spec1.spec')}"],
                             expected="A contract named B was declared twice")
        suite.expect_failure(description="illegal parametric contracts",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--parametric_contracts', 'C'],
                             expected="Cannot find parametric contracts {'C'} in the project contract list")
        suite.expect_failure(description="json file with other files",
                             run_flags=[_p('A.sol'), _p('empty.json'), '--verify', f"A:{_p('spec1.spec')}"],
                             expected="No other files are allowed with a file of type .json")
        suite.expect_failure(description="no typecheck_only",
                             run_flags=[f"{_p('A.sol')}:A", '--verify', f"A:{_p('spec1.spec')}", '--typecheck_only'],
                             expected="unrecognized arguments: --typecheck_only")
        suite.expect_failure(description="--precise_bitwise_ops and `prover_arg`",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--prover_args',
                                        '-smt_preciseBitwiseOps',
                                        '--precise_bitwise_ops'],
                             expected="Cannot use both `precise_bitwise_ops` and -smt_preciseBitwiseOps in "
                                      "`prover_args`")

        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.AFTER_BUILD))
        suite.expect_failure(description="wrong slot in link",
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", '--link', 'A:bb=A'],
                             expected="Error in linkage: link A:bb, variable bb does not exist in contract A")

    def test_source_tree(self) -> None:
        Path(f'{CITests_path}/test_data/dir1/dir2').mkdir(parents=True, exist_ok=True)
        self.__test_source_tree_template('dir1', '.', '.')  # running from cwd
        self.__test_source_tree_template('dir1/dir2', '..', '.')  # running from a cwd descendant
        self.__test_source_tree_template('.', 'dir1', 'dir1')  # running from a cwd ancestor

    def __test_source_tree_template(self, running_dir: str, cwd_to_files: str, sources_root_to_cwd: str) -> None:
        """
        Template used for running the same test from different directories
        :param running_dir: relative from test_data
        :param cwd_to_files: relative path from .certora_sources to cwd
        :param sources_root_to_cwd: relative path from .certora_sources to original cwd
        """

        with Util.change_working_directory(Path(f'{CITests_path}/test_data') / running_dir):

            args = [
                f"{cwd_to_files}/contracts/Example.sol",
                '--verify', f"Example:{cwd_to_files}/spec/Example.spec",
                '--disable_local_typechecking',
                '--solc_allow_path', cwd_to_files,
                '--packages', f"@openzeppelin={cwd_to_files}/node_modules/@openzeppelin",
                "--solc", "solc8.17",
                "--yul_abi", f"{cwd_to_files}/erc20.json",
                "--assert_autofinder_success"
            ]
            args = TestUtil.add_test_flag(args, Util.TestValue.AFTER_BUILD)
            try:
                run_certora(args)
                assert False, f"test_jar_flag: No Test Result for {args}"
            except Util.TestResultsReady:
                source_dir = f".certora_internal/latest/.certora_sources/{sources_root_to_cwd}"
                expected_paths = [
                    Path(f"{source_dir}/contracts/Example.sol"),
                    Path(f"{source_dir}/node_modules/@openzeppelin/contracts/token/ERC20/IERC20.sol"),
                    Path(f"{source_dir}/spec/Example.spec"),
                    Path(f"{source_dir}/erc20.json")
                ]
                for p in expected_paths:
                    assert p.exists(), f"test_source_tree: {p} was not found"
                shutil.rmtree('.certora_internal')

    def test_abs_to_rel(self) -> None:
        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))
        result = suite.expect_checkpoint(
            description='abs to rel',
            run_flags=[os.path.abspath(_p('A.sol')),
                       '--packages', f"a={os.path.abspath(_p('.'))}", f"b={os.path.abspath(_p('..'))}",
                       '--verify', f"A:{os.path.abspath(_p('spec1.spec'))}",
                       '--packages_path', os.path.abspath(_p('..')),
                       '--yul_abi', os.path.abspath(_p('erc20.json')),
                       '--prover_resource_files', f"A:{os.path.abspath(_p('spec1.spec'))}",
                       f"B:{os.path.abspath(_p('erc20.spec'))}"])
        expected_values = {
            'files': [_p('A.sol')],
            'packages': [f"a={_p('.')}", f"b={_p('..')}"],
            'verify': f"A:{_p('spec1.spec')}",
            'packages_path': _p('..'),
            'yul_abi': _p('erc20.json'),
            'prover_resource_files': [f"A:{_p('spec1.spec')}", f"B:{_p('erc20.spec')}"]
        }
        for key, expected in expected_values.items():
            assert getattr(result, key) == expected, f"test_abs_to_rel: {key} {getattr(result, key)}"

        result = suite.expect_checkpoint(
            description='abs to rel',
            run_flags=['--bytecode_spec', os.path.abspath(_p('erc20.spec')),
                       '--bytecode_jsons', os.path.abspath(_p('erc20.json'))])
        assert result.bytecode_spec == _p('erc20.spec'), f"test_abs_to_rel: bytecode_spec {result.bytecode_spec}"
        assert result.bytecode_jsons == [_p('erc20.json')], f"test_abs_to_rel: bytecode_jsons {result.bytecode_jsons}"

    def test_jar_flag(self) -> None:
        for attr in Attrs.get_attribute_class().attribute_list():
            has_no_jar_flag = not attr.jar_flag
            is_illegal_attr = (attr in [Attrs.EvmProverAttributes.CLOUD_GLOBAL_TIMEOUT,
                                        Attrs.EvmProverAttributes.SOLC_ARGS])

            # Ranger attributes are ignored by "normal" runs and aren't sent to the jar
            is_ranger_attribute = attr in [Attrs.EvmProverAttributes.RANGE,
                                            Attrs.EvmProverAttributes.RANGER_FAILURE_LIMIT]

            if has_no_jar_flag or is_illegal_attr or is_ranger_attribute:
                continue
            try:
                args = test_simple_args(attr)
                args = TestUtil.add_test_flag(args, Util.TestValue.BEFORE_LOCAL_PROVER_CALL)
                value = TestUtil.get_valid_value(attr)
                # bytecode command already contains the jar flag no need to append
                if attr not in [Attrs.EvmProverAttributes.BYTECODE_JSONS,
                                Attrs.EvmProverAttributes.BYTECODE_SPEC]:

                    args += [attr.get_flag()]
                    if value:
                        args += [value]
                run_certora(args)
                assert False, f"test_jar_flag: No Test Result for {args}"
            except Util.TestResultsReady as e:
                jar_command = e.data
                if attr.jar_flag:  # not really needed to please mypy
                    expected_flag_and_value = attr.jar_flag
                    if value:
                        expected_flag_and_value += f" {value}"

                # some values may be quoted with single or double quotes, instead of complex parsing (values may include
                # spaces, and quotes) we simply compare after removing all quotes single and double from expected and
                # the generated jar command
                def rem_quotes(string: str) -> str:
                    return string.replace("'", "").replace('"', '')
                if rem_quotes(expected_flag_and_value) not in rem_quotes(jar_command):
                    self.fail(f"test_jar_flag: failed for {attr} '{expected_flag_and_value}' not in '{jar_command}'")
            except Exception as e:
                self.fail(f"test_jar_flag: failed for {args} - {e}")

    def test_validation_funcs(self) -> None:
        attrs_with_validation: List[AttrUtil.AttributeDefinition] = []  # type: ignore
        for attr in Attrs.EvmProverAttributes.attribute_list():
            if attr.attr_validation_func != AttrUtil.default_validation:
                attrs_with_validation.append(attr)  # type: ignore
        for attr in Attrs.SolanaProverAttributes.attribute_list():
            if attr.attr_validation_func != AttrUtil.default_validation:
                attrs_with_validation.append(attr)  # type: ignore
        for attr in Attrs.SorobanProverAttributes.attribute_list():
            if attr.attr_validation_func != AttrUtil.default_validation:
                attrs_with_validation.append(attr)  # type: ignore
        for attr in MutAttrs.MutateAttributes.attribute_list():  # type: ignore
            if attr.attr_validation_func != AttrUtil.default_validation:
                attrs_with_validation.append(attr)  # type: ignore
        for attr in MutAttrs.MutateAttributes.attribute_list():  # type: ignore
            if attr.attr_validation_func != AttrUtil.default_validation:
                attrs_with_validation.append(attr)  # type: ignore

        for attr in attrs_with_validation:  # type: ignore
            try:
                tested_object = TestUtil.TEST_VALUES[attr.attr_validation_func]
            except Exception:
                raise RuntimeError(f"could not find {attr.attr_validation_func.__name__} in TEST_VALUES")
            if TestUtil.ENUM_KEY in tested_object:
                TestUtil.validate_enum(attr, tested_object[TestUtil.ENUM_KEY])  # type: ignore
            if TestUtil.VALID_KEY in tested_object:
                TestUtil.validate_valid_values(attr, tested_object[TestUtil.VALID_KEY])
            if TestUtil.INVALID_KEY in tested_object:
                TestUtil.validate_invalid_values(attr, tested_object[TestUtil.INVALID_KEY])

    def test_package_file(self) -> None:
        def check_run(expect: List[str]) -> None:
            packages_attr = getattr(result, 'packages', None)
            assert packages_attr, f"{description}: package is None"
            got = sorted([dep.split('=')[0] for dep in packages_attr])
            assert expect == got, f"{description}. Expected: {expect}. Got: {got}"

        suite = TestUtil.ProverTestSuite(conf_file_template=_p('mutation_conf_top_level.conf'),
                                test_attribute=str(Util.TestValue.CHECK_ARGS))
        Path(Util.REMAPPINGS_FILE).unlink(missing_ok=True)
        Path(Util.PACKAGE_FILE).unlink(missing_ok=True)
        result = suite.expect_checkpoint(description=f"running with {Util.REMAPPINGS_FILE}")
        packages = getattr(result, 'packages', 'missing')
        assert packages != 'missing', "expected 'packages' to be in context with value None"
        assert not packages, f"expected 'packages' to be None. Got: {result.packages}"

        remapping = "a=lib\nb=lib"
        with open(Util.REMAPPINGS_FILE, "w") as file:
            file.write(remapping)
        description = f"running with {Util.REMAPPINGS_FILE}"
        result = suite.expect_checkpoint(description=description)
        check_run(['a', 'b'])

        remapping = '{"dependencies": {"c": "^3.4.1"},"devDependencies": {"d": "^5.0.8"}}'
        with open(Util.PACKAGE_FILE, "w") as file:
            file.write(remapping)
        description = f"running with {Util.REMAPPINGS_FILE} and {Util.PACKAGE_FILE}"
        result = suite.expect_checkpoint(description=description)
        check_run(['a', 'b', 'c', 'd'])

        description = "--packages - ignore files"
        result = suite.expect_checkpoint(description=description, run_flags=['--packages', 'd=lib'])
        check_run(['d'])

        # duplications
        remapping = "a=lib\na=lib"
        with open(Util.REMAPPINGS_FILE, "w") as file:
            file.write(remapping)
        description = f"duplicates in {Util.REMAPPINGS_FILE}"
        suite.expect_failure(description=description, expected="remappings.txt includes duplicated")
        Path(Util.REMAPPINGS_FILE).unlink(missing_ok=True)

        remapping = '{"dependencies": {"c": "^3.4.1"},"devDependencies": {"c": "^5.0.8"}}'
        with open(Util.PACKAGE_FILE, "w") as file:
            file.write(remapping)
        description = f"running with {Util.PACKAGE_FILE}"
        suite.expect_failure(description=description, expected="appear(s) multiple times in package.json")
        Path(Util.PACKAGE_FILE).unlink(missing_ok=True)

        remapping = "a=lib\nb=lib"
        with open(Util.REMAPPINGS_FILE, "w") as file:
            file.write(remapping)
        remapping = '{"dependencies": {"b": "^3.4.1"},"devDependencies": {"c": "^5.0.8"}}'
        with open(Util.PACKAGE_FILE, "w") as file:
            file.write(remapping)
        description = f"duplicates in {Util.REMAPPINGS_FILE} and Util.PACKAGE_FILE"
        suite.expect_failure(description=description, expected="package.json and remappings.txt include duplicated")
        Path(Util.REMAPPINGS_FILE).unlink(missing_ok=True)
        Path(Util.REMAPPINGS_FILE).unlink(missing_ok=True)

    def test_solc_args(self) -> None:

        base_command = TestUtil.CLIBuilder().append(f"--test {Util.TestValue.CHECK_SOLC_OPTIONS}")

        #
        #  Test --solc_optimize
        #

        command = copy.copy(base_command).append('--solc_optimize')

        try:
            run_certora(command.as_list())
            assert False, f"test_solc_args: No Test Result for {command}"
        except Util.TestResultsReady as e:
            try:
                if not json.loads(e.data['standard_json_input'])['settings']['optimizer']['enabled']:
                    self.fail("test_solc_args: failed for\n" + str(command) +
                              "\n['settings']['optimizer']['enabled'] not set to True")
            except KeyError:
                self.fail(f"test_solc_args: failed for '{command}'"
                          f"\nkey ['settings']['optimizer']['enabled'] is missing")
            except Exception as e:
                self.fail("test_solc_args: failed for\n" + str(command) + '\n' + str(e))
        #
        #  Test --solc_optimize 500
        #
        runs = 500
        command = copy.copy(base_command).append(f"--solc_optimize {str(runs)}")

        try:
            run_certora(command.as_list())
            assert False, f"test_solc_args: No Test Result for {command}"
        except Util.TestResultsReady as e:
            try:
                jobject = e.data['standard_json_input']
                if not json.loads(jobject)['settings']['optimizer']['enabled']:
                    self.fail("test_solc_args: failed for\n" + str(command) +
                              "\n['settings']['optimizer']['enabled'] not set to True")
                if json.loads(jobject)['settings']['optimizer']['runs'] != runs:
                    self.fail("test_solc_args: failed for\n" + str(command) + "\nruns mismatch: " +
                              f"expected: {runs} got: {json.loads(jobject)['settings']['optimizer']['runs']}")
            except KeyError:
                self.fail("test_solc_args: failed for:\n" + str(command) +
                          "\ndid not find ['settings']['optimizer']['enabled'] or "
                          "['settings']['optimizer']['runs']")

        #
        #  Test --solc_optimize_map
        #
        #       execution stops just before the solc compiler is actually called, we only test the first mapping
        map_value = f'{CITests_path}/test_data/A.sol=15,B=15,{CITests_path}/test_data/C.sol=0'
        command = copy.copy(base_command).append(f"--solc_optimize_map {map_value}")

        try:
            run_certora(command.as_list())
            assert False, f"test_solc_args: No Test Result for {command}"
        except Util.TestResultsReady as e:
            try:
                enabled_value = json.loads(e.data['standard_json_input'])['settings']['optimizer']
                expected_value = {'enabled': True, 'runs': 15}
                if enabled_value != expected_value:
                    self.fail("test_solc_args: failed for\n" + str(command) + "\noptimizer is not set\n" +
                              f"expected {expected_value}, got {enabled_value}")
            except KeyError:
                self.fail("test_solc_args: failed for" + str(command) +
                          "key ['settings']['optimizer'] is missing")
            except Exception as e:
                self.fail("test_solc_args: failed for\n" + str(command) + '\n' + str(e))

        #
        #  Test --solc_via_ir
        #

        def test_via_ir(command: TestUtil.CLIBuilder) -> None:
            try:
                run_certora(command.as_list())
                assert False, f"test_solc_args: No Test Result for {command}"
            except Util.TestResultsReady as e:
                try:
                    enabled_value = json.loads(e.data['standard_json_input'])['settings']['viaIR']
                    if not enabled_value:
                        self.fail("test_solc_args: failed for {command}\nviaIR not set to True")
                except KeyError:
                    self.fail("test_solc_args: failed for\n" + str(command) +
                              "\nkey ['settings']['viaIR'] is missing")
                except Exception as e:
                    self.fail("test_solc_args: failed for\n" + str(command) + Util.NEW_LINE + str(e))

        test_via_ir(copy.copy(base_command).append('--solc_via_ir'))
        test_via_ir(copy.copy(base_command).append('--solc_experimental_via_ir'))

        #
        #  Test --solc_evm_version
        #
        version = 'berlin'
        command = copy.copy(base_command).append(f"--solc_evm_version {version}")

        try:
            run_certora(command.as_list())
            assert False, f"test_solc_args: No Test Result for {command}"
        except Util.TestResultsReady as e:
            try:
                if json.loads(e.data['standard_json_input'])['settings']['evmVersion'] != version:
                    self.fail(f"test_solc_args: failed for {command}{Util.NEW_LINE} expected version: {version}"
                              f" got: {json.loads(e.data['standard_json_input'])['settings']['evmVersion']}")
            except KeyError:
                self.fail("test_solc_args: failed for" + str(command) +
                          "\nkey ['settings']['evmVersion'] is missing")
            except Exception as e:
                self.fail("test_solc_args: failed for\n" + str(command) + '\n' + str(e))

        #
        #  Test --solc_allow_path
        #
        dir_path = _p('dir1')
        command = copy.copy(base_command).append(f"--solc_allow_path {dir_path}")

        try:
            run_certora(command.as_list())
            assert False, f"test_solc_args: No Test Result for {command}"
        except Util.TestResultsReady as e:
            try:
                if e.data['main_path'] != dir_path:
                    self.fail("test_solc_args: failed for\n" + str(command) + "\nsolc_allow_path mismatch: " +
                              f"expected: {dir_path} got: {e.data['main_path']}")
            except Exception as e:
                self.fail("test_solc_args: failed for\n" + str(command) + Util.NEW_LINE + str(e))

        #
        #  Test --solc_maps
        #
        #   testing that the right compiler is called based on --solc_map value

        context = CertoraContext()
        context.solc = None
        files = [f'{CITests_path}/test_data/A.sol',
                 f'{CITests_path}/test_data/B.sol',
                 f'{CITests_path}/test_data/C.sol',
                 f'{CITests_path}/test_data/dir1/A.sol']
        context.compiler_map = OrderedDict([
            (f'{CITests_path}/test_data/A.sol', 'solc5.11'),
            (f'{CITests_path}/test_data/B.sol', 'solc5.9'),
            (f'{CITests_path}/test_data/dir1/*.sol', 'solc7.0'),
            ('**/*.sol', 'solc6.8')
        ])

        expected_results = ['solc5.11', 'solc5.9', 'solc6.8', 'solc7.0']

        for ind, f in enumerate(files):
            res = get_relevant_compiler(Path(f), context)
            if res != expected_results[ind]:
                self.fail(f"test_solc_args: mapping failed for {f}: expected: {expected_results[ind]} got: {res}")
        #
        #  Test map attribtues
        #
        #   testing that the right map value is set in the solc command json object

        suite = TestUtil.ProverTestSuite(conf_file_template=f'{TestEVM_path}/MultiContract/sameFile/SolcArgs/Default_for_ci.conf',
                                test_attribute=str(Util.TestValue.CHECK_SOLC_OPTIONS))

        expected = {
            'A.sol': [
                '"viaIR": true',
                '"evmVersion": "istanbul"',
                '"optimizer": {"enabled": true, "runs": 15'
            ],
            'C.sol': [
                '"evmVersion": "london"',
                '"optimizer": {"enabled": true, "runs": 14'
            ]
        }

        for file in list(expected.keys()):
            cond = f"build_arg_contract_file.endswith('{file}')"
            json_obj = suite.expect_checkpoint(description='check solc obj', run_flags=['--test_condition', cond])
            obj_as_str = json_obj['standard_json_input'].decode('utf-8')
            for s in expected[file]:
                assert s in obj_as_str, f"test_solc_args: expecting to find {s} in {obj_as_str}"

    def test_internal_function(self) -> None:
        context = CertoraContext()
        context.server = "staging"
        assert Ctx.is_staging(context), "is_staging for 'staging'"
        assert Ctx.is_supported_server(context), "is_supported_server for 'staging'"
        context.server = "production"
        assert not Ctx.is_staging(context), "is_staging for 'production'"
        assert Ctx.is_supported_server(context), "is_supported_server for 'production'"
        context.server = "any_server"
        assert not Ctx.is_staging(context), "is_staging for 'any_server'"
        assert not Ctx.is_supported_server(context), "is_supported_server for 'any_server'"

    def test_metadata(self) -> None:
        self.__test_main_spec([_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}"], _p('spec1.spec'))
        self.__test_main_spec(['--bytecode_jsons', _p('erc20.json'), '--bytecode_spec',
                               _p('erc20.spec')], _p('erc20.spec'))

    def __test_main_spec(self, args: List[str], expected_main_spec: Optional[str]) -> None:
        """
        Tests that the main_spec field in the metadtata file is as expected.
        """
        # The Metadtata file isn't created for local runs, so we must provide a server. The run is never actually sent
        args = args + ['--server', 'production']
        args = TestUtil.add_test_flag(args, Util.TestValue.CHECK_METADATA)
        try:
            run_certora(args)
            assert False, f"__test_main_spec: No Test Result for {args}"
        except Util.TestResultsReady as e:
            assert e.data.main_spec == expected_main_spec, \
                f"main_spec in metadata file is {e.data.main_spec}, expected {expected_main_spec}"

    def test_split_rules(self) -> None:
        rules = {'always_false', 'always_true', 'tautology'}
        suite = TestUtil.ProverTestSuite(conf_file_template=f'{TestEVM_path}/RulePickingTest/Default_for_ci.conf',
                                test_attribute=str(Util.TestValue.AFTER_RULE_SPLIT))
        commands = suite.expect_checkpoint(description='split to 3 runs', run_flags=['--split_rules', 'always*'])
        commands_rule = set()
        group_ids = set()

        for command in commands:
            commands_rule.add(command[command.index('--rule') + 1])
            group_ids.add(command[command.index('--group_id') + 1])
        assert commands_rule == rules, "test_split_rules: expecting each rule in a separate run"
        assert len(group_ids) == 1, f"test_split_rules: expecting length of group ids to be 1 got {len(group_ids)}"
        assert Vf.validate_uuid(group_ids.pop()), "test_split_rules: not a valid uuid"
        commands = suite.expect_checkpoint(description='split to 3 runs', run_flags=['--split_rules', '*true'])
        assert commands[0][commands[0].index('--rule') + 1] == 'always_true', "test_split_rules: bad rule for first run"
        index = commands[1].index('--rule')
        success = {commands[1][index + 1], commands[1][index + 2]} == {'always_false', 'tautology'}
        assert success, "test_split_rules: bad rule for second run"
        suite.expect_failure(description="filter all rules ",
                             run_flags=['--split_rules', '*true', '--exclude_rule', '*'],
                             expected="Failed to get rules")

    def test_storage_extension_harnesses(self) -> None:
        suite = TestUtil.ProverTestSuite(conf_file_template=f'{CITests_path}/test_data/storage_extensions/CI.conf',
                                test_attribute=str(Util.TestValue.AFTER_BUILD))

        suite.expect_failure(description="Overwrite an existing target slot",
                             run_flags=['--storage_extension_harnesses', 'Test=Overwrite'],
                             expected="Slot 0 added to Test by Overwrite is already mapped by Test")

        suite.expect_failure(description="Overwrite an existing extension slot",
                             run_flags=['--storage_extension_harnesses', 'Test=Spec1', 'Test=Spec'],
                             expected="Slot 9619688881439974453 added to Test")

        suite.expect_failure(description="Overwrite an existing target field name",
                             run_flags=['--storage_extension_harnesses', 'Test=OverwriteVar'],
                             expected="Var 'existing' added to Test by OverwriteVar is already declared by Test")

        suite.expect_failure(description="Overwrite an existing extension field name",
                             run_flags=['--storage_extension_harnesses', 'Test=Spec1', 'Test=Spec2'],
                             expected="Var 'b1' added to Test")

    def test_automatic_storage_extension_harnesses(self) -> None:
        # Test the no storage extension flow (no extracted storage extension from the contract)
        with Util.change_working_directory('Public/TestEVM/Counter'):
            suite = TestUtil.ProverTestSuite(conf_file_template='counter.conf',
                                    test_attribute=str(Util.TestValue.STORAGE_EXTENSION_LAYOUT))
            suite.expect_success(description="ERC7201 storage extension harnesses no flow",
                                 run_flags=['--disable_local_typechecking', '--storage_extension_annotation'])
            result = suite.expect_checkpoint(description="Expecting empty mapping",
                                             run_flags=['--disable_local_typechecking', "--storage_extension_annotation"])
            assert result == {}, "test_automatic_storage_extension_harnesses: expected empty mapping"

        # Test the normal flow (extracted storage extension from the contract)
        with Util.change_working_directory('Public/TestEVM/StorageExtensions'):
            suite = TestUtil.ProverTestSuite(conf_file_template='ERC7201.conf',
                                    test_attribute=str(Util.TestValue.STORAGE_EXTENSION_LAYOUT))
            suite.expect_success(description="ERC7201 storage extension harnesses normal flow",
                                 run_flags=['--disable_local_typechecking', '--storage_extension_annotation'])
            result = suite.expect_checkpoint(description="test_automatic_storage_extension_harnesses:  mapping",
                                             run_flags=['--disable_local_typechecking', '--storage_extension_annotation'])
            assert len(result) == 1, "test_automatic_storage_extension_harnesses: expected only one mapping"
            assert list(result.keys())[0][1] == 'Test_ERC7201'

        # Test 2 structs but only one storage extension
        suite = TestUtil.ProverTestSuite(conf_file_template=f'{CITests_path}/test_data/automatic_storage_extensions/single_struct/single_struct.conf',
                                test_attribute=str(Util.TestValue.STORAGE_EXTENSION_LAYOUT))
        suite.expect_success(description="Single struct with storage extension out of 2",
                            run_flags=['--disable_local_typechecking', '--storage_extension_annotation'])
        result = suite.expect_checkpoint(description="Expecting mapping",
                                         run_flags=['--disable_local_typechecking', '--storage_extension_annotation'])
        added_fields = list(result.values())[0][0]
        assert len(added_fields) == 1, "test_automatic_storage_extension_harnesses: expected only one added field"
        assert added_fields[0]["label"] == "ext_test_book1"

        # Test 2 structs with the same storage extension variable
        suite = TestUtil.ProverTestSuite(conf_file_template=f'{CITests_path}/test_data/automatic_storage_extensions/same_extension/same_extension.conf',
                                test_attribute=str(Util.TestValue.STORAGE_EXTENSION_LAYOUT))
        suite.expect_failure(description="Same extension for 2 structs",
                             run_flags=['--disable_local_typechecking', '--storage_extension_annotation'],
                             expected="DeclarationError: Identifier already declared.")

    @staticmethod
    def get_card_object(parent, nested):
        return next((section for section in parent if section.card_title == nested), None)

    @staticmethod
    def get_inner_object(parent, nested):
        return next((section for section in parent if section.inner_title == nested), None)

    def test_solidity_configuration_layout_data(self) -> None:
        """
        Tests that the configuration_layout is as expected.
        """
        args = [_p('A.sol'), '--solc', 'solc8.28', '--verify', f"A:{_p('spec1.spec')}", '--server', 'production']
        args = TestUtil.add_test_flag(args, Util.TestValue.CHECK_CONFIG_LAYOUT)
        try:
            run_certora(args)
            raise AssertionError(f"__test_main_spec: No Test Result for {args}")
        except Util.TestResultsReady as e:
            layout = e.data.configuration_layout

            # Validating files section in RunConfigurationLayout
            files = self.get_card_object(layout, 'files')
            assert files, f"Error! files section is expected to exist in configuration layout data!\n{layout}"
            assert _p('A.sol') in files.content[0].content, \
                f"Error! files in configuration layout is {files.content[0].content}, expected {_p('A.sol')}"
            assert 'prover/cli' in files.content[0].doc_link and 'files' in files.content[0].doc_link, \
                f"Error! doc_link in configuration layout is {files.content[0].doc_link}\n" \
                f"expected 'prover/cli' and '#files' in link!"

            # Validating general section exists in RunConfigurationLayout
            # Note: we do not have Git or CLI version info on CI
            assert self.get_card_object(layout, 'general'), \
                   f"Error! General section is expected to exist in configuration layout data!\n{layout}"

            # Validating options section
            options_data = self.get_card_object(layout, 'options')
            assert options_data, \
                f"Error! Options section is expected to exist in configuration layout data!\n{layout}"

            verify_data = self.get_inner_object(options_data.content, 'verify')
            assert verify_data and f"A:{_p('spec1.spec')}" == verify_data.content, \
                f"Error! verify flag in options section is incorrect!\n" \
                f"expected: 'A:{_p('spec1.spec')}', actual: '{verify_data}'"

            server_data = self.get_inner_object(options_data.content, 'server')
            assert server_data and "production" == server_data.content, \
                f"Error! server flag in general section is incorrect!\n" \
                f"expected: 'production', actual: '{server_data.content}'"

            # Validating solidity compiler section in RunConfigurationLayout
            solidity_compiler_data = self.get_card_object(layout, 'solidity_compiler')
            assert solidity_compiler_data, \
                f"Error! Solidity compiler section is expected to exist in configuration layout data!\n{layout}"
            solc_data = self.get_inner_object(solidity_compiler_data.content, 'solc')
            assert solc_data and "solc8.28" == solc_data.content and "FLAG" == solc_data.content_type, \
                f"Error! solc flag in solidity_compiler section is incorrect!\n" \
                f"expected: 'solc8.28' of content_type 'FLAG', actual: '{solc_data}'"


    def test_override_base_config(self) -> None:

        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))

        # creating 2 conf files: a base and a child
        base_data = {
            "files": ["Test/CITests/test_data/A.sol"],
            "solc": "solc5.11"
        }

        child_data = {
            "verify": "A:Test/CITests/test_data/spec1.spec",
            "override_base_config": "base.conf"
        }

        with open("base.conf", "w") as f: json.dump(base_data, f, indent=4)
        with open("child.conf", "w") as f: json.dump(child_data, f, indent=4)

        # Normal case solc is defined in base
        result = suite.expect_checkpoint(description="override base: simple", run_flags=['child.conf'])
        assert result.solc == "solc5.11", f"test_override_base_config: expecting solc5.11, got {result.solc}"

        # base conf has bad attribute
        base_data_bad = {
            "solc": "solc5.11",
            "no_such_key": "base.conf"
        }
        with open("base.conf", "w") as f: json.dump(base_data_bad, f, indent=4)
        suite.expect_failure(description="override base: override_base_config in base", run_flags=['child.conf'],
                             expected="is not a known attribute")
        with open("base.conf", "w") as f: json.dump(base_data, f, indent=4)  # restore to valid

        # base conf cannot have override_base_config
        base_data_cycle = {
            "solc": "solc5.11",
            "override_base_config": "base.conf"
        }
        with open("base.conf", "w") as f: json.dump(base_data_cycle, f, indent=4)
        suite.expect_failure(description="override base: override_base_config in base", run_flags=['child.conf'],
                             expected="base config cannot include 'override_base_config'")
        with open("base.conf", "w") as f: json.dump(base_data, f, indent=4)  # restore to valid


        # solc is defined in base and in child - value in child shadows base
        child_data['solc'] = "solc6.12"
        with open("child.conf", "w") as f: json.dump(child_data, f, indent=4)
        result = suite.expect_checkpoint(description="override base: child override", run_flags=['child.conf'])
        assert result.solc == "solc6.12", f"test_override_base_config: expecting solc6.12, got {result.solc}"

        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.AFTER_BUILD))  # build to get source tree
        # solc is defined also in CLI - - value in CLI shadows confs
        result = suite.expect_checkpoint(description="override base: CLI override",
                                         run_flags=['child.conf', '--solc', 'solc5.11'])
        assert result.solc == "solc5.11", f"test_override_base_config: expecting solc5.11, got {result.solc}"
        # verifing both base and child are in the source tree
        root = Path(".certora_internal/latest/.certora_sources")
        assert (root / "base.conf").exists(), "test_override_base_config: base conf does not exist"
        assert (root / "child.conf").exists(), "test_override_base_config: child conf does not exist"

        # making sure 'override_base_config' not in run.conf and 'solc' got the CLI value
        with open(Util.get_last_conf_file(), "r") as f:
            last_run_context = json.load(f)
            assert 'override_base_config' not in last_run_context, "'override_base_config' should not be in run.conf"
            assert last_run_context['solc'] == 'solc5.11', (f"run.conf should get the value from CLI (solc5.11)"
                                                            f", got {last_run_context['solc']}")

        # base conf does not exist
        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))
        child_data['override_base_config'] = "does_not_exist.conf"
        with open("child.conf", "w") as f: json.dump(child_data, f, indent=4)
        suite.expect_failure(description="override base: base does not exist", run_flags=['child.conf'],
                             expected="Error when reading child.conf: Cannot load base config: does_not_exist.conf")

        # base conf is not a valid JSON
        with open("base_bad.conf", "w") as f: f.write("Not JSON")
        child_data['override_base_config'] = "base_bad.conf"
        with open("child.conf", "w") as f: json.dump(child_data, f, indent=4)
        suite.expect_failure(description="override base: base does not exist", run_flags=['child.conf'],
                             expected="Error when reading child.conf: Cannot load base config: base_bad.conf")

    def test_concord(self) -> None:
        suite = TestUtil.ConcordTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))
        suite.expect_success(description='run concord with 2 contracts and one check_method',
                             run_flags=[_p('A.sol:A'), _p('A.sol:B'), "--check_method", "m()"])
        suite.expect_failure(description='run concord with bad check_method',
                             run_flags=[_p('A.sol:A'), _p('A.sol:B'), "--check_method", "m("],
                             expected="attribute/flag 'check_method': Malformed method 'm(' in")
        suite.expect_failure(description='run concord with bad check_method',
                             run_flags=[_p('A.sol:A'), _p('A.sol:B'), "--check_method", "Contract.m()"],
                             expected="checked method cannot contain a dot")
        suite.expect_failure(description='run concord with no check_method',
                             run_flags=[_p('A.sol:A'), _p('A.sol:B')],
                             expected="'check_method' attribute/flag is mandatory when running Concord")
        suite.expect_failure(description='run concord with one contract',
                             run_flags=[_p('A.sol'), f"{_p('A.sol')}:A", "--check_method", "m()"],
                             expected="expecting 2 contracts for Concord, got 1")
        for attr in Attrs.ConcordAttributes.unsupported_attributes():

            if attr in [Attrs.ConcordAttributes.METHOD, Attrs.ConcordAttributes.VERIFY, Attrs.ConcordAttributes.FOUNDRY,
                        Attrs.ConcordAttributes.PROJECT_SANITY, Attrs.ConcordAttributes.CONTRACT_EXTENSIONS,
                        Attrs.ConcordAttributes.PARAMETRIC_CONTRACTS]:
                continue
            run_flags = [_p('A.sol'), "--check_method", "m()", attr.get_flag()]
            if attr.arg_type != AttrUtil.AttrArgType.BOOLEAN:
                run_flags.append(TestUtil.get_valid_value(attr))
            attr_name = attr.get_conf_key()
            suite.expect_failure(description=f'unsupported methods: {attr_name}',
                                 run_flags=run_flags,
                                 expected=f"{attr_name}")


    def test_ranger(self) -> None:
        suite = TestUtil.ProverTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))
        suite.expect_success(description='run certoraRun with ranger attributes',
                             run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}", "--range", "8",
                                        "--ranger_failure_limit", "7"])
        suite = TestUtil.RangerTestSuite(test_attribute=str(Util.TestValue.CHECK_ARGS))

        result = suite.expect_checkpoint(description='run certoraRun with ranger attributes',
                                         run_flags=[_p('A.sol'), '--verify', f"A:{_p('spec1.spec')}",
                                                    "--coverage_info",
                                                    "--independent_satisfy",
                                                    "--multi_assert_check",
                                                    "--multi_example"])
        assert result.loop_iter == Util.DEFAULT_RANGER_LOOP_ITER, ("test_ranger: expect default loop iter "
                                                                   f"{Util.DEFAULT_RANGER_LOOP_ITER} got {result.loop_iter}")
        assert result.range == Util.DEFAULT_RANGER_RANGE, ("test_ranger: expect default range "
                                                                   f"{Util.DEFAULT_RANGER_RANGE} got {result.range}")
        assert result.ranger_failure_limit == Util.DEFAULT_RANGER_FAILURE_LIMIT, ("test_ranger: expect default "
                                                                                  "ranger_failure_limit "
                                                                                  f"{Util.DEFAULT_RANGER_FAILURE_LIMIT}"
                                                                                  f" got {result.ranger_failure_limit}")
        for attr in Attrs.RangerAttributes.unsupported_attributes():
            attr_name = attr.get_conf_key()
            assert not getattr(result, attr_name), f"test_ranger: {attr_name} should not be in set during Ranger run"


if __name__ == '__main__':
    test_argv = [f"{sys.argv[1]}, {sys.argv[2]}"]
    runner = unittest.main(argv=test_argv, exit=False)
    if not runner.result.wasSuccessful():
        exit(1)
