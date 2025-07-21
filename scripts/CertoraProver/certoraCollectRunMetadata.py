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
import dataclasses
import json
from typing import Any, Dict, List, Optional
import subprocess
from datetime import datetime, timezone
import logging
from pathlib import Path
from copy import deepcopy
import Shared.certoraUtils as Utils

import sys
scripts_dir_path = Path(__file__).parent.resolve()  # containing directory
sys.path.insert(0, str(scripts_dir_path))

from CertoraProver.certoraContextClass import CertoraContext
import CertoraProver.certoraContextAttributes as Attrs

metadata_logger = logging.getLogger("metadata")


def collect_args_with_jar_flags() -> List[str]:
    return_array = []
    for attr in Attrs.get_attribute_class().attribute_list():
        if attr.jar_flag:
            return_array.append(f"{attr.jar_flag},{attr.name.lower()},{str(attr.arg_type)}")
    return return_array


def get_main_spec(context: CertoraContext) -> Optional[str]:
    return getattr(context, 'spec_file', None) or getattr(context, 'bytecode_spec', None)

# jsonify sets as lists
class MetadataEncoder(json.JSONEncoder):
    def default(self, obj: Any) -> Any:
        if isinstance(obj, set):
            return list(obj)
        if dataclasses.is_dataclass(obj):
            return dataclasses.asdict(obj)
        return json.JSONEncoder.default(self, obj)


class RunMetaData:
    """
    Carries information about a run of CVT.
    This includes
      - which arguments CVT was started with,
      - information about the state (snapshot) of the git repository that CVT was called in (we expect this to be the
        repository where the program and spec lie in that CVT was started on).

    arguments:
    raw_args -- arguments to `certoraRun.py`, basically python's sys.argv list
    conf -- configuration as processed by certoraConfigIO
    origin -- origin URL of the git repo
    revision -- commit hash of the currently checked-out revision
    branch -- branch name of the currently checked-out revision
    cwd_relative -- current working directory, relative to the root of the git repository
    dirty -- true iff the git repository has changes (git diff is not empty)
    main_spec -- the relative path to the main spec file that should be displayed by default at the web report
    conf_path -- the relative path form the cwd_relative to the configuration file
    group_id -- optional identifier for grouping this run
    java_version -- version of Java used during the run, if available
    python_version -- version of Python running the process
    certora_ci_client -- name of the CI client if available, derived from environment
    timestamp -- UTC timestamp when the run metadata was generated
    CLI_package_name -- name of the CLI package
    CLI_version -- version of the CLI
    jar_flag_info -- CLI attributes which are jarFlags
    """
    def __init__(self, raw_args: List[str], conf: Dict[str, Any], origin: str, revision: str,
                 branch: str, cwd_relative: Path, dirty: bool, main_spec: Optional[str],
                 conf_path: Optional[Path], group_id: Optional[str], java_version: str):
        self.raw_args = raw_args
        self.conf = conf
        self.origin = origin
        self.revision = revision
        self.branch = branch
        self.cwd_relative = cwd_relative
        self.dirty = dirty
        self.main_spec = main_spec
        self.conf_path = conf_path
        self.group_id = group_id
        self.python_version = ".".join(str(x) for x in sys.version_info[:3])
        self.java_version = java_version
        self.certora_ci_client = Utils.get_certora_ci_name()
        self.timestamp = str(datetime.now(timezone.utc).timestamp())
        _, self.CLI_package_name, self.CLI_version = Utils.get_package_and_version()
        self.jar_flag_info = collect_args_with_jar_flags()

    def __repr__(self) -> str:
        return (
            f" raw_args: {self.raw_args}\n"
            f" conf: {self.conf}\n"
            f" origin: {self.origin}\n"
            f" revision: {self.revision}\n"
            f" branch: {self.branch}\n"
            f" cwd_relative: {self.cwd_relative}\n"
            f" dirty: {self.dirty}\n"
            f" CLI_version: {self.CLI_version}\n"
            f" CLI_package_name: {self.CLI_package_name}\n"
            f" main_spec: {self.main_spec}\n"
            f" conf_path: {self.conf_path}\n"
            f" group_id: {self.group_id}\n"
            f" python_version: {self.python_version}\n"
            f" java_version: {self.java_version}\n"
            f" CertoraCI client: {self.certora_ci_client}\n"
            f" jar_flag_info: {self.jar_flag_info}\n"
        )

    @classmethod
    def dump_file(cls, data: dict) -> None:
        with Utils.get_certora_metadata_file().open("w+") as f:
            json.dump(data, f, indent=4, sort_keys=True, cls=MetadataEncoder)

    @classmethod
    def load_file(cls) -> dict:
        with Utils.get_certora_metadata_file().open() as f:
            return json.load(f)

    def dump(self) -> None:
        if self.__dict__:  # dictionary containing all the attributes defined for GitInfo
            try:
                dump_dict = deepcopy(self.__dict__)
                # Casting from path to string
                dump_dict['cwd_relative'] = str(self.cwd_relative)
                if self.conf_path:
                    dump_dict['conf_path'] = str(self.conf_path)
                self.dump_file(dump_dict)
            except Exception as e:
                print(f"failed to write meta data file {Utils.get_certora_metadata_file()}")
                metadata_logger.debug('encountered an error', exc_info=e)


