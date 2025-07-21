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

import itertools
import json
import os
import re
import uuid

import requests
import sys
import threading
import time
import zipfile
from pathlib import Path
from functools import lru_cache

scripts_dir_path = Path(__file__).parents[1]
sys.path.insert(0, str(scripts_dir_path))
from CertoraProver.certoraJobList import JobList
from CertoraProver.certoraExtensionInfo import ExtensionInfoWriter
from CertoraProver.certoraContextClass import CertoraContext
from Shared import certoraUtils as Util
import CertoraProver.certoraContextAttributes as Attrs
import CertoraProver.certoraContext as Ctx
from Shared import certoraValidateFuncs as Vf
from Mutate import mutateUtil as MutUtil

from typing import Optional, Dict, Any, List, Union, cast
from tqdm import tqdm

import logging

cloud_logger = logging.getLogger("cloud")

MAX_FILE_SIZE = 25 * 1024 * 1024
SOLANA_MAX_FILE_SIZE = 2 * MAX_FILE_SIZE
NO_OUTPUT_LIMIT_MINUTES = 15
MAX_POLLING_TIME_MINUTES = 150
LOG_READ_FREQUENCY = 10
MAX_ATTEMPTS_TO_FETCH_OUTPUT = 3
DELAY_FETCH_OUTPUT_SECONDS = 10
VERIFICATION_REQUEST_RETRIES = 5
VERIFICATION_REQUEST_SLEEP = 60

# error messages
CONNECTION_ERR_PREFIX = "Connection error:"
GENERAL_ERR_PREFIX = "An error occurred:"
SERVER_ERR_PREFIX = "Server Error:"
STATUS_ERR_PREFIX = "Error Status:"
TIMEOUT_MSG_PREFIX = "Request timed out."
VAAS_ERR_PREFIX = "Server reported an error:"

CONTACT_CERTORA_MSG = "please contact Certora on https://www.certora.com"

Response = requests.models.Response

FEATURES_REPORT_FILE = Path("featuresReport.json")

class EcoEnum(Util.NoValEnum):
    EVM = Util.auto()
    SOROBAN = Util.auto()
    SOLANA = Util.auto()
    SUI = Util.auto()

class ProductEnum(Util.NoValEnum):
    PROVER = Util.auto()
    RANGER = Util.auto()
    CONCORD = Util.auto()


class TimeError(Exception):
    """A custom exception used to report on time elapsed errors"""


def validate_version_and_branch(context: CertoraContext) -> None:
    """
    Gets the latest package version and compares to the local package version.
    If the major version is different - i.e. there is new breaking syntax, will raise an error.
    If a minor version is different, it just warns the user and recommends him to upgrade the package.

    If the Python package is a dev package (not the production package), checks if the user provided a branch or a
    commit sha, and if not raises an appropriate error.

    If there are problems when performing this check, skips it with a warning. Possible reasons:
    1. Connectivity problems
    2. A Certora installed package is not found
    3. Unexpected version format, either for the local package or the remote
    4. If the local version is more advanced than the remote version

    :param context: context ojbect
    :raises:
        CertoraUserInputError if:
         - The local package version is not compatible with the latest package version.
         - The local package is a dev package and no branch name was given
    """
    installed, package_name, version = Util.get_package_and_version()
    if not installed:
        if not (context.prover_version or context.commit_sha1):
            raise Util.CertoraUserInputError(
                "No package is installed. You must use the certoraRun script with "
                "'prover_version' BRANCH "
                "or provide a specific 'commit_sha1'.")
        else:
            return

    if not re.search(r"^\d+\.\d+\.\d+$", version):  # Version should be a string in X.Y.Z format
        """
        If the local version was not found, the value of `version` is an error message. prints it
        """
        cloud_logger.warning(f"{package_name}")
        return
    try:
        distribution_url = f"https://pypi.org/pypi/{package_name}/json"
        response = requests.get(distribution_url, timeout=10)
        out = response.json()  # raises ValueError: No JSON object could be decoded
        latest = out['info']['version']
        if "." in latest and "." in version:
            # below lines raise ValueError: invalid literal for int() with base 10
            remote_main, remote_sub, remote_patch = [int(x) for x in latest.split(".")]
            local_main, local_sub, local_patch = [int(x) for x in version.split(".")]

            installation_cmd = f"pip install {package_name} --upgrade\n" \
                               f"or\n" \
                               f"pip3 install {package_name} --upgrade"

            must_upgrade_msg = \
                f"Incompatible package {package_name} version {version} with the latest version {latest}." \
                f" Please upgrade by running:\n" \
                f"{installation_cmd}"

            recommended_upgrade_msg = \
                f"You are using {package_name} version {version}; however, version {latest} is available." \
                f" It is recommended to upgrade by running: {installation_cmd}"

            beta_upgrade_message = \
                f"You are using {package_name} version {version}; however, version {latest} is available. " \
                f"You must upgrade by running: {installation_cmd}"

            compare_cloud_versions_check(local_main, local_sub, local_patch, remote_main, remote_sub, remote_patch,
                                         package_name, recommended_upgrade_msg, must_upgrade_msg, beta_upgrade_message)

            if package_name not in [Util.PRODUCTION_PACKAGE_NAME, Util.BETA_PACKAGE_NAME,
                                    Util.BETA_MIRROR_PACKAGE_NAME]:
                # it is guaranteed to be a Certora dev package by the previous call to Util.get_package_and_version()

                if not (context.prover_version or context.commit_sha1):  # if it is None or the empty string
                    raise Util.CertoraUserInputError(
                        f"You must use the package {package_name} with either 'prover_version' BRANCH "
                        "or provide a specific 'commit_sha1'.")
    except (requests.exceptions.RequestException, ValueError) as e:
        if isinstance(e, Util.CertoraUserInputError):
            raise e
        cloud_logger.warning(f"Failed to find the latest package version of {package_name}. Local version is {version}")
        cloud_logger.debug("When trying to find the latest package version, got the following exception:", exc_info=e)


