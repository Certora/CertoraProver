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

from datetime import datetime
from shutil import copyfile
from typing import Any, Dict, Optional
from pathlib import Path
import click
import sys
import requests
import time
import logging

from Shared import certoraUtils as Util
from CertoraProver import certoraContextValidator as Cv

job_logger = logging.getLogger("jobList")


def get_temp_recent_jobs_file() -> Path:
    return Util.CERTORA_INTERNAL_ROOT / f".tmp{Util.RECENT_JOBS_FILE}"


class JobList:
    """
    Represents Recently executed jobs

    Structure is as follows:
    {
        "path_to_test/working_dir": [
            {
                "job_id": {JOB_ID},
                "output_url": {REPORT_URL: https://{DOMAIN}/output/.../data.json...},
                "notify_msg": {OPTIONAL: NOTIFICATION_MSG}
            },...
        ],...
    }

    Each path has a (FIFO) queue of up to {MAX_LENGTH} recently executed jobs
    The first element in the queue is the most recent one

    """

    jobs = {}  # type: Dict[str, Any]

    MAX_LENGTH = 10

    recent_jobs_path = Path()
    temp_file_path = Path()

    def __init__(self, current_path: Path = Path.cwd()):
        self.current_path = current_path.resolve().as_posix()
        self.recent_jobs_path = Util.get_recent_jobs_file()
        self.temp_file_path = get_temp_recent_jobs_file()
        self.get_recent_jobs(self.recent_jobs_path)

    def add_job(self, job_id: str, output_url: str, notify_msg: str, domain: str, user_id: str,
                anonymous_key: str) -> None:
        if not self.current_path:
            job_logger.debug(f"Current path attribute is missing. Skipped adding job {job_id} to recent jobs list")
            return
        new_job = {
            "anonymous_key": anonymous_key,
            "domain": domain,
            "job_id": job_id,
            "notify_msg": notify_msg,
            "output_url": output_url,
            "time": int(time.time()),
            "user_id": user_id
        }  # type: Dict[str, Any]
        try:
            if self.current_path not in self.jobs.keys():
                self.jobs[self.current_path] = []
            self.jobs[self.current_path].insert(0, new_job)  # insert at the front of the list
            if len(self.jobs[self.current_path]) > self.MAX_LENGTH:
                self.remove_oldest_job()
        except AttributeError as e:
            job_logger.debug(f"Couldn't add job {job_id}. Recent jobs file format may have changed", exc_info=e)
            self.rename_recent_jobs_file()

    def remove_oldest_job(self) -> None:
        if not self.current_path:
            job_logger.debug("Current path attribute is missing")
            return
        if len(self.jobs[self.current_path]):
            removed_job = self.jobs[self.current_path].pop()  # remove the last element
            job_logger.debug(f"Removed job {removed_job.get('jobId', '')} from recent jobs list")

    def remove_jobs_in_current_path(self) -> bool:
        if not self.current_path:
            job_logger.error("Current path attribute is missing")
            return False

        self.jobs[self.current_path] = []
        return True

    def get_latest_job(self) -> Optional[Dict[str, Any]]:
        if not self.current_path:
            job_logger.error("Current path attribute is missing")
            return None

        if self.current_path not in self.jobs:
            job_logger.error(f"Current path {self.current_path} is not in jobs")
            return None

        return max(self.jobs[self.current_path], key=lambda job: 0 if "time" not in job else job["time"])

    def get_data(self) -> Dict[str, Any]:
        return self.jobs

    def save_data(self) -> None:
        # backup
        succeeded = self.copy(self.recent_jobs_path, self.temp_file_path)
        if not succeeded:
            job_logger.debug("Couldn't create a backup file.")

        try:
            output_data = self.get_data()

            # convert all top level keys from Path objects to strings:
            all_file_paths = [file_path for file_path in output_data]
            for file_path in all_file_paths:
                output_data[str(file_path)] = output_data.pop(file_path)

            Util.write_json_file(output_data, self.recent_jobs_path)

            # remove the backup file
            Util.remove_file(self.temp_file_path)
        except (ValueError, OSError) as e:
            job_logger.debug("Error occurred when saving json data", exc_info=e)
            self.revert()

    def save_recent_jobs_to_path(self) -> None:
        """
        Saves the ten last recent runs in the build directory to the recent jobs file.
        For example, if we run the tool from C:/Users/uri/Projects/CertoraProver/Test/Bank
        We will save in file C:/Users/uri/Projects/CertoraProver/Test/Bank/<build_dir>/.certora_recent_jobs.json:

        {
            "workingDir": "C:/Users/uri/Projects/CertoraProver/Test/Bank",
            "recentJobs": [
                {
                    "anonymous_key": "cdc45f5ee2e5e529db67c4f2ef969b46f758c80b",
                    "domain": "https://prover.certora.com",
                    "job_id": "8e05726cfa0ed98505bc",
                    "notify_msg": "",
                    "output_url": "https://prover.certora.com/output/53342/8e057...8505bc?anonymousKey=cdc45f5...",
                    "time": 1634060491,
                    "user_id": "53342"
                }
                ...
            ]
        }
        """
        all_data = self.get_data()
        path = Util.get_build_dir()
        if path not in all_data:
            job_logger.debug(f"Couldn't create a recent jobs file for {path}.")
            job_logger.debug(f"All data keys = {all_data.keys()}.")
        else:
            path_data = \
                {
                    "workingDir": path.as_posix(),
                    "recentJobs": all_data[str(path)]
                }

            if self.recent_jobs_path.exists():
                # backup

                succeeded = self.copy(self.recent_jobs_path, self.temp_file_path)
                if not succeeded:
                    job_logger.debug("Couldn't create a backup file.")

            try:
                job_logger.debug(f"writing recent jobs file to {Util.abs_posix_path(self.recent_jobs_path)}")
                Util.write_json_file(path_data, self.recent_jobs_path)
                # remove the backup file
                Util.remove_file(self.temp_file_path)
            except (ValueError, OSError) as e:
                job_logger.debug("Error occurred when saving json data", exc_info=e)
                self.revert()

    def revert(self) -> None:
        """
        used when recent job file is corrupted
        overrides this file with the backup file (if exists)
        """
        if self.temp_file_path.is_file():
            succeeded = self.copy(self.temp_file_path, self.recent_jobs_path)
            if succeeded:
                # remove the backup file
                Util.remove_file(self.temp_file_path)
        else:
            job_logger.debug("Couldn't revert recent jobs changes. Backup file does not exist")

    def get_recent_jobs(self, file_path: Path) -> None:
        """
        Sets the jobs attribute to be the JSON object stored in the supplied file_path
        If file_path format is wrong (on ValueError) renames the file
        """
        try:
            recent_jobs = Util.read_json_file(file_path)
            self.jobs = recent_jobs
        except FileNotFoundError:
            job_logger.debug(f"Couldn't find recent jobs file in {file_path}", exc_info=True)
        except ValueError:
            job_logger.debug("Recent jobs file has incorrect format", exc_info=True)
            self.rename_recent_jobs_file()

    def copy(self, src_path: Path, dst_path: Path) -> bool:
        try:
            copyfile(src_path, dst_path)
            return True
        except OSError as e:
            job_logger.debug(f"Couldn't copy {src_path}", exc_info=e)
        return False

    def rename_recent_jobs_file(self) -> None:
        now = datetime.now()
        current_time = now.strftime("%Y-%m-%d_%H-%M-%S-%f")
        name = Util.CERTORA_INTERNAL_ROOT / f".incompatible.{current_time}{Util.RECENT_JOBS_FILE}"
        try:
            self.recent_jobs_path.rename(name)
            job_logger.warning(f"Recent jobs file was renamed. Please, see {name}")
        except (OSError, FileExistsError) as e:
            job_logger.debug("Couldn't rename the recent jobs file", exc_info=e)

    def get_asset_url_from_last_job(self, asset: str) -> Optional[str]:
        latest_job = self.get_latest_job()
        if latest_job is None:
            return None
        domain = latest_job.get("domain", None)
        job_id = latest_job.get("job_id", None)
        if domain is None or job_id is None:
            return None
        url = f"{Util.get_api_domain(domain)}/v1/domain/jobs/{job_id}/f/{asset}"
        return url