def improvise_cwd_relative(cwd: Path) -> Path:
    """
    Computes the metadata entry called `cwd_relative`. This entry indicates the working directory of the toolrun
    relative to the repository root of the git repo that the test lies in. Normally this is computed using git calls.
    This method is a fallback for when there is no `git` executable, or the current working dir is not in a git working
    copy.
    It looks for standard case for our internal regression tests, namely `CertoraProver/Test'.
    :param cwd: working directory of the current tool run.
    :return:
    """
    cwd_abs = cwd.resolve()
    # Find the index of "Test" directory in the path
    test_index = str(cwd_abs).find("Test")
    if test_index != -1:  # If "Test" directory is found
        # Extract the path from "Test" directory onwards
        relative_path = str(cwd_abs)[test_index:]
        return Path(relative_path)
    else:
        return cwd_abs  # Return the absolute path if "Test" directory is not found

def build_conf_path_relative(relative_cwd: Path, context: CertoraContext) -> Optional[Path]:
    """
    Compute the path to the conf file from the root directory
    :param relative_cwd: relative path to conf dir from root dir
    :param context: contains path to conf file from execution command
    :return: relative path to conf file from root
    """
    if hasattr(context, "conf_file"):
        conf_file_path = Path(context.conf_file)
        return relative_cwd / Path(conf_file_path.stem + conf_file_path.suffix)
    else:
        return None


def collect_run_metadata(wd: Path, raw_args: List[str], context: CertoraContext) \
        -> RunMetaData:

    # This is a temporary hotfix to fix a bug on windows. If git does not exist on client calls to to_relative()
    # cause exception and mess up paths
    if Utils.is_windows():
        return RunMetaData(raw_args=raw_args,
                           conf=context.conf_options,
                           origin="",
                           revision="",
                           branch="",
                           cwd_relative=wd,
                           dirty=True,
                           main_spec=None,
                           conf_path=None,
                           group_id=None,
                           java_version=context.java_version)

    # collect information about current git snapshot
    cwd_abs = wd.absolute()

    is_git_executable = False
    git_present_out = None
    try:
        git_present_out = subprocess.run(['git', '--version'], cwd=wd,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        is_git_executable = git_present_out.returncode == 0
    except Exception as e:
        metadata_logger.debug('error occurred when running git executable', exc_info=e)
    if not is_git_executable:
        metadata_logger.debug(f'no git executable found in {wd}, not collecting any repo metadata')
        if git_present_out:
            metadata_logger.debug(f'running git --version returned {git_present_out}')
        cwd_relative = improvise_cwd_relative(wd)
        conf_path = build_conf_path_relative(cwd_relative, context)
        return RunMetaData(raw_args=raw_args,
                           conf=context.conf_options,
                           origin="",
                           revision="",
                           branch="",
                           cwd_relative=cwd_relative,
                           dirty=True,
                           main_spec=get_main_spec(context),
                           conf_path=conf_path,
                           group_id=context.group_id,
                           java_version=context.java_version)

    try:
        sha_out = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=wd,
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        sha = sha_out.stdout.decode().strip()

        branch_name_out = subprocess.run(['git', 'rev-parse', '--abbrev-ref', 'HEAD'], cwd=wd,
                                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        branch_name = branch_name_out.stdout.decode().strip()

        origin_out = subprocess.run(['git', 'remote', 'get-url', 'origin'], cwd=wd,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        origin = origin_out.stdout.decode().strip()

        base_dir_out = subprocess.run(['git', 'rev-parse', '--show-toplevel'], cwd=wd,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        base_dir = base_dir_out.stdout.decode().strip()

        cwd_relative = cwd_abs.relative_to(base_dir)

        dirty_out = subprocess.run(['git', 'diff', '--shortstat'], cwd=wd,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        dirty = dirty_out.stdout.decode().strip() != ''

        conf_path = build_conf_path_relative(cwd_relative, context)
        data = RunMetaData(raw_args=raw_args,
                           conf=context.conf_options,
                           origin=origin,
                           revision=sha,
                           branch=branch_name,
                           cwd_relative=cwd_relative,
                           dirty=dirty,
                           main_spec=get_main_spec(context),
                           conf_path=conf_path,
                           group_id=context.group_id,
                           java_version=context.java_version)

        metadata_logger.debug(f' collected data:\n{str(data)}')

        return data
    except Exception as e:
        metadata_logger.debug('error occurred when running git executable', exc_info=e)
        cwd_relative = improvise_cwd_relative(wd)
        conf_path = build_conf_path_relative(cwd_relative, context)
        return RunMetaData(raw_args=raw_args,
                           conf=context.conf_options,
                           origin="",
                           revision="",
                           branch="",
                           cwd_relative=cwd_relative,
                           dirty=True,
                           main_spec=get_main_spec(context),
                           conf_path=conf_path,
                           group_id=context.group_id,
                           java_version=context.java_version)
