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

from __future__ import annotations

import os
import sys
import random
import json5

from pathlib import Path
from typing import Dict, Callable, Any, List, Tuple, Type, Optional
import jinja2

scripts_dir_path = Path(__file__).parent.parent.resolve()  # one directory up
sys.path.insert(0, str(scripts_dir_path))

from Shared import certoraValidateFuncs as Vf
import CertoraProver.certoraContextAttributes as Attrs
from Shared import certoraAttrUtil as AttrUtil
from Shared import certoraUtils as Util
from Mutate import mutateConstants as MConstants
from certoraSolanaProver import run_solana_prover
from certoraSorobanProver import run_soroban_prover
from certoraRanger import run_ranger
from certoraRun import run_certora


class InvalidResultException(Exception):
    pass


ENUM_KEY = 'enum'
VALID_KEY = 'valid'
INVALID_KEY = 'invalid'
XXX = '// XXX'
YYY = '// YYY'


def replace_x(replacement: str) -> List[Tuple[str, str]]:
    return [(XXX, replacement)]


def replace_y(replacement: str) -> List[Tuple[str, str]]:
    return [(YYY, replacement)]


def replace_x_y(replacement_x: str, replacement_y: str) -> List[Tuple[str, str]]:
    return [(XXX, replacement_x), (YYY, replacement_y)]


def json_to_str(obj: dict) -> str:
    return json5.dumps(obj)[1:-1]


def path_test_file(filename: str) -> str:
    test_path: Path = Path(os.getenv("CERTORA_TEST_DATA_DIRECTORY")) / filename
    return os.path.normpath(test_path)


