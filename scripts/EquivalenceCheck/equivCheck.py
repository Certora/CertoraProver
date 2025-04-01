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

import argparse
import os
import shutil
import sys
import json
from pathlib import Path
from argparse import Namespace, ArgumentParser
from typing import List, Optional

# Credit for this work goes to Nick and Netanel.

scripts_dir_path = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(scripts_dir_path))
from CertoraProver.certoraBuild import InputConfig, CertoraBuildGenerator, FunctionSig, VY
from CertoraProver.certoraVerifyGenerator import CertoraVerifyGenerator
from CertoraProver.certoraContext import get_args
from certoraRun import run_certora

LOG_PATH = Path('EqCheck_log.log')
SANITY_PATH = Path('sanity.spec')
DEF_TEMPLATE_PATH = Path('Eq_template.spec')
MC_TEMPLATE_PATH = Path('Eq_mc_template.spec')
MC_NO_OUT_TEMPLATE_PATH = Path('Eq_mc_no_out_template.spec')
DEFAULT_CONF_PATH = Path("Eq_default.conf")
SANITY_CONF_PATH = Path("Eq_sanity.conf")
TMP_SANITY_CONF = Path("tmp.conf")

VERIFY = "verify"
SOLC = "solc"
SOLC_MAP = "solc_map"
COMPILER_MAP = "compiler_map"
FILES = "files"
FUNCTIONS = "functions"
CONTRACTS = "contracts"
PROVER_ARGS = "prover_args"
OPTIMISTIC_FALLBACK_SETTING = "optimistic_fallback"


