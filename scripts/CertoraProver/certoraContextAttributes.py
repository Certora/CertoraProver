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

import logging

import sys
from pathlib import Path
from typing import Type, List, Optional

scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))
from Shared import certoraUtils as Util
from Shared import certoraAttrUtil as AttrUtil
from Shared import certoraValidateFuncs as Vf
from CertoraProver.certoraCollectConfigurationLayout import AttributeJobConfigData, MainSection


attributes_logger = logging.getLogger("attributes")

FORBIDDEN_PROVER_ARGS = ['-solanaInlining', '-solanaSummaries']


def validate_prover_args(value: str) -> str:

    strings = value.split()
    for attr in EvmProverAttributes.attribute_list():
        if attr.jar_flag is None:
            continue
        for string in strings:

            if string == attr.jar_flag:
                # globalTimeout will get a special treatment, because the actual attr is the wrong one
                if attr.jar_flag == BackendAttributes.CLOUD_GLOBAL_TIMEOUT.jar_flag:
                    actual_attr = BackendAttributes.GLOBAL_TIMEOUT
                else:
                    actual_attr = attr

                flag_name = actual_attr.get_flag()
                if not attr.temporary_jar_invocation_allowed:
                    raise Util.CertoraUserInputError(
                        f"Use CLI flag '{flag_name}' instead of 'prover_attrs' with {string} as value")

    for string in strings:
        if string in FORBIDDEN_PROVER_ARGS:
            raise Util.CertoraUserInputError(
                f"Use a Prover option instead of 'prover_attrs' with {string} as value")
    return value


class CommonAttributes(AttrUtil.Attributes):

    MSG = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_msg,
        help_msg="Add a message description to your run",
        default_desc="No message",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    DEBUG = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SHOW_DEBUG_TOPICS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    DEBUG_TOPICS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    VERSION = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Show the Prover version",
        default_desc="",
        argparse_args={
            'action': AttrUtil.VERSION,
            'version': 'This message should never be reached'
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    COMMIT_SHA1 = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_git_hash,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    PROVER_VERSION = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_prover_version,
        help_msg="Use a specific Prover revision",
        default_desc="Uses the latest public Prover version",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SERVER = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_server_value,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    WAIT_FOR_RESULTS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_wait_for_results,
        help_msg="Wait for verification results before terminating the run",
        default_desc="Sends request and does not wait for results",
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore,
            'default': None,  # 'default': when --wait_for_results was not used
            'const': str(Vf.WaitForResultOptions.ALL)  # when --wait_for_results was used without an argument
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    # used by certoraMutate, ignored by certoraRun
    MUTATIONS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.MAP,
        argparse_args={
            'action': AttrUtil.NotAllowed
        },
        affects_build_cache_key=False,
        disables_build_cache=False,
        # Avoiding presentation of this attribute in Config Tab
        config_data=None
    )

    RUN_SOURCE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_run_source,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    BUILD_DIR = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_build_dir,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    BUILD_ONLY = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    COMPILATION_STEPS_ONLY = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Compile the spec and the code without sending a verification request to the cloud",
        default_desc="Sends a request after source compilation and spec syntax checking",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    OVERRIDE_BASE_CONFIG = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_conf_file,
        help_msg="Path to parent conf",
        default_desc="",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    URL_VISIBILITY = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_url_visibility,
        help_msg="Sets the visibility of the generated report link",
        default_desc="Generate a Private report link",
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore,
            'default': None,  # 'default': when --url_visibility was not used
            # when --url_visibility was used without an argument its probably because the link should be public
            'const': str(Vf.UrlVisibilityOptions.PUBLIC)
        },
        affects_build_cache_key=False,
        disables_build_cache=False,
        # Avoiding presentation of this attribute in Config Tab
        config_data=None
    )


class DeprecatedAttributes(AttrUtil.Attributes):
    pass

    SOLC_MAP = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_compiler_map,
        arg_type=AttrUtil.AttrArgType.MAP,
        deprecation_msg="`solc_map` is deprecated, use `compiler_map` instead",
        help_msg='Map contracts to the appropriate Solidity compiler in case not all contract files are compiled '
                 'with the same Solidity compiler version. \n\nCLI Example: '
                 '\n  --solc_map A=solc8.11,B=solc8.9,C=solc7.5\n\nJSON Example: '
                 '\n  "solc_map: {"'
                 '\n    "A": "solc8.11",'
                 '\n    "B": "solc8.9",'
                 '\n    "C": "solc7.5"'
                 '\n  }',
        default_desc="Uses the same Solidity compiler version for all contracts",
        argparse_args={
            'action': AttrUtil.UniqueStore,
            'type': lambda value: Vf.parse_ordered_dict('solc_map', value)
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )


