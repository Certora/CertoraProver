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
from typing import Optional
from unittest.mock import patch

from CertoraProver.certoraContextClass import CertoraContext
from Shared import proverCommon

APP_REPORT = "https://app.example.com/jobs/run1"
LEGACY_REPORT = "https://prover.certora.com/output/1/run1?anonymousKey=k"


def run_remote_with_app_report_url(app_report_url: str) -> Optional[proverCommon.CertoraRunResult]:
    context = CertoraContext(compilation_steps_only=False, wait_for_results="all")
    with patch.object(proverCommon.Cv, "validate_certora_key", return_value="key"), \
            patch.object(proverCommon, "CloudVerification") as cloud_cls:
        cloud = cloud_cls.return_value
        cloud.reportUrl = LEGACY_REPORT
        cloud.app_report_url = app_report_url
        cloud.cli_report.return_value = True
        _, result = proverCommon.run_remote(context, [], {})
    return result


def violations_output(result: Optional[proverCommon.CertoraRunResult]) -> str:
    @proverCommon.catch_exits
    def entry_point() -> None:
        raise proverCommon.CertoraFoundViolations("violations were found", result)

    out = io.StringIO()
    with redirect_stdout(out), patch.object(proverCommon, "console"):
        try:
            entry_point()
        except SystemExit:
            pass
    return out.getvalue()


class TestRunRemoteAppLink(unittest.TestCase):
    def test_app_report_link_is_set_and_printed(self) -> None:
        result = run_remote_with_app_report_url(APP_REPORT)
        assert result is not None
        self.assertEqual(result.link, LEGACY_REPORT)
        self.assertEqual(result.rule_report_link, LEGACY_REPORT)
        self.assertEqual(result.app_report_link, APP_REPORT)
        output = violations_output(result)
        self.assertIn(f"report url: {APP_REPORT}\n", output)
        self.assertNotIn("anonymousKey", output)

    def test_legacy_link_when_no_app_report_link(self) -> None:
        result = run_remote_with_app_report_url("")
        assert result is not None
        self.assertEqual(result.link, LEGACY_REPORT)
        self.assertEqual(result.rule_report_link, LEGACY_REPORT)
        self.assertIsNone(result.app_report_link)
        self.assertIn(f"report url: {LEGACY_REPORT}\n", violations_output(result))


if __name__ == "__main__":
    unittest.main()