def compare_cloud_versions_check(local_main: int, local_sub: int, local_patch: int, remote_main: int, remote_sub: int,
                                 remote_patch: int, package_name: str, recommended_upgrade_msg: str,
                                 major_upgrade_msg: str, beta_upgrade_message: str) -> None:
    """
    This method does the actual logic of comparing the local version to the remote pypi version,
    and printing the right errors or warnings according to the package we have.
    """
    # checks for certora-cli
    if package_name == Util.PRODUCTION_PACKAGE_NAME:
        if remote_main > local_main:
            cloud_logger.warning(major_upgrade_msg)

        elif remote_main == local_main and \
            (remote_sub > local_sub or
             (remote_sub == local_sub and remote_patch > local_patch)):
            """
            The main version number is the same, but one of the minor versions are not.
            Therefore, it is only a recommendation/warning.
            """
            cloud_logger.warning(recommended_upgrade_msg)
    elif package_name == Util.BETA_PACKAGE_NAME:  # checks for certora-cli-beta
        # in beta, we force the user to have the latest version
        if remote_main != local_main or remote_sub != local_sub or remote_patch != local_patch:
            raise Util.CertoraUserInputError(beta_upgrade_message)
    else:  # checks for all other packages (kind of moot now)
        if remote_main > local_main or \
            (remote_main == local_main and
             (remote_sub > local_sub or
              (remote_sub == local_sub and remote_patch > local_patch))):
            cloud_logger.warning(recommended_upgrade_msg)


def progress_bar(total: int = 70, describe: str = "Initializing verification") -> None:
    for _ in tqdm(range(total),
                  bar_format="{l_bar}{bar}| [remaining-{remaining}]",
                  ncols=70, desc=describe, ascii=".#"):
        time.sleep(1)


def parse_json(response: Response) -> Dict[str, Any]:
    try:
        json_response = response.json()
    except ValueError:
        cloud_logger.error(f"{GENERAL_ERR_PREFIX} Could not parse JSON response")
        print(response.text)  # Should we print the whole response here?
        return {}
    return json_response


def compress_files(zip_file_path: Path, *resource_paths: Path, short_output: bool = False) -> bool:
    with zipfile.ZipFile(zip_file_path, 'w', zipfile.ZIP_DEFLATED) as zip_obj:

        total_files = 0
        for path in resource_paths:
            if path.is_dir():
                total_dir_files = get_total_files(path)
                if total_dir_files == 0:
                    cloud_logger.warning(f"{GENERAL_ERR_PREFIX} Provided directory - '{path}' is empty.")
                    # when running on a json, it is ok to return True here, but we need to include the .json file
                    return True
                elif total_dir_files > 0:
                    total_files += total_dir_files
            elif path.is_file():
                total_files += 1
            else:
                cloud_logger.error(f"{GENERAL_ERR_PREFIX} Provided file - '{path}' does not exist.")
                return False
        if total_files < 1:
            if len(resource_paths) == 0:
                cloud_logger.error(f"{GENERAL_ERR_PREFIX} No file was provided. {CONTACT_CERTORA_MSG}")
            else:
                cloud_logger.error(f"{GENERAL_ERR_PREFIX} Provided file(s) - "
                                   f"{', '.join([str(file) for file in resource_paths])} do(es) not exist.")
            return False

        i = 0

        for path in resource_paths:
            if path.is_dir():
                try:
                    # traverse the directory recursively for all files
                    for file_path in Path(path).rglob("*"):
                        if file_path.is_file():
                            posix_path = file_path.as_posix()
                            zip_obj.write(posix_path, os.path.relpath(posix_path, Util.get_build_dir()))
                            if not short_output:
                                i += 1
                                cloud_logger.debug(f"Compressing ({i}/{total_files}) - {file_path}")
                except OSError:
                    Util.flush_stdout()
                    cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  f"Could not compress a directory - {path}")
                    return False
            else:  # zip file
                try:
                    """
                    Why do we use the base name? Otherwise, when we provide a relative path dir_a/dir_b/file.tac,
                    the zip function will create a directory dir_a, inside it a directory dir_b and inside that file.tac
                    """

                    zip_obj.write(path, os.path.relpath(path, Util.get_build_dir()))
                    if not short_output:
                        i += 1
                        cloud_logger.debug(f"Compressing ({i}/{total_files}) - {path}")
                except OSError:
                    Util.flush_stdout()
                    cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  f"Could not compress {path}")
                    return False

    # Solana binary files can become heavy, thus we need to increase size limit.
    max_file_size = MAX_FILE_SIZE if not Attrs.is_rust_app() else SOLANA_MAX_FILE_SIZE
    if zip_file_path.stat().st_size > max_file_size:
        cloud_logger.error(f"{GENERAL_ERR_PREFIX} Max 25MB file size exceeded.")
        return False

    return True


def get_total_files(directory: Path) -> int:
    try:
        total_files = len([path for path in directory.rglob('*') if path.is_file()])
        return total_files
    except OSError:
        cloud_logger.error(f"{GENERAL_ERR_PREFIX} Could not traverse {directory}")
        return -1


def output_error_response(response: Response) -> None:
    cloud_logger.error(f"{STATUS_ERR_PREFIX}: {response.status_code}")
    if response.status_code == 500:
        cloud_logger.error(f"{SERVER_ERR_PREFIX} {CONTACT_CERTORA_MSG}")
        return
    try:
        error_response = response.json()
        if "errorString" in error_response:
            cloud_logger.error(f"{VAAS_ERR_PREFIX} {error_response['errorString']}")
        elif "message" in error_response:
            cloud_logger.error(f"{VAAS_ERR_PREFIX} {error_response['message']}")
    except Exception as e:
        cloud_logger.error(f"{GENERAL_ERR_PREFIX}: request failed", exc_info=e)
        print(response.text)


