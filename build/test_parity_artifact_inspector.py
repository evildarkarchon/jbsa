"""Contract tests for build-tool-neutral migration artifact inspection."""

import json
import os
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import parity_artifact_inspector


class ArtifactInspectorTests(unittest.TestCase):
    """Semantic build differences must be visible without comparing archive envelopes."""

    def _build_modular_jar(self, root: Path, requires_logging: bool, method: str) -> Path:
        """Compile one real modular fixture JAR with the repository's selected JDK."""
        source = root / "source" / "example" / "api"
        classes = root / "classes"
        source.mkdir(parents=True)
        classes.mkdir()
        requirement = "requires java.logging;" if requires_logging else ""
        (root / "source" / "module-info.java").write_text(
            f"module example.module {{ {requirement} exports example.api; }}", encoding="utf-8"
        )
        (source / "Api.java").write_text(
            f"package example.api; public final class Api {{ public {method} }}", encoding="utf-8"
        )
        java_home = Path(os.environ["JAVA_HOME"])
        subprocess.run(
            [
                str(java_home / "bin" / "javac.exe"),
                "-d",
                str(classes),
                str(root / "source" / "module-info.java"),
                str(source / "Api.java"),
            ],
            check=True,
        )
        jar = root / "artifact.jar"
        subprocess.run(
            [str(java_home / "bin" / "jar.exe"), "--create", "--file", str(jar), "-C", str(classes), "."],
            check=True,
        )
        return jar

    def test_java_contract_detects_jpms_and_public_signature_changes(self):
        """Changed exports/requires or public descriptors must invalidate semantic parity."""
        test_target = Path.cwd() / "target"
        test_target.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=test_target) as temporary:
            root = Path(temporary)
            first = self._build_modular_jar(root / "first", False, "String name() { return \"x\"; }")
            second = self._build_modular_jar(root / "second", False, "String name() { return \"x\"; }")
            changed = self._build_modular_jar(root / "changed", True, "long size() { return 1L; }")
            java_home = Path(os.environ["JAVA_HOME"])

            baseline = parity_artifact_inspector.inspect_java_contract(first, java_home)
            self.assertEqual(
                parity_artifact_inspector.compare_snapshots(
                    baseline, parity_artifact_inspector.inspect_java_contract(second, java_home)
                ),
                [],
            )
            paths = [difference["path"] for difference in parity_artifact_inspector.compare_snapshots(
                baseline, parity_artifact_inspector.inspect_java_contract(changed, java_home)
            )]
            self.assertEqual(paths, ["module_descriptor", "public_signatures[0].signature"])

    def test_archive_comparison_ignores_envelope_but_detects_payload_changes(self):
        """A changed entry payload must fail even when ZIP timestamps and ordering are ignored."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first" / "artifact.jar"
            second = root / "second" / "artifact.jar"
            changed = root / "changed" / "artifact.jar"
            first.parent.mkdir()
            second.parent.mkdir()
            changed.parent.mkdir()
            with zipfile.ZipFile(first, "w") as archive:
                archive.writestr("example/Api.class", b"public contract")
                archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
            with zipfile.ZipFile(second, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
                archive.writestr("example/Api.class", b"public contract")
            with zipfile.ZipFile(changed, "w") as archive:
                archive.writestr("example/Api.class", b"changed payload")
                archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")

            matching = parity_artifact_inspector.compare_snapshots(
                parity_artifact_inspector.inspect_archive(first),
                parity_artifact_inspector.inspect_archive(second),
            )
            mismatching = parity_artifact_inspector.compare_snapshots(
                parity_artifact_inspector.inspect_archive(first),
                parity_artifact_inspector.inspect_archive(changed),
            )

            self.assertEqual(matching, [])
            self.assertEqual(
                [difference["path"] for difference in mismatching],
                ["entries[1].sha256"],
            )

    def test_consumer_pom_normalization_retains_publication_semantics(self):
        """Dependency order may vary, but classifier and scope changes must remain observable."""
        first = """<project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>library</artifactId>
          <version>1.2.3</version><name>Library</name><dependencies>
          <dependency><groupId>g</groupId><artifactId>b</artifactId><version>2</version><scope>runtime</scope></dependency>
          <dependency><groupId>g</groupId><artifactId>a</artifactId><version>1</version><classifier>windows</classifier></dependency>
          </dependencies></project>"""
        reordered = first.replace(
            "<dependency><groupId>g</groupId><artifactId>b</artifactId><version>2</version><scope>runtime</scope></dependency>\n          <dependency><groupId>g</groupId><artifactId>a</artifactId><version>1</version><classifier>windows</classifier></dependency>",
            "<dependency><groupId>g</groupId><artifactId>a</artifactId><version>1</version><classifier>windows</classifier></dependency>\n          <dependency><groupId>g</groupId><artifactId>b</artifactId><version>2</version><scope>runtime</scope></dependency>",
        )
        changed = reordered.replace("<classifier>windows</classifier>", "<classifier>linux</classifier>")

        normalized = parity_artifact_inspector.normalize_consumer_pom(first)
        equivalent = parity_artifact_inspector.normalize_consumer_pom(reordered)
        different = parity_artifact_inspector.normalize_consumer_pom(changed)

        self.assertEqual(normalized, equivalent)
        self.assertIsNone(normalized["parent"])
        self.assertEqual(
            normalized["dependencies"][0],
            {
                "artifact_id": "a",
                "classifier": "windows",
                "exclusions": [],
                "group_id": "g",
                "optional": False,
                "scope": "compile",
                "type": "jar",
                "version": "1",
            },
        )
        self.assertEqual(
            [difference["path"] for difference in parity_artifact_inspector.compare_snapshots(normalized, different)],
            ["dependencies[0].classifier"],
        )

    def test_sbom_normalization_detects_hash_and_dependency_edge_changes(self):
        """CycloneDX component ordering is incidental, but hashes and graph edges are contractual."""
        first = {
            "bomFormat": "CycloneDX",
            "specVersion": "1.6",
            "metadata": {"component": {"bom-ref": "root", "group": "example", "name": "root", "version": "1"}},
            "components": [
                {"bom-ref": "b", "group": "g", "name": "b", "version": "2", "purl": "pkg:maven/g/b@2", "hashes": [{"alg": "SHA-256", "content": "b" * 64}]},
                {"bom-ref": "a", "group": "g", "name": "a", "version": "1", "purl": "pkg:maven/g/a@1", "hashes": [{"alg": "SHA-256", "content": "a" * 64}]},
            ],
            "dependencies": [{"ref": "root", "dependsOn": ["b", "a"]}],
        }
        reordered = json.loads(json.dumps(first))
        reordered["components"].reverse()
        reordered["dependencies"][0]["dependsOn"].reverse()
        changed = json.loads(json.dumps(reordered))
        changed["components"][0]["hashes"][0]["content"] = "c" * 64
        changed["dependencies"][0]["dependsOn"].pop()

        normalized = parity_artifact_inspector.normalize_sbom(first)
        self.assertEqual(normalized, parity_artifact_inspector.normalize_sbom(reordered))
        paths = [difference["path"] for difference in parity_artifact_inspector.compare_snapshots(
            normalized, parity_artifact_inspector.normalize_sbom(changed)
        )]
        self.assertEqual(paths, ["components[0].hashes[0].content", "dependencies[0].depends_on[1]"])

    def test_tree_inventory_and_generic_comparison_report_renamed_or_changed_bytes(self):
        """Runtime and staging inventories must retain relative names, sizes, and SHA-256 values."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / "first"
            second = root / "second"
            first.mkdir()
            second.mkdir()
            (first / "runtime.jar").write_bytes(b"same")
            (second / "renamed.jar").write_bytes(b"same")

            differences = parity_artifact_inspector.compare_snapshots(
                parity_artifact_inspector.inspect_tree(first),
                parity_artifact_inspector.inspect_tree(second),
            )

            self.assertEqual([difference["path"] for difference in differences], ["files[0].path"])


if __name__ == "__main__":
    unittest.main()