TEST_VALUES: Dict[Callable, Dict[str, Any]] = {
    Attrs.validate_prover_args: {
        'valid': ['-aa 1 -bb 2 -cc', 'vv'],
        'invalid': ['-b 8']
    },

    Vf.validate_address: {
        'valid': ['a:0xf99', 'a:99'],
        'invalid': ['a:fp99', 'a', ':', ':8', 'a:af']
    },

    Vf.validate_build_dir: {
        'valid': ['subdir']
    },

    Vf.validate_conf_file: {
        'valid': [path_test_file('tac_file.conf')],
        'invalid': [path_test_file('file.out'), path_test_file('empty.json')]
    },

    Vf.validate_dir: {
        'valid': ['.', path_test_file('lib')],
        'invalid': ['no_dir', path_test_file('file.out')]
    },

    Vf.validate_exec_file: {
        'valid': ['python3'],
        'invalid': ['python4']
    },

    Vf.file_exists_and_readable: {
        'valid': [
            path_test_file('A.sol')
        ],
        'invalid': [
            path_test_file('lib'),
            path_test_file('lib_does_not_exist')
        ]
    },

    Vf.validate_git_hash: {
        'valid': ['AA'],
        'invalid': ['G', '00000000000000000000000000000000000000000']
    },

    Vf.validate_evm_input_file: {
        'valid': [
            path_test_file('_simple$.sol'),
            path_test_file('_simple$.sol') + ':_Simple$',
            path_test_file('empty.tac'),
            path_test_file('tac_file.conf'),
            path_test_file('erc20.json'),
            path_test_file('empty.sol:Contract')
        ],
        'invalid': [
            'a.c',
            path_test_file('empty1.tac'),
            path_test_file('_simple$.sol') + ':_Simple$.sol',
            path_test_file('_simple$.sol') + '::_Simple$',
            path_test_file('empty1.sol')
        ]
    },

    Vf.validate_jar: {
        'valid': [path_test_file('empty.jar')],
        'invalid':
            [
                path_test_file('erc20.java'),
                path_test_file('erc2.jar')
            ]
    },

    Vf.validate_json_file: {
        'valid': [path_test_file('erc20.json')],
        'invalid':
            [
                path_test_file('fake.json'),
                path_test_file('erc2.jar'),
                path_test_file('json5valid.json'),
        ]
    },

    Vf.validate_json5_file: {
        'valid': [
            path_test_file('json5valid.json'),
            path_test_file('erc20.json')
        ],
        'invalid': [
            path_test_file('fake.json'),
            path_test_file('erc2.jar')
        ]
    },

    Vf.validate_orig_run: {
        'valid': [
            'https://prover.certora.com/output/69614/4d116df10c304f05a180c05df90decb3?anonymousKey=11b8a5f1eadfc7d1b7c8f2fe25462558e3db018d'
        ],
        'invalid': [
            'https://vaas-stg.certora.com/output7/20941/e11ca8e3864e4395928beb0c996213fc?anonymousKey=69a6bf3bf1',
            'https://vaas-dvv.certora.com/output/20941/e11ca8e3864e4395928beb0c996213fc?anonymousKey=69a6bf3bf1',
            'https://vaas-stg.certora.com'

        ]
    },

    Vf.validate_writable_path: {
        'valid': [
            path_test_file('temp_new_dir')
        ],
        'invalid': [
            path_test_file('.'),
            path_test_file('lib')
        ]
    },

    Vf.validate_link_attr: {
        'valid': [
            '_Simple$:1=_simple$',
            '_Simple$:1=$2'
        ],
        'invalid': [
            '_Simple$:1==_simple$',
            '_Simple$=1=_simple$',
            'a=a:1',
            'a',
            '='
        ]
    },

    Vf.validate_multi_example_value: {
        'enum': Vf.MultiExampleValue
    },

    Vf.validate_non_negative_integer: {
        'valid': ['4', '0'],
        'invalid': ['-1', 'a']
    },

    Vf.validate_non_negative_integer_or_minus_1: {
        'valid': ['4', '0', '-1'],
        'invalid': ['-2', 'a']
    },

    Vf.validate_optional_readable_file: {
        'valid': [
            path_test_file('_simple$.sol')
        ],
        'invalid': [
            os.getenv("CERTORA_TEST_DATA_DIRECTORY"),
            'test_data'
        ]
    },

    Vf.validate_packages: {
        'valid': [
            f"ds-test={path_test_file('lib/ds-test')}",
            'ds-test=.'
        ]
    },

    Vf.validate_positive_integer: {
        'valid': ['4', 4],
        'invalid': ['-1', '0', 'a', 0, -1, '0.4', 0.4]
    },

    Vf.validate_prototype_attr: {
        'valid': ['00=AA', '0F=_Simple'],
        'invalid': ['0=A=0', '0G=AA']
    },

    Vf.validate_resource_files: {
        'valid': [f"label:{path_test_file('_simple$.sol')}"],
        'invalid': ['a', f"label={path_test_file('_simple$.sol')}"]
    },

    Vf.validate_run_source: {
        'enum': Vf.RunSources
    },

    Vf.validate_sanity_value: {
        'enum': Vf.RuleSanityValue
    },

    Vf.validate_coverage_info: {
        'enum': Vf.CoverageInfoValue
    },

    Vf.validate_server_value: {
        'valid': ['server', '1', '-', '_'], 'invalid': ['$', 'a!']
    },

    Vf.validate_uuid: {
        'valid': [
            '3c4e5c1abfa24659b76bde5fd7b4e300',
            '3C4E5C1ABFA24659B76BDE5FD7B4E300',
            '3C4E5C1A-BFA2-4659-B76B-DE5FD7B4E300'
        ],
        'invalid': [
            '3c4e5c1abfa24659b76bde5fd7b4e3',
            'XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX'
        ]
    },

    Vf.validate_prover_version: {
        'valid': ['legal', 'legal/1', 'legal-/9', 'a_b'], 'invalid': ['$', 'a!', 'bad.bad', 'a/b//c', '9/a', 'a\\b']
    },

    Vf.validate_job_definition: {
        'valid': ['job', '1', '_', 'job1_2'], 'invalid': ['$', 'a!', 'job-1']
    },

    Vf.validate_compiler_map: {
        'valid': [
            {'A.sol': 'solc6.1', 'B': 'solc6.1', 'C': 'solc5.12'}
        ],
        'invalid': [
            {'A.sol': 'solc68.1'}
        ]
    },

    Vf.validate_solc_optimize_map: {
        'valid': [
            {'A': '15', 'B': '15', 'C.sol': '0'}
        ],
        'invalid': [
            {'A': '-1'},
            {'A': 'aa'}
        ]
    },

    Vf.validate_solc_evm_version_map: {
        'valid': [
            {'A': 'cancun', 'B': 'berlin', 'C.sol': 'london'}
        ],
        'invalid': [
            {'A': 19},
            {'B': {'aa'}}
        ]
    },

    Vf.validate_solc_via_ir_map: {
        'valid': [
            {'A': True, 'B': False, 'C.sol': False}
        ],
        'invalid': [
            {'A': 19},
            {'B': {'aa'}},
            {'A': True, 'B': 19, 'C.sol': False}
        ]
    },

    Vf.validate_spec_file: {
        'valid': [
            path_test_file('erc20.spec'), path_test_file('erc20.cvl')],
        'invalid': [
            path_test_file('erc20.sol')
        ]
    },

    Vf.validate_struct_link: {
        'valid': ['A:A=C1$1', 'A:0=C'],
        'invalid': ['A:-1=C', 'A:0.1=C', 'A=C',
                    'C=A:1', 'A:3', 'A=3', 'A:=3',
                    ':=C', ':=', ':', '=', '', ' '
                    ]
    },

    Vf.validate_test_value: {
        'enum': Util.TestValue
    },

    Vf.validate_tool_output_path: {
        'valid': [
            path_test_file('file.out')
        ],
        'invalid': ['.']
    },

    Vf.validate_verify_attr: {
        'valid': [f"_Simple$:{path_test_file('_simple$.spec')}"],
        'invalid': [
            'a', 'a.spec', '', ' ',
            f"Bank.spec:{path_test_file('_simple$.spec')}",
            f"a:{path_test_file('_simple$.spec:c')}",
            path_test_file('_simple$.spec:Band'),
            'New/York:dc.spec'
        ]
    },

    Vf.validate_rule_name: {
        'valid': ['a', '_a123', '_'],
        'invalid': ['1', 'rule1 rule2', 'rule1,rule2']
    },

    Vf.validate_contract_name: {
        # first valid should be '_Simple$' otherwise test_jar_flag will fail
        'valid': ['_Simple$', '_a123', '_', 'contract2', '$contract_'],
        'invalid': ['1', 'contract 1 ', 'contract1,contract2']
    },

    Vf.validate_cloud_global_timeout: {
        'invalid': ['1', 'str', 3, True, '']
    },

    Vf.validate_solc_args: {
        'invalid': ['1', 'str', 3, True, '']
    },


    Vf.validate_msg: {
        'valid': ['a', '2', '=', ',', ' ', "'", ':', '.', '\\', '-', '/', '"', '_', '[', ']', '(', ')', 300 * 's'],
        'invalid': ['@', '$', 'a!', 'a“']
    },

    Vf.validate_fe_value: {
        'enum': Util.FeValue
    },

    Vf.validate_false: {
        'enum': Vf.FalseValue
    },

    Vf.validate_on_off: {
        'enum': Vf.OnOffValue
    },

    Vf.validate_wait_for_results: {
        'enum': Vf.WaitForResultOptions
    },

    Vf.validate_url_visibility: {
        'enum': Vf.UrlVisibilityOptions
    },

    Vf.validate_function_finder_mode: {
        'enum': Vf.FunctionFinderMode
    },

    Vf.validate_readable_file: {
        'valid': [path_test_file('_simple$.sol')],
        'invalid': [path_test_file('not_so_simple$.sol')]
    },

    Vf.validate_manual_mutants: {
        'valid': [
            [
                {
                    MConstants.FILE_TO_MUTATE: "C.sol",
                    MConstants.MUTANTS_LOCATION: "mulSolc8ManualMutations"
                }
            ]
        ],
        'invalid': [
            [
                {
                    MConstants.MUTANTS_LOCATION: "mulSolc8ManualMutations"
                }
            ]
        ]
    },

    Vf.validate_universal_mutator_mutants: {
        'valid': [
            [
                {
                    MConstants.FILE_TO_MUTATE: "C.sol",
                    MConstants.MUTANTS_LOCATION: "mulSolc8ManualMutations"
                }
            ]
        ],
        'invalid': [
            [
                {
                    MConstants.MUTANTS_LOCATION: "mulSolc8ManualMutations"
                }
            ]
        ]
    },

    Vf.validate_contract_extension_attr: {
        'valid': [
            {
                "A": [
                    {
                        "extension": "B",
                        "exclude": ["foo", "bar"]
                    }
                ]
            }
        ],
        'invalid': [
            # list instead of dict
            [{
                "A": [
                    {
                        "extension": "B",
                        "exclude": ["foo", "bar"]
                    }
                ]
            }],
            # extensions is a dict instead of a list
            {
                "A":
                    {
                        "extension": "B",
                        "exclude": ["foo", "bar"]
                    }
            },
            # extensions is a list of lists instead of a list of dicts
            {
                "A": [
                    [{
                        "extension": "B",
                        "exclude": ["foo", "bar"]
                    }]
                ]
            },
            # missing "extension"
            {
                "A": [
                    {
                        "exclude": ["foo", "bar"]
                    }
                ]
            },
            # missing "exclude"
            {
                "A": [
                    {
                        "extension": "B",
                    }
                ]
            },
            # exclude is not a list
            {
                "A": [
                    {
                        "extension": "B",
                        "exclude": "foo"
                    }
                ]
            },
            # extra keys in extension dict
            {
                "A": [
                    {
                        "extension": "B",
                        "exclude": ["foo", "bar"],
                        "extra": "wow"
                    }
                ]
            }
        ]
    },

    Vf.validate_method_flag: {
        'valid': [
            "transfer(address,uint256)",
            "_Simple$.transfer(address,uint256)",
            "_.transfer(address,uint256)",
            "A.add(uint256,uint256)"
        ],
        'invalid': [
            "transfer(address,uint256))",
            "_Simple$.transfer((address,uint256)",
            "_.transfer(address,uint256[5][)",
            "Too.Many.Dots.foo()",
            "Inv@lidContract.bar()",
            "noWhitespace.foo(uint, bool)"
        ]
    },

    Vf.validate_solana_extension: {
        'valid': [
            "file_not_exist.so"
        ],
        'invalid': [
            "file_not_exist.sol",
            "empty.o"
        ]
    },

    Vf.validate_soroban_extension: {
        'valid': [
            "file_not_exist.wasm"
        ],
        'invalid': [
            "file_not_exist.sol",
            "empty.o"
        ]
    },

    Vf.validate_storage_extension_harness_attr: {
        'valid': ['A=B'],
        'invalid': ['A']
    },

    Vf.validate_equivalence_contracts: {
        'valid': ["A=B"],
        "invalid": ["A", "A=3", "3;5"]
    }
}


