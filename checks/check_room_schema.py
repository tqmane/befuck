"""Run with python3 checks/check_room_schema.py; no APK or Android SDK needed."""

import json
from pathlib import Path
import re
import sqlite3
import unittest
from check_known_strings import read_pools


class RoomCreationCheck(unittest.TestCase):
    def setUp(self):
        source = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/tqmane/"
                  "befuck/symbols/KnownMappings3970.kt").read_text(encoding="utf-8")
        _, pools = read_pools()
        self.repairs = {}
        for symbol in ("roomCreateFollowFeedInfos", "roomCreateActivityCenterItem"):
            match = re.search(r'"' + symbol + r'" to Pair\("([^"]+)", "([^"]+)"\)', source)
            self.assertIsNotNone(match, f"Missing SQL repair: {symbol}")
            owner, field = match.groups()
            self.repairs[symbol] = pools["L" + owner.replace(".", "/") + ";"][field]
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        for sql in self.repairs.values():
            self.db.execute(sql)

    def test_schema_matches_room_validation(self):
        # q05.g in BeReal 3.97.0 (3597523): name, affinity, NOT NULL,
        # default value, and 1-based position in the primary key.
        expected = {
            "FollowFeedInfos": [
                ("userIdFollower", "TEXT", 1, None, 1),
                ("type", "TEXT", 1, None, 2),
                ("nextCursor", "TEXT", 0, None, 0),
                ("totalCount", "INTEGER", 0, None, 0),
            ],
            "ActivityCenterItemEntity": [
                ("id", "TEXT", 1, None, 1),
                ("seen", "INTEGER", 1, None, 0),
                ("activityType", "TEXT", 1, None, 0),
                ("counterInfo", "INTEGER", 1, None, 0),
                ("mainElementId", "TEXT", 1, None, 0),
                ("lastUpdatedAt", "INTEGER", 1, None, 0),
                ("accountOwnerUid", "TEXT", 1, None, 0),
                ("commentPreview", "TEXT", 0, None, 0),
            ],
        }
        for table, columns in expected.items():
            with self.subTest(table=table):
                actual = [row[1:] for row in self.db.execute(f"PRAGMA table_info(`{table}`)")]
                self.assertEqual(columns, actual)
                self.assertEqual([], self.db.execute(f"PRAGMA foreign_key_list(`{table}`)").fetchall())

    def test_creation_is_idempotent_and_preserves_rows(self):
        self.db.execute("INSERT INTO FollowFeedInfos VALUES ('fixture', 'following', NULL, NULL)")
        for sql in self.repairs.values():
            self.db.execute(sql)
        self.assertEqual(1, self.db.execute("SELECT count(*) FROM FollowFeedInfos").fetchone()[0])
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO FollowFeedInfos VALUES ('fixture', 'following', NULL, NULL)")
        self.db.execute("INSERT INTO FollowFeedInfos VALUES ('fixture', 'followers', NULL, NULL)")

    def test_activity_index_and_child_foreign_key(self):
        # The host runs this index immediately after the repaired CREATE TABLE.
        self.db.execute("CREATE INDEX index_ActivityCenterItemEntity_accountOwnerUid_seen "
                        "ON ActivityCenterItemEntity (accountOwnerUid, seen)")
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.execute("CREATE TABLE child (activityId TEXT REFERENCES ActivityCenterItemEntity(id) "
                        "ON DELETE CASCADE)")
        self.db.execute("INSERT INTO ActivityCenterItemEntity VALUES "
                        "('activity', 0, 'fixture', 1, 'element', 0, 'owner', NULL)")
        self.db.execute("INSERT INTO child VALUES ('activity')")
        self.db.execute("DELETE FROM ActivityCenterItemEntity WHERE id = 'activity'")
        self.assertEqual(0, self.db.execute("SELECT count(*) FROM child").fetchone()[0])


if __name__ == "__main__":
    unittest.main()
