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
from typing import Any, Dict, List
from unittest.mock import MagicMock

from Mutate import mutateApp
from Mutate import mutateConstants as MConstants

APP_MUTATION = "https://app.example.com/mutations/m1"


def notification_after_initiate(resp_obj: Dict[str, Any]) -> str:
    """Runs the initiate request with the given response body, then returns the notification message."""
    app = object.__new__(mutateApp.MutateApp)
    app.mutation_url = ''
    app.orig_run = app.msg = None
    app.server = MConstants.PRODUCTION
    web_utils = MagicMock()
    web_utils.put_json_request_with_timeout.return_value.status_code = 200
    web_utils.put_json_request_with_timeout.return_value.json.return_value = {
        MConstants.ID: "id1", MConstants.COLLECT_SIGNED_URL: "https://upload.example.com", **resp_obj}
    assert app.get_mutation_test_id_request(web_utils, 3) == ("id1", "https://upload.example.com")
    out = io.StringIO()
    with redirect_stdout(out):
        app.print_notification_msg()
    return out.getvalue()


class TestMutationAppLink(unittest.TestCase):
    def test_prints_mutation_url(self) -> None:
        output = notification_after_initiate({"mutationUrl": APP_MUTATION})
        self.assertIn(f"progress at {APP_MUTATION}\n", output)
        self.assertNotIn(MConstants.MUTATION_DASHBOARD_PRODUCTION, output)

    def test_prints_dashboard_when_mutation_url_null_or_absent(self) -> None:
        cases: List[Dict[str, Any]] = [{"mutationUrl": None}, {}]
        for resp_obj in cases:
            with self.subTest(resp_obj=resp_obj):
                output = notification_after_initiate(resp_obj)
                self.assertIn(f"progress at {MConstants.MUTATION_DASHBOARD_PRODUCTION}\n", output)


if __name__ == "__main__":
    unittest.main()