class EquivalenceChecker:

    def __init__(self) -> None:
        self.funcs: List[List[FunctionSig]] = []
        self.conf_path: Optional[Path] = None
        self.compiler_map: Optional[Path] = None
        self.solcs: List[str] = []
        self.files: List[Path] = []
        self.is_multicontract = False
        self.path: Optional[Path] = None
        self.conf_mode = False
        self.functions: List[str] = []
        self.contracts: List[str] = []
        self.args: Optional[Namespace] = None
        self.sigs: List[FunctionSig] = []

    def run(self, args: argparse.Namespace) -> None:
        self.path = Path(__file__).parent.resolve()

        # windows handling may come with a later version
        if sys.platform == 'win32':
            print("Windows is not yet supported")
            sys.exit(1)

        self.args = args
        self.contracts = args.contracts
        self.functions = args.functions
        self.conf_mode = args.conf_mode

        if self.conf_mode:
            self.read_input_conf_file()
        else:
            self.files = args.files
            self.solcs = args.solcs

        if self.functions[0] == self.functions[1] and \
                self.contracts[0] == self.contracts[1] and self.files[0] == self.files[1]:
            # oops, looks like a potentially trivial run, but what if _compilers_ are different?
            if self.solcs[0] == self.solcs[1]:
                print("TRIVIAL RUN: You are comparing a function to itself. Please check your arguments \
                    and submit a report to Certora if this is a mistake")
            else:
                # compilers are not equal. let's duplicate one file...
                file_to_dup = Path(self.files[1])
                # todo: remove this new file after all is done - but currently we do not remove anything anyway...
                new_file = file_to_dup.parent / f"{file_to_dup.stem}2{file_to_dup.suffix}"
                shutil.copy(file_to_dup, new_file)
                self.files[1] = new_file
                # in Vyper, the name of the file is also the name of the contract, so need to update here
                if new_file.suffix == VY:
                    self.contracts[1] = new_file.stem

        self.build_fun_signatures()

        # find the signature of functionA and functionB
        self.sigs = list()
        self.sigs.append(grab_func_from_list(self.functions[0], self.funcs[0]))
        self.sigs.append(grab_func_from_list(self.functions[1], self.funcs[1]))

        # validate signatures
        self.validate_signatures()

        # generate spec files from template
        self.is_multicontract = (self.contracts[0] != self.contracts[1]) or (self.files[0] != self.files[1])
        spec = self.generate_spec()
        print(f'Certora spec file generated! Check it out at {spec}')

        conf = self.generate_conf_file(spec)

        run_certora([str(conf)])
        print("Certora Equivalence Check successfully generated!")
        print("Go check the link above to track the progress of your job and see results")

    def build_fun_signatures(self) -> None:
        if self.path is None:
            raise RuntimeError("Expected path to not be None.")
        for i in range(2):
            file_i = self.files[i]
            contract_i = self.contracts[i]
            solc_i = self.solcs[i]
            with (self.path / SANITY_CONF_PATH).open() as sanity_conf:
                contents = json.load(sanity_conf)
                contents[FILES].append(str(file_i))
                contents[SOLC] = solc_i
                contents[VERIFY] = f'{contract_i}:{self.path / SANITY_PATH}'
                contents["build_only"] = True
            tmp_conf = self.path / TMP_SANITY_CONF
            with tmp_conf.open('w') as temp_sanity:
                json.dump(contents, temp_sanity, indent=4)
            context = get_args([str(tmp_conf)])
            config = InputConfig(context)
            cfb = CertoraBuildGenerator(config, context)
            certora_verify_generator = CertoraVerifyGenerator(context)
            certora_verify_generator.copy_specs()
            certora_verify_generator.dump()
            cfb.build(certora_verify_generator)
            os.remove(tmp_conf)
            self.funcs.append(cfb.collect_func_source_code_signatures_source(file_i, contract_i, solc_i))

    def read_input_conf_file(self) -> None:
        if self.args is None:
            raise RuntimeError("Expected args to not be None.")
        self.conf_path = Path(self.args.conf_path).resolve()
        with self.conf_path.open() as conf_file:
            contents = json.load(conf_file)
            files = contents[FILES]
            if len(files) != 2:
                print("INPUT ERROR: must provide two files for reference")
                sys.exit(1)
            self.files = []
            for file in files:
                # if the conf file contains absolute path already then we use it
                if Path(file).exists():
                    self.files.append(file)
                # otherwise append the relative path to the conf file location to create absolute path
                else:
                    self.files.append(resolve_file(self.conf_path.parent / file))
            # case where either or both of these will be in the conf file
            if not self.functions and FUNCTIONS in contents:
                # if the user has functions in the conf file but manually inputs different ones
                # we want to use the ones they input via CLI
                self.functions = contents[FUNCTIONS]
            if not self.contracts and CONTRACTS in contents:
                # same as above but for contracts
                self.contracts = contents[CONTRACTS]
            if not self.contracts:
                print('INPUT ERROR: must provide at least two contracts via conf or CLI. Please use "contracts" \
                      in the conf file')
                sys.exit(1)
            if not self.functions:
                print('INPUT ERROR: must provide functions via conf or CLI. Please use "functions" \
                      in the conf file')
                sys.exit(1)
            if len(self.contracts) != 2:
                print("INPUT ERROR: not enough contracts provided. Must supply at least 2 contracts")
                sys.exit(1)
            if len(self.functions) != 2:
                print("INPUT ERROR: must provide at least 2 seperate functions")
                sys.exit(1)
            if SOLC in contents:
                self.solcs = [contents[SOLC], contents[SOLC]]
            elif SOLC_MAP in contents or COMPILER_MAP in contents:
                self.compiler_map = contents[SOLC_MAP] or contents[COMPILER_MAP]
            else:
                print("INPUT ERROR: conf file must contain either a solc or solc map")
                sys.exit(1)

    def generate_spec(self) -> Path:
        """
            Creates the spec to be run from the template
            :return: Path to spec built
        """
        if self.path is None:
            raise RuntimeError("Expected path to not be None.")
        output_dir = Path(self.files[0]).parent
        # parameter_list = self.sigs[0][1]
        outputs = self.sigs[0][2]

        # create section declaring output variables
        outputs_dec = ""
        outputs_in_a = ""
        outputs_in_b = ""
        outputs_compare = ""
        functionA = self.functions[0]
        functionB = self.functions[1]
        contractA = self.contracts[0]
        contractB = self.contracts[1]
        # if functionA == functionB:
        #     functionB = functionA + "B"
        for index, var in enumerate(outputs):
            var_a = f'{functionA}_{contractA}_{var.replace("[]", "")}_out{index}'
            var_b = f'{functionB}_{contractB}_{var.replace("[]", "")}_out{index}'

            outputs_dec += f'{var} {var_a};\n    '
            outputs_dec += f'{var} {var_b};\n    '
            if index == 0:
                outputs_in_a += f'{var_a}'
                outputs_in_b += f'{var_b}'
            else:
                outputs_in_a += f', {var_a}'
                outputs_in_b += f', {var_b}'
            outputs_compare += f'assert({var_a} == {var_b});\n    '

        # read in the template

        if self.is_multicontract:
            if len(outputs) > 0:
                temp_path = self.path / MC_TEMPLATE_PATH
            else:
                temp_path = self.path / MC_NO_OUT_TEMPLATE_PATH
        else:
            temp_path = self.path / DEF_TEMPLATE_PATH
        with temp_path.resolve().open() as template:
            spec = template.read()

        # fill in the template
        spec = spec.replace("CONTRACTB", self.contracts[1])
        spec = spec.replace("<Fa>", self.functions[0])
        spec = spec.replace("<Fb>", self.functions[1])
        spec = spec.replace("<Ca>", contractA)
        spec = spec.replace("<Cb>", contractB)
        spec = spec.replace("OUTPUTS_DEC", outputs_dec.strip())
        spec = spec.replace("OUTPUTS_IN_A", outputs_in_a)
        spec = spec.replace("OUTPUTS_IN_B", outputs_in_b)
        spec = spec.replace("COMPARE_OUTPUTS", outputs_compare.strip())

        # save spec as a new file
        file_name = f"{self.functions[0]}_{contractA}_to_{self.functions[1]}_{contractB}_equivalence.spec"
        spec_file_path = output_dir / file_name
        with spec_file_path.open('w') as spec_file:
            spec_file.write(spec)

        return spec_file_path

    def validate_signatures(self) -> None:
        """
            check function signature for the proper inputs
        """
        stateless_identifiers = ["view", "pure"]
        sig_a = self.sigs[0]
        sig_b = self.sigs[1]
        if sig_a[1] != sig_b[1]:
            print("INPUT ERROR: functions must have the same input parameters")
            sys.exit(1)

        if sig_a[2] != sig_b[2]:
            print("INPUT ERROR: function must have the same output parameters")
            sys.exit(1)

        if sig_a[3] == "view" and sig_b[3] == "view" and \
                (self.contracts[0] != self.contracts[1] or self.files[0] != self.files[1]):
            print("INPUT ERROR: cannot yet compare two view functions from two separate contracts")
            sys.exit(1)

        if (sig_a[3] == "pure" and sig_b[3] == "view") or (sig_a[3] == "view" and sig_b[3] == "pure"):
            print("INPUT ERROR: cannot compare a view function to a pure function")
            sys.exit(1)

        if (sig_a[3] not in stateless_identifiers and sig_b[3] in stateless_identifiers) or \
                (sig_b[3] not in stateless_identifiers and sig_a[3] in stateless_identifiers):
            print("INPUT ERROR: cannot compare a stateful function with a stateless one")
            sys.exit(1)

    def generate_conf_file(self, spec: Path) -> Path:
        if self.path is None or self.args is None:
            raise RuntimeError("Expected path and args to not be None.")
        if self.conf_mode:
            if self.conf_path is None:
                raise RuntimeError("Running in conf mode but conf_path is not set.")
            # set up the user defined conf file to run
            with self.conf_path.open() as conf_file:
                contents = json.load(conf_file)
                contents['rule_sanity'] = "basic"
                contents[FILES] = list()
                for file in self.files:
                    contents['files'].append(str(file))
        else:
            # update default conf_file for running
            with (self.path / DEFAULT_CONF_PATH).open() as conf_file:
                contents = json.load(conf_file)
                for file in self.files:
                    contents[FILES].append(str(file))
                if self.args.bitvector is True:
                    contents.setdefault(PROVER_ARGS, []).append(["-smt_bitVectorTheory true"])
                if self.args.test_mode is True:
                    if 'prover_version' in contents:
                        del contents['prover_version']
                    if 'server' in contents:
                        del contents['server']
                    contents['tool_output'] = 'out.json'
                if self.solcs[0] == self.solcs[1]:
                    contents[SOLC] = self.solcs[0]
                else:
                    contents[COMPILER_MAP] = {self.contracts[0]: self.solcs[0], self.contracts[1]: self.solcs[1]}
                contents['msg'] = f'EquivalenceCheck of {self.functions[0]} and {self.functions[1]}'
        contents[VERIFY] = f'{self.contracts[0]}:{spec}'
        contents[OPTIMISTIC_FALLBACK_SETTING] = True

        # set prover_args. optimisticFallback is always set for consistent send() operations
        contents.setdefault(PROVER_ARGS, [])
        if self.is_multicontract:
            contents[PROVER_ARGS].extend([
                "-assumeContractsHaveUniqueAddresses false",  # to allow setting same address to both
                "-enableStorageSplitting false"  # to allow opcode hooks on sload/sstore
            ])

        conf_file_path = spec.parent / (spec.stem + '.conf')
        with conf_file_path.open('w') as conf_file:
            json.dump(contents, conf_file, indent=4)
        print(f'Conf file generated: {conf_file_path}')
        return conf_file_path


