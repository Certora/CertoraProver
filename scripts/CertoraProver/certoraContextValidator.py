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
import os
import re
import sys
import itertools
import tempfile
import fnmatch
from pathlib import Path
from wcmatch import glob
from typing import Dict, List, Tuple, Set, Any, Union, OrderedDict

import CertoraProver.certoraContext as Ctx
import CertoraProver.certoraContextAttributes as Attrs
from Shared import certoraValidateFuncs as Vf
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util
from Shared import certoraAttrUtil as AttrUtil
from CertoraProver.certoraProjectScanner import scan_project


scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

validation_logger = logging.getLogger("validation")

KEY_ENV_VAR = "CERTORAKEY"


class CertoraContextValidator:
    def __init__(self, context: CertoraContext):
        self.context = context

    def handle_concord_attrs(self) -> None:
        if Attrs.is_concord_app():
            for attr in (Attrs.ConcordAttributes.unsupported_attributes()):
                attr_name = attr.get_conf_key()
                if getattr(self.context, attr_name):
                    if attr.arg_type == AttrUtil.AttrArgType.BOOLEAN:
                        setattr(self.context, attr_name, False)
                    else:
                        setattr(self.context, attr_name, None)
                    raise Util.CertoraUserInputError(f"Concord does not support {attr_name}")
            if not self.context.check_method:
                raise Util.CertoraUserInputError("'check_method' attribute/flag is mandatory when running Concord")
            contracts = getattr(self.context, 'contracts', None)
            if not contracts:
                raise Util.CertoraUserInputError("No contracts were specified for Concord")
            if len(contracts) != 2:
                raise Util.CertoraUserInputError(f"expecting 2 contracts for Concord, got {len(contracts)}: {contracts}")

    def handle_ranger_attrs(self) -> None:
        # unset unsupported attributes
        if Attrs.is_ranger_app():
            for attr in Attrs.RangerAttributes.unsupported_attributes():
                attr_name = attr.get_conf_key()
                if getattr(self.context, attr_name):
                    if attr.arg_type == AttrUtil.AttrArgType.BOOLEAN:
                        setattr(self.context, attr_name, False)
                    else:
                        setattr(self.context, attr_name, None)
                    validation_logger.info(f"Ranger does not support {attr_name}, ignoring this attribute")

            # setting the default Ranger attributes

            self.context.range = self.context.range or Util.DEFAULT_RANGER_RANGE
            self.context.ranger_failure_limit = self.context.ranger_failure_limit or Util.DEFAULT_RANGER_FAILURE_LIMIT
            if self.context.loop_iter and self.context.loop_iter != Util.DEFAULT_RANGER_LOOP_ITER:
                validation_logger.info(f"While running Ranger, loop iter is {Util.DEFAULT_RANGER_LOOP_ITER} "
                                       f"ignoring the set value of {self.context.loop_iter}")
            self.context.loop_iter = self.context.loop_iter or Util.DEFAULT_RANGER_LOOP_ITER

            for attr in Attrs.RangerAttributes.true_by_default_attributes():
                attr_name = attr.get_conf_key()
                setattr(self.context, attr_name, True)

        else:
            if self.context.range:
                self.context.range = None
                validation_logger.info("the 'range' attribute is ignored when not running from the Ranger App")
            if self.context.ranger_failure_limit:
                self.context.ranger_failure_limit = None
                validation_logger.info("the 'ranger_failure_limit' is ignored when not running from the Ranger App")

    def validate(self) -> None:

        for attr_def in Attrs.get_attribute_class().attribute_list():
            conf_key = attr_def.get_conf_key()
            attr = getattr(self.context, conf_key, None)
            if attr_def.deprecation_msg and attr:
                validation_logger.warning(attr_def.deprecation_msg)
            if attr is None or (attr is False and attr_def.arg_type == AttrUtil.AttrArgType.BOOLEAN):
                continue
            if attr_def.arg_type == AttrUtil.AttrArgType.STRING:
                self.validate_type_string(attr_def)
            elif attr_def.arg_type == AttrUtil.AttrArgType.LIST:
                self.validate_type_list(attr_def)
            elif attr_def.arg_type == AttrUtil.AttrArgType.BOOLEAN:
                self.validate_type_boolean(attr_def)
            elif attr_def.arg_type == AttrUtil.AttrArgType.MAP:
                self.validate_type_any(attr_def)
            else:
                raise RuntimeError(f"{attr_def.arg_type} - unknown arg type")

    def __get_key_and_value(self, attr: AttrUtil.AttributeDefinition) -> Tuple[str, Any]:
        conf_key = attr.get_conf_key()
        value = getattr(self.context, conf_key, None)
        return conf_key, value

    def validate_type_list(self, attr: AttrUtil.AttributeDefinition) -> None:
        conf_key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"calling validate_type_list with null value {conf_key}")

        # QOL - if someone did e.g. `rule: "r"` they almost certainly meant `rule: ["r"]`, so
        # make life a little easier for users and allow this. Note that when we later generate
        # the `run.conf` it will have the square brackets as is officially required.
        if isinstance(value, str):
            value = [value]
            setattr(self.context, conf_key, value)

        if not isinstance(value, List):
            syntax_suggestion = ''
            if self.context.is_conf:
                syntax_suggestion = f'{Util.NEW_LINE}Did you forget the brackets? attribute in the conf file should' \
                                    f' look something like:{Util.NEW_LINE}{Util.NEW_LINE}   ' \
                                    f'"{conf_key}": [<comma separated list of values>]{Util.NEW_LINE}{Util.NEW_LINE}'
            raise Util.CertoraUserInputError(f"The value of attribute '{conf_key}' ('{value}')' is not a "
                                             f"list {syntax_suggestion}")
        for v in value:
            if not isinstance(v, str):
                raise Util.CertoraUserInputError(f"value in {conf_key} {v} in {value} is not a string")
            attr.validate_value(v, cli_flag=False)

    def validate_type_any(self, attr: AttrUtil.AttributeDefinition) -> None:
        conf_key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"calling validate_type_any with null value {conf_key}")
        attr.validate_value(value)

    def validate_type_string(self, attr: AttrUtil.AttributeDefinition) -> None:
        conf_key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"calling validate_type_string with null value {conf_key}")
        if not isinstance(value, str):
            raise Util.CertoraUserInputError(f"value of {conf_key} {value} is not a string")
        attr.validate_value(value)

    def validate_type_boolean(self, attr: AttrUtil.AttributeDefinition) -> None:
        conf_key, value = self.__get_key_and_value(attr)

        if value is None:
            raise RuntimeError(f"{conf_key}: calling validate_type_boolean with None")
        # for backward compatibility the value [] is considered true
        elif type(value) is list and len(value) == 0:
            setattr(self.context, conf_key, True)
        elif value not in [True, False]:
            raise Util.CertoraUserInputError(f"value of {conf_key} {value} is not a boolean (true/false)")

    def check_rust_args_post_argparse(self) -> None:
        context = self.context
        if context.files:
            if context.build_script:
                raise Util.CertoraUserInputError("'files' and 'build_script' cannot be both set for Rust projects")
            if len(context.files) > 1:
                raise Util.CertoraUserInputError("Rust projects must specify exactly one executable in 'files'.")

        if context.url_visibility and context.local:
            validation_logger.info("'url_visibility' has no effect in local tool runs")
        set_attr_default(context, attr_name=Attrs.CommonAttributes.URL_VISIBILITY.name.lower(),
                         ci_value=str(Vf.UrlVisibilityOptions.PUBLIC),
                         default_value=str(Vf.UrlVisibilityOptions.PRIVATE))

    def check_args_post_argparse(self) -> None:
        """
        Performs checks over the arguments after basic argparse parsing

        argparse parses option one by one. This is the function that checks all relations between different options and
        arguments. We assume here that basic syntax was already checked.
        @param context: A namespace including all CLI arguments provided
        @raise CertoraUserInputError if input is illegal
        """

        def __default_path() -> Path:
            """ Keep the paths relative here, same as what the user will likely do """
            path = Path(".") / "contracts"
            if path.is_dir():
                return path
            return Path(".")

        context = self.context

        # Make sure context.solc_allow_path is set to a relative path
        if context.solc_allow_path is None:
            context.solc_allow_path = str(__default_path())
        elif Path(context.solc_allow_path).is_absolute():
            try:
                str(Path(context.solc_allow_path).relative_to(Path.cwd()))
            except ValueError:
                # The paths aren't relative to each other, so we must stay with
                # the absolute one.
                pass

        set_bytecode_relative_paths(context)
        check_files_input(context.files)
        check_vyper_flag(context)
        check_via_ir_attr(context)
        check_contract_name_arg_inputs(context)  # Here context.contracts is set
        Util.check_packages_arguments(context)
        convert_to_compiler_map(context)
        if Attrs.is_evm_app():
            if context.compiler_map is None and context.bytecode_jsons is None:  # xxx - we would need to reiterate here
                # once we allow combining raw bytecode with Solidity
                context.solc = Vf.is_solc_file_valid(context.solc)
            check_map_attributes(context)
        check_parametric_contracts(context)
        check_optional_jar_flags_consistency(context)
        check_autofinder_settings(context)

        check_rule(context)

        if context.split_rules and (context.build_only or context.compilation_steps_only):
            raise Util.CertoraUserInputError("cannot use 'compilation_steps_only' or 'build_only' with 'split_rules'")

        if context.compilation_steps_only and context.build_only:
            raise Util.CertoraUserInputError("cannot use both 'compilation_steps_only' and 'build_only'")

        set_attr_default(context, attr_name=Attrs.CommonAttributes.WAIT_FOR_RESULTS.name.lower(),
                         ci_value=str(Vf.WaitForResultOptions.ALL),
                         default_value=str(Vf.WaitForResultOptions.NONE))
        if context.wait_for_results and context.wait_for_results != str(Vf.WaitForResultOptions.NONE) and context.local:
            validation_logger.warning("'wait_for_results' has no effect in local tool runs")

        if context.url_visibility and context.local:
            validation_logger.info("'url_visibility' has no effect in local tool runs")
        set_attr_default(context, attr_name=Attrs.CommonAttributes.URL_VISIBILITY.name.lower(),
                         ci_value=str(Vf.UrlVisibilityOptions.PUBLIC),
                         default_value=str(Vf.UrlVisibilityOptions.PRIVATE))

        # packages must be in a normal form (no unneeded . or ..)
        if context.packages:
            for idx, el in enumerate(context.packages):
                key, path = el.split('=')
                abs_path = os.path.abspath(path)
                context.packages[idx] = '='.join([key, os.path.relpath(abs_path, os.getcwd())])

        if context.debug_topics is not None or context.show_debug_topics:
            context.debug = True

        if context.dynamic_bound is not None and int(context.dynamic_bound) > 0:
            validation_logger.warning(
                "'disable_source_finders' is set to true while 'dynamic_bound' is set to 1 or higher"
            )
            context.disable_source_finders = True
        package_name = Util.get_package_and_version()[1]
        # if --fe_version was not set then if the package is alpha/beta we set it to latest else we set it to production
        if not context.fe_version:
            lastest_packages = [Util.ALPHA_PACKAGE_MASTER_NAME, Util.BETA_PACKAGE_NAME, Util.BETA_MIRROR_PACKAGE_NAME]
            if package_name in lastest_packages:
                context.fe_version = str(Util.FeValue.LATEST)
            else:
                context.fe_version = str(Util.FeValue.PRODUCTION)
        if Util.ALPHA_PACKAGE_NAME in package_name and not (context.prover_version or context.commit_sha1):
            raise Util.CertoraUserInputError('When running an "alpha release", a specific branch must be supplied '
                                             'via --prover_version. For example: "--prover_version master"')

        check_conflicting_branch_and_hash(context)