def get_valid_value(attr: AttrUtil.AttributeDefinition) -> str:

    if attr.attr_validation_func in TEST_VALUES:
        tested_object = TEST_VALUES[attr.attr_validation_func]
        if ENUM_KEY in tested_object:
            return tested_object[ENUM_KEY].values()[0]
        if VALID_KEY in tested_object:
            return tested_object[VALID_KEY][0]
        else:
            raise Util.ImplementationError(f"No valid value for {attr.name}")
    elif attr == Attrs.EvmAttributes.METHOD:
        return 'withdraw()'
    else:  # no validation function was defined, default type is used
        if attr.arg_type == AttrUtil.AttrArgType.STRING \
           or attr.arg_type == AttrUtil.AttrArgType.LIST:
            return 'val'
        elif attr.arg_type == AttrUtil.AttrArgType.BOOLEAN:
            return ''
        elif attr.arg_type == AttrUtil.AttrArgType.INT:
            return '5'
        else:
            raise Util.ImplementationError(f"Unknown type {attr.arg_type}")


def add_test_flag(args: List[str], test_value: Util.TestValue) -> List[str]:
    return args + ['--test', str(test_value)]


class CLIBuilder:
    base_command = f'{path_test_file("A.sol")} {path_test_file("B.sol")} {path_test_file("C.sol")} ' \
                   f'--verify A:{path_test_file("spec1.spec")} --solc_map A=solc6.8,B=solc5.9,C=solc6.8'

    def __init__(self, initial_string: str = base_command):
        self.string = initial_string

    def append(self, text: str) -> CLIBuilder:
        self.string += f" {text}"
        return self

    def as_list(self) -> List[str]:
        return self.string.split()

    def __str__(self) -> str:
        return str(self.string)