def grab_func_from_list(target: str, funcs: List[FunctionSig]) -> FunctionSig:
    for func in funcs:
        if func[0] == target:
            return func
    print(f'Could not find function {target}, make sure your function is not internal or in a library')
    sys.exit(1)


def resolve_file(file: str) -> Path:
    """
    resolve files, raises an error more cleanly if the file is wrong
    :param file: to be resolved
    :return: a resolved path
    """
    try:
        out = Path(file).resolve(strict=True)
        return out
    except FileNotFoundError:
        print(f'Could not find file {file}, \
              make sure you pass the relative path from the directory being run in')
        sys.exit(1)


def split_args(args: argparse.Namespace) -> argparse.Namespace:
    """
        splits the colon separated CLI args into a namespace used by the EquivalenceChecker
    """
    args.conf_mode = 'conf_path' in args
    if args.conf_mode:
        if args.functionA and args.functionB:
            if ':' in args.functionA and ':' in args.functionB:
                split_a = str(args.functionA).split(':')
                split_b = str(args.functionB).split(':')
                args.contracts = [split_a[0], split_b[0]]
                args.functions = [split_a[1], split_b[1]]
                args.solcs = None
                args.files = None
            elif ':' in args.functionA or ':' in args.functionB:
                print("INPUT ERROR: both arguments to CertoraEqCheck should have the same number of components \
                      separated by semicolons.")
                sys.exit(1)
            else:
                # set these to None for now and fill them in once we parse the conf file
                args.contracts = None
                args.functions = None
        elif args.functionA or args.functionB:
            print('INPUT ERROR: must give arguments for both contract/functions')
            sys.exit(1)
        else:
            args.contracts = None
            args.functions = None
    else:
        split_a = args.arg_a.split(':')
        split_b = args.arg_b.split(':')
        if len(split_a) < 3:
            print(f'INPUT ERROR: wrong input format given, please use file:function:solc for each function \
                  \n Input: {args.arg_a}')
            sys.exit(1)
        if len(split_b) < 3:
            print(f'INPUT ERROR: wrong input format given, please use file:function:solc for each function \
                  \n Input: {args.arg_b}')
            sys.exit(1)
        if len(split_a) == 4:
            args.file_a, args.contract_a, args.function_a, args.solc_a = split_a
        else:
            args.file_a, args.function_a, args.solc_a = split_a
            args.contract_a = Path(args.file_a).stem

        if len(split_b) == 4:
            args.file_b, args.contract_b, args.function_b, args.solc_b = split_b
        else:
            args.file_b, args.function_b, args.solc_b = split_b
            args.contract_b = Path(args.file_b).stem

        args.files = [args.file_a, args.file_b]
        args.contracts = [args.contract_a, args.contract_b]
        args.solcs = [args.solc_a, args.solc_b]
        args.functions = [args.function_a, args.function_b]
    return args


