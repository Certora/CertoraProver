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

import io
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from typing import Any, Optional
from unittest.mock import MagicMock, patch

from CertoraProver import certoraCloudIO
from CertoraProver.certoraContextClass import CertoraContext


def make_verification() -> certoraCloudIO.CloudVerification:
    cv = object.__new__(certoraCloudIO.CloudVerification)
    cv.context = CertoraContext(test=None, server=None, url_visibility=None, coinbase_mode=False)
    cv.anonymousKey = cv.presigned_url = cv.msg = ""
    cv.reportUrl = cv.app_report_url = cv.app_jobs_url = ""
    cv.runName = "run1"
    cv.userId = -1
    cv.ZipFilePath = cv.logZipFilePath = Path("nonexistent_test_zip_file")
    return cv


def run_send_verification_request(json_response: dict,
                                  cv: Optional[certoraCloudIO.CloudVerification] = None) -> tuple[bool, str]:
    cv = cv or make_verification()
    with patch.object(certoraCloudIO.CloudVerification, "prepare_auth_data", return_value={}), \
            patch.object(certoraCloudIO.CloudVerification, "verification_request", return_value=object()), \
            patch.object(certoraCloudIO, "parse_json", return_value=json_response):
        out = io.StringIO()
        with redirect_stdout(out):
            result: bool = cv._CloudVerification__send_verification_request("cl_args")  # type: ignore[attr-defined]
        return result, out.getvalue()


class TestSendVerificationRequestMessage(unittest.TestCase):
    def test_prints_server_message_when_present(self) -> None:
        json_response: dict[str, Any] = {
            "anonymousKey": "k", "presigned_url": "u", "userId": 1,
            "message": "Starting job as user a@b.com."}
        result, output = run_send_verification_request(json_response)
        self.assertTrue(result)
        self.assertIn("Starting job as user a@b.com.", output)

    def test_no_output_when_message_missing(self) -> None:
        json_response: dict[str, Any] = {"anonymousKey": "k", "presigned_url": "u", "userId": 1}
        result, output = run_send_verification_request(json_response)
        self.assertTrue(result)
        self.assertEqual(output, "")


APP_REPORT = "https://app.example.com/jobs/run1"
APP_JOBS = "https://app.example.com/jobs"
CLI_REPORT = "https://prover.certora.com/output/1/run1?anonymousKey=k"


def verified_cloud(**app_fields: Any) -> certoraCloudIO.CloudVerification:
    """Returns a CloudVerification after a verify response with the given app link fields and the CLI-built URLs."""
    cv = make_verification()
    json_response: dict[str, Any] = {"anonymousKey": "k", "presigned_url": "u", "userId": 1, **app_fields}
    result, _ = run_send_verification_request(json_response, cv)
    assert result
    cv.set_report_url(cv.userId, cv.anonymousKey)
    cv.done = cv.triggered = False
    return cv


def printed(func: Any, *args: Any) -> str:
    out = io.StringIO()
    with redirect_stdout(out), patch.object(certoraCloudIO.CloudVerification, "check_file_exists", return_value=True):
        func(*args)
    return out.getvalue()