def invalid_value(function: Callable[..., Any], *parameters: Any) -> None:
    """
    This function gets a function pointer and a list of parameters. The function guarantees that when calling the
    function with the parameters an exception will be thrown indicating bad input. Note that if no
    exception was thrown or if the only exception thrown was TestResultsReady (which is not an indication of bad
    input) the test will fail on the assert
    """
    msg = f"calling {function.__name__} on {parameters} did not raise Exception"
    try:
        print(Util.orange_text(msg))
        function(*parameters)
    except Util.TestResultsReady:
        assert False, msg
    except Exception:
        return
    assert False, msg


def valid_value(function: Callable[..., Any], *parameters: Any) -> None:
    """
    This function gets a function pointer and a list of parameters. The function guarantees that when calling the
    function with the parameters an exception will *not* be thrown indicating the input is valid. Note if exception
    (which is not TestResultsReady) was thrown the test will failed on assert
    """
    try:
        print(Util.orange_text(f"calling {function.__name__} with {parameters}"))
        function(*parameters)
    except Util.TestResultsReady:
        pass
    except Exception as e:
        assert False, f"Valid value calling {function.__name__} with {parameters} raised Exception\n{e}"


def validate_enum(attr: AttrUtil.AttributeDefinition, enum_class: Type[Util.NoValEnum]) -> None:
    for value in enum_class.values():
        valid_value(attr.attr_validation_func, value)
    invalid_value(attr.attr_validation_func, 'not_legal_value')