def ext_equivcheck_entry_point() -> None:
    parser = ArgumentParser(
        prog='The Certora Equivalence Checker',
        usage=f'python3 {sys.argv[0]} def fileA:contractA:functionA:solcA fileB:contractB:functionB:solcB',
        description='A Certora tool for checking the equivalence of two pure Solidity functions.')

    # setup different modes of entering inputs
    subs = parser.add_subparsers()
    conf_parser = subs.add_parser('conf', help='--conf <path to conf file> <contract>:<functionA> \
                                                <contractB>:<functionB>')
    default_parser = subs.add_parser('def', help='<path to fileA>:<contractA>:<functionA>:<solcA>  \
                                                    <path to fileB>:<contractB>:<functionB>:<solcB>')

    # arguments for --conf
    conf_parser.add_argument('conf_path', help='relative path to your intended conf file')
    # optional positional arguments
    conf_parser.add_argument('functionA', nargs="?", help='usage Contract:Function - name of the first contract and \
                                                           function you are comparing')
    conf_parser.add_argument('functionB', nargs="?", help='usage Contract:Function - name of the second contract and \
                                                           function you are comparing')
    # arguments for --def
    default_parser.add_argument('arg_a', help='Usage: file:contract:function:solc - relative path of the first file \
                                                name of contract, name of function, solc executable name')
    default_parser.add_argument('arg_b', help='Usage: file:contract:function:solc - relative path of the second file \
                                                name of contract, name of function, solc executable name')

    default_parser.add_argument('--bitvector', type=bool, help='Usage: --bitvector true will run \
                                                               the prover in bitvector mode.')
    default_parser.add_argument('--test_mode', type=bool, help='run equivalence checker in test mode')

    args = split_args(parser.parse_args())
    EquivalenceChecker().run(args)