def download_asset(url: Optional[str], asset_name: str) -> bool:
    if url is None:
        job_logger.error(f"Failed to get url for {asset_name}")
        return False

    try:
        certora_key = Cv.validate_certora_key()
    except Util.CertoraUserInputError as e:
        job_logger.error(f"Cannot download {asset_name}: {e}")
        return False
    headers = Util.api_headers(certora_key)
    try:
        r = requests.get(url, stream=True, headers=headers, timeout=10)
        if r.status_code == requests.codes.ok:
            with open(asset_name, 'wb+') as downloaded_file:
                for chunk in r.iter_content(chunk_size=8192):
                    downloaded_file.write(chunk)
            return True
        else:
            job_logger.error(f"Got error {r.status_code} when getting {url}")
            return False
    except (requests.exceptions.Timeout, requests.exceptions.RequestException, ConnectionError):
        job_logger.error(f"Could not download {url}", exc_info=True)
        return False


@click.command()
@click.option("--clear", type=click.BOOL, default=False, help="clear recent jobs")
@click.option("--get_last", type=click.STRING, help="download asset from most recent job")
def main(clear: bool, get_last: Optional[str]) -> None:
    if clear and get_last is not None:
        job_logger.error("Could not use both --clear and --get_last")
        sys.exit(1)
    if not clear and get_last is None:
        job_logger.error("At least one option must be selected")
        sys.exit(1)

    result = True
    job_list = JobList()
    if clear:
        result = job_list.remove_jobs_in_current_path()
        if result:
            job_list.save_data()

    if get_last is not None:
        asset_name = get_last
        url = job_list.get_asset_url_from_last_job(asset_name)
        result = download_asset(url, asset_name)
    if not result:
        sys.exit(1)


if __name__ == '__main__':
    main()