def warn_validate_file_args(files: List[str]) -> Tuple[Set[str], Set[str], Dict[str, str], Dict[str, Set[str]]]:
    """
    Verifies all file inputs are legal. If they are not, throws an exception.
    If there are any redundancies or duplication, warns the user.
    Otherwise, it returns a set of all legal contract names.
    @param files: A list of string of form: [contract.sol[:contract_name] ...]
    @return: (contracts, files, contract_to_file, file_to_contracts)
        contracts - a set of contract names
        files - a set of paths to files containing contracts
        contract_to_file - a mapping from contract name -> file containing it
        file_to_contracts - a mapping from a file path -> name of the contracts within it we verify
    """

    """
    The logic is complex, and better shown by examples.
    Legal use cases:
    1. A.sol B.sol
        ->  contracts=(A, B), files=(A.sol, B.sol), contract_to_file={'A': 'A.sol', 'B': 'B.sol'},
            file_to_contracts = {'A.sol': ['A'], 'B.sol': ['B']}
    2. A.sol:a B.sol:b C.sol
        ->  contracts=(a, b, C), files=(A.sol, B.sol, C.sol),
            contract_to_file={'a': 'A.sol', 'b': 'B.sol', 'C': 'C.sol'},
            file_to_contracts = {'A.sol': ['a'], 'B.sol': ['b'], 'C.sol': ['C']}
    3. A.sol:B B.sol:c
        ->  contracts=(B, c), files=(A.sol, B.sol),
            contract_to_file={'B': 'A.sol', 'c': 'B.sol'},
            file_to_contracts = {'A.sol': ['B'], 'B.sol': ['c']}
    4. A.sol:b A.sol:c
        ->  contracts=(b, c), files=(A.sol),
            contract_to_file={'b': 'A.sol', 'c': 'A.sol'},
            file_to_contracts = {'A.sol': ['b', 'c']}

    Warning cases:
    4. A.sol A.sol
        -> A.sol is redundant
    5. A.sol:a A.sol:a
        -> A.sol:a is redundant
    6. A.sol:A
        -> contract name A is redundant (it's the default name)

    Illegal cases:
    7. A.sol:a B.sol:a
        -> The same contract name cannot be used twice
    8. ../A.sol A.sol
        -> The same contract name cannot be used twice
    9. A.sol:B B.sol
        -> The same contract name cannot be used twice
    10. A.sol:a A.sol
        -> The same file cannot contain two different contracts
    11. A.sol A.sol:a
        -> The same file cannot contain two different contracts

    Warning are printed only if the input is legal
    @raise CertoraUserInputError in an illegal case (see above)
    """
    if len(files) == 1 and (files[0].endswith(".conf") or files[0].endswith(".tac")):
        return set(), set(), dict(), dict()  # No legal contract names

    declared_contracts = set()
    file_paths = set()
    all_warnings = set()

    contract_to_file: Dict[str, str] = dict()
    file_to_contracts: Dict[str, Set[str]] = dict()

    for f in files:

        default_contract_name = Util.get_trivial_contract_name(f)
        posix_path = os.path.relpath(Util.abs_posix_path_obj(f), Path.cwd())
        assert posix_path.count(':') < 2
        if ':' in posix_path:
            filepath_str, contract_name = posix_path.split(":")
            if contract_name == default_contract_name:
                all_warnings.add(f"contract name {contract_name} is the same as the file name and can be omitted "
                                 f"from {filepath_str}:{contract_name}")
        else:
            filepath_str = posix_path
            contract_name = default_contract_name

        if filepath_str in file_to_contracts:
            if contract_name in file_to_contracts[filepath_str]:
                all_warnings.add(f"file argument {f} appears more than once and is redundant")
                continue

        if contract_name in contract_to_file and contract_to_file[contract_name] != filepath_str:
            # A.sol:a B.sol:a
            raise Util.CertoraUserInputError(f"A contract named {contract_name} was declared twice for files "
                                             f"{contract_to_file[contract_name]}, {filepath_str}")

        contract_to_file[contract_name] = filepath_str
        file_to_contracts.setdefault(filepath_str, set()).add(contract_name)
        declared_contracts.add(contract_name)
        file_paths.add(filepath_str)

    for warning in all_warnings:
        validation_logger.warning(warning)

    return declared_contracts, file_paths, contract_to_file, file_to_contracts

