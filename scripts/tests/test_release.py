import importlib.util
from pathlib import Path
import tempfile
import unittest

MODULE = Path(__file__).resolve().parents[1] / "check-release.py"
spec = importlib.util.spec_from_file_location("check_release", MODULE)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseGuardTest(unittest.TestCase):
    def test_accepts_final_semver_tag(self):
        self.assertEqual("1.6.9", release.release_version("agentic-sdk-v1.6.9"))

    def test_rejects_unsafe_or_non_release_tags(self):
        for tag in ("v1.6.9", "agentic-sdk-v1.6.9-SNAPSHOT", "agentic-sdk-v01.6.9", "agentic-sdk-v1.6", "agentic-sdk-v1.6.9\n", "agentic-sdk-v1.6.9;echo unsafe"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release.release_version(tag)

    def test_rejects_hidden_parent_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><parent/></project>')
            with self.assertRaisesRegex(ValueError, "without a parent"):
                release.read_project(project)

    def test_rejects_stale_example_dependency(self):
        with tempfile.TemporaryDirectory() as directory:
            project = Path(directory)
            (project / "examples").mkdir()
            (project / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>com.aliyun.odps</groupId><artifactId>agentic-sdk</artifactId><version>2.0.0</version></project>')
            (project / "examples/pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><properties><agentic-sdk.version>1.6.9</agentic-sdk.version></properties></project>')
            with self.assertRaisesRegex(ValueError, "example dependency"):
                release.read_project(project)

    def test_current_project_has_resolved_identity(self):
        self.assertTrue(release.read_project(MODULE.parent.parent))


if __name__ == "__main__":
    unittest.main()