class EvmAttributes(AttrUtil.Attributes):

    SOLC = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_exec_file,
        help_msg="Path to the Solidity compiler executable file",
        default_desc="Calling `solc`",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER,
        )
    )

    VYPER = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_exec_file,
        help_msg="Path to the Vyper compiler executable file",
        default_desc="Calling `vyper`",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    SOLC_VIA_IR = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Pass the `--via-ir` flag to the Solidity compiler",
        default_desc="",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_VIA_IR_MAP = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_solc_via_ir_map,
        arg_type=AttrUtil.AttrArgType.MAP,
        help_msg='Map contracts to the appropriate via_ir value',
        default_desc="do not set via_ir during compilation unless solc_via_ir is set",
        argparse_args={
            'action': AttrUtil.UniqueStore,
            'type': lambda value: Vf.parse_ordered_dict('solc_via_ir_map', value, bool)
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_EXPERIMENTAL_VIA_IR = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        default_desc="",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_EVM_VERSION = AttrUtil.AttributeDefinition(
        help_msg="Instruct the Solidity compiler to use a specific EVM version",
        default_desc="Uses the Solidity compiler's default EVM version",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_EVM_VERSION_MAP = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_solc_evm_version_map,
        arg_type=AttrUtil.AttrArgType.MAP,
        help_msg='Map contracts to the appropriate EVM version',
        default_desc="Uses the same Solidity EVM version for all contracts",
        argparse_args={
            'action': AttrUtil.UniqueStore,
            'type': lambda value: Vf.parse_ordered_dict('solc_evm_version_map', value)
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    COMPILER_MAP = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_compiler_map,
        arg_type=AttrUtil.AttrArgType.MAP,
        help_msg='Map contracts to the appropriate compiler in case not all contract files are compiled '
                 'with the same compiler version. \n\nCLI Example: '
                 '\n  --compiler_map A=solc8.11,B=solc8.9,C=solc7.5\n\nJSON Example: '
                 '\n  "compiler_map": {'
                 '\n    "A": "solc8.11", '
                 '\n    "B": "solc8.9", '
                 '\n    "C": "solc7.5"'
                 '\n  }',
        default_desc="Uses the same compiler version for all contracts",
        argparse_args={
            'action': AttrUtil.UniqueStore,
            'type': lambda value: Vf.parse_ordered_dict('compiler_map', value)
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_ALLOW_PATH = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_dir,
        help_msg="Set the base path for loading Solidity files",
        default_desc="Only the Solidity compiler's default paths are allowed",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_OPTIMIZE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer_or_minus_1,
        help_msg="Tell the Solidity compiler to optimize the gas costs of the contract for a given number of runs",
        default_desc="Uses the Solidity compiler's default optimization settings",
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore,
            'const': '-1'
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_OPTIMIZE_MAP = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_solc_optimize_map,
        arg_type=AttrUtil.AttrArgType.MAP,
        help_msg='Map contracts to their optimized number of runs in case not all contract files are compiled '
                 'with the same number of runs. \n\nCLI Example:'
                 '\n  --solc_optimize_map A=200,B=300,C=200\n\nJSON Example:'
                 '\n  "solc_optimize_map": {'
                 '\n    "A": "200",'
                 '\n    "B": "300",'
                 '\n    "C": "200"'
                 '\n  }',
        default_desc="Compiles all contracts with the same optimization settings",
        argparse_args={
            'action': AttrUtil.UniqueStore,
            'type': lambda value: Vf.parse_ordered_dict('solc_optimize_map', value)
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    SOLC_ARGS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_solc_args,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    PACKAGES_PATH = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_dir,
        help_msg="Look for Solidity packages in the given directory",
        default_desc="Looks for the packages in $NODE_PATH",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    PACKAGES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_packages,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Map packages to their location in the file system",
        default_desc="Takes packages mappings from `package.json` `remappings.txt` if exist, conflicting mappings"
                     " cause the script abnormal termination",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.NEW_SECTION
        )
    )

    NO_MEMORY_SAFE_AUTOFINDERS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        # This is a hidden flag, the following two attributes are left intentionally as comments to help devs
        # help_msg="Don't instrument internal function finders using memory-safe assembly",
        # default_desc="Uses memory-safe bytecode annotations to identify internal functions",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    STRICT_SOLC_OPTIMIZER = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        # This is a hidden flag, the following two attributes are left intentionally as comments to help devs
        # help_msg="Allow Solidity compiler optimizations that can interfere with internal function finders",
        # default_desc="Disables optimizations that may invalidate the bytecode annotations that identify "
        #              "internal functions",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    DISABLE_SOLC_OPTIMIZERS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.SOLIDITY_COMPILER
        )
    )

    YUL_ABI = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_json_file,
        # This is a hidden flag, the following two attributes are left intentionally as comments to help devs
        # help_msg="An auxiliary ABI file for yul contracts",
        default_desc="",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=True
    )

    YUL_OPTIMIZER_STEPS = AttrUtil.AttributeDefinition(
        # overrides the hardcoded yul optimizer steps, set in certoraBuild.py
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    CACHE = AttrUtil.AttributeDefinition(
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )
    LINK = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_link_attr,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Link a slot in a contract with another contract. \n\nFormat: "
                 "\n  <Contract>:<field>=<Contract>\n\n"
                 "Example: \n  Pool:asset=Asset\n\n"
                 "The field asset in contract Pool is a contract of type Asset",
        default_desc="The slot can be any address, often resulting in unresolved calls and havocs that lead to "
                     "non-useful counter examples",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,  # not sure, better be careful
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.NEW_SECTION
        )
    )

    ADDRESS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_address,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Set the address of a contract to a given address",
        default_desc="Assigns addresses arbitrarily",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    STRUCT_LINK = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_struct_link,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Link a slot in a struct with another contract. \n\nFormat: "
                 "\n  <Contract>:<slot#>=<Contract>\n"
                 "Example: \n  Bank:0=BankToken Bank:1=LoanToken\n\n"
                 "The first field in contract Bank is a contract of type BankToken and the second of type LoanToken ",
        default_desc="The slot can be any address, often resulting in unresolved calls and havocs that lead to "
                     "non-useful counter examples",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,  # carefulness
        disables_build_cache=False
    )

    STORAGE_EXTENSION_HARNESSES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_storage_extension_harness_attr,
        # The docs aren't ready yet. The flag is hidden; the lines below are commented to help us devs
        # help_msg="List of ContractA=ContractB where ContractB is the name of a 'storage extension prototype`. "
        #          "See the documentation for details",
        # default_desc="",
        disables_build_cache=False,
        affects_build_cache_key=True,
        arg_type=AttrUtil.AttrArgType.LIST,
        argparse_args={
            'nargs': AttrUtil.MULTIPLE_OCCURRENCES,
            'action': AttrUtil.APPEND
        }
    )

    CONTRACT_EXTENSIONS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_contract_extension_attr,
        help_msg="Dictionary of extension contracts. Format:\n"
                 '{\n'
                 '  "ExtendedContractA: [\n'
                 '    {\n'
                 '      "extension": "ExtenderA",\n'
                 '      "exclude": [\n'
                 '        "method1",\n'
                 '        ...,\n'
                 '        "methodN"\n'
                 '      ]\n'
                 '    },\n'
                 '    {\n'
                 '      ...\n'
                 '    }\n'
                 '  ],\n'
                 '  "ExtendedContractB: [\n'
                 '    ...\n'
                 '  ],\n'
                 '  ...\n'
                 '}',
        default_desc="",
        affects_build_cache_key=True,
        disables_build_cache=False,
        arg_type=AttrUtil.AttrArgType.MAP,
        argparse_args={
            'action': AttrUtil.UniqueStore
        }
    )

    PROTOTYPE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_prototype_attr,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Set the address of the contract's create code. \n\nFormat: "
                 "\n  <hex address>=<Contract>\n\n"
                 "Example: \n  0x3d602...73\n\n"
                 "Contract Foo will be created from the code in address 0x3d602...73",
        default_desc="Calls to the created contract will be unresolved, causing havocs that may lead to "
                     "non-useful counter examples",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    EXCLUDE_RULE = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        attr_validation_func=Vf.validate_rule_name,
        jar_flag='-excludeRule',
        help_msg="Filter out the list of rules/invariants to verify. Asterisks are interpreted as wildcards",
        default_desc="Verifies all rules and invariants",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SPLIT_RULES = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        attr_validation_func=Vf.validate_rule_name,
        help_msg="List of rules to be sent to Prover each on a separate run",
        default_desc="Verifies all rules and invariants in a single run",
        argparse_args={
            'nargs': AttrUtil.MULTIPLE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    VERIFY = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_verify_attr,
        help_msg="Path to The Certora CVL formal specifications file. \n\nFormat: "
                 "\n  <contract>:<spec file>\n"
                 "Example: \n  Bank:specs/Bank.spec\n\n"
                 "spec files suffix must be .spec",
        default_desc="",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    BUILD_CACHE = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        help_msg="Enable caching of the contract compilation process",
        default_desc="Compiles contract source files from scratch each time",
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    FUNCTION_FINDER_MODE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_function_finder_mode,
        # This is a hidden flag, the following two attributes are left intentionally as comments to help devs
        # help_msg="Use `relaxed` mode to increase internal function finders precision, "
        #          "but may cause `stack too deep` errors unless using `via-ir`",
        # default_desc="Takes less stack space but internal functions may be missed",
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore,
            'default': None
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    DISABLE_LOCAL_TYPECHECKING = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    INTERNAL_FUNCS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_json_file,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=True  # prefer to be extra careful with this rare option
    )

    IGNORE_SOLIDITY_WARNINGS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Ignore all Solidity compiler warnings",
        default_desc="Treats certain severe Solidity compiler warnings as errors",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    PARAMETRIC_CONTRACTS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        attr_validation_func=Vf.validate_contract_name,
        jar_flag='-contract',
        help_msg="Filter the set of contracts whose functions will be verified in parametric rules/invariants",
        default_desc="Verifies all functions in all contracts in the file list",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    EQUIVALENCE_CONTRACTS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_equivalence_contracts,
        arg_type=AttrUtil.AttrArgType.STRING,
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    BYTECODE_JSONS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_json_file,
        arg_type=AttrUtil.AttrArgType.LIST,
        jar_flag='-bytecode',
        default_desc="",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,
        disables_build_cache=True
    )

    BYTECODE_SPEC = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_spec_file,
        jar_flag='-spec',
        default_desc="",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=True
    )

    DISABLE_INTERNAL_FUNCTION_INSTRUMENTATION = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=True
    )

    FOUNDRY_TESTS_MODE = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-foundry',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    AUTO_DISPATCHER = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-autoDispatcher',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        help_msg="automatically add `DISPATCHER(true)` summaries for all calls with unresolved callees",
        default_desc=""
    )

    MAX_GRAPH_DEPTH = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        jar_flag='-graphDrawLimit',
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    DISABLE_AUTO_CACHE_KEY_GEN = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    DYNAMIC_BOUND = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        jar_flag='-dynamicCreationBound',
        help_msg="Set the maximum amount of times a contract can be cloned",
        default_desc="0 - calling create/create2/new causes havocs that can lead to non-useful counter examples",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    CONTRACT_RECURSION_LIMIT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        help_msg="Specify the maximum depth of recursive calls verified for Solidity functions due to inlining",
        jar_flag='-contractRecursionLimit',
        default_desc="0 - no recursion is allowed",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    DYNAMIC_DISPATCH = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-dispatchOnCreated',
        help_msg="Automatically apply the DISPATCHER summary on newly created instances",
        default_desc="Contract method invocations on newly created instances "
                     "causes havocs that can lead to non-useful counter examples",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            unsound=True
        )
    )

    HASHING_LENGTH_BOUND = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        jar_flag='-hashingLengthBound',
        help_msg="Set the maximum length of otherwise unbounded data chunks that are being hashed",
        default_desc="224 bytes (7 EVM words)",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    METHOD = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_method_flag,
        jar_flag='-method',
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Filter methods to be verified by their signature",
        default_desc="Verifies all public or external methods. In invariants pure and view functions are ignored",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    EXCLUDE_METHOD = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_method_flag,
        jar_flag='-excludeMethod',
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Filter out methods to be verified by their signature",
        default_desc="Verifies all public or external methods. In invariants pure and view functions are ignored",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    OPTIMISTIC_CONTRACT_RECURSION = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Assume the recursion limit is never reached in cases of "
                 "recursion of Solidity functions due to inlining",
        jar_flag='-optimisticContractRecursion',
        default_desc="May show counter examples where the recursion limit is reached",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    STORAGE_EXTENSION_ANNOTATION = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    EXTRACT_STORAGE_EXTENSION_ANNOTATION = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    OPTIMISTIC_HASHING = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Bound the length of data (with potentially unbounded length) to the value given in "
                 "`hashing_length_bound`",
        jar_flag='-optimisticUnboundedHashing',
        default_desc="May show counter examples with hashing applied to data with unbounded length",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            unsound=True
        )
    )

    OPTIMISTIC_SUMMARY_RECURSION = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Assume the recursion limit of Solidity functions within a summary is never reached",
        default_desc="Can show counter examples where the recursion limit was reached",
        jar_flag='-optimisticSummaryRecursion',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            unsound=True
        )
    )

    NONDET_DIFFICULT_FUNCS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-autoNondetDifficultInternalFuncs',
        help_msg="Summarize as NONDET all value-type returning difficult internal functions which are view or pure",
        default_desc="Tries to prove the unsimplified code",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    NONDET_MINIMAL_DIFFICULTY = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        jar_flag='-autoNondetMinimalDifficulty',
        help_msg="Set the minimal `difficulty` threshold for summarization by `nondet_difficult_funcs`",
        default_desc="50",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    OPTIMISTIC_FALLBACK = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-optimisticFallback',
        help_msg="Prevent unresolved external calls with an empty input buffer from affecting storage states",
        default_desc="Unresolved external calls with an empty input buffer cause havocs "
                     "that can lead to non-useful counter examples",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            unsound=True
        )
    )

    SUMMARY_RECURSION_LIMIT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        help_msg="Determine the number of recursive calls we verify "
                 "in case of recursion of Solidity functions within a summary",
        jar_flag='-summaryRecursionLimit',
        default_desc="0 - no recursion is allowed",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    PROJECT_SANITY = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        help_msg="Perform basic sanity checks on all contracts in the current project",
        default_desc="",
    )

    FOUNDRY = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        help_msg="Verify all Foundry fuzz tests in the current project",
        default_desc="",
    )

    RANGE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        help_msg="The maximal length of function call sequences Ranger checks",
        default_desc=f"The default value ('{Util.DEFAULT_RANGER_RANGE}') is used",
        jar_flag="-boundedModelChecking",
        affects_build_cache_key=False,
        disables_build_cache=False,
    )

    RANGER_FAILURE_LIMIT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        help_msg="Once this number of violations are found, no new Ranger call sequence checks will be started. Checks already in progress will continue.",
        default_desc=f"Once {Util.DEFAULT_RANGER_FAILURE_LIMIT} violations are found, no new Ranger call sequence checks will be started.",
        jar_flag="-boundedModelCheckingFailureLimit",
        affects_build_cache_key=False,
        disables_build_cache=False,
    )

    MAX_CONCURRENT_RULES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        help_msg="Set the maximum number of parallel rule evaluations. "
                 "Lower values (e.g., 1, 2, or 4) may reduce memory usage in large runs. "
                 "This can sometimes help to mitigate out of memory problems.",
        default_desc="Number of available CPU cores.",
        temporary_jar_invocation_allowed=True,
        jar_flag="-maxConcurrentRules",
        affects_build_cache_key=False,
        disables_build_cache=False,
    )

    @classmethod
    def hide_attributes(cls) -> List[str]:
        # do not show these attributes in the help message
        return [cls.RANGER_FAILURE_LIMIT.name, cls.RANGE.name]