def check_via_ir_attr(context: CertoraContext) -> None:
    context.solc_via_ir = bool(context.solc_via_ir or context.solc_experimental_via_ir)


def convert_to_compiler_map(context: CertoraContext) -> None:

    # the compiler flags
    compiler_map_flags_keys = [Attrs.EvmProverAttributes.SOLC_MAP.get_conf_key(),
                               Attrs.EvmProverAttributes.COMPILER_MAP.get_conf_key()]
    compiler_flags_keys = compiler_map_flags_keys + [Attrs.EvmProverAttributes.SOLC.get_conf_key(),
                                                     Attrs.EvmProverAttributes.VYPER.get_conf_key()]

    # dictionary of the compiler flags that are not None value
    compiler_flags = {k: getattr(context, k) for k in compiler_flags_keys if getattr(context, k) is not None}

    # no compiler flags, defaults will be used
    if len(compiler_flags) == 0:
        return

    # if a map flags is set it is the only compiler flag
    if len(compiler_flags) > 1 and any(key in compiler_flags for key in compiler_map_flags_keys):
        raise Util.CertoraUserInputError("compiler map flags cannot be set with other compiler flags. "
                                         f"Got {compiler_flags}")

    if context.solc_map:
        context.compiler_map = context.solc_map
        context.solc_map = None


