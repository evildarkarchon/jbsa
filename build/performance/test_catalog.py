"""Behavioral checks of required PV1 lanes and targeted selection."""
import copy
import unittest

import catalog


class CatalogTest(unittest.TestCase):
    """A missing family or omitted impact must never shrink qualification silently."""

    def test_matrix_keeps_decode_only_layouts_and_both_sharing_modes(self):
        """Removing a decode lane or sharing case loses independently required coverage."""
        cases = catalog.create_catalog()["cases"]
        for family in ("fo4-gnrl-v7", "fo4-gnrl-v8", "fo4-dx10-v7", "fo4-dx10-v8"):
            matching = [c for c in cases if c["identity"]["archive_family_or_layout"] == family]
            self.assertTrue(matching)
            self.assertTrue(all(c["identity"]["surface"].startswith("unpack") for c in matching))
        shares = [c for c in cases if c["identity"]["workload"] == "shared-content"]
        self.assertEqual({"yes", "no"}, {c["configuration"]["sharing"] for c in shares})

    def test_targeted_selection_cannot_omit_affected_cases(self):
        """A hand-picked subset cannot stand in for the full declared impact."""
        document = catalog.create_catalog()
        impact = {"reason": "zlib change", "selectors": [{"codec": "zlib"}], "case_ids": []}
        with self.assertRaises(ValueError):
            catalog.select_cases(document, "targeted", impact, 16)

    def profile(self):
        """Supply an explicit synthetic runtime profile for identity behavior tests."""
        return {"profile_id": "test-release-profile", "codecs": {
            codec: {"provider": provider, "version": version,
                    "configuration": {"parameters": {"level": 6}, "size_dispatch": {"mode": "streaming"},
                                      "native_configuration": {"mode": "none"}}}
            for codec, provider, version in [("stored", "none", "none"), ("zlib", "jdk", "25.0.4.1+1"),
                                             ("lz4-frame", "lwjgl", "3.4.3-lz4-1.10.0"), ("raw-lz4", "lwjgl", "3.4.3-lz4-1.10.0")]}}

    def test_default_catalog_cannot_masquerade_as_runtime_bound_profile(self):
        """Verify default catalog cannot masquerade as runtime bound profile."""
        document = catalog.create_catalog()
        self.assertIsNone(document["profiles"])
        for case in document["cases"]:
            self.assertFalse(case["configuration"]["profile_bound"])
            self.assertIsNone(case["mapping"]["codec_profile_sha256"])

    def test_runtime_profile_change_creates_new_case_ids_and_exact_mapping(self):
        """Verify runtime profile change creates new case ids and exact mapping."""
        profile = self.profile()
        first = catalog.create_catalog(profile)
        changed = copy.deepcopy(profile)
        changed["codecs"]["zlib"]["configuration"]["parameters"]["level"] = 9
        second = catalog.create_catalog(changed)
        self.assertFalse({c["identity"]["case_id"] for c in first["cases"]} &
                         {c["identity"]["case_id"] for c in second["cases"]})
        for case in first["cases"]:
            self.assertEqual(catalog.digest(profile), case["mapping"]["codec_profile_sha256"])
            self.assertEqual(profile["codecs"][case["mapping"]["codec"]], case["mapping"]["provider_configuration"])
            self.assertTrue(case["configuration"]["profile_bound"])
        self.assertEqual(first["cases"], catalog.select_cases(first, "full", logical_processors=16))

    def test_runtime_profile_requires_every_codec_and_explicit_settings(self):
        """Verify runtime profile requires every codec and explicit settings."""
        for missing in ("zlib", "parameters", "size_dispatch", "native_configuration", "profile_id"):
            profile = self.profile()
            if missing == "zlib": del profile["codecs"][missing]
            elif missing == "profile_id": del profile[missing]
            else: del profile["codecs"]["zlib"]["configuration"][missing]
            with self.assertRaises(ValueError): catalog.create_catalog(profile)

    def test_random_gates_only_have_meaningful_index_and_payload_workloads(self):
        """Verify random gates only have meaningful index and payload workloads."""
        cases = catalog.create_catalog()["cases"]
        metadata = [case for case in cases if case["identity"]["surface"] == "random-metadata"]
        payload = [case for case in cases if case["identity"]["surface"] == "random-payload"]
        self.assertEqual({"metadata-100k"}, {case["identity"]["workload"] for case in metadata})
        self.assertEqual({"bulk-compressible", "bulk-incompressible", "dds-mipmapped"},
                         {case["identity"]["workload"] for case in payload})


if __name__ == "__main__":
    unittest.main()