class TestAppLinks(unittest.TestCase):
    def test_app_links_are_printed(self) -> None:
        cv = verified_cloud(reportUrl=APP_REPORT, jobsUrl=APP_JOBS)
        output = printed(cv.print_output_links)
        self.assertIn(f"Manage your jobs at {APP_JOBS}\n", output)
        self.assertIn(f"see verification results at {APP_REPORT}\n", output)
        self.assertIn(f"View the results at {APP_REPORT}\n", printed(cv.print_verification_summary))
        output += printed(cv.print_error_and_status_url, "err")
        self.assertIn(f"For further details visit {APP_REPORT}\n", output)
        self.assertNotIn("anonymousKey", output)

    def test_report_links_when_fields_null_or_absent(self) -> None:
        cases: list[dict[str, Any]] = [{"reportUrl": None, "jobsUrl": None}, {}, {"reportUrl": "", "jobsUrl": 5}]
        for fields in cases:
            with self.subTest(fields=fields):
                cv = verified_cloud(**fields)
                output = printed(cv.print_output_links)
                self.assertIn("Manage your jobs at https://app.certora.com\n", output)
                self.assertIn(f"see verification results at {CLI_REPORT}\n", output)
                self.assertIn(f"View the results at {CLI_REPORT}\n", printed(cv.print_verification_summary))
                self.assertIn(f"For further details visit {CLI_REPORT}\n",
                              printed(cv.print_error_and_status_url, "err"))

    def test_cli_verify_keeps_report_url_internally(self) -> None:
        cv = make_verification()
        cv.vscode_extension_info_writer = MagicMock()
        json_response = {"anonymousKey": "k", "presigned_url": "u", "userId": 1,
                         "reportUrl": APP_REPORT, "jobsUrl": APP_JOBS}
        with patch.object(certoraCloudIO.CloudVerification, "prepare_auth_data", return_value={}), \
                patch.object(certoraCloudIO.CloudVerification, "verification_request", return_value=object()), \
                patch.object(certoraCloudIO, "parse_json", return_value=json_response), \
                patch.object(certoraCloudIO.CloudVerification, "_CloudVerification__compress_and_upload_zip_files",
                             return_value=True), \
                patch.object(certoraCloudIO, "JobList") as job_list_cls, redirect_stdout(io.StringIO()):
            self.assertIsNotNone(cv.cli_verify("cl_args"))
        self.assertEqual(cv.reportUrl, CLI_REPORT)
        self.assertEqual(cv.app_report_url, APP_REPORT)
        self.assertEqual(job_list_cls.return_value.add_job.call_args.args[1], CLI_REPORT)
        cv.vscode_extension_info_writer.add_field.assert_called_once_with("verification_report_url", CLI_REPORT)

    def test_poll_log_error_prints_app_report_url(self) -> None:
        cv = verified_cloud(reportUrl=APP_REPORT)
        cv.context.key = "key"
        cv.logUrl = "https://api.example.com/logs"
        with patch.object(certoraCloudIO, "send_get_request"), \
                patch.object(certoraCloudIO, "is_success_response", return_value=False) as is_success:
            cv.poll_log()
        self.assertEqual(is_success.call_args.args[1], APP_REPORT)

    def test_missing_output_json_refers_to_app_report_url(self) -> None:
        cv = verified_cloud(reportUrl=APP_REPORT)
        cv.context.key = "key"
        cv.context.no_compare = False
        cv.logUrl = "https://api.example.com/logs"
        cv.expected_filename = "expected.json"
        cv.zipOutputUrl = cv.jsonOutputUrl = ""
        cv.max_attempts_to_fetch_output = cv.delay_fetch_output_seconds = 1
        with patch.object(certoraCloudIO.Ctx, "is_minimal_cli_output", return_value=False), \
                patch.object(certoraCloudIO.CloudVerification, "poll_log"), \
                patch.object(certoraCloudIO.CloudVerification, "print_verification_summary"), \
                patch.object(certoraCloudIO.Util, "get_zip_output_url_file", return_value=MagicMock()), \
                patch.object(certoraCloudIO, "check_results_from_web", return_value=False) as check_results, \
                redirect_stdout(io.StringIO()):
            self.assertFalse(cv.cli_report(MagicMock(), "all"))
        self.assertEqual(check_results.call_args.args[4], APP_REPORT)

    def test_staging_links_stay_on_staging_host(self) -> None:
        cv = make_verification()
        cv.context.server = "staging"
        cv.anonymousKey, cv.userId = "k", 1
        cv.set_report_url(cv.userId, cv.anonymousKey)
        cv.done = cv.triggered = False
        output = printed(cv.print_output_links)
        self.assertIn("Manage your jobs at https://vaas-stg.certora.com\n", output)
        self.assertIn("see verification results at https://vaas-stg.certora.com/output/1/run1?anonymousKey=k\n",
                      output)

    def test_api_calls_stay_on_api_host(self) -> None:
        cv = make_verification()
        cv.set_json_output_url(cv.userId, cv.anonymousKey)
        self.assertEqual(cv.jsonOutputUrl, "https://data-api.certora.com/v1/domain/jobs/run1/f/output.json")
        self.assertEqual(certoraCloudIO.Util.get_api_domain("https://app.certora.com"),
                         "https://data-api.certora.com")


if __name__ == "__main__":
    unittest.main()