def check_vyper_flag(context: CertoraContext) -> None:
    if context.vyper:
        vy_paths = [path for path in context.files if path.endswith(".vy")]
        if not vy_paths:
            validation_logger.warning("vyper attribute was set but no Vyper files were set")
        if context.solc:
            raise Util.CertoraUserInputError("cannot set both vyper attribute and solc attribute")

def check_contract_name_arg_inputs(context: CertoraContext) -> None:
    """
    This function verifies that all options that expect to get contract names get valid contract names.
    If they do, nothing happens. If there is any error, an exception is thrown.
    @param context: Namespace containing all command line arguments
    @raise CertoraUserInputError if a contract name argument was expected, but not given.
    """
    contract_names, file_paths, contract_to_file, file_to_contract = warn_validate_file_args(context.files)
    context.contracts = contract_names
    context.file_paths = file_paths
    context.file_to_contract = file_to_contract
    context.contract_to_file = contract_to_file

    # we print the warnings at the end of this function, only if no errors were found. Each warning appears only once
    all_warnings = set()

    # Link arguments can be either: contractName:slot=contractName
    #   or contractName:slot=integer(decimal or hexadecimal)
    if context.link is not None:
        for link in context.link:
            executable = link.split(':')[0]
            executable = Util.get_trivial_contract_name(executable)
            if executable not in contract_names:
                __suggest_contract_name(f"Error in linkage: link `{link}`, contract {executable} does not exist",
                                        executable, contract_names, contract_to_file)

            library_or_const = link.split('=')[1]
            try:
                parsed_int = int(library_or_const, 0)  # can be either a decimal or hexadecimal number
                if parsed_int < 0:
                    raise Util.CertoraUserInputError(f"slot number is negative at {link}")
            except ValueError:
                library_name = Util.get_trivial_contract_name(library_or_const)
                if library_name not in contract_names:
                    __suggest_contract_name(f"Error in linkage: link `{link}`, contract {library_name} does not exist",
                                            library_name, contract_names, contract_to_file)

        check_conflicting_link_args(context)

    context.verified_contract_files = []

    context.spec_file = None

    if context.verify is not None:
        contract, spec = context.verify.split(':')
        contract = Util.get_trivial_contract_name(contract)
        if contract not in contract_names:
            __suggest_contract_name(f"'verify' argument, {contract}, doesn't match any contract name", contract,
                                    contract_names, contract_to_file)
        context.verified_contract_files.append(contract_to_file[contract])
        context.spec_file = spec

    contract_to_address = dict()
    if context.address:
        for address_str in context.address:
            contract = address_str.split(':')[0]
            if contract not in contract_names:
                __suggest_contract_name(f"unrecognized contract in 'address' argument {address_str}", contract,
                                        contract_names, contract_to_file)
            number = address_str.split(':')[1]
            if contract not in contract_to_address:
                contract_to_address[contract] = number
            elif contract_to_address[contract] != number:
                raise Util.CertoraUserInputError(f'contract {contract} was given two different addresses: '
                                                 f'{contract_to_address[contract]} and {number}')
            else:
                all_warnings.add(f'address {number} for contract {contract} defined twice')
    context.address = contract_to_address

    if context.struct_link:
        contract_slot_to_contract = dict()
        for link in context.struct_link:
            location = link.split('=')[0]
            destination = link.split('=')[1]
            origin = location.split(":")[0]
            if origin not in contract_names:
                __suggest_contract_name(
                    f"'struct_link' argument {link} is illegal: {origin} is not a defined contract name", origin,
                    contract_names, contract_to_file)
            if destination not in contract_names:
                __suggest_contract_name(
                    f"'struct_link' argument {link} is illegal: {destination} is not a defined contract name",
                    destination, contract_names, contract_to_file)

            if location not in contract_slot_to_contract:
                contract_slot_to_contract[location] = destination
            elif contract_slot_to_contract[location] == destination:
                all_warnings.add(f"'struct_link' argument {link} appeared more than once")
            else:
                raise Util.CertoraUserInputError(f"{location} has two different definitions in 'struct_link': "
                                                 f"{contract_slot_to_contract[location]} and {destination}")

    for warning in all_warnings:
        validation_logger.warning(warning)


