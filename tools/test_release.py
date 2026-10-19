import json
from pathlib import Path
import tempfile
import unittest

import release


class ReleaseChecksTest(unittest.TestCase):
    def test_stable_version_parsing(self):
        self.assertEqual(release.read_version('versionName = "0.3.2"\nversionCode = 10'), ("0.3.2", 10))

    def test_prerelease_and_shell_input_are_rejected(self):
        for version in ["0.3.2-debug", "0.3.2; echo bad", "0.3.2\nextra", "v0.3.2", "00.3.2"]:
            with self.subTest(version=version), self.assertRaises(ValueError):
                release.read_version(f'versionName = "{version}"\nversionCode = 10')

    def test_release_must_increase_both_versions(self):
        release.check_new_version("0.3.2", 10, [("0.3.0", 8), ("0.3.1", 9)])
        for version, code in [("0.3.1", 10), ("0.3.0", 10), ("0.3.2", 9), ("0.3.2", 8)]:
            with self.subTest(version=version, code=code), self.assertRaises(ValueError):
                release.check_new_version(version, code, [("0.3.1", 9)])

    def badging(self):
        return "package: name='dev.davplayer.app' versionCode='10' versionName='0.3.2'\nsdkVersion:'26'\nnative-code: 'arm64-v8a'\n"

    def test_valid_release_badging(self):
        release.verify_badging(self.badging(), "0.3.2", 10)

    def test_debuggable_package_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "debuggable"):
            release.verify_badging(self.badging() + "application-debuggable\n", "0.3.2", 10)

    def test_wrong_version_or_architecture_is_rejected(self):
        for badging in [self.badging().replace("versionCode='10'", "versionCode='9'"),
                        self.badging().replace("'arm64-v8a'", "'arm64-v8a' 'x86_64'"),
                        self.badging().replace("dev.davplayer.app", "other.app")]:
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                release.verify_badging(badging, "0.3.2", 10)

    def signature(self):
        return (f"Number of signers: 1\nSigner #1 certificate SHA-256 digest: {release.CERTIFICATE_SHA256}\n"
                "Verified using v2 scheme (APK Signature Scheme v2): true\n"
                "Verified using v3 scheme (APK Signature Scheme v3): true\n")

    def test_fixed_certificate_is_required(self):
        release.verify_signature(self.signature())
        with self.assertRaisesRegex(ValueError, "certificate"):
            release.verify_signature(self.signature().replace(release.CERTIFICATE_SHA256, "0" * 64))

    def test_extra_signers_and_missing_schemes_are_rejected(self):
        for signature in [self.signature().replace("Number of signers: 1", "Number of signers: 10"),
                          self.signature().replace("v2): true", "v2): false"),
                          self.signature().replace("v3): true", "v3): false")]:
            with self.subTest(signature=signature), self.assertRaises(ValueError):
                release.verify_signature(signature)

    def test_source_metadata_must_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for parent in ["app", "playback/mpv", "docs/releases"]:
                (root / parent).mkdir(parents=True, exist_ok=True)
            (root / "app/build.gradle.kts").write_text('versionName = "0.3.2"\nversionCode = 10')
            (root / "playback/mpv/native-sources.json").write_text(json.dumps({"release": "0.3.2"}))
            (root / "SOURCES.md").write_text("Source tag v0.3.2")
            notes = root / "docs/releases/v0.3.2.md"
            notes.write_text("Release notes")
            self.assertEqual(release.metadata(root), ("0.3.2", 10))
            (root / "playback/mpv/native-sources.json").write_text(json.dumps({"release": "0.3.1"}))
            with self.assertRaisesRegex(ValueError, "must match"):
                release.metadata(root)
            (root / "playback/mpv/native-sources.json").write_text(json.dumps({"release": "0.3.2"}))
            notes.unlink()
            with self.assertRaisesRegex(ValueError, "notes"):
                release.metadata(root)

    def test_failed_or_skipped_test_results_block_signing(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for module in ["app", "playback/mpv"]:
                path = root / module / "build/test-results/testReleaseUnitTest"
                path.mkdir(parents=True)
                (path / "TEST-suite.xml").write_text('<testsuite tests="1" errors="0" failures="0" skipped="0"/>')
            lint = root / "app/build/reports/lint-results-release.xml"
            lint.parent.mkdir(parents=True)
            lint.write_text('<issues><issue severity="Warning"/></issues>')
            self.assertEqual(release.test_results(root), ({"app": 1, "playback/mpv": 1}, 1))
            suite = root / "app/build/test-results/testReleaseUnitTest/TEST-suite.xml"
            for field in ["errors", "failures", "skipped"]:
                suite.write_text(f'<testsuite tests="1" {field}="1"/>')
                with self.subTest(field=field), self.assertRaises(ValueError):
                    release.test_results(root)
            suite.write_text('<testsuite tests="1" errors="0" failures="0" skipped="0"/>')
            lint.write_text('<issues><issue severity="Error"/></issues>')
            with self.assertRaisesRegex(ValueError, "Lint"):
                release.test_results(root)


if __name__ == "__main__":
    unittest.main()
