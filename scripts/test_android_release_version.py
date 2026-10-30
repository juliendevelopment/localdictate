import unittest
from android_release_version import parse_tag

class AndroidReleaseVersionTest(unittest.TestCase):
    def test_valid_versions(self):
        for tag, name, code in [
            ("android-v0.0.1", "0.0.1", 1),
            ("android-v0.1.0", "0.1.0", 100),
            ("android-v1.2.3", "1.2.3", 10203),
            ("android-v210000.0.0", "210000.0.0", 2100000000),
        ]:
            with self.subTest(tag=tag):
                actual = parse_tag(tag)
                self.assertEqual(name, actual.name)
                self.assertEqual(code, actual.code)

    def test_rejects_aliases_suffixes_and_leading_zeroes(self):
        for tag in ["v1.2.3", "1.2.3", "android-v01.2.3", "android-v1.02.3",
                    "android-v1.2.03", "android-v1.2.3-rc1", "android-v1.2.3+build",
                    " android-v1.2.3", "android-v1.2.3\n", "android-v-1.2.3", "android-v1.2"]:
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError): parse_tag(tag)

    def test_rejects_collisions_zero_and_overflow(self):
        for tag in ["android-v0.0.0", "android-v1.100.0", "android-v1.0.100",
                    "android-v210000.0.1", "android-v999999.0.0"]:
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError): parse_tag(tag)

    def test_ordering_across_component_boundaries(self):
        tags = ["android-v0.0.1", "android-v0.0.99", "android-v0.1.0",
                "android-v0.99.99", "android-v1.0.0", "android-v1.0.1"]
        codes = [parse_tag(tag).code for tag in tags]
        self.assertEqual(codes, sorted(set(codes)))

if __name__ == "__main__": unittest.main()