def valid_input_file(filename: str) -> bool:
    pattern_match_sol = bool(re.match(r'([\w.$]+)\.sol:([\w$]+)', Path(filename).name))
    ends_with_extension = any(filename.endswith(ext) for ext in Util.VALID_FILE_EXTENSIONS)
    return pattern_match_sol or ends_with_extension


def check_files_attribute(context: CertoraContext) -> None:
    """
    context.files is accessed before general validation checks. We make sure no invalid values are interpreted as files
    incorrectly due to badly formatted flags or files with the wrong suffix for example the command "certoraRun
    file.conf -msg test1", since -msg is not a valid flag (should be --msg) -msg is considered a file and so does
    test1. That is why when running from command line we check that the files are legal (have the right suffix) and if
    we see badly formed flags (single dash) as files we warn about that as well.
    """

    error_message = f'Please remember, CLI flags should be preceded with double dashes!{Util.NEW_LINE}' \
                    'For more help run the tool with the option --help'
    flag_value = [s for s in list(map(lambda s: s.strip(), context.files)) if s.startswith('-') and
                  len(s) > 1 and s[1] != '-']
    if len(flag_value) > 0:
        raise Util.CertoraUserInputError(f"Bad arguments {flag_value}: {error_message}")
    bad_ext = [s for s in list(map(lambda s: s.strip(), context.files)) if not valid_input_file(s)]
    if len(bad_ext) > 0:
        raise Util.CertoraUserInputError(f"Bad arguments {bad_ext}: files must end with one of "
                                         f"these extensions {Util.VALID_FILE_EXTENSIONS}")
    check_for_conf(context)


def check_for_conf(context: CertoraContext) -> None:
    """
    @param context: A namespace including all CLI arguments provided
    @raise an CertoraUserInputError when there are several input files and one of them is a conf file
    """
    context.is_conf = any(file.endswith(".conf") for file in context.files)

    if context.is_conf and len(context.files) > 1:
        raise Util.CertoraUserInputError("No other files are allowed when using a config file")


def pre_validation_checks(context: CertoraContext) -> None:
    """
    This function includes pre-validation checks:
    1. Check that inputs that start with a single dash are not interpreted as files.
    2. Check whether we need to read a config file
    """
    check_files_attribute(context)
    check_for_conf(context)

def _get_project_file_contract_pairs(context: CertoraContext) -> List[Tuple[Path, str]]:
    """
    Returns a list of pairs of <file_path, contract> for each contract within the project.
    In case of two contracts with the same name (in different files) only one of them will
    be in the list, and a warning will be emitted about the discarded one.
    """
    project_files = scan_project()
    if len(project_files) == 0:
        raise Util.CertoraUserInputError("Did not find any contracts to perform sanity checks on")
    file_contract_pairs = [(result.file_path, contract) for result in project_files for contract in result.contracts]
    seen = {}
    deduped_file_contract_pairs: List[Tuple[Path, str]] = []
    for file, contract in sorted(file_contract_pairs, key=lambda pair: len(pair[0].parents)):
        if contract not in seen:
            deduped_file_contract_pairs.append((file, contract))
            seen[contract] = file
        else:
            logging.warning(f"Skipping contract {contract} from file {file} because a contract with the same name already exists (in {seen[contract]})")

    return deduped_file_contract_pairs


def _generate_temp_spec(context: CertoraContext, contents: str) -> str:
    """
    Generate a temporary .spec file with [contents].
    This file will be deleted by the garbage collector once it's no longer in use.
    It's stored in the context object so that it won't be deleted when this function ends - we need it
    to stay around until the end of the prover run.
    Returns the file name
    """
    context.auto_spec_file = tempfile.NamedTemporaryFile("w", delete=True, suffix=".spec", dir=Util.CERTORA_INTERNAL_ROOT)
    context.auto_spec_file.write(contents)
    context.auto_spec_file.flush()
    return context.auto_spec_file.name