class InternalUseAttributes(AttrUtil.Attributes):
    '''
    These attributes are for development/testing purposes and are used by R&D and automation
    '''
    TEST = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_test_value,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )
    # the test exception will be fired only if the condition holds
    TEST_CONDITION = AttrUtil.AttributeDefinition(
        argparse_args={
            'action': AttrUtil.UniqueStore,
            'default': 'True'
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    EXPECTED_FILE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_optional_readable_file,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    NO_COMPARE = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )


class BackendAttributes(AttrUtil.Attributes):

    RULE = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        attr_validation_func=Vf.validate_rule_name,
        jar_flag='-rule',
        help_msg="Verify only the given list of rules/invariants. Asterisks are interpreted as wildcards",
        default_desc="Verifies all rules and invariants",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    UNUSED_SUMMARY_HARD_FAIL = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_on_off,
        jar_flag='-unusedSummaryHardFail',
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    ASSERT_AUTOFINDER_SUCCESS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    # when this option is enabled, it also enables `--assert_autofinder_success` logically
    ASSERT_SOURCE_FINDERS_SUCCESS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        # this is okay because even if we run with it and then without it, we still generated regular autofinders
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    DISABLE_SOURCE_FINDERS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    USE_PER_RULE_CACHE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_false,
        jar_flag='-usePerRuleCache',
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    LOOP_ITER = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        jar_flag='-b',
        help_msg="Set the maximum number of loop iterations",
        default_desc="A single iteration for variable iterations loops, all iterations for fixed iterations loops",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SMT_TIMEOUT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_positive_integer,
        jar_flag='-t',
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    MULTI_ASSERT_CHECK = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_no_value=True,
        jar_flag='-multiAssertCheck',
        help_msg="Check each assertion statement that occurs in a rule, separately",
        default_desc="Stops after a single violation of any assertion is found",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )
    INDEPENDENT_SATISFY = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_no_value=False,
        jar_flag='-independentSatisfies',
        help_msg="Check each `satisfy` statement that occurs in a rule while ignoring previous ones",
        default_desc="For each `satisfy` statement, assumes that all previous `satisfy` statements were fulfilled",
        argparse_args={
            'action': AttrUtil.STORE_TRUE,
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SAVE_VERIFIER_RESULTS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_no_value=True,
        jar_flag='-saveVerifierResults',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    INCLUDE_EMPTY_FALLBACK = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-includeEmptyFallback',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    RULE_SANITY = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_sanity_value,
        help_msg="Select the type of sanity check that will be performed during execution",
        jar_flag='-ruleSanityChecks',
        default_desc="Basic sanity checks (Vacuity and trivial invariant check)",
        argparse_args={
            'action': AttrUtil.UniqueStore,
            'default': None,  # 'default': when no --rule_sanity given
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    MULTI_EXAMPLE = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_multi_example_value,
        help_msg="Show several counter examples for failed `assert` statements "
                 "and several witnesses for verified `satisfy` statements",
        jar_flag='-multipleCEX',
        default_desc="Shows a single example",
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore,
            'default': None,  # 'default': when no --multi_example given
            'const': Vf.MultiExampleValue.BASIC.name.lower()
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    NO_CALLTRACE_STORAGE_INFORMATION = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-noCalltraceStorageInformation',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    OPTIMISTIC_LOOP = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-assumeUnwindCond',
        jar_no_value=True,
        help_msg="Assume the loop halt conditions hold, after unrolling loops",
        default_desc="May produce a counter example showing a case where loop halt conditions don't hold after "
                     "unrolling",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            unsound=True
        )
    )

    JAR = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_jar,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    JAVA_ARGS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        argparse_args={
            'action': AttrUtil.APPEND,
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    QUEUE_WAIT_MINUTES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    MAX_POLL_MINUTES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    LOG_QUERY_FREQUENCY_SECONDS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    MAX_ATTEMPTS_TO_FETCH_OUTPUT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    DELAY_FETCH_OUTPUT_SECONDS = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    PROVER_ARGS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        attr_validation_func=validate_prover_args,
        help_msg="Send flags directly to the Prover",
        default_desc="",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    CLOUD_GLOBAL_TIMEOUT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_cloud_global_timeout,
        jar_flag='-globalTimeout',
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    GLOBAL_TIMEOUT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_non_negative_integer,
        jar_flag='-userGlobalTimeout',
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    COINBASE_MODE = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-coinbaseFeaturesMode',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    ENFORCE_REQUIRE_REASON = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-enforceRequireReasonInCVL',
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    # resource files are string of the form <label>:<path> the client will add the file to .certora_sources
    # and will change the path from relative/absolute path to
    PROVER_RESOURCE_FILES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_resource_files,
        jar_flag='-resourceFiles',
        arg_type=AttrUtil.AttrArgType.LIST,
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=True,
        disables_build_cache=False
    )

    FE_VERSION = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_fe_value,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    JOB_DEFINITION = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_job_definition,
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )
    MUTATION_TEST_ID = AttrUtil.AttributeDefinition(
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SMT_USE_BV = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-smt_useBV',
        jar_no_value=True,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    PRECISE_BITWISE_OPS = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        help_msg="Show precise bitwise operation counter examples. Models mathints as unit256 that may over/underflow",
        default_desc="May report counterexamples caused by incorrect modeling of bitwise operations,"
                     " but supports unbounded integers (mathints)",
        jar_flag='-smt_preciseBitwiseOps',
        jar_no_value=True,
        temporary_jar_invocation_allowed=True,
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )
    GROUP_ID = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_uuid,
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    PROTOCOL_NAME = AttrUtil.AttributeDefinition(
        help_msg="Add the protocol's name for easy filtering in the dashboard",
        default_desc="The `package.json` file's `name` field if found",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    PROTOCOL_AUTHOR = AttrUtil.AttributeDefinition(
        help_msg="Add the protocol's author for easy filtering in the dashboard",
        default_desc="The `package.json` file's `author` field if found",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SHORT_OUTPUT = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.BOOLEAN,
        jar_flag='-ciMode',
        help_msg="Reduce verbosity",
        default_desc="",
        argparse_args={
            'action': AttrUtil.STORE_TRUE
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    TOOL_OUTPUT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_tool_output_path,
        jar_flag='-json',
        argparse_args={
            'action': AttrUtil.UniqueStore,
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    COVERAGE_INFO = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_coverage_info,
        jar_flag='-coverageInfo',
        argparse_args={
            'nargs': AttrUtil.SINGLE_OR_NONE_OCCURRENCES,
            'action': AttrUtil.UniqueStore,
            'default': None,  # 'default': when no --coverage_info given
            'const': Vf.CoverageInfoValue.BASIC.name.lower()  # 'default': when empty --coverage_info is given
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )


class RustAttributes(AttrUtil.Attributes):

    BUILD_SCRIPT = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_exec_file,
        help_msg="script to build a rust project",
        default_desc="Using default building command",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    CARGO_FEATURES = AttrUtil.AttributeDefinition(
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="a list of strings that are extra features passed to the build_script",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    CARGO_TOOLS_VERSION = AttrUtil.AttributeDefinition(
        help_msg="Platform tools version to use",
        default_desc="Platform tools version is chosen automatically",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )


class EvmProverAttributes(CommonAttributes, DeprecatedAttributes, EvmAttributes, InternalUseAttributes,
                          BackendAttributes):
    FILES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_evm_input_file,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="Solidity or Vyper contract files for analysis or a conf file",
        default_desc="",
        argparse_args={
            'nargs': AttrUtil.MULTIPLE_OCCURRENCES
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.NEW_SECTION
        )
    )


class ConcordAttributes(EvmProverAttributes):
    CHECK_METHOD = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_check_method_flag,
        help_msg="the method to be checked by Concord equivalent checker",
        default_desc="Mandatory for Concord",
        jar_flag='-method',
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    @classmethod
    def unsupported_attributes(cls) -> List[AttrUtil.AttributeDefinition]:
        return [cls.VERIFY, cls.MSG, cls.PROTOCOL_NAME, cls.PROTOCOL_AUTHOR, cls.RULE, cls.EXCLUDE_RULE,
                cls.SPLIT_RULES, cls.EXCLUDE_METHOD, cls.PARAMETRIC_CONTRACTS, cls.COVERAGE_INFO, cls.FOUNDRY,
                cls.INDEPENDENT_SATISFY, cls.MULTI_ASSERT_CHECK, cls.MULTI_EXAMPLE, cls.PROJECT_SANITY,
                cls.RULE_SANITY, cls.ADDRESS, cls.CONTRACT_EXTENSIONS, cls.CONTRACT_RECURSION_LIMIT, cls.LINK,
                cls.OPTIMISTIC_CONTRACT_RECURSION, cls.STRUCT_LINK, cls.DYNAMIC_BOUND, cls.DYNAMIC_DISPATCH,
                cls.PROTOTYPE, cls.METHOD]


class RangerAttributes(EvmProverAttributes):
    @classmethod
    def unsupported_attributes(cls) -> List[AttrUtil.AttributeDefinition]:
        return [cls.PROJECT_SANITY, cls.RULE_SANITY, cls.COVERAGE_INFO, cls.FOUNDRY, cls.INDEPENDENT_SATISFY,
                cls.MULTI_ASSERT_CHECK, cls.MULTI_EXAMPLE, cls.VYPER]

    @classmethod
    def true_by_default_attributes(cls) -> List[AttrUtil.AttributeDefinition]:
        return [cls.OPTIMISTIC_LOOP, cls.OPTIMISTIC_FALLBACK, cls.AUTO_DISPATCHER, cls.OPTIMISTIC_HASHING]

    @classmethod
    def hide_attributes(cls) -> List[str]:
        # do not show these attributes in the help message
        combined_list = cls.unsupported_attributes() + cls.true_by_default_attributes()
        return [attr.name for attr in combined_list] + [cls.LOOP_ITER.name, cls.RANGER_FAILURE_LIMIT.name]


class SorobanProverAttributes(CommonAttributes, InternalUseAttributes, BackendAttributes, RustAttributes):
    FILES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_soroban_extension,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="binary .wat files for the Prover",
        default_desc="",
        argparse_args={
            'nargs': AttrUtil.MULTIPLE_OCCURRENCES
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.NEW_SECTION
        )
    )


class SuiProverAttributes(CommonAttributes, InternalUseAttributes, BackendAttributes):
    FILES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_dir,
        arg_type=AttrUtil.AttrArgType.LIST,
        argparse_args={
            'nargs': AttrUtil.MULTIPLE_OCCURRENCES
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.NEW_SECTION
        )
    )

    MOVE_PATH = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_dir,
        arg_type=AttrUtil.AttrArgType.STRING,
        help_msg="path to a directory which includes all binary .mv files for the Prover",
        default_desc="",
        argparse_args={
            'action': AttrUtil.UniqueStore
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.NEW_SECTION
        )
    )


class SolanaProverAttributes(CommonAttributes, InternalUseAttributes, BackendAttributes, RustAttributes):
    FILES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_solana_extension,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="contract files for analysis SOLANA_FILE.so or a conf file",

        default_desc="",
        argparse_args={
            'nargs': AttrUtil.MULTIPLE_OCCURRENCES
        },
        affects_build_cache_key=True,
        disables_build_cache=False,
        config_data=AttributeJobConfigData(
            main_section=MainSection.NEW_SECTION
        )
    )

    SOLANA_INLINING = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_readable_file,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="a list of paths for the inlining files of Solana contracts",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )

    SOLANA_SUMMARIES = AttrUtil.AttributeDefinition(
        attr_validation_func=Vf.validate_readable_file,
        arg_type=AttrUtil.AttrArgType.LIST,
        help_msg="a list of paths for the summaries files of Solana contracts",
        argparse_args={
            'nargs': AttrUtil.ONE_OR_MORE_OCCURRENCES,
            'action': AttrUtil.APPEND
        },
        affects_build_cache_key=False,
        disables_build_cache=False
    )


ATTRIBUTES_CLASS: Optional[Type[AttrUtil.Attributes]] = None


def get_attribute_class() -> Type[AttrUtil.Attributes]:
    if not ATTRIBUTES_CLASS:
        set_attribute_class(EvmProverAttributes)
    assert ATTRIBUTES_CLASS
    return ATTRIBUTES_CLASS


def set_attribute_class(cls: Type[AttrUtil.Attributes]) -> None:
    global ATTRIBUTES_CLASS
    ATTRIBUTES_CLASS = cls
    cls.set_attribute_list()


def is_solana_app() -> bool:
    return get_attribute_class() == SolanaProverAttributes


def is_soroban_app() -> bool:
    return get_attribute_class() == SorobanProverAttributes


def is_rust_app() -> bool:
    return is_soroban_app() or is_solana_app()


# Ranger and Concord will also return true for this function
def is_evm_app() -> bool:
    return issubclass(get_attribute_class(), EvmProverAttributes)


def is_ranger_app() -> bool:
    return get_attribute_class() == RangerAttributes


def is_concord_app() -> bool:
    return get_attribute_class() == ConcordAttributes


def is_sui_app() -> bool:
    return get_attribute_class() == SuiProverAttributes
