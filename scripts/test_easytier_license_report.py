import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("license_report", ROOT / "scripts/easytier-license-report.py")
REPORT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORT)


class LicenseSourceTests(unittest.TestCase):
    def test_actual_raw_github_and_published_crate_symlink_stubs_are_not_notices(self):
        # Exact bad inputs found in rustls-platform-verifier-android and guarden.
        for text in ["../LICENSE-APACHE", "../LICENSE-MIT", "../LICENSE", "../../COPYING", r"..\LICENSE"]:
            self.assertTrue(REPORT.is_notice_pointer(text), text)
        self.assertFalse(REPORT.is_notice_pointer("Permission is hereby granted, free of charge, to any person obtaining a copy"))

    def test_reviewed_supplements_contain_text_and_match_their_source_hashes(self):
        entries = json.loads((ROOT / "config/easytier/supplemental-sources.json").read_text())
        self.assertTrue(entries)
        for entry in entries:
            body = (ROOT / "config/easytier/supplemental" / entry["path"]).read_bytes()
            self.assertTrue(body.strip(), entry["path"])
            self.assertFalse(REPORT.is_notice_pointer(body.decode()), entry["path"])
            self.assertEqual(entry["sha256"], hashlib.sha256(body).hexdigest(), entry["path"])

    def test_resolved_rustls_and_guarden_grants_are_reproduced(self):
        base = ROOT / "config/easytier/supplemental"
        apache = (base / "rustls-platform-verifier-android-0.1.1/LICENSE-APACHE").read_text()
        self.assertIn("TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", apache)
        for relative in ["rustls-platform-verifier-android-0.1.1/LICENSE-MIT", "guarden-0.2.0/LICENSE", "guarden-macros-0.2.0/LICENSE"]:
            self.assertIn("Permission is hereby granted", (base / relative).read_text())
            self.assertIn("THE SOFTWARE IS PROVIDED", (base / relative).read_text())


if __name__ == "__main__":
    unittest.main()