def check_mode_of_operation(context: CertoraContext) -> None:
    """

    @param context: A namespace including all CLI arguments provided
    @raise an CertoraUserInputError when:
        1. when both --equivalence_contracts and --verify flags were given
        2. when the file is not .tac|.conf|.json and neither --equivalence_contracts nor --verify were used
        3. If either --bytecode_jsons or --bytecode_spec was used without the other.
    """
    context.is_verify = context.verify is not None and len(context.verify) > 0
    context.is_bytecode = context.bytecode_jsons is not None and len(context.bytecode_jsons) > 0
    context.is_equivalence = context.equivalence_contracts is not None or context.is_concord_app

    if (context.project_sanity or context.foundry) and \
       (context.is_verify or context.is_bytecode or context.is_equivalence):
        raise Util.CertoraUserInputError("The 'project_sanity' and 'foundry' options cannot coexist with the 'verify', "
                                         "'assert_contract' or 'bytecode_jsons' options")

    if context.project_sanity and context.foundry:
        raise Util.CertoraUserInputError("The 'project_sanity' and 'foundry' options cannot coexist")

    if len(list(filter(None, [context.is_verify, context.is_equivalence]))) > 1:
        raise Util.CertoraUserInputError("only one option of 'verify', 'equivalence' can be used")

    has_bytecode_spec = context.bytecode_spec is not None
    if has_bytecode_spec != context.is_bytecode:
        raise Util.CertoraUserInputError("Must use 'bytecode' together with 'bytecode_spec'")

    if not context.files and not any((context.is_bytecode, context.project_sanity, context.foundry)):
        raise Util.CertoraUserInputError("Should always provide input files, unless 'bytecode_jsons' or "
                                         "'project_sanity' or 'foundry' are used")

    if context.is_bytecode and context.files:
        raise Util.CertoraUserInputError("Cannot use 'bytecode_jsons' with other files")

    if context.foundry and context.files:
        raise Util.CertoraUserInputError("Cannot use 'foundry' with other files")

    special_file_type = None

    if context.project_sanity:
        if not context.files:
            file_contract_pairs = _get_project_file_contract_pairs(context)
            main_contract = file_contract_pairs[0][1]  # any contract
            context.files = \
                [f"{file}:{contract}" if file.stem != contract else str(file) for file, contract in file_contract_pairs]
        else:
            first = context.files[0]
            if ':' in first:
                main_contract = first.split(':')[1]
            else:
                main_contract = Path(first).stem

        spec_file_name = _generate_temp_spec(context, "use builtin rule sanity;\n")

        context.verify = f"{main_contract}:{spec_file_name}"
        context.is_verify = True
        # In this mode we want to make life easy for the user,
        # so use the auto-dispatcher mode to avoid many unresolved calls
        context.auto_dispatcher = True

    if context.foundry:
        file_contract_pairs = _get_project_file_contract_pairs(context)
        test_pairs = [(file, contract) for file, contract in file_contract_pairs
                      if file.stem.endswith(".t") and 'lib' not in file.parents]
        context.files = [f"{file}:{contract}" for file, contract in test_pairs]
        main_contract = test_pairs[0][1]  # any contract
        spec_file_name = _generate_temp_spec(context, "use builtin rule verifyFoundryFuzzTestsNoRevert;\n")

        context.foundry_tests_mode = True
        context.verify = f"{main_contract}:{spec_file_name}"
        context.is_verify = True
        # In this mode we want to make life easy for the user,
        # so use the auto-dispatcher mode to avoid many unresolved calls
        context.auto_dispatcher = True

    if context.files:
        conf_files_inside_conf_file = set([_file for _file in context.files if _file.endswith(".conf")])
        if conf_files_inside_conf_file:
            raise Util.CertoraUserInputError(
                f"Cannot use conf files inside a conf file: {','.join(conf_files_inside_conf_file)}")

        special_file_suffixes = [".tac", ".json"] + [Util.SOLANA_EXEC_EXTENSION, Util.SOROBAN_EXEC_EXTENSION]
        for input_file in context.files:
            special_file_type = next((suffix for suffix in special_file_suffixes if input_file.endswith(suffix)), None)

            if special_file_type and len(context.files) > 1:
                raise Util.CertoraUserInputError(
                    f"No other files are allowed with a file of type {special_file_type}")

    if (
        not Attrs.is_concord_app() and
        not any([context.is_verify, context.is_bytecode,
                 context.equivalence_contracts, special_file_type]) and not context.build_only):
        raise Util.CertoraUserInputError("You must use 'verify' when running the Certora Prover")

def find_matching_contracts(context: CertoraContext, pattern: str) -> List[str]:
    result = []
    for contract in context.contract_to_file:
        if fnmatch.fnmatch(contract, pattern):
            result.append(context.contract_to_file[contract])
    return result

def find_matching_files(context: CertoraContext, pattern: str) -> List[str]:
    result = []

    for path in context.file_paths:
        if glob.globmatch(path, pattern, flags=glob.GLOBSTAR):
            result.append(path)
    return result

