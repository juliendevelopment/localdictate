"""Explicit fixtures exercise parsing only, never stand in for a real APK."""
import unittest
from check_android_apk import parse_badging, signer_digest

class ApkMetadataTest(unittest.TestCase):
    fixture = "package: name='be.arte.localdictate' versionCode='100' versionName='0.1.0' platformBuildVersionCode='35'\nsdkVersion:'29'\ntargetSdkVersion:'35'\n"
    def test_release_metadata(self):
        data = parse_badging(self.fixture)
        self.assertEqual("be.arte.localdictate", data["package"])
        self.assertEqual("0.1.0", data["versionName"])
        self.assertEqual(100, data["versionCode"])
        self.assertFalse(data["debuggable"])
    def test_real_aapt_codename_does_not_override_package(self):
        real = "package: name='be.arte.localdictate' versionCode='100' versionName='0.1.0' platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35' compileSdkVersionCodename='15'\nsdkVersion:'29'\ntargetSdkVersion:'35'\n"
        data = parse_badging(real)
        self.assertEqual("be.arte.localdictate", data["package"])
        self.assertEqual("0.1.0", data["versionName"])
    def test_debug_flag_detected(self):
        self.assertTrue(parse_badging(self.fixture + "application-debuggable\n")["debuggable"])
    def test_missing_or_preview_sdk_fails(self):
        for bad in ["", self.fixture.replace("targetSdkVersion:'35'", "targetSdkVersion:'Preview'"), self.fixture.replace("versionName='0.1.0'", "")]:
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError): parse_badging(bad)
    def test_exactly_one_signer(self):
        fixture = "Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n"
        self.assertEqual("a" * 64, signer_digest(fixture))
        for bad in ["", fixture + fixture.replace("#1", "#2"), fixture.replace("a" * 64, "not-a-digest")]:
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError): signer_digest(bad)

if __name__ == "__main__": unittest.main()