def validate_valid_values(attr: AttrUtil.AttributeDefinition, values: List[str]) -> None:
    for value in values:
        valid_value(attr.attr_validation_func, value)


def validate_invalid_values(attr: AttrUtil.AttributeDefinition, values: List[str]) -> None:
    for value in values:
        invalid_value(attr.attr_validation_func, value)


class TestSuite:
    def __init__(self, run_func: Callable, conf_file_template: str = '',
                 build_script_template: Optional[Path] = None,
                 test_attribute: Optional[Util.TestValue] = None,
                 common_flags: List[str] = []):
        self.conf_file_template = conf_file_template  # path to the template conf file
        self.build_script_template = build_script_template  # path to the template build script
        self.test_attribute = test_attribute  # value for --test in case we want to stop before completion
        self.common_flags = common_flags  # flags common to all runs in the suite
        self.run_func = run_func
        self.supported_kwargs = ['description', 'expected', 'test_attribute', 'replacements', 'run_flags', 'build_script_context']

    def check_params(self, **kwargs: Any) -> None:
        for kwarg in kwargs:
            if kwarg not in self.supported_kwargs:
                raise Util.ImplementationError(f"unknown keyword: {kwarg}")
        if 'description' not in kwargs:
            raise Util.ImplementationError(f"test missing description: {kwargs}")

    def expect_failure(self, **kwargs: Any) -> None:
        self.check_params(**kwargs)

        if 'expected' not in kwargs:
            raise Util.ImplementationError(f"test missing expected: {kwargs}")

        cmd_args = self.get_command_args(**kwargs)
        try:
            self.run_func(cmd_args)
            raise InvalidResultException(f"succeeded, expected failure: {cmd_args}{Util.NEW_LINE}description: "
                                         f"{kwargs['description']}")
        except Util.TestResultsReady:
            raise InvalidResultException(f"Expecting failure before checkpoint{cmd_args}{Util.NEW_LINE}"
                                         f"when running: {cmd_args}{Util.NEW_LINE} "
                                         f"description: {kwargs['description']}") from None

        except Exception as e:
            if kwargs['expected'] not in str(e):
                raise InvalidResultException(f"when running: {cmd_args}{Util.NEW_LINE} "
                                             f"description: {kwargs['description']}"
                                             f"{Util.NEW_LINE}Expecting: {Util.NEW_LINE}\"{kwargs['expected']}\""
                                             f"{Util.NEW_LINE}Got: {Util.NEW_LINE}\"{e}\"") from None

    def expect_success(self, **kwargs: Any) -> None:
        self.check_params(**kwargs)

        cmd_args = self.get_command_args(**kwargs)
        try:
            self.run_func(cmd_args)
        except Util.TestResultsReady:
            assert True
        except Exception as e:
            raise InvalidResultException(f"failed, expected success: {cmd_args}{Util.NEW_LINE}description: "
                                         f"{kwargs['description']} "
                                         f"{Util.NEW_LINE}Exception got:{e}") from None

    def expect_checkpoint(self, **kwargs: Any) -> Any:
        self.check_params(**kwargs)

        cmd_args = self.get_command_args(**kwargs)
        test_attribute = cmd_args[cmd_args.index("--test") + 1]  # get the checkpoint of the test
        if '--test' not in cmd_args:
            raise Util.ImplementationError(f"test missing test_attribute: {kwargs}")
        try:
            self.run_func(cmd_args)
            raise AssertionError(f"Terminating before reaching checkpoint '{test_attribute}"
                                 f"{Util.NEW_LINE}Description: {kwargs['description']}")

        except Util.TestResultsReady as e:
            return e.data
        except Exception as e:
            raise AssertionError(f"Exception before reaching checkpoint '{test_attribute}'"
                                 f"{Util.NEW_LINE}Description: {kwargs['description']}"
                                 f"{Util.NEW_LINE}Got:{str(e)}{Util.NEW_LINE}{Util.NEW_LINE}") from None

    def conf_file_content(self) -> str:
        with open(self.conf_file_template, 'r') as file:
            return file.read()

    @staticmethod
    def conf_arg(conf: str) -> List[str]:
        return [conf]

    def get_command_args(self, **kwargs: Any) -> List[str]:
        run_conf_file = self.conf_file_template
        if 'replacements' in kwargs:
            if not run_conf_file:
                raise Util.ImplementationError("'replacements' without settings 'conf_file")
            run_conf_file = self.create_conf_file_and_inject(kwargs['replacements'])

        if 'build_script_context' in kwargs:
            build_script = self.create_build_script_and_inject(kwargs['build_script_context'])
            run_conf_file = self.add_to_conf_file(run_conf_file, {'build_script': f"./{build_script}"})

        test_attribute_args = []
        if 'test_attribute' in kwargs:
            test_attribute_args = ['--test',  str(kwargs['test_attribute'])]
        elif self.test_attribute:
            test_attribute_args = ['--test', str(self.test_attribute)]
        run_flags = kwargs.get('run_flags', [])
        cmd_args = self.conf_arg(run_conf_file) + run_flags + test_attribute_args + self.common_flags
        return [x for x in cmd_args if x]  # remove empty strings for the arg list

    def create_conf_file_and_inject(self, texts: List[Tuple[str, str]]) -> str:
        content = self.conf_file_content()
        for old_text, new_text in texts:
            content = content.replace(old_text, new_text)
        dest = f"temp_{random.randint(0, 99999):05d}.conf"
        with open(dest, 'w') as file:
            file.write(content)
        return dest

    def create_build_script_and_inject(self, build_script_context: Dict[str, Any]) -> str:
        if not self.build_script_template:
            raise Util.ImplementationError("'build_script_context' without settings 'build_script_template")

        mandatory_keys = ['argument', 'output_data']
        if not all(key in build_script_context for key in mandatory_keys):
            raise Util.ImplementationError(f"Missing mandatory keys in context: {build_script_context}")

        with open(self.build_script_template, 'r') as file:
            template = jinja2.Template(file.read())
        content = template.render(build_script_context)
        dest = f"temp_{random.randint(0, 99999):05d}.py"
        with open(dest, 'w') as file:
            file.write(content)

        # Make sure the file is executable
        Path(dest).chmod(0o755)
        return dest

    def add_to_conf_file(self, conf_file: str, data: Dict[str, Any]) -> str:
        with open(conf_file, 'r') as file:
            content: dict = json5.load(file)
        content.update(data)
        dest = f"temp_{random.randint(0, 99999):05d}.conf"
        with open(dest, 'w') as file:
            file.write(json5.dumps(content))
        return dest


class SorobanProverTestSuite(TestSuite):
    def __init__(self, **kwargs: Any):
        super().__init__(run_soroban_prover, **kwargs)


class SolanaProverTestSuite(TestSuite):
    def __init__(self, **kwargs: Any):
        super().__init__(run_solana_prover, **kwargs)

class RangerTestSuite(TestSuite):
    def __init__(self, **kwargs: Any):
        super().__init__(run_ranger, **kwargs)


class ProverTestSuite(TestSuite):
    def __init__(self, **kwargs: Any):
        super().__init__(run_certora, **kwargs)

    @staticmethod
    def conf_arg(conf: str) -> List[str]:
        return [conf] if conf else []


def replace_in_file(file_path: Path, old: str, new: str) -> None:
    with file_path.open('r') as f:
        content = f.read().replace(old, new)
    with file_path.open('w') as f:
        f.write(content)
