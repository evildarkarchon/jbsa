"""Behavior checks for the deterministic corpus's public planning and disk seams."""

import copy
import hashlib
import inspect
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest


MODULE = Path(__file__).with_name("corpus.py")


class CorpusTests(unittest.TestCase):
    """Reject altered identities and prove exact workload and texture arithmetic."""

    def setUp(self):
        """Load the script through the same public module used by orchestration."""
        self.assertTrue(MODULE.exists(), "The deterministic corpus implementation is missing")
        spec = importlib.util.spec_from_file_location("corpus", MODULE)
        self.corpus = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.corpus)

    def test_required_workloads_have_exact_counts_totals_and_shared_payloads(self):
        """Verify required workloads have exact counts totals and shared payloads."""
        expected = {"metadata-100k": (100000, 268435456), "mixed-10k": (10000, 2147483648),
                    "bulk-compressible": (8, 2147483648), "bulk-incompressible": (8, 2147483648),
                    "dds-mipmapped": (256, 2147483648), "shared-content": (10000, 1073741824)}
        self.assertEqual(set(expected), set(self.corpus.definitions()))
        for name, (count, size) in expected.items():
            files = self.corpus.plan(name)
            self.assertEqual(count, len(files), name)
            self.assertEqual(size, sum(f["length"] for f in files), name)
            self.assertEqual(count, len({f["path"].casefold() for f in files}), name)
        shared = self.corpus.plan("shared-content")
        self.assertEqual(5000, len({f["payload_id"] for f in shared}))
        self.assertEqual(shared[0]["length"], shared[5000]["length"])
        self.assertEqual(b"".join(self.corpus.file_bytes(shared[0])), b"".join(self.corpus.file_bytes(shared[5000])))

    def test_dds_headers_and_chunks_independently_match_mip_geometry(self):
        """Verify dds headers and chunks independently match mip geometry."""
        formats, dimensions, mips, chunks, cube = set(), set(), set(), set(), set()
        for recipe in self.corpus.plan("dds-mipmapped"):
            self.assertLessEqual(recipe["length"], 67108864, "A balancing texture must not dominate the workload")
            header = next(self.corpus.file_bytes(recipe))
            self.assertEqual(128, len(header))
            self.assertEqual(b"DDS ", header[:4])
            self.assertEqual(124, struct.unpack_from("<I", header, 4)[0])
            self.assertEqual(32, struct.unpack_from("<I", header, 76)[0])
            height, width = struct.unpack_from("<II", header, 12)
            mip_count = struct.unpack_from("<I", header, 28)[0]
            is_cube = bool(struct.unpack_from("<I", header, 112)[0] & 0x200)
            fourcc = header[84:88]
            block = 8 if fourcc in (b"DXT1", b"BC4U") else 16
            lengths = [((max(1,width >> i)+3)//4)*((max(1,height >> i)+3)//4)*block for i in range(mip_count)]
            self.assertEqual(128 + sum(lengths)*(6 if is_cube else 1), recipe["length"])
            spans = recipe["structural"]["chunks"]
            self.assertEqual(recipe["length"]-128, sum(span["length"] for span in spans))
            self.assertEqual(0, spans[0]["offset"])
            for a, b in zip(spans, spans[1:]):
                self.assertEqual(a["offset"]+a["length"], b["offset"])
            formats.add(fourcc); dimensions.add((width,height)); mips.add(mip_count)
            chunks.add(len(spans)); cube.add(is_cube)
        self.assertGreaterEqual(len(formats), 5)
        self.assertGreaterEqual(len(dimensions), 5)
        self.assertGreaterEqual(len(mips), 4)
        self.assertEqual({1,2,3,4}, chunks)
        self.assertEqual({True,False}, cube)

    def small_manifest(self):
        """Use a real bounded recipe while preserving its non-gating scope."""
        recipe = self.corpus.plan("mixed-10k")[0]
        recipe["length"] = 129
        return self.corpus.build_manifest("mixed-10k", files=[recipe])

    def test_materialize_reproduces_hash_and_rejects_changed_bytes_and_extra_paths(self):
        """Verify materialize reproduces hash and rejects changed bytes and extra paths."""
        manifest = self.small_manifest()
        self.assertEqual("smoke", manifest["scope"])
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)/"payload"
            self.corpus.materialize(manifest, root)
            self.corpus.verify_materialization(manifest, root)
            path = root/manifest["files"][0]["path"]
            self.assertEqual(129, path.stat().st_size)
            self.assertEqual(manifest["files"][0]["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            path.write_bytes(b"x"*129)
            with self.assertRaises(ValueError):
                self.corpus.materialize(manifest, root)
            self.corpus.materialize(manifest, Path(temporary)/"second")
            (Path(temporary)/"second"/"unexpected").write_bytes(b"x")
            with self.assertRaises(ValueError):
                self.corpus.verify_materialization(manifest, Path(temporary)/"second")

    def test_manifest_tampering_and_unsafe_paths_fail_before_creating_destination(self):
        """Verify manifest tampering and unsafe paths fail before creating destination."""
        for change in ("digest", "length", "path", "hash"):
            manifest = self.small_manifest()
            if change == "digest": manifest["manifest_sha256"] = "0"*64
            if change == "length": manifest["files"][0]["length"] += 1
            if change == "path": manifest["files"][0]["path"] = "../escape"
            if change == "hash": manifest["files"][0]["sha256"] = "0"*64
            with tempfile.TemporaryDirectory() as temporary:
                destination = Path(temporary)/"payload"
                with self.assertRaises(ValueError): self.corpus.materialize(manifest, destination)
                self.assertFalse(destination.exists())

    def test_resealed_path_and_normative_recipe_changes_are_rejected(self):
        """Verify resealed path and normative recipe changes are rejected."""
        for unsafe in ("../escape", "x//file.bin", "aux.bin", "C:/file.bin", "x/../file.bin"):
            manifest = self.small_manifest()
            manifest["files"][0]["path"] = unsafe
            manifest["manifest_sha256"] = self.corpus.manifest_digest(manifest)
            with self.assertRaises(ValueError): self.corpus.validate_manifest(manifest)
        manifest = self.small_manifest()
        manifest["scope"] = "normative"
        manifest["manifest_sha256"] = self.corpus.manifest_digest(manifest)
        with self.assertRaises(ValueError): self.corpus.validate_manifest(manifest)

    def test_manifest_rejects_smoke_as_qualification_and_digest_mismatched_generation(self):
        """Verify manifest rejects smoke as qualification and digest mismatched generation."""
        manifest = self.small_manifest()
        with self.assertRaises(ValueError): self.corpus.validate_manifest(manifest, require_normative=True)
        manifest["files"][0]["sha256"] = "0"*64
        manifest["manifest_sha256"] = self.corpus.manifest_digest(manifest)
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError): self.corpus.materialize(manifest, temporary)
            self.assertFalse((Path(temporary)/manifest["files"][0]["path"]).exists())

    def test_materialization_rejects_unlisted_empty_directories(self):
        """Verify materialization rejects unlisted empty directories."""
        manifest = self.small_manifest()
        with tempfile.TemporaryDirectory() as temporary:
            self.corpus.materialize(manifest, temporary)
            (Path(temporary)/"unlisted-empty-directory").mkdir()
            with self.assertRaises(ValueError): self.corpus.verify_materialization(manifest, temporary)

    def test_cli_emits_definitions_and_round_trips_committed_manifest_format(self):
        """Verify cli emits definitions and round trips committed manifest format."""
        result = subprocess.run([sys.executable, str(MODULE), "definitions"], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(6, len(json.loads(result.stdout)))
        manifest = self.small_manifest()
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/"manifest.json.gz"
            self.corpus.write_manifest(manifest, path)
            self.assertEqual(manifest, self.corpus.read_manifest(path))
            result = subprocess.run([sys.executable, str(MODULE), "materialize", str(path), str(Path(temporary)/"payload")], capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_committed_full_manifests_validate_and_small_payloads_match_pinned_hashes(self):
        """Verify committed full manifests validate and small payloads match pinned hashes."""
        root = MODULE.parents[2]/"tests"/"performance"/"corpus"
        for workload in self.corpus.definitions():
            manifest = self.corpus.read_manifest(root/(workload+".json.gz"))
            self.corpus.validate_manifest(manifest, require_normative=True)
            for entry in (manifest["files"][0], manifest["files"][-1]):
                if entry["length"] < 1048576:
                    digest = hashlib.sha256()
                    for block in self.corpus.file_bytes(entry): digest.update(block)
                    self.assertEqual(entry["sha256"], digest.hexdigest(), entry["path"])

    def test_dds_specializations_preserve_exact_workloads_and_validate_bytes_not_extensions(self):
        """Verify dds specializations preserve exact workloads and validate bytes not extensions."""
        self.assertIn("variant", inspect.signature(self.corpus.plan).parameters,
                      "DDS workload specializations are not available")
        for workload, count, total in (("metadata-100k",100000,268435456), ("mixed-10k",10000,2147483648),
                                       ("bulk-compressible",8,2147483648), ("bulk-incompressible",8,2147483648)):
            original = self.corpus.plan(workload)
            files = self.corpus.plan(workload, variant="dds-source")
            self.assertEqual(count, len(files))
            self.assertEqual(total, sum(item["length"] for item in files))
            self.assertEqual([item["path"] for item in original], [item["path"] for item in files])
            self.assertEqual([item["algorithm"] for item in original], [item["algorithm"] for item in files])
            for recipe in files:
                header = next(self.corpus.file_bytes(recipe))
                self.assertEqual(b"DDS ", header[:4])
                self.assertEqual(b"DXT1", header[84:88])
                height, width = struct.unpack_from("<II", header, 12)
                self.assertTrue(1 <= width <= 65535 and 1 <= height <= 65535)
                self.assertEqual(1, struct.unpack_from("<I", header, 28)[0])
                self.assertEqual(128+((width+3)//4)*((height+3)//4)*8, recipe["length"])
                self.assertEqual(recipe["length"]-128, recipe["structural"]["chunks"][0]["length"])
            if workload.startswith("bulk-"):
                self.assertEqual({268435456}, {item["length"] for item in files})
            if workload == "mixed-10k":
                self.assertEqual(2000, sum(item["structural"]["extension_no_compress"] for item in files))
                self.assertEqual("no-compress", files[8000]["structural"]["content_class"])

    def test_medium_projection_keeps_mix_and_binds_full_parent_identity(self):
        """Verify medium projection keeps mix and binds full parent identity."""
        self.assertTrue(hasattr(self.corpus, "build_projection"), "Medium corpus projections are not implemented")
        parent = self.corpus.read_manifest(MODULE.parents[2]/"tests/performance/corpus/mixed-10k.json.gz")
        projected = self.corpus.build_projection(parent)
        self.corpus.validate_manifest(projected, require_normative=True)
        self.assertEqual(1000, projected["file_count"])
        self.assertEqual(214748365, projected["logical_bytes"])
        self.assertEqual({"compressible":500,"pseudorandom":300,"no-compress":200}, projected["content_mix"])
        self.assertEqual(parent["manifest_sha256"], projected["projection"]["parent_manifest_sha256"])
        self.assertEqual(parent["files"][0], projected["files"][0])
        self.assertEqual(parent["files"][9990], projected["files"][-1])
        for field in ("parent_manifest_sha256", "parent_recipe_sha256"):
            altered = copy.deepcopy(projected)
            altered["projection"][field] = "0"*64
            altered["manifest_sha256"] = self.corpus.manifest_digest(altered)
            with self.assertRaises(ValueError): self.corpus.validate_manifest(altered, require_normative=True)
        with self.assertRaises(ValueError): self.corpus.build_projection(projected)
        with self.assertRaises(ValueError): self.corpus.build_projection(self.small_manifest())

    def test_dds_specialization_materializes_real_header_on_non_dds_extension(self):
        """Verify dds specialization materializes real header on non dds extension."""
        self.assertIn("variant", inspect.signature(self.corpus.plan).parameters)
        recipe = self.corpus.plan("metadata-100k", variant="dds-source")[0]
        manifest = self.corpus.build_manifest("metadata-100k", files=[recipe], variant="dds-source")
        self.assertEqual("smoke", manifest["scope"])
        self.assertEqual("dds-source", manifest["variant"])
        with tempfile.TemporaryDirectory() as temporary:
            self.corpus.materialize(manifest, temporary)
            path = Path(temporary)/recipe["path"]
            self.assertEqual(".txt", path.suffix)
            data = path.read_bytes()
            self.assertEqual(b"DDS ", data[:4])
            self.assertEqual(recipe["length"], len(data))

    def test_committed_specializations_and_projection_cli_keep_normative_identity(self):
        """Verify committed specializations and projection cli keep normative identity."""
        root = MODULE.parents[2]/"tests/performance/corpus"
        for workload in ("metadata-100k", "mixed-10k", "bulk-compressible", "bulk-incompressible"):
            manifest = self.corpus.read_manifest(root/(workload+"-dds-source.json.gz"))
            self.corpus.validate_manifest(manifest, require_normative=True)
            self.assertEqual("dds-source", manifest["variant"])
            for entry in (manifest["files"][0], manifest["files"][-1]):
                if entry["length"] < 1048576:
                    self.assertEqual(entry["sha256"], hashlib.sha256(b"".join(self.corpus.file_bytes(entry))).hexdigest())
        for name, byte_count in (("mixed-10k",214748365), ("mixed-10k-dds-source",214748416)):
            committed = self.corpus.read_manifest(root/(name+"-medium-1000.json.gz"))
            self.assertEqual(byte_count, committed["logical_bytes"])
            self.assertEqual(1000, committed["file_count"])
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary)/"projection.json.gz"
            result = subprocess.run([sys.executable, str(MODULE), "projection",
                                     str(root/"mixed-10k-dds-source.json.gz"), "--output", str(destination)],
                                    capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            actual = self.corpus.read_manifest(destination)
            expected = self.corpus.read_manifest(root/"mixed-10k-dds-source-medium-1000.json.gz")
            self.assertEqual(expected["manifest_sha256"], actual["manifest_sha256"])

    def test_unknown_variant_and_resealed_dds_geometry_are_invalid(self):
        """Verify unknown variant and resealed dds geometry are invalid."""
        with self.assertRaises(ValueError): self.corpus.plan("mixed-10k", variant="arbitrary")
        with self.assertRaises(ValueError): self.corpus.plan("shared-content", variant="dds-source")
        manifest = self.corpus.read_manifest(MODULE.parents[2]/"tests/performance/corpus/mixed-10k-dds-source.json.gz")
        manifest["files"][0]["structural"]["width"] += 4
        manifest["manifest_sha256"] = self.corpus.manifest_digest(manifest)
        with self.assertRaises(ValueError): self.corpus.validate_manifest(manifest, require_normative=True)


if __name__ == "__main__":
    unittest.main()