def check_map_attributes(context: CertoraContext) -> None:

    map_attrs = Attrs.EvmAttributes.all_map_attrs()
    for map_attr_name in map_attrs:
        if map_attr_name.endswith(Util.MAP_SUFFIX):
            attr_prefix = map_attr_name[:-len(Util.MAP_SUFFIX)]
        else:
            raise RuntimeError(f"check_map_attributes: {map_attr_name} does not ends with _map")
        map_attr = getattr(context, map_attr_name, None)
        if not map_attr:
            continue
        base_attr = getattr(context, attr_prefix, None)
        # check that both XXX and XXX_map were not set, for boolean attributes where the default is False
        # we also check the map value was not set to False explicitly in the conf file
        if base_attr and map_attr and not (base_attr is False and context.conf_options.get(base_attr) is not None):
            raise Util.CertoraUserInputError(f"You cannot use both '{attr_prefix}' and '{map_attr_name}' arguments")
        if not isinstance(map_attr, OrderedDict):
            raise RuntimeError(f"`map_attr` is not an ordered dictionary, got {map_attr}")

        # will check that all the contract files are matched
        file_list: Dict[str, bool] = {path: False for path in context.file_paths}

        for key, value in map_attr.items():
            pattern = key.split(':')[0]  # ignore the contract part
            if Path(pattern).suffix == "":
                for path in find_matching_contracts(context, pattern):
                    file_list[path] = True
            else:
                for path in find_matching_files(context, pattern):
                    file_list[path] = True

        none_keys = [k for k, v in file_list.items() if v is False]
        if none_keys:
            raise Util.CertoraUserInputError(f"The following files are not matched in {map_attr_name}: {none_keys}")


def check_parametric_contracts(context: CertoraContext) -> None:
    """
    Checks that all contracts in --parametric_contracts exist
    @param context: a namespace containing command line arguments
    @raises CertoraUserInputError if contract in --parametric_contracts does not exist
    """
    if context.parametric_contracts is None:
        return
    unfound_contracts = set(context.parametric_contracts) - set(context.contracts)
    if unfound_contracts:
        raise Util.CertoraUserInputError(
            f"Cannot find parametric contracts {unfound_contracts} in the project contract list: {context.contracts}")


def check_autofinder_settings(context: CertoraContext) -> None:
    if context.disable_internal_function_instrumentation and context.assert_autofinder_success:
        raise Util.CertoraUserInputError("'disable_internal_function_instrumentation' and 'assert_autofinder_success'"
                                         " cannot be both set")


def check_rule(context: CertoraContext) -> None:
    """
    Checks that we do not use --rule or --exclude_rule unless --verify or --bytecode_spec were set
    @param context: a namespace containing command line arguments
    @raises CertoraUserInputError when the user specifies a rule when not in verify or bytecode_spec modes
    """
    if context.rule or context.exclude_rule or context.split_rules:
        if not context.verify and context.bytecode_spec is None:
            raise Util.CertoraUserInputError(
                "rules flag/attributes such as --rule, --exclude_rule or --split_rules are only supported "
                "when 'verify' or 'bytecode_spec' are set")


def check_optional_jar_flags_consistency(context: CertoraContext) -> None:
    """
    This function checks if there are any optional JAR flags set in the given Certora context
    that conflict with `prover_args`, and raises an error if such conflicts are found.

    Args:
        context (CertoraContext): The Certora context containing configuration settings.

    Raises:
        CertoraUserInputError: If conflicts are found between optional JAR flags and `prover_args`.
    """
    attrs_with_jar_flags = Ctx.collect_args_with_jar_flags(context)
    for attr in attrs_with_jar_flags:
        if attr.temporary_jar_invocation_allowed:
            jar_flag = attr.jar_flag
            if context.prover_args and any([jar_flag in arg for arg in context.prover_args]):
                raise Util.CertoraUserInputError(
                    f"Cannot use both `{attr.get_conf_key()}` and {jar_flag} in `prover_args`. "
                    f"It is recommended to remove the flag from `prover_args`")


def check_no_pretty_quotes(args_list: List[str]) -> None:
    """
    :param args_list: A list of CL arguments
    :raises CertoraUserInputError if there are wrong quotation marks “ in use (" are the correct ones)
    """
    for arg in args_list:
        if '“' in arg:
            raise Util.CertoraUserInputError('Please replace “ with " quotation marks')


def __suggest_contract_name(err_msg: str, contract_name: str, all_contract_names: Set[str],
                            contract_to_file: Dict[str, str]) -> None:
    err_str = err_msg
    suggestions = Util.get_closest_strings(contract_name, list(all_contract_names), max_suggestions=1)

    if len(suggestions) == 1:
        suggested_contract = suggestions[0]
        err_str = f'{err_str}. Maybe you meant contract {suggested_contract} ' \
                  f'(found in file {contract_to_file[suggested_contract]})?'
    err_str += ' \nNote: To specify a contract in a differently-named sol file, you can ' \
               'provide the contract name explicitly, ie: certoraRun sol_file.sol:XYZcontract ' \
               '--verify XYZcontract:spec_file.spec'

    """
    Why do we raise from None?
    We run this function from an except block. We explicitly want to discard the context of the exception caught in the
    wrapping except block. If we do not discard the previous exception context, we see the following confusing pattern:
        "During handling of the above exception, another exception occurred:"
    """
    raise Util.CertoraUserInputError(err_str) from None  # ignore prev exception context