def is_success_response(json_response: Dict[str, Any], status_url: str = "") -> bool:
    """
    @param json_response:
    @param status_url:
    @return: False when the server response missing the success field or success value False
    """
    if "success" not in json_response:
        cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  "The server returned an unexpected response:")
        print(json_response)
        print(CONTACT_CERTORA_MSG)
        return False
    if not json_response["success"]:
        if "errorString" in json_response:
            cloud_logger.error(f'{json_response["errorString"]} {status_url}')
        else:
            cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  "The server returned an error with no message:")
            print(json_response)
            print(CONTACT_CERTORA_MSG)
        return False
    return True


def print_conn_error() -> None:
    cloud_logger.error(f"{CONNECTION_ERR_PREFIX}: Server is currently unavailable. Please try again later.")
    print(f"For further information, {CONTACT_CERTORA_MSG}", flush=True)


def look_for_path(path: str) -> Optional[Response]:
    try:
        r = requests.get(path, timeout=10)
        if r.status_code == requests.codes.ok:
            return r
    except (requests.exceptions.Timeout, requests.exceptions.RequestException, ConnectionError):
        print_conn_error()
    return None


def fetch_results_from_web(output_url: str, max_attempts: int, delay_between_attempts_seconds: int) -> \
        Optional[Union[Dict[str, Any], List[Dict[str, Any]]]]:
    attempts = 0
    actual = None
    while actual is None and attempts < max_attempts:
        attempts += 1
        response = look_for_path(output_url)
        try:  # read as json
            if response is not None:
                actual = response.json()
        except json.decoder.JSONDecodeError:
            # when '' is returned
            pass
        if actual is None and attempts < max_attempts:
            time.sleep(delay_between_attempts_seconds)
    return actual


def check_results_from_web(output_url: str,
                           max_attempts: int,
                           delay_between_attempts_seconds: int,
                           expected_filename: str,
                           report_url: str) -> bool:
    """
    Returns true if the actual results match the expected results.
    @param output_url - URL of output.json
    @param max_attempts - max number of attempts to get output.json
    @param delay_between_attempts_seconds - delay in seconds between attempts to get output.json
    @param expected_filename - name of the expected file to compare output.json to. If the expected file
                                does not exist, will require all rules to succeed
    @param report_url - a full report URL to refer the user to in case output.json does not exist
    """
    actual = fetch_results_from_web(output_url, max_attempts, delay_between_attempts_seconds)
    if actual is None:
        # Could not find actual results file output.json... print report URL for more details
        cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  f"Could not find job results, please refer to {report_url} for"
                           f" additional information")
        return False
    return Util.check_results(cast(dict, actual), expected_filename)


def save_features_json_from_web(output_url: str, max_attempts: int, delay_between_attempts_seconds: int) -> None:
    features_json_content = fetch_results_from_web(output_url, max_attempts, delay_between_attempts_seconds)
    if features_json_content is None:
        cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  f"Could not download features report file ({FEATURES_REPORT_FILE})")
        return
    try:
        Util.write_json_file(features_json_content, FEATURES_REPORT_FILE)
    except (ValueError, OSError) as e:
        cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  f"Error occurred when saving json data: {e}")
        return
    Util.print_completion_message(f"{FEATURES_REPORT_FILE} was successfully created")


def send_get_request(session: requests.Session, url: str, data: Dict[str, str], lim: int = 60) -> \
        Optional[Dict[str, Any]]:
    """
    Sends a get request to the supplied url with the data as a query parameters and returns the response as a dictionary
    It's important to know that we expect to get a JSON format response (that's why we call the `parse_json` function)
    @param session: requests session object
    @param url: a string
    @param data: a dictionary of parameters to be sent in the query string
    @param lim: a number of seconds to wait for a server response
    @return: a server response as a dictionary, or None on 502 status code
    @raise requests.exceptions.RequestException: on 4XX/5XX status code
    @raise requests.exceptions.Timeout:  if no bytes have been received for `lim` seconds

    * we treat the 502 error differently as it is usually resolved relatively quickly
    """
    response = session.get(url, params=data, timeout=lim)
    if response.status_code != requests.codes.ok:
        if response.status_code != 502:
            output_error_response(response)
            raise requests.exceptions.RequestException
        else:
            cloud_logger.debug("502 Bad Gateway")
            return None
    else:
        return parse_json(response)


