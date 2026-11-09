import json
import os
import sys
import unittest
from unittest.mock import patch

import check_bereal_update as check


BASE = {"version": "3.99.1", "updated": 1793791552}
NEW = {"version": "4.0.0", "updated": 1794396352}


def listing(version="3.99.1", updated=1793791552):
    details = [None] * 146
    details[140] = [[[version]]]
    details[145] = [["Nov 4, 2026", [updated, 0]]]
    data = [None, [None, None, details]]
    return "AF_initDataCallback({key: 'ds:5', hash: 'x', data:" + json.dumps(data) + ", sideChannel: {}});"


class PlayUpdateCheckTest(unittest.TestCase):
    def setUp(self):
        self.checked_in_baseline = check.BASELINE
        baseline = patch.object(check, "BASELINE")
        baseline.start().read_text.return_value = json.dumps(BASE)
        self.addCleanup(baseline.stop)

    def test_extracts_the_app_version_and_timestamp_not_other_page_numbers(self):
        self.assertEqual(BASE, check.parse_listing('<div>9.9.9</div>' + listing()))
        self.assertEqual(NEW, check.parse_listing(listing(**NEW)))

    def test_missing_ambiguous_or_invalid_store_data_is_not_an_update(self):
        for html in ("consent page", listing() + listing(), listing("Varies with device"),
                     listing(updated=None), listing(updated=True), listing(updated=-1),
                     "AF_initDataCallback({key: 'ds:5', data:[]});"):
            with self.subTest(html=html[:60]), self.assertRaises((ValueError, TypeError, IndexError)):
                check.parse_listing(html)

    def test_same_or_older_listing_does_not_contact_github(self):
        for current in (BASE, {**BASE, "updated": BASE["updated"] - 1}):
            with self.subTest(current=current), patch.object(check, "fetch_listing", return_value=current), \
                    patch.object(check, "github_api") as api, patch.object(sys, "argv", ["check", "--create-issue"]):
                check.main()
                api.assert_not_called()

    def test_store_failure_never_creates_an_issue(self):
        with patch.object(check, "fetch_listing", side_effect=ValueError("changed layout")), \
                patch.object(check, "github_api") as api, patch.object(sys, "argv", ["check", "--create-issue"]):
            with self.assertRaises(ValueError):
                check.main()
            api.assert_not_called()

    def test_dry_run_does_not_contact_github(self):
        with patch.object(check, "fetch_listing", return_value=NEW), patch.object(check, "github_api") as api, \
                patch.object(sys, "argv", ["check"]):
            check.main()
            api.assert_not_called()

    def test_new_update_creates_one_issue_with_store_and_run_links(self):
        with patch.object(check, "github_api", side_effect=[[], {"html_url": "https://github.com/example/repo/issues/1"}]) as api:
            result = check.ensure_issue(NEW, BASE, "example/repo", "synthetic-token", "https://github.com/example/repo/actions/runs/1")
        self.assertTrue(result.startswith("Created:"))
        payload = api.call_args.args[2]
        self.assertIn("4.0.0", payload["title"])
        self.assertIn(check.PLAY_URL, payload["body"])
        self.assertIn("/actions/runs/1", payload["body"])

    def test_closed_issues_are_deduplicated_across_pages_but_prs_are_not_issues(self):
        marker = "<!-- bereal-google-play:4.0.0:1794396352 -->"
        first = [{"pull_request": {}, "body": marker}] * 100
        second = [{"state": "closed", "body": marker, "html_url": "https://github.com/example/repo/issues/7"}]
        with patch.object(check, "github_api", side_effect=[first, second]) as api:
            self.assertIn("Already reported:", check.ensure_issue(NEW, BASE, "example/repo", "synthetic-token", "run"))
        self.assertEqual(2, api.call_count)
        self.assertIn("page=2", api.call_args.args[0])

    def test_update_with_same_version_but_new_store_timestamp_is_reportable(self):
        with patch.object(check, "fetch_listing", return_value={**BASE, "updated": NEW["updated"]}), \
                patch.object(check, "ensure_issue", return_value="Created: synthetic") as create, \
                patch.object(sys, "argv", ["check", "--create-issue"]), \
                patch.dict(os.environ, {"GITHUB_REPOSITORY": "example/repo", "GITHUB_RUN_ID": "123", "GH_TOKEN": "synthetic-token"}):
            check.main()
            create.assert_called_once()

    def test_checked_in_baseline_is_valid(self):
        check.validate_snapshot(json.loads(self.checked_in_baseline.read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
