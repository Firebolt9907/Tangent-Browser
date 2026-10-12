import unittest

from release_version import MAX_VERSION_CODE, release_metadata


class ReleaseVersionTest(unittest.TestCase):
    def test_beta_metadata(self):
        self.assertEqual(release_metadata(12), {
            "tag": "beta-12", "version_name": "beta-12",
            "version_code": 1000012, "prerelease": "true",
        })

    def test_stable_tag_and_manual_input_agree(self):
        self.assertEqual(release_metadata(13, "v1.2.3"), release_metadata(13, "1.2.3"))
        self.assertEqual(release_metadata(13, "1.2.3")["prerelease"], "false")

    def test_version_codes_increase_across_both_feeds(self):
        releases = [release_metadata(7), release_metadata(8, "1.0.0"), release_metadata(9)]
        self.assertEqual([r["version_code"] for r in releases], [1000007, 1000008, 1000009])

    def test_rejects_invalid_and_injected_versions(self):
        for version in ("1.0", "01.0.0", "v1.0.0-beta", "1.0.0\nprerelease=false", "../../apk"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                release_metadata(1, version)

    def test_android_version_code_boundary(self):
        self.assertEqual(release_metadata(1, base=MAX_VERSION_CODE - 1)["version_code"], MAX_VERSION_CODE)
        for run, base in ((0, 1000000), (1, -1), (1, MAX_VERSION_CODE)):
            with self.subTest(run=run, base=base), self.assertRaises(ValueError):
                release_metadata(run, base=base)


if __name__ == "__main__":
    unittest.main()