class CloudVerification:
    """Represents an AWS Cloud verification"""

    def __init__(self, context: CertoraContext, timings: Optional[Dict[str, float]] = None) -> None:
        self.context = context
        self.queue_wait_minutes = NO_OUTPUT_LIMIT_MINUTES
        self.max_poll_minutes = MAX_POLLING_TIME_MINUTES
        self.log_query_frequency_seconds = LOG_READ_FREQUENCY
        self.max_attempts_to_fetch_output = MAX_ATTEMPTS_TO_FETCH_OUTPUT
        self.delay_fetch_output_seconds = DELAY_FETCH_OUTPUT_SECONDS
        self.verification_request_retries = VERIFICATION_REQUEST_RETRIES
        self.verification_request_sleep = VERIFICATION_REQUEST_SLEEP
        self.max_poll_error_msg = f"The contract is being processed for more than {self.max_poll_minutes} minutes"
        self.max_no_output_error_msg = f"There was no output for {self.queue_wait_minutes} minutes."
        self.timings = timings
        self.sleep_seconds = 2

        for timer in ['queue_wait_minutes', 'max_poll_minutes', 'log_query_frequency_seconds',
                      'max_attempts_to_fetch_output', 'delay_fetch_output_seconds']:
            val = getattr(self.context, timer)
            if val is not None:
                setattr(self, timer, val)

        self.runName = uuid.uuid4().hex
        self.ZipFilePath = Util.CERTORA_INTERNAL_ROOT / (self.runName + ".zip")
        self.logZipFilePath = Util.CERTORA_INTERNAL_ROOT / (self.runName + "_cli_debug_log.zip")
        self.checkUrl = ""
        self.jsonOutputUrl = ""
        self.logUrl = ""
        self.statusUrl = ""
        self.reportUrl = ""
        self.zipOutputUrl = ""
        self.jobDataUrl = ""
        self.featuresResults = ""
        if not Ctx.is_minimal_cli_output(self.context):
            self.anim_thread = threading.Thread(target=self.animate)
        self.done = False
        self.triggered = False

        self.anonymousKey = ""
        self.presigned_url = ""
        self.userId = -1
        self.msg = ""
        self.user_defined_cache = context.user_defined_cache if Attrs.is_evm_app() else None
        self.expected_filename = context.expected_file

        self.set_protocol_name_and_author(context)

        self.vscode_extension_info_writer = ExtensionInfoWriter()

    def __set_url(self, url_attr: str, index: str, user_id: int, anonymousKey: str,
                  requested_resource: Optional[str] = None) -> None:
        """
        DO NOT USE THIS, use set_output_url() etc. instead.
        This function is intended for internal use by the aforementioned functions. We DO NOT check that the url_attr is
        defined!
        @param url_attr: name of the attribute we want to set in self. For example, if url_attr == "logUrl",
                         then self.logUrl will be set.
        @param index: name of the url index of this request
        @param user_id: id number of the user sending the request
        @param current_job_anonymous_key: user's anonymous key
        """
        if self.runName == "":
            cloud_logger.debug(f"setting {url_attr}: runName is not defined.")
        else:
            resource_req = f"{'/' + requested_resource if requested_resource is not None else ''}"
            url = f"{self.get_domain()}/{index}/{user_id}/{self.runName}{resource_req}?anonymousKey={anonymousKey}"
            setattr(self, url_attr, url)

    def set_protocol_name_and_author(self, context: CertoraContext) -> None:
        """
        Sets the `protocol_name` and `protocol_author` attributes of the `self` object based on the `context` argument.
        If these attributes are not specified in the `context` argument, the function attempts to read the data from the
        `package.json` file and set the attributes based on the values found in the file.

        :param context: An instance of the `CertoraContext` class that contains the `protocol_name` and
                        `protocol_author` attributes.
        """
        self.protocol_name = context.protocol_name
        self.protocol_author = context.protocol_author

        if not self.protocol_name or not self.protocol_author:
            if Util.PACKAGE_FILE.is_file():
                # Try to read the data from package.json
                with open(Util.PACKAGE_FILE) as package_file:
                    package_data = json.load(package_file)

                if not self.protocol_name:
                    self.protocol_name = package_data.get("name")

                if not self.protocol_author:
                    author_obj = package_data.get("author")
                    # we saw that the author can be either a string or a dictionary
                    # with a 'name' key
                    if isinstance(author_obj, dict):
                        if "name" in author_obj and isinstance(author_obj["name"], str):
                            self.protocol_author = author_obj["name"]
                        else:
                            # if dict but no "name" in dict, then we do not know how
                            # to parse the author
                            cloud_logger.info(f"Could not parse author name from {author_obj}")
                    elif isinstance(author_obj, str):
                        self.protocol_author = author_obj

        self.protocol_name = self.protocol_name.strip() if self.protocol_name else None
        self.protocol_author = self.protocol_author.strip() if self.protocol_author else None

    def print_error_and_status_url(self, err_msg: str, status_url: Union[str, None] = None) -> None:
        if status_url is None:
            status_url = self.statusUrl
        self.stop_animation()
        cloud_logger.error(f"{GENERAL_ERR_PREFIX} {err_msg}")
        if status_url:
            print("For further details visit", status_url)
        print("Closing connection...", flush=True)

    def check_polling_timeout(self, start: float, max_duration_in_minutes: int, error_msg: str) -> None:
        stop = time.perf_counter()
        if stop - start > max_duration_in_minutes * 60:  # polling time in seconds
            self.print_error_and_status_url(error_msg, '')
            raise TimeError()

    # jar output (logs) url
    def set_output_url(self, user_id: int, anonymous_key: str) -> None:
        self.__set_url("logUrl", "job", user_id, anonymous_key)

    # index report url
    def set_report_url(self, user_id: int, anonymous_key: str) -> None:
        self.__set_url("reportUrl", "output", user_id, anonymous_key)

    # index report url
    def set_requested_resource_url(self, user_id: int, resource_name: str, resource_file: str, anonymous_key: str) \
            -> None:
        self.__set_url(resource_name, "output", user_id, anonymous_key, requested_resource=resource_file)

    # status page url
    def set_status_url(self, user_id: int, anonymous_key: str) -> None:
        self.__set_url("statusUrl", "jobStatus", user_id, anonymous_key)

    # compressed output folder url
    def set_zip_output_url(self, user_id: int, anonymous_key: str) -> None:
        self.__set_url("zipOutputUrl", "zipOutput", user_id, anonymous_key)

    # json output url
    def set_json_output_url(self, user_id: int, anonymous_key: str) -> None:
        self.__set_url("jsonOutputUrl", "jsonOutput", user_id, anonymous_key)

    def set_check_file_url(self, user_id: int, anonymous_key: str) -> None:
        self.__set_url("checkUrl", "exists", user_id, anonymous_key)

    def set_job_data_url(self, user_id: int, anonymous_key: str) -> None:
        self.__set_url("jobDataUrl", "jobData", user_id, anonymous_key)

    def prepare_auth_data(self, cl_args: str) -> Optional[Dict[str, Any]]:
        """
        :param cl_args: A string that can be copied to and run by the shell to recreate this run.
        @return: An authentication data dictionary to send to server
        """

        auth_data = {
            "runName": self.runName,
            "run_source": self.context.run_source,
            "group_id": self.context.group_id,
            "branch": self.context.prover_version if self.context.prover_version else '',
            'ci': Util.is_ci_or_git_action()
        }

        _, _, version = Util.get_package_and_version()
        # current schema accepts only a sequence of numbers no `+`. this is a very crude solution for alpha versions
        temp_version = version.split("+")
        if len(temp_version) > 0:
            version = temp_version[0]

        if version:
            auth_data["version"] = version

        jar_settings = Ctx.collect_jar_args(self.context)

        if Attrs.is_rust_app():
            rust_jar_settings = [Path(self.context.files[0]).name]

            if Attrs.is_solana_app():
                def paths_in_source_dir(attr_values: List[str]) -> str:
                    cwd_rel_in_sources = Util.get_certora_sources_dir() / self.context.cwd_rel_in_sources
                    values: list[str] = \
                        [os.path.relpath(cwd_rel_in_sources / value, Util.get_build_dir()) for value in attr_values]
                    return ','.join(values)

                if self.context.solana_summaries:
                    rust_jar_settings.append('-solanaSummaries')
                    rust_jar_settings.append(paths_in_source_dir(self.context.solana_summaries))

                if self.context.solana_inlining:
                    rust_jar_settings.append('-solanaInlining')
                    rust_jar_settings.append(paths_in_source_dir(self.context.solana_inlining))

            auth_data["jarSettings"] = rust_jar_settings + jar_settings

        elif Attrs.is_sui_app():
            sui_jar_settings = ['-movePath', Path(self.context.move_path).name]
            auth_data["jarSettings"] = sui_jar_settings + jar_settings

        elif Attrs.is_concord_app():
            auth_data["jarSettings"] = jar_settings + ["-equivalenceCheck", "true"]
        else:
            auth_data["jarSettings"] = jar_settings

        if self.context.coinbase_mode:
            auth_data["jarSettings"].append(Attrs.EvmProverAttributes.COINBASE_MODE.jar_flag)

        if self.context.java_args is not None:
            auth_data["javaArgs"] = self.context.java_args.strip()

        if self.context.commit_sha1:
            auth_data["git_hash"] = self.context.commit_sha1

        if self.context.job_definition:
            auth_data["jobDefinition"] = self.context.job_definition

        auth_data["useLatestFe"] = self.context.fe_version == str(Util.FeValue.LATEST)

        if Attrs.is_solana_app():
            auth_data["ecosystem"] = EcoEnum.SOLANA.name
        elif Attrs.is_soroban_app():
            auth_data["ecosystem"] = EcoEnum.SOROBAN.name
        elif Attrs.is_sui_app():
            auth_data["ecosystem"] = EcoEnum.SUI.name
        else:
            auth_data["ecosystem"] = EcoEnum.EVM.name

        if Attrs.is_ranger_app():
            auth_data["product"] = ProductEnum.RANGER.name
        elif Attrs.is_concord_app():
            auth_data["product"] = ProductEnum.CONCORD.name
        else:
            auth_data["product"] = ProductEnum.PROVER.name

        if Attrs.is_evm_app() and self.context.cache is not None:
            auth_data["toolSceneCacheKey"] = self.context.cache

        if self.context.msg is not None:
            auth_data["msg"] = self.context.msg

        if self.timings is not None:
            auth_data.update(self.timings)

        auth_data["buildArgs"] = cl_args

        if self.protocol_name:
            auth_data["protocolName"] = self.protocol_name

        if self.protocol_author:
            auth_data["protocolAuthor"] = self.protocol_author

        if Attrs.is_evm_app() and self.context.verify:
            auth_data["primaryContract"] = self.context.verify.split(':')[0]
        cloud_logger.debug(f'authdata = {auth_data}')

        if self.context.mutation_test_id:
            auth_data["mutationTestId"] = self.context.mutation_test_id
        return auth_data

    def print_group_id_url(self) -> None:
        group_id_url = f"{self.get_domain()}/?groupIds={self.context.group_id}"
        print(f"See all jobs generated from splitting the rules at {group_id_url}", flush=True)

    def print_output_links(self) -> None:
        print(f"Manage your jobs at {self.get_domain()}")
        print(f"Follow your job and see verification results at {self.url_print_format()}", flush=True)

    def print_verification_summary(self) -> None:
        report_exists = self.check_file_exists(params={"filename": "index.html", "certoraKey": self.context.key})
        if report_exists:
            print(f"Job is completed! View the results at {self.url_print_format()}")
        print("Finished verification request", flush=True)

    def __send_verification_request(self, cl_args: str) -> bool:
        """
        Sends an authentication request to the server.
        Sets the user id, anonymous key, presigned url and message parameters of this CloudVerification
        :param cl_args: A string that can be copied to and run by the shell to recreate this run.
        :return: True if there were no errors
        """
        auth_data = self.prepare_auth_data(cl_args)
        if auth_data is None:
            return False
        if self.context.test == str(Util.TestValue.CHECK_AUTH_DATA):
            raise Util.TestResultsReady(auth_data)

        resp = self.verification_request(auth_data)  # send post request to /cli/verify

        if resp is None:  # on error
            return False

        json_response = parse_json(resp)
        if not json_response:
            return False

        if not is_success_response(json_response):
            return False

        try:
            self.anonymousKey = json_response["anonymousKey"]
            self.presigned_url = json_response["presigned_url"]
            self.userId = json_response["userId"]
            self.msg = auth_data.get("msg", "")
            return True
        except Exception as e:  # (Json) ValueError
            cloud_logger.error(f"{GENERAL_ERR_PREFIX}"  f"Unexpected response {e}")
            return False

    def __compress_and_upload_zip_files(self) -> bool:
        """
        compresses all files to a zip file and uploads it to the server
        :return: True if there were no errors in compressing or uploading
        """
        cloud_logger.debug("Compressing the files")
        # remove previous zip file
        Util.remove_file(self.ZipFilePath)

        # create new zip file
        if self.context.is_tac:
            # We zip the tac file itself
            result = compress_files(self.ZipFilePath, self.context.files[0],
                                    short_output=Ctx.is_minimal_cli_output(self.context))
        elif Attrs.is_evm_app() and self.context.is_bytecode:
            # We zip the bytecode jsons and the spec
            paths = [Util.get_certora_build_file(), Util.get_certora_verify_file(),
                     Util.get_certora_metadata_file(), Util.get_configuration_layout_data_file()]
            if Util.get_certora_sources_dir().exists():
                paths.append(Util.get_certora_sources_dir())

            for bytecode_json in self.context.bytecode_jsons:
                paths.append(Path(bytecode_json))
            paths.append(Path(self.context.bytecode_spec))
            result = compress_files(self.ZipFilePath, *paths,
                                    short_output=Ctx.is_minimal_cli_output(self.context))
        elif Attrs.is_rust_app():
            files_list = [Util.get_certora_metadata_file(),
                          Util.get_configuration_layout_data_file(),
                          Util.get_build_dir() / Path(self.context.files[0]).name]

            if Util.get_debug_log_file().exists():
                files_list.append(Util.get_debug_log_file())

            if Util.get_certora_sources_dir().exists():
                files_list.append(Util.get_certora_sources_dir())

            if hasattr(self.context, 'build_script') and self.context.build_script:
                if attr_file := getattr(self.context, 'rust_logs_stdout', None):
                    files_list.append(Util.get_build_dir() / Path(attr_file).name)
                if attr_file := getattr(self.context, 'rust_logs_stderr', None):
                    files_list.append(Util.get_build_dir() / Path(attr_file).name)

            result = compress_files(self.ZipFilePath, *files_list,
                                    short_output=Ctx.is_minimal_cli_output(self.context))
        elif Attrs.is_sui_app():
            files_list = [Util.get_certora_metadata_file(),
                          Util.get_configuration_layout_data_file(),
                          Util.get_build_dir() / Path(self.context.move_path).name]

            if Util.get_certora_sources_dir().exists():
                files_list.append(Util.get_certora_sources_dir())

            result = compress_files(self.ZipFilePath, *files_list,
                                    short_output=Ctx.is_minimal_cli_output(self.context))

        else:
            # Zip the log file first separately and again with the rest of the files, so it will not be decompressed
            # on each run in order to save space
            result = compress_files(self.logZipFilePath, Util.get_debug_log_file(),
                                    short_output=Ctx.is_minimal_cli_output(self.context))

            if not result:
                return False
            files_list = [Util.get_certora_build_file(), Util.get_certora_verify_file(),
                          Util.get_certora_metadata_file(), Util.get_configuration_layout_data_file(),
                          self.logZipFilePath]
            if Util.get_certora_sources_dir().exists():
                files_list.append(Util.get_certora_sources_dir())
            result = compress_files(self.ZipFilePath, *files_list, short_output=Ctx.is_minimal_cli_output(self.context))

        Util.flush_stdout()
        if not result:
            return False
        if self.context.test == str(Util.TestValue.CHECK_ZIP):
            raise Util.TestResultsReady(self.ZipFilePath)

        cloud_logger.debug("Uploading files...")
        if self.upload(self.presigned_url, self.ZipFilePath):
            try:
                if self.context.mutation_test_id:
                    counter = MutUtil.mutant_counter(self.context.mutation_test_id)
                    total = MutUtil.TOTAL_MUTANTS
                    Util.print_completion_message(f"Mutation {counter}/{total} submitted to server")
                else:
                    Util.print_completion_message("Job submitted to server")

            except Exception:
                Util.print_completion_message("Job submitted to server")

            print()
        else:  # upload error
            return False

        self.set_status_url(self.userId, self.anonymousKey)

        return True

    def cli_verify_and_report(self, cl_args: str, wait_for_results: str) -> bool:
        """
        Sends a verification request to HTTP Handler, uploads a zip file, and outputs the results.
        :param wait_for_results: If "all", we will wait for the results of the verification. If "none",
               the run will be terminated after sending the job to the cloud.
        :param cl_args: A string that can be copied to and run by the shell to recreate this run.
        @returns If compareToExpected is True, returns True when the expected output equals the actual results.
                 Otherwise, returns False if there was at least one violated rule.
        """

        post_result = self.__send_verification_request(cl_args)
        if not post_result:
            return False

        file_upload_success = self.__compress_and_upload_zip_files()

        if not file_upload_success:
            return False

        # set results urls. They are all functions of the form: self.set_output_url(self.userId, self.anonymousKey)
        for func_name in ["set_output_url", "set_report_url", "set_zip_output_url", "set_json_output_url",
                          "set_check_file_url", "set_status_url", "set_job_data_url"]:
            func = getattr(self, func_name)
            func(self.userId, self.anonymousKey)

        if self.context.coinbase_mode:
            self.set_requested_resource_url(self.userId, 'featuresResults', 'featuresResults.json', self.anonymousKey)

        # update jobs list
        job_list = JobList()
        job_list.add_job(
            self.runName, self.reportUrl, self.msg, self.get_domain(), str(self.userId), self.anonymousKey)

        # Generate a json file for the VS Code extension with the relevant url
        self.vscode_extension_info_writer.add_field("verification_report_url", self.url_print_format())
        self.vscode_extension_info_writer.write_file()

        if not wait_for_results or wait_for_results == str(Vf.WaitForResultOptions.NONE):
            job_list.save_data()
            job_list.save_recent_jobs_to_path()
            self.print_output_links()
            return True

        else:  # We wait for the results then print them
            self.print_output_links()

            if self.logUrl == "":  # on error
                return False

            thread = threading.Thread(target=job_list.save_data)
            thread.start()

            try:  # print the logs unless we wanted short cli output (ci/short output flag)
                if Ctx.is_minimal_cli_output(self.context):
                    self.poll_job_status()
                else:
                    print("Output:", flush=True)
                    self.poll_log()
                self.stop_animation()
                thread.join()
            except (Exception, KeyboardInterrupt):
                try:
                    self.stop_animation()
                    thread.join()
                    raise
                except KeyboardInterrupt:
                    print("You were disconnected from server, but your request is still being processed.")
                    self.print_output_links()
                except requests.exceptions.RequestException:
                    # other requests exceptions
                    print_conn_error()
                except TimeError:
                    self.print_output_links()
                except Exception as e:
                    print("Encountered an error: ", e)
                finally:
                    return False

            # Record the URL of the zip file for use by the mutation testing tool.
            with Util.get_zip_output_url_file().open('w') as url_file:
                _ = url_file.write("{}\n".format(self.zipOutputUrl))

            print()
            self.print_verification_summary()

            if self.context.no_compare:
                return True

            result_check_success = check_results_from_web(self.jsonOutputUrl,
                                                          self.max_attempts_to_fetch_output,
                                                          self.delay_fetch_output_seconds,
                                                          self.expected_filename,
                                                          self.reportUrl)
            if self.context.coinbase_mode:
                save_features_json_from_web(self.featuresResults, self.max_attempts_to_fetch_output,
                                            self.delay_fetch_output_seconds)

            return result_check_success

    def verification_request(self, auth_data: Dict[str, Any]) -> Optional[Response]:
        verify_url = self.get_domain() + "/v2/cli/verify"
        response = None
        print("\nConnecting to server...")
        cloud_logger.debug(f"requesting verification from {verify_url}")
        headers = {
            'Authorization': f'Bearer {self.context.key}',
            "Content-Type": "application/json"
        }

        # retry on request timeout or 502 (must take no more than 3 minutes)
        # print error message on the 3rd exception and return
        for i in range(self.verification_request_retries):
            try:
                response = requests.post(verify_url, json=auth_data, headers=headers, timeout=60)
                cloud_logger.debug(f"\n\n=================\n\nresponse - Status Code: {response.status_code}\n\n"
                                   f"Headers:\n {response.headers}\n\nText:\n {response.text}\n\n=================\n\n")
                if response is None:
                    continue
                status = response.status_code
                if status == requests.codes.ok:  # 200
                    break
                if status == 403:
                    print("You have no permission. Please, make sure you entered a valid key.")
                    if self.context.server and (self.context.server.upper() != Util.SupportedServers.PRODUCTION.name):
                        print("Please note - failure may be related to using the server"
                              f" <{self.context.server.lower()}> which is not a standard server")
                    return None
                elif status == 401:
                    print("Invalid key or key is missing. Please, make sure you entered a valid key.")
                    return None
                elif status >= 500:
                    cloud_logger.debug(f'{status} received. Retry...')
                    if i < self.verification_request_retries:
                        print(f'Received an invalid response, error code: {status}. '
                              f'Retrying in {self.verification_request_sleep} seconds...')
                    else:
                        print("Oops, an error occurred when sending your request. Please try again later")
                        return None
                else:  # status != 200, 403, 5XX
                    output_error_response(response)
                    return None
            except requests.exceptions.Timeout:
                if i < self.verification_request_retries:
                    print(f'Request timeout. Retrying in {self.verification_request_sleep} seconds...')
                else:
                    cloud_logger.error(f"{TIMEOUT_MSG_PREFIX} {CONTACT_CERTORA_MSG}")
                    return None
            except (requests.exceptions.RequestException, ConnectionError):
                if i < self.verification_request_retries:
                    print(f'Request exception encountered. Retrying in {self.verification_request_sleep} seconds...')
                else:
                    print_conn_error()

            time.sleep(self.verification_request_sleep)
        return response

    def poll_log(self) -> None:
        has_output = True
        params = {}
        next_token = ""
        start_poll_t = time.perf_counter()

        self.start_animation()
        s = requests.Session()

        while True:
            try:
                if next_token:  # used for retrieving the logs in chunks
                    params = {"nextToken": next_token}

                json_response = send_get_request(s, self.logUrl, params)
                if json_response is None:  # currently, it's set to None when response status code is 502
                    all_output = None
                    new_token = next_token  # keep the same token
                    status = "PROCESSED"
                elif not json_response:  # Error parsing json
                    self.print_error_and_status_url("Failed to parse response. For more information visit")
                    break
                elif not is_success_response(json_response, self.statusUrl):  # look for execution exceptions
                    break
                else:
                    status = json_response.get("status")  # type: ignore[assignment]
                    if status is None or "nextToken" not in json_response:
                        self.print_error_and_status_url(
                            f"got an unexpected response: status-{status}; token-{new_token}")
                        break

                    new_token = json_response["nextToken"]  # type: ignore[assignment]

                    if "logEventsList" not in json_response:  # response does not include `logEventsList`
                        self.print_error_and_status_url("No output is available.")
                        break
                    all_output = json_response["logEventsList"]

                if all_output:
                    has_output = True
                    self.stop_animation()
                    for outputLog in all_output:
                        msg = outputLog.get("message", "")
                        print(msg, flush=True)
                elif has_output:  # first missing output
                    has_output = False
                    first_miss_out = time.perf_counter()  # start a timer
                else:  # missing output
                    self.check_polling_timeout(first_miss_out, self.queue_wait_minutes, self.max_no_output_error_msg)
                if new_token == next_token and next_token != "":
                    if status == "SUCCEEDED" or status == "FAILED":
                        # When finished it returns the same token you passed in
                        break
                    else:  # the job is still being processed
                        time.sleep(self.log_query_frequency_seconds)
                else:
                    next_token = new_token
                    time.sleep(self.sleep_seconds)
            except (requests.exceptions.Timeout, requests.exceptions.ConnectionError):
                # catch timeout and connectionError and resend the request
                time.sleep(self.sleep_seconds)
            self.check_polling_timeout(start_poll_t, self.max_poll_minutes, self.max_poll_error_msg)

    def poll_job_status(self) -> None:
        has_output = True
        job_status = "jobStatus"
        attribute = {"attr": job_status}
        status = None
        start_poll_t = time.perf_counter()
        s = requests.Session()

        while True:
            try:
                json_response = send_get_request(s, self.jobDataUrl, attribute)
                if json_response is None:  # e.g., when response.status_code is 502
                    status = None  # make sure we remove the previous status
                elif not json_response:  # Error parsing json - empty json object is returned
                    self.print_error_and_status_url("Failed to parse response. For more information visit")
                    break
                else:  # json_response is not empty
                    try:
                        status = json_response.get(job_status, None)
                    except AttributeError:  # in case we get an array
                        cloud_logger.error(f"couldn't retrieve '{job_status}' from {json_response}")
                if status == "SUCCEEDED" or status == "FAILED":
                    break
                else:  # the job is still being processed
                    if status:
                        has_output = True
                    elif has_output:  # first miss
                        has_output = False
                        first_miss = time.perf_counter()  # start a timer
                    else:  # sequential miss
                        self.check_polling_timeout(first_miss, self.queue_wait_minutes, self.max_no_output_error_msg)
                    time.sleep(self.log_query_frequency_seconds)
            except (requests.exceptions.Timeout, requests.exceptions.ConnectionError):
                # catch timeout and connectionError and resend the request
                time.sleep(self.sleep_seconds)
            self.check_polling_timeout(start_poll_t, self.max_poll_minutes, self.max_poll_error_msg)

    @staticmethod
    def upload(presigned_url: str, path_to_upload: Path) -> Optional[Response]:
        """
        Uploads user contract/s as a zip file to S3

        Parameters
        ----------
        presigned_url : str
            S3 presigned url
        path_to_upload : Path
            zip file name

        Returns
        -------
        Response
            S3 response if successfull - can be handled as a json object
            None if excepted
        """
        upload_fail_msg = f"couldn't upload file - {path_to_upload}"
        try:
            with open(path_to_upload, "rb") as my_file:
                return requests.put(presigned_url, data=my_file, headers={"content-type": "application/zip"})
        except ConnectionError as e:
            cloud_logger.error(f"{CONNECTION_ERR_PREFIX} {upload_fail_msg}", exc_info=e)
        except requests.exceptions.Timeout as e:
            cloud_logger.error(f"{TIMEOUT_MSG_PREFIX} {upload_fail_msg}", exc_info=e)
        except requests.exceptions.RequestException as e:
            cloud_logger.error(f"{GENERAL_ERR_PREFIX} {upload_fail_msg}", exc_info=e)
        except OSError as e:
            cloud_logger.error(f"OSError: {upload_fail_msg}", exc_info=e)

        return None

    def check_file_exists(self, params: Dict[str, Any]) -> bool:
        try:
            r = requests.get(self.checkUrl, params=params, timeout=10)
            if r.status_code == requests.codes.ok:
                return True
        except (requests.exceptions.Timeout, requests.exceptions.RequestException, ConnectionError) as e:
            cloud_logger.error(f"{GENERAL_ERR_PREFIX} request failed", exc_info=e)
        return False

    def animate(self, status: str = "processing") -> None:
        for c in itertools.cycle(['|', '/', '-', '\\']):
            if self.done:
                break
            sys.stdout.write(f'\r{status} ' + c)
            sys.stdout.flush()
            time.sleep(0.1)
        sys.stdout.write('\r')

    def start_animation(self) -> None:
        if hasattr(self, "anim_thread"):
            if not self.triggered:  # make sure we run the start function once
                self.triggered = True
                self.anim_thread.start()

    def stop_animation(self) -> None:
        if not self.done:
            self.done = True  # used for stopping the animation
            if hasattr(self, "anim_thread"):
                self.anim_thread.join()  # wait for the animation thread

    def __del__(self) -> None:
        Util.remove_file(self.ZipFilePath)
        Util.remove_file(self.logZipFilePath)

    def url_print_format(self) -> str:
        if self.reportUrl and self.context.url_visibility == str(Vf.UrlVisibilityOptions.PRIVATE):
            return self.privatize_url()
        else:
            return self.reportUrl

    @lru_cache(maxsize=1, typed=False)
    def get_domain(self) -> str:
        """
        Calculates the domain this job will be sent to (potentially). The default is to production
        """
        if self.context.server:
            key = self.context.server.upper()
            if hasattr(Util.SupportedServers, key):
                domain = Util.SupportedServers[key].value
            else:
                domain = f"https://{self.context.server}.certora.com"
        else:
            domain = Util.SupportedServers.PRODUCTION.value

        return domain

    @lru_cache(maxsize=1)
    def privatize_url(self) -> str:
        return self.reportUrl.split('?anonymousKey=')[0]
