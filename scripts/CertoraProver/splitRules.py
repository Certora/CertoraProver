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
import re
from pathlib import Path
import subprocess
import tempfile
import uuid

from typing import List, Set, Optional

import CertoraProver.certoraContext as Ctx
import CertoraProver.certoraContextAttributes as Attrs
import CertoraProver.certoraApp as App
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util

scripts_dir_path = Path(__file__).parent.resolve()
sys.path.insert(0, str(scripts_dir_path))

split_rules_logger = logging.getLogger("split_rules")

def update_msg(msg: str, rule_str: str) -> str:
    pattern = r"\(Rule\(s\): .*?\)$"  # Matches "(Rule(s): some text )" at the end of msg

    if re.search(pattern, msg):
        return re.sub(pattern, f" (Rule(s): {rule_str})", msg)
    else:
        return f"{msg} (Rule(s): {rule_str})"

class SplitRulesHandler():
    context: CertoraContext
    all_rules: Optional[Set[str]] = None  # all rules in the spec file (from Typechecker.jar --listRules)
    split_rules: Optional[Set[str]] = None  # all rules that should be run separately (based on --split_rules)
    rest_rules: Optional[Set[str]] = None  # all rules that should not be run separately ( all_rules - split_rules)

    def __init__(self, context: CertoraContext):
        if not context:
            raise ValueError("SplitRulesHandler: context must be set")
        SplitRulesHandler.context = context

    def generate_runs(self) -> int:
        """
        get the split rules and the rest rules, call certoraRun with the appropriate --rule
        :return: 1 if some runs failed 0 if all runs succeeded
        """
        self.all_rules = self.get_cvl_rules()
        assert len(self.all_rules) > 0, "generate_runs: all rules were filtered out"
        self.split_rules = self.get_cvl_rules(True)
        self.rest_rules = self.all_rules - self.split_rules
        return self.run_commands()

    def get_cvl_rules(self, split_rules: bool = False) -> Set[str]:
        """
        getting cvl rules by calling Typechecker.jar with the -listRules option. The flags -buildDirectory and
        -excludeRule must be sent (if exist) since they are needed for getting the correct rule list
        :param split_rules:
        :return:
        """
        def jar_list_value(list_attr: List[str]) -> str:
            return ','.join(list_attr)

        with tempfile.NamedTemporaryFile("r", dir=Util.get_build_dir()) as tmp_file:
            args = ["-listRules", tmp_file.name]

            if self.context.exclude_rule:
                args += ['-excludeRule', jar_list_value(self.context.exclude_rule)]

            if not split_rules and self.context.rule:
                args += ['-rule',  jar_list_value(self.context.rule)]
            elif split_rules and self.context.split_rules:
                args += ['-rule', jar_list_value(self.context.split_rules)]

            try:
                Ctx.run_local_spec_check(False, self.context, args, print_errors=False)
                lines = tmp_file.read().split("\n")
                return set(lines)

            except Exception as e:
                raise Util.CertoraUserInputError(f"Failed to get {'split ' if split_rules else ''}rules\n{e}")

    def run_commands(self) -> int:
        rule_flag = Attrs.EvmProverAttributes.RULE.get_flag()
        split_rules_flag = Attrs.EvmProverAttributes.SPLIT_RULES.get_flag()
        msg_flag = Attrs.CommonAttributes.MSG.get_flag()

        # it is important to use the cache, when the difference between the runs is only the rules that apply
        build_cache_flag = Attrs.EvmProverAttributes.BUILD_CACHE.get_flag()

        group_id_flag = Attrs.EvmProverAttributes.GROUP_ID.get_flag()
        disable_local_typechecking_flag = Attrs.EvmProverAttributes.DISABLE_LOCAL_TYPECHECKING.get_flag()

        def remove_rule_flags_from_cli() -> List[str]:
            # any --rule flag should be removed from CLI during splitting, since it is set during the split
            new_cli = []
            skip = False
            for item in self.context.args_list:
                if item.startswith(rule_flag) or item.startswith(split_rules_flag) or item.startswith(msg_flag):
                    skip = True
                elif item.startswith('--') and skip:
                    skip = False
                if not skip:
                    new_cli.append(item)
            return new_cli

        def get_cmd() -> str:
            """
            set executable for the split, if called from command line then it is the first string in argv (prover_cmd)
            if called as library then if running in local mode we use certoraRun.py otherwise certoraRun (from package)
            :return:
            """
            assert self.context.app == App.EvmApp, "Split rules is supported only for EVM apps"
            if hasattr(self.context, 'prover_cmd'):
                return self.context.prover_cmd
            if self.context.local:
                return Util.CERTORA_RUN_SCRIPT
            return Util.CERTORA_RUN_APP

        def generate_prover_calls() -> List[List[str]]:
            # generate the command line for the runs: a run for each split rule, and another run collecting the rest
            # of the rules
            cli_commands = []
            args = remove_rule_flags_from_cli()
            if not self.context.group_id:
                self.context.group_id = str(uuid.uuid4())

            if not self.context.msg:
                self.context.msg = ''

            cmd = [get_cmd()] + args + [group_id_flag, self.context.group_id, disable_local_typechecking_flag,
                                        build_cache_flag, split_rules_flag]

            if self.split_rules:
                for rule in self.split_rules:
                    cli_commands.append(cmd + [rule_flag, rule, msg_flag, update_msg(self.context.msg, rule)])
            if self.rest_rules:
                cli_commands.append(cmd + [rule_flag] + list(self.rest_rules) +
                                    [msg_flag, update_msg(self.context.msg, "rest of the rules")])
            return cli_commands
        # end of run_commands() nested functions

        prover_calls = generate_prover_calls()
        if self.context.test == str(Util.TestValue.AFTER_RULE_SPLIT):
            raise Util.TestResultsReady(prover_calls)

        processes = []
        # Start all processes
        for command in prover_calls:
            split_rules_logger.debug(f"Running {' '.join(command)}")
            processes.append(subprocess.Popen(command))

        # Wait for all processes to complete and collect return codes
        return_codes = [p.wait() for p in processes]

        return_value = 0
        for i, return_code in enumerate(return_codes):
            if return_code != 0:
                split_rules_logger.debug(f"Process {i} failed with exit code {return_code}")
                return_value = 1

        return return_value