def check_conflicting_link_args(context: CertoraContext) -> None:
    """
    Detects contradicting definitions of slots in link and throws.
    DOES NOT check for file existence, format legality, or anything else.
    We assume the links contain no duplications.
    @param context: A context object, where context.link includes a list of strings that are the link arguments
    @raise CertoraUserInputError if a slot was given two different definitions
    """
    pair_list = itertools.permutations(context.link, 2)
    for pair in pair_list:
        link_a = pair[0]
        link_b = pair[1]
        slot_a = link_a.split('=')[0]
        slot_b = link_b.split('=')[0]
        if slot_a == slot_b:
            raise Util.CertoraUserInputError(f"slot {slot_a} was defined multiple times: {link_a}, {link_b}")


def check_conflicting_branch_and_hash(context: CertoraContext) -> None:
    """
    A run can be sent to either a specific branch or a specific commit hash, but never both
    @param context: A context object
    @raise CertoraUserInputError if both a branch and a specific commit hash were specified
    """
    commit_hash = context.commit_sha1
    if commit_hash:
        staging_branch = context.prover_version
        if context.local:
            raise Util.CertoraUserInputError("Cannot run on a specific commit in local runs. ")
        elif Ctx.is_staging(context) and bool(staging_branch):
            raise Util.CertoraUserInputError(f"Cannot run on both a specific branch {staging_branch} "
                                             f"and a specific commit {commit_hash}")


def validate_certora_key() -> str:
    f"""
    Checks that the environment variable {KEY_ENV_VAR} is legal and returns a valid Certora key.
    If the environment variable {KEY_ENV_VAR} has a legal value, returns it.
    @raise RuntimeError if {KEY_ENV_VAR} is undefined, empty, or has an illegal value.
    """
    key = os.environ.get(KEY_ENV_VAR, "")
    if not key:
        raise Util.CertoraUserInputError(f"The environment variable {KEY_ENV_VAR} does not contain a Certora key.\n"
                                         f"To get a free Certora key please visit {Util.KEY_SIGNUP_URL}")

    if not re.match(r'^[0-9A-Fa-f]+$', key):  # checks if the key is a hexadecimal number (without leading 0x)
        raise Util.CertoraUserInputError(f"environment variable {KEY_ENV_VAR} has an illegal value")
    if not len(key) in Util.LEGAL_CERTORA_KEY_LENGTHS:
        raise Util.CertoraUserInputError(f"environment variable {KEY_ENV_VAR} has an illegal length")
    return key

def check_files_input(file_list: List[str]) -> None:
    """
    Verifies that correct input was inserted as input to files.
    As argparser verifies the files exist and the correctness of the format, we only check if only a single operation
    mode was used.
    The allowed disjoint cases are:
    1. Use a single .conf file
    2. Use a single .tac file
    3. Use a single .so file
    4. Use a single .wasm file
    5. Use any number of [contract.sol:nickname ...] (at least one is guaranteed by argparser)
    @param file_list: A list of strings representing file paths
    @raise CertoraUserInputError if more than one of the modes above was used
    """
    num_files = len(file_list)
    if num_files > 1:  # if there is a single file, there cannot be a mix between file types
        for file in file_list:
            if '.tac' in file:
                raise Util.CertoraUserInputError(f'The tac file {file} cannot be accompanied with other files')
            if '.conf' in file:
                raise Util.CertoraUserInputError(
                    f'The conf file {file} cannot be accompanied with other files')
            if file.endswith(Util.SOLANA_EXEC_EXTENSION):
                raise Util.CertoraUserInputError(f'The Solana file {file} cannot be accompanied with other files')
            if file.endswith(Util.SOROBAN_EXEC_EXTENSION):
                raise Util.CertoraUserInputError(f'The Soroban file {file} cannot be accompanied with other files')


def set_attr_default(context: CertoraContext, attr_name: str, ci_value: str, default_value: str) -> None:
    if getattr(context, attr_name, None) is None:
        if Util.is_ci_or_git_action():
            setattr(context, attr_name, ci_value)
        else:
            setattr(context, attr_name, default_value)


def mode_has_spec_file(context: CertoraContext) -> bool:
    return not (context.is_tac or context.is_equivalence)


def to_relative_paths(paths: Union[str, List[str]]) -> Union[str, List[str]]:
    cwd = os.getcwd()

    if isinstance(paths, str):
        return os.path.relpath(paths, cwd)

    if isinstance(paths, list):
        return [os.path.relpath(path, cwd) for path in paths]

def set_bytecode_relative_paths(context: CertoraContext) -> None:
    for attr in [Attrs.EvmProverAttributes.BYTECODE_JSONS.get_conf_key(),
                 Attrs.EvmProverAttributes.BYTECODE_SPEC.get_conf_key()]:
        value = getattr(context, attr, None)
        if value:
            setattr(context, attr, to_relative_paths(value))
