"""Contract tests for build-tool-neutral migration artifact inspection."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

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
            second = root / "second" / "artifact.jar"
            second.parent.mkdir()
            shutil.copy2(first, second)
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
            self.assertEqual(
                paths,
                ["module_descriptor", "public_signatures[class=example.api.Api].signature"],
            )

    def test_java_contract_batches_public_signature_inspection(self):
        """All public classes should share one bounded JDK-tool launch without changing output."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            java_home = root / "jdk"
            (java_home / "bin").mkdir(parents=True)
            (java_home / "bin" / "jar.exe").touch()
            (java_home / "bin" / "javap.exe").touch()
            artifact = root / "fixture.jar"
            with zipfile.ZipFile(artifact, "w") as archive:
                archive.writestr("module-info.class", b"module")
                archive.writestr("example/Alpha.class", b"alpha")
                archive.writestr("example/Beta.class", b"beta")
                archive.writestr("example/Gamma.class", b"gamma")

            javap_output = """Compiled from \"Alpha.java\"
public final class example.Alpha {
  public example.Alpha();
    descriptor: ()V
}
Compiled from \"Beta.java\"
public final class example.Beta {
  public int size();
    descriptor: ()I
}
Compiled from \"Gamma.java\"
public final class example.Gamma {
  public java.lang.String name();
    descriptor: ()Ljava/lang/String;
}
"""

            def completed(command, **_options):
                """Return deterministic jar or batched-javap output at the subprocess boundary."""
                output = (
                    "example.module jar:file:/fixture.jar!/module-info.class\nexports example"
                    if command[0].endswith("jar.exe")
                    else javap_output
                )
                return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")

            with mock.patch.object(parity_artifact_inspector.subprocess, "run", side_effect=completed) as run:
                contract = parity_artifact_inspector.inspect_java_contract(artifact, java_home)

            self.assertEqual(
                [signature["class"] for signature in contract["public_signatures"]],
                ["example.Alpha", "example.Beta", "example.Gamma"],
            )
            self.assertEqual(run.call_count, 2)
            self.assertEqual(
                run.call_args_list[1].args[0][-3:],
                ["example.Alpha", "example.Beta", "example.Gamma"],
            )

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
                ["entries[path=example/Api.class].sha256"],
            )

    def test_benchmark_contract_records_launch_services_classes_and_module_absence(self):
        """A shaded benchmark must expose its launch and service shape without becoming modular."""
        with tempfile.TemporaryDirectory() as temporary:
            jar = Path(temporary) / "benchmarks-standalone.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr(
                    "META-INF/MANIFEST.MF",
                    b"Manifest-Version: 1.0\nMain-Class: org.openjdk.jmh.Main\n\n",
                )
                archive.writestr(
                    "META-INF/services/example.Service",
                    b"example.Second\nexample.First\n",
                )
                archive.writestr("example/ArchiveBenchmark.class", b"benchmark")
                archive.writestr("org/openjdk/jmh/Main.class", b"runner")

            contract = parity_artifact_inspector.inspect_benchmark_contract(jar)

            self.assertEqual(contract["main_class"], "org.openjdk.jmh.Main")
            self.assertEqual(contract["module_descriptors"], [])
            self.assertEqual(contract["benchmark_classes"], ["example/ArchiveBenchmark.class"])
            self.assertEqual(
                contract["service_descriptors"],
                [{"path": "META-INF/services/example.Service", "providers": ["example.First", "example.Second"]}],
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
        reordered["components"][0]["hashes"].insert(0, {"alg": "MD5", "content": "ignored"})
        changed = json.loads(json.dumps(reordered))
        changed["components"][0]["hashes"][1]["content"] = "c" * 64
        changed["dependencies"][0]["dependsOn"].pop()

        normalized = parity_artifact_inspector.normalize_sbom(first)
        self.assertEqual(normalized, parity_artifact_inspector.normalize_sbom(reordered))
        paths = [difference["path"] for difference in parity_artifact_inspector.compare_snapshots(
            normalized, parity_artifact_inspector.normalize_sbom(changed)
        )]
        self.assertEqual(
            paths,
            ["components[bom_ref=a].hashes[0].content", "dependencies[ref=root].depends_on[1]"],
        )

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

            self.assertEqual(
                [difference["path"] for difference in differences],
                ["files[path=renamed.jar]", "files[path=runtime.jar]"],
            )

    def test_keyed_collection_comparison_does_not_shift_later_entries(self):
        """One inserted archive entry must produce one difference rather than shifting its peers."""
        expected = {
            "entries": [
                {"path": "a.txt", "size": 1, "sha256": "a"},
                {"path": "c.txt", "size": 1, "sha256": "c"},
            ]
        }
        actual = {
            "entries": [
                {"path": "a.txt", "size": 1, "sha256": "a"},
                {"path": "b.txt", "size": 1, "sha256": "b"},
                {"path": "c.txt", "size": 1, "sha256": "c"},
            ]
        }

        differences = parity_artifact_inspector.compare_snapshots(expected, actual)

        self.assertEqual(
            differences,
            [{"actual": actual["entries"][1], "expected": None, "path": "entries[path=b.txt]"}],
        )

    def test_gradle_build_layout_locates_canonical_outputs(self):
        """The Gradle inspector must consume declared output identities instead of Maven paths."""
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = root / "target" / "compliance" / "build-layout.json"
            manifest.parent.mkdir(parents=True)
            manifest.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "outputs": [
                            {"id": "library-binary", "path": "jbsa/target/libs/jbsa-1.2.3.jar"},
                            {
                                "id": "library-consumer-pom",
                                "path": "jbsa/target/publications/library/pom-default.xml",
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            outputs = parity_artifact_inspector.load_build_layout(root)

            self.assertEqual(outputs["library-binary"], root / "jbsa/target/libs/jbsa-1.2.3.jar")
            self.assertEqual(
                outputs["library-consumer-pom"],
                root / "jbsa/target/publications/library/pom-default.xml",
            )


if __name__ == "__main__":
    unittest.main()
