"""Black-box tests for the compact Assurance v2 plan expander."""

import json
from collections.abc import Callable
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
COMMAND = ROOT / "build" / "assurance" / "plan.py"
PLAN = ROOT / "tests" / "assurance" / "plan.json"


class AssurancePlanTests(unittest.TestCase):
    """Verify Assurance v2 behavior through its command-line boundary."""

    def run_command(
        self,
        command: str,
        output: Path,
        plan: Path = PLAN,
        *arguments: str,
    ) -> subprocess.CompletedProcess[str]:
        """Run a public plan command for the supplied plan and output paths."""
        return subprocess.run(
            [
                sys.executable,
                str(COMMAND),
                command,
                str(plan),
                "--output",
                str(output),
                *arguments,
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )

    def assert_invalid(
        self, mutate: Callable[[dict[str, object]], None], expected_error: str
    ) -> None:
        """Assert that validation rejects a modified copy without publishing output."""
        with tempfile.TemporaryDirectory() as temporary:
            plan_path = Path(temporary) / "plan.json"
            output_path = Path(temporary) / "receipt.json"
            plan = json.loads(PLAN.read_text(encoding="utf-8"))
            mutate(plan)
            plan_path.write_text(json.dumps(plan), encoding="utf-8")

            result = self.run_command("validate", output_path, plan_path)

            self.assertEqual(2, result.returncode)
            self.assertIn(expected_error, result.stderr)
            self.assertFalse(output_path.exists())

    def test_compact_plan_expands_all_qualified_assurance_work(self) -> None:
        """Expand every completed family, including all qualified Starfield variants."""
        with tempfile.TemporaryDirectory() as temporary:
            first_path = Path(temporary) / "first.json"
            second_path = Path(temporary) / "second.json"
            first = self.run_command("expand", first_path)
            second = self.run_command("expand", second_path)

            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(first_path.read_bytes(), second_path.read_bytes())
            expanded = json.loads(first_path.read_text(encoding="utf-8"))

        self.assertEqual(
            {
                "bsa-067",
                "bsa-068",
                "bsa-069",
                "fo4-dx10-v7",
                "fo4-dx10-v8",
                "fo4-gnrl-v8",
                "sf-dx10-v2",
                "sf-dx10-v3-m3",
                "sf-gnrl-v2",
                "sf-gnrl-v3-m3",
                "tes3",
            },
            {scenario["capability_id"] for scenario in expanded["assurance_scenarios"]},
        )
        self.assertEqual(
            {"fo4-gnrl-v7"},
            {scenario["capability_id"] for scenario in expanded["incomplete_scenarios"]},
        )
        self.assertTrue(
            any(scenario["scenario_id"] == "decode-entries" for scenario in expanded["assurance_scenarios"])
        )
        self.assertTrue(
            any(scenario["scenario_id"] == "zlib-round-trip" for scenario in expanded["assurance_scenarios"])
        )
        self.assertTrue(all(scenario["test_selectors"] for scenario in expanded["assurance_scenarios"]))
        self.assertTrue(
            all(scenario["semantic_expectation_id"] for scenario in expanded["assurance_scenarios"])
        )
        self.assertTrue(
            any(
                "Bsa069ConformanceIT" in selector
                for scenario in expanded["assurance_scenarios"]
                for selector in scenario["test_selectors"]
            )
        )

        lanes = expanded["performance_lanes"]
        self.assertGreaterEqual(len(lanes), 20)
        self.assertLessEqual(len(lanes), 30)
        represented_paths = {lane["implementation_path"] for lane in lanes}
        for implementation_path in (
            "stored",
            "zlib",
            "lz4-frame",
            "raw-lz4",
            "metadata-heavy",
            "bulk",
            "general-ba2",
            "dds",
            "random-access",
            "peak-memory",
        ):
            self.assertIn(implementation_path, represented_paths)
        self.assertTrue(all(lane["risks"] for lane in lanes))
        scaling = [lane for lane in lanes if lane["surface"] == "scaling"]
        self.assertEqual(1, len(scaling))
        self.assertEqual([1, 2, 4, 8, 16], scaling[0]["workers"])
        self.assertFalse(any(lane["surface"] == "output-size" for lane in lanes))
        self.assertTrue(
            any(
                lane["surface"] == "throughput" and "output-size" in lane["metrics"]
                for lane in lanes
            )
        )

        trace = expanded["traceability"]
        self.assertIn("decode-entries", trace["JBSA-ASR-003"])
        self.assertIn("zlib-round-trip", trace["JBSA-ASR-004"])

    def test_validate_writes_a_deterministic_receipt(self) -> None:
        """Validate the compact plan and summarize its reviewed surfaces."""
        with tempfile.TemporaryDirectory() as temporary:
            first_path = Path(temporary) / "first.json"
            second_path = Path(temporary) / "second.json"
            first = self.run_command("validate", first_path)
            second = self.run_command("validate", second_path)

            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(first_path.read_bytes(), second_path.read_bytes())
            self.assertEqual(
                {
                    "capability_count": 12,
                    "performance_lane_count": 30,
                    "scenario_count": 44,
                    "status": "valid",
                    "version": "assurance-v2",
                },
                json.loads(first_path.read_text(encoding="utf-8")),
            )

    def test_semantic_expectation_identity_ignores_run_identity(self) -> None:
        """Keep behavioral identity stable when candidate and toolchain identity change."""
        with tempfile.TemporaryDirectory() as temporary:
            plan_path = Path(temporary) / "plan.json"
            first_path = Path(temporary) / "first.json"
            second_path = Path(temporary) / "second.json"
            plan = json.loads(PLAN.read_text(encoding="utf-8"))

            first = self.run_command("expand", first_path)
            plan["run_identity"] = {
                "candidate": "different-candidate",
                "jvm": "different-jvm",
                "toolchain": "different-toolchain",
            }
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            second = self.run_command("expand", second_path, plan_path)

            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(0, second.returncode, second.stderr)
            first_rows = json.loads(first_path.read_text(encoding="utf-8"))["assurance_scenarios"]
            second_rows = json.loads(second_path.read_text(encoding="utf-8"))["assurance_scenarios"]

        self.assertEqual(
            [scenario["semantic_expectation_id"] for scenario in first_rows],
            [scenario["semantic_expectation_id"] for scenario in second_rows],
        )

    def test_semantic_expectation_identity_changes_with_asserted_behavior(self) -> None:
        """Reidentify a Semantic Expectation when its asserted observation changes."""
        with tempfile.TemporaryDirectory() as temporary:
            plan_path = Path(temporary) / "plan.json"
            first_path = Path(temporary) / "first.json"
            second_path = Path(temporary) / "second.json"
            plan = json.loads(PLAN.read_text(encoding="utf-8"))

            first = self.run_command("expand", first_path)
            plan["scenarios"][0]["expected"] += " Changed semantics."
            plan_path.write_text(json.dumps(plan), encoding="utf-8")
            second = self.run_command("expand", second_path, plan_path)

            self.assertEqual(0, first.returncode, first.stderr)
            self.assertEqual(0, second.returncode, second.stderr)
            first_rows = json.loads(first_path.read_text(encoding="utf-8"))["assurance_scenarios"]
            second_rows = json.loads(second_path.read_text(encoding="utf-8"))["assurance_scenarios"]

        first_identity = next(
            scenario["semantic_expectation_id"]
            for scenario in first_rows
            if scenario["assurance_scenario_id"] == "tes3:decode-entries"
        )
        second_identity = next(
            scenario["semantic_expectation_id"]
            for scenario in second_rows
            if scenario["assurance_scenario_id"] == "tes3:decode-entries"
        )
        self.assertNotEqual(first_identity, second_identity)

    def test_select_affected_rows_and_fail_closed_for_unknown_impact(self) -> None:
        """Select mapped impact for ordinary changes and promote unknown impact to full."""
        with tempfile.TemporaryDirectory() as temporary:
            affected_path = Path(temporary) / "affected.json"
            unknown_path = Path(temporary) / "unknown.json"

            affected = self.run_command(
                "select",
                affected_path,
                PLAN,
                "--tier",
                "affected",
                "--changed",
                "jbsa/src/main/java/io/github/evildarkarchon/jbsa/internal/tes3/Tes3Reader.java",
            )
            unknown = self.run_command(
                "select",
                unknown_path,
                PLAN,
                "--tier",
                "affected",
                "--changed",
                "unmapped/new-surface.txt",
            )

            self.assertEqual(0, affected.returncode, affected.stderr)
            self.assertEqual(0, unknown.returncode, unknown.stderr)
            affected_selection = json.loads(affected_path.read_text(encoding="utf-8"))
            unknown_selection = json.loads(unknown_path.read_text(encoding="utf-8"))

        self.assertEqual("affected", affected_selection["selected_tier"])
        self.assertTrue(affected_selection["assurance_scenarios"])
        self.assertTrue(
            all(scenario["capability_id"] == "tes3" for scenario in affected_selection["assurance_scenarios"])
        )
        self.assertEqual("full", unknown_selection["selected_tier"])
        self.assertEqual("unknown-impact", unknown_selection["selection_reason"])

    def test_select_accepts_large_changed_path_file(self) -> None:
        """Read PR impact from one file without expanding the Windows process command line."""
        with tempfile.TemporaryDirectory() as temporary:
            changed_file = Path(temporary) / "changed.json"
            output = Path(temporary) / "selection.json"
            changed = [
                f"jbsa/src/main/java/io/github/evildarkarchon/jbsa/internal/tes3/Changed{index:04}.java"
                for index in range(500)
            ]
            changed_file.write_text(json.dumps(changed), encoding="utf-8")

            result = self.run_command(
                "select", output, PLAN, "--tier", "affected", "--changed-file", str(changed_file)
            )

            self.assertEqual(0, result.returncode, result.stderr)
            selection = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(changed, selection["changed_paths"])
            self.assertEqual("affected", selection["selected_tier"])
            self.assertTrue(selection["assurance_scenarios"])

    def test_selects_aggregate_performance_lanes_for_any_affected_capability(self) -> None:
        """A multi-capability decode lane follows any intersecting affected family."""
        with tempfile.TemporaryDirectory() as temporary:
            selected_path = Path(temporary) / "selected.json"
            selected = self.run_command(
                "select",
                selected_path,
                PLAN,
                "--tier",
                "affected",
                "--environment",
                "local",
                "--changed",
                "tests/fixtures/synthetic/artifacts/archives/fo4-dx10-v8-zlib.hex",
            )
            self.assertEqual(0, selected.returncode, selected.stderr)
            lane_ids = {
                lane["lane_id"]
                for lane in json.loads(selected_path.read_text(encoding="utf-8"))[
                    "performance_lanes"
                ]
            }
        self.assertIn("fo4-v78-dds-decode-checkpoint", lane_ids)

    def test_select_shared_core_and_empty_impact_as_full(self) -> None:
        """Fail closed for shared public code and absent change information."""
        with tempfile.TemporaryDirectory() as temporary:
            shared_path = Path(temporary) / "shared.json"
            empty_path = Path(temporary) / "empty.json"
            shared = self.run_command(
                "select",
                shared_path,
                PLAN,
                "--tier",
                "affected",
                "--changed",
                "jbsa/src/main/java/io/github/evildarkarchon/jbsa/BethesdaArchives.java",
            )
            empty = self.run_command("select", empty_path, PLAN, "--tier", "affected")

            self.assertEqual(0, shared.returncode, shared.stderr)
            self.assertEqual(0, empty.returncode, empty.stderr)
            shared_selection = json.loads(shared_path.read_text(encoding="utf-8"))
            empty_selection = json.loads(empty_path.read_text(encoding="utf-8"))

        self.assertEqual("full", shared_selection["selected_tier"])
        self.assertEqual("shared-core-impact", shared_selection["selection_reason"])
        self.assertEqual("full", empty_selection["selected_tier"])
        self.assertEqual("unknown-impact", empty_selection["selection_reason"])

    def test_select_mainline_and_release_run_complete_applicable_matrix(self) -> None:
        """Select every executable Assurance Scenario for mainline and release qualification."""
        with tempfile.TemporaryDirectory() as temporary:
            expanded_path = Path(temporary) / "expanded.json"
            full_path = Path(temporary) / "full.json"
            release_path = Path(temporary) / "release.json"
            expanded = self.run_command("expand", expanded_path)
            full = self.run_command(
                "select", full_path, PLAN, "--tier", "full", "--environment", "hosted"
            )
            release = self.run_command(
                "select", release_path, PLAN, "--tier", "release", "--environment", "release"
            )

            self.assertEqual(0, expanded.returncode, expanded.stderr)
            self.assertEqual(0, full.returncode, full.stderr)
            self.assertEqual(0, release.returncode, release.stderr)
            scenarios = json.loads(expanded_path.read_text(encoding="utf-8"))["assurance_scenarios"]
            full_selection = json.loads(full_path.read_text(encoding="utf-8"))
            release_selection = json.loads(release_path.read_text(encoding="utf-8"))

        hosted_rows = [
            scenario["assurance_scenario_id"]
            for scenario in scenarios
            if "hosted" in scenario["environments"]
        ]
        release_rows = [
            scenario["assurance_scenario_id"]
            for scenario in scenarios
            if "release" in scenario["environments"]
        ]
        self.assertEqual(hosted_rows, [scenario["assurance_scenario_id"] for scenario in full_selection["assurance_scenarios"]])
        self.assertEqual(
            release_rows,
            [scenario["assurance_scenario_id"] for scenario in release_selection["assurance_scenarios"]],
        )
        self.assertTrue(
            all(lane["release_gate"] for lane in release_selection["performance_lanes"])
        )
        self.assertEqual(23, len(release_selection["performance_lanes"]))

    def test_environment_selection_keeps_local_oracle_and_checkpoint_explicit(self) -> None:
        """Represent local-only evidence without making unavailable hosted prerequisites pass."""
        with tempfile.TemporaryDirectory() as temporary:
            hosted_path = Path(temporary) / "hosted.json"
            local_path = Path(temporary) / "local.json"
            hosted = self.run_command(
                "select", hosted_path, PLAN, "--tier", "full", "--environment", "hosted"
            )
            local = self.run_command(
                "select", local_path, PLAN, "--tier", "full", "--environment", "local"
            )
            self.assertEqual(0, hosted.returncode, hosted.stderr)
            self.assertEqual(0, local.returncode, local.stderr)
            hosted_ids = {
                scenario["assurance_scenario_id"]
                for scenario in json.loads(hosted_path.read_text(encoding="utf-8"))["assurance_scenarios"]
            }
            local_selection = json.loads(local_path.read_text(encoding="utf-8"))
            local_ids = {scenario["assurance_scenario_id"] for scenario in local_selection["assurance_scenarios"]}

        self.assertNotIn("bsa-069:oracle-differential", hosted_ids)
        self.assertNotIn("bsa-069:performance-checkpoint", hosted_ids)
        self.assertIn("bsa-069:oracle-differential", local_ids)
        self.assertIn("bsa-069:performance-checkpoint", local_ids)
        self.assertEqual(
            [
                "bsa-069-lz4-checkpoint",
                "fo4-v78-dds-decode-checkpoint",
                "fo4-v78-general-decode-checkpoint",
                "sf-dx10-v2-zlib-checkpoint",
                "sf-dx10-v3-raw-lz4-checkpoint",
                "sf-gnrl-v2-zlib-checkpoint",
                "sf-gnrl-v3-raw-lz4-checkpoint",
            ],
            [lane["lane_id"] for lane in local_selection["performance_lanes"]],
        )

    def test_validate_rejects_duplicate_stable_ids(self) -> None:
        """Reject duplicate capability identifiers before publishing a receipt."""
        def duplicate_capability(plan: dict[str, object]) -> None:
            """Give two capabilities the same stable identifier."""
            plan["capabilities"][1]["id"] = "tes3"  # type: ignore[index]

        self.assert_invalid(duplicate_capability, "duplicate capability id: tes3")

    def test_validate_rejects_unknown_capability_status(self) -> None:
        """Accept only qualified, incomplete, or planned capability states."""
        def replace_status(plan: dict[str, object]) -> None:
            """Replace a reviewed lifecycle status with an unknown value."""
            plan["capabilities"][0]["status"] = "done"  # type: ignore[index]

        self.assert_invalid(replace_status, "unsupported capability status: done")

    def test_validate_rejects_unstable_ids(self) -> None:
        """Reject identifiers whose spelling would make generated scenario names unstable."""
        def replace_scenario_id(plan: dict[str, object]) -> None:
            """Replace a kebab-case scenario identifier with display text."""
            plan["scenarios"][0]["id"] = "Decode Entries"  # type: ignore[index]

        self.assert_invalid(replace_scenario_id, "unstable scenario id: Decode Entries")

    def test_validate_rejects_uncovered_qualified_capability(self) -> None:
        """Reject a qualified capability that no executable scenario selects."""
        def orphan_capability(plan: dict[str, object]) -> None:
            """Move a qualified capability outside every family and risk selector."""
            plan["capabilities"][0]["family"] = "orphan"  # type: ignore[index]
            plan["capabilities"][0]["risk_tags"] = []  # type: ignore[index]

        self.assert_invalid(orphan_capability, "qualified capability has no scenario: tes3")

    def test_validate_rejects_empty_qualified_test_selectors(self) -> None:
        """Reject an executable scenario that has no existing JUnit selector to run."""
        def empty_selector(plan: dict[str, object]) -> None:
            """Remove the bsa-067 selector from its matching zlib scenario."""
            scenario = next(  # type: ignore[assignment]
                item
                for item in plan["scenarios"]  # type: ignore[index]
                if item["id"] == "zlib-round-trip"
            )
            scenario["test_selectors"]["bsa-067"] = []

        self.assert_invalid(
            empty_selector,
            "qualified scenario has no test selectors: bsa-067:zlib-round-trip",
        )

    def test_validate_rejects_more_than_thirty_performance_lanes(self) -> None:
        """Keep the executable performance surface below the v2 lane budget."""
        def expand_lanes(plan: dict[str, object]) -> None:
            """Replace the compact lane set with thirty-one unique lanes."""
            lane = plan["performance"]["lanes"][0]  # type: ignore[index]
            plan["performance"]["lanes"] = [  # type: ignore[index]
                {**lane, "id": f"lane-{index}"} for index in range(31)
            ]

        self.assert_invalid(expand_lanes, "performance plan must contain 20 to 30 lanes")

    def test_validate_requires_one_scaling_workers_vector(self) -> None:
        """Represent worker scaling once, with all worker counts in one vector."""
        def split_scaling(plan: dict[str, object]) -> None:
            """Add a second worker-specific scaling lane."""
            lane = dict(  # type: ignore[arg-type]
                next(
                    item
                    for item in plan["performance"]["lanes"]  # type: ignore[index]
                    if item["surface"] == "scaling"
                )
            )
            lane["id"] = "worker-scaling-four"
            lane["workers"] = [4]
            plan["performance"]["lanes"] = plan["performance"]["lanes"][:-1]  # type: ignore[index]
            plan["performance"]["lanes"].append(lane)  # type: ignore[index]

        self.assert_invalid(split_scaling, "performance plan must have one scaling lane")

    def test_validate_keeps_output_size_on_throughput_lanes(self) -> None:
        """Reject output-size as a standalone measured surface."""
        def separate_output_size(plan: dict[str, object]) -> None:
            """Turn an existing throughput lane into an output-size-only surface."""
            plan["performance"]["lanes"][1]["surface"] = "output-size"  # type: ignore[index]

        self.assert_invalid(
            separate_output_size,
            "output-size must be a metric on a throughput lane",
        )

    def test_validate_rejects_unregistered_requirement_references(self) -> None:
        """Keep requirement-to-scenario traceability closed over the plan registry."""
        def replace_requirement(plan: dict[str, object]) -> None:
            """Point one scenario at a requirement absent from the registry."""
            plan["scenarios"][0]["requirements"] = ["JBSA-CONF-999"]  # type: ignore[index]

        self.assert_invalid(
            replace_requirement,
            "scenario decode-entries references unknown requirement: JBSA-CONF-999",
        )

    def test_validate_requires_family_or_risk_selectors(self) -> None:
        """Limit scenario applicability to compact family and risk-tag selectors."""
        def replace_selector(plan: dict[str, object]) -> None:
            """Replace supported selectors with an unrelated dimension."""
            plan["scenarios"][0]["applies_to"] = {"profiles": ["default"]}  # type: ignore[index]

        self.assert_invalid(
            replace_selector,
            "scenario has no supported applies_to selector: decode-entries",
        )

    def test_qualified_bsa069_inventory_is_executable_and_preserves_evidence(self) -> None:
        """Publish complete 0x69 archetypes with selectors and repository evidence."""
        with tempfile.TemporaryDirectory() as temporary:
            output_path = Path(temporary) / "expanded.json"
            result = self.run_command("expand", output_path)

            self.assertEqual(0, result.returncode, result.stderr)
            expanded = json.loads(output_path.read_text(encoding="utf-8"))

        scenarios = [
            scenario
            for scenario in expanded["assurance_scenarios"]
            if scenario["capability_id"] == "bsa-069"
        ]
        self.assertEqual(
            {
                "decode-entries",
                "embedded-name-round-trip",
                "lz4-frame-round-trip",
                "malformed-input",
                "oracle-differential",
                "performance-checkpoint",
                "sse-cli-selection",
                "sse-flags",
                "sse-layout-padding",
                "sse-lz4-bounded-resources",
                "sse-lz4-cancellation",
                "sse-sharing-splitting-ordering",
                "stored-mixed-round-trip",
                "unsafe-extraction",
                "unsupported-codec-decode",
                "unsupported-codec-encode",
            },
            {scenario["scenario_id"] for scenario in scenarios},
        )
        self.assertFalse(
            any(
                scenario["capability_id"] == "bsa-069"
                for scenario in expanded["incomplete_scenarios"]
            )
        )
        self.assertTrue(all(scenario["test_selectors"] for scenario in scenarios))
        self.assertTrue(all(scenario["evidence_refs"] for scenario in scenarios))
        self.assertTrue(
            all((ROOT / reference).is_file() for scenario in scenarios for reference in scenario["evidence_refs"])
        )
        selectors = {selector for scenario in scenarios for selector in scenario["test_selectors"]}
        for method in (
            "readsAndExtractsIndependentGameVectors",
            "warnsForStoredEmbeddedNames",
            "rejectsMalformedLz4FramesAtContentEof",
            "independentlyValidatesEveryEncodeMode",
            "pinnedOracleCrossDecodesEveryFraming",
            "recordsCurrentMachineCheckpoint",
            "rejectsForeignCodecsBeforeSourceEffects",
            "rejectsCorruptionTruncationTrailingFramesAndWrongOutputSize",
            "normalizedIdentityPreservesNonAsciiSpellingAndRejectsUnsafeOrUnmappableNames",
            "packsAndUnpacksSseLz4Frame",
            "treatsNonzeroFolderPaddingAsNoncanonical",
            "clearsSseMiscellaneousFileFlag",
            "sharesEqualRecordsAndPublishesReadableSplitParts",
            "refusesUnadmittedMemoryBeforeReadingOrWriting",
            "cancelsLz4BeforePublication",
        ):
            self.assertTrue(any(selector.endswith("#" + method) for selector in selectors), method)

        lanes = [
            lane
            for lane in expanded["performance_lanes"]
            if lane.get("capability_id") == "bsa-069"
        ]
        self.assertEqual(1, len(lanes))
        self.assertEqual("qualified", lanes[0]["status"])
        self.assertEqual("development-checkpoint", lanes[0]["qualification_scope"])
        self.assertIn(
            "docs/development/evidence/issue44-performance/measurements.csv",
            lanes[0]["evidence_refs"],
        )
        self.assertIn(
            "docs/development/evidence/issue44-performance/conditions.txt",
            lanes[0]["evidence_refs"],
        )
        self.assertFalse(lanes[0]["release_gate"])
        self.assertEqual("lz4-frame", lanes[0]["codec"])
        self.assertIn("output-size", lanes[0]["metrics"])

        traceability = expanded["traceability"]
        for requirement in (
            "JBSA-BSA-004",
            "JBSA-BSA-013",
            "JBSA-BSA-015",
            "JBSA-CLI-004",
            "JBSA-CLI-005",
            "JBSA-CODEC-008",
            "JBSA-LIB-010",
            "JBSA-SCHED-006",
            "JBSA-SCHED-010",
        ):
            self.assertTrue(traceability[requirement], requirement)

    def test_qualified_starfield_general_inventory_is_executable(self) -> None:
        """Keep both Starfield wire variants complete, selected, and evidence-backed."""
        with tempfile.TemporaryDirectory() as temporary:
            output_path = Path(temporary) / "expanded.json"
            result = self.run_command("expand", output_path)
            self.assertEqual(0, result.returncode, result.stderr)
            expanded = json.loads(output_path.read_text(encoding="utf-8"))

        scenarios = [
            scenario
            for scenario in expanded["assurance_scenarios"]
            if scenario["capability_id"] in {"sf-gnrl-v2", "sf-gnrl-v3-m3"}
        ]
        self.assertFalse(
            any(
                scenario["capability_id"] in {"sf-gnrl-v2", "sf-gnrl-v3-m3"}
                for scenario in expanded["incomplete_scenarios"]
            )
        )
        self.assertTrue(all(scenario["test_selectors"] for scenario in scenarios))
        scenario_ids = {scenario["assurance_scenario_id"] for scenario in scenarios}
        self.assertIn("sf-gnrl-v2:starfield-independent-validation", scenario_ids)
        self.assertIn("sf-gnrl-v3-m3:raw-lz4-round-trip", scenario_ids)
        lanes = {lane["lane_id"]: lane for lane in expanded["performance_lanes"]}
        for lane_id in (
            "sf-gnrl-v2-zlib-checkpoint",
            "sf-gnrl-v3-raw-lz4-checkpoint",
        ):
            self.assertEqual("qualified", lanes[lane_id]["status"])
            self.assertFalse(lanes[lane_id]["release_gate"])
            self.assertTrue(
                all((ROOT / reference).is_file() for reference in lanes[lane_id]["evidence_refs"])
            )

    def test_qualified_starfield_dds_inventory_is_executable(self) -> None:
        """Keep both Starfield DDS codec variants complete and evidence-backed."""
        with tempfile.TemporaryDirectory() as temporary:
            output_path = Path(temporary) / "expanded.json"
            result = self.run_command("expand", output_path)
            self.assertEqual(0, result.returncode, result.stderr)
            expanded = json.loads(output_path.read_text(encoding="utf-8"))

        capability_ids = {"sf-dx10-v2", "sf-dx10-v3-m3"}
        scenarios = [
            scenario
            for scenario in expanded["assurance_scenarios"]
            if scenario["capability_id"] in capability_ids
        ]
        self.assertFalse(
            any(
                scenario["capability_id"] in capability_ids
                for scenario in expanded["incomplete_scenarios"]
            )
        )
        self.assertTrue(all(scenario["test_selectors"] for scenario in scenarios))
        self.assertTrue(all(scenario["evidence_refs"] for scenario in scenarios))
        self.assertTrue(
            all(
                (ROOT / reference).is_file()
                for scenario in scenarios
                for reference in scenario["evidence_refs"]
            )
        )
        scenario_ids = {scenario["assurance_scenario_id"] for scenario in scenarios}
        for capability_id in capability_ids:
            for scenario_id in (
                "decode-entries",
                "malformed-input",
                "oracle-differential",
                "performance-checkpoint",
                "starfield-dds-bounded-resources",
                "starfield-dds-chunk-reconstruction",
                "starfield-dds-cli-selection",
                "starfield-dds-directxtex-validation",
                "starfield-dds-independent-validation",
                "starfield-dds-stored-mixed-decode",
                "unsupported-codec-decode",
                "unsupported-codec-encode",
            ):
                self.assertIn(f"{capability_id}:{scenario_id}", scenario_ids)
        self.assertIn("sf-dx10-v2:zlib-round-trip", scenario_ids)
        self.assertIn("sf-dx10-v3-m3:raw-lz4-round-trip", scenario_ids)

        selectors = {selector for scenario in scenarios for selector in scenario["test_selectors"]}
        for method in (
            "readsAndExtractsProjectAuthoredVersions",
            "writesCodecSelectedHeadersAndRoundTrips",
            "independentlyValidatesStarfieldWireVersions",
            "directXTexAcceptsEveryStarfieldReconstruction",
            "rejectsUnsupportedMethodInvalidRawLz4AndResourceLimits",
            "reconstructsOpaqueMipPayloadAcrossVersionAndCodecInteractions",
            "packagedProfileIdentifiesLevelTwelveRawLz4",
            "pinnedLocalOracleCrossDecodesBothDirections",
        ):
            self.assertTrue(any(selector.endswith("#" + method) for selector in selectors), method)

        lanes = {lane["lane_id"]: lane for lane in expanded["performance_lanes"]}
        for lane_id in (
            "sf-dx10-v2-zlib-checkpoint",
            "sf-dx10-v3-raw-lz4-checkpoint",
        ):
            self.assertEqual("qualified", lanes[lane_id]["status"])
            self.assertEqual("development-checkpoint", lanes[lane_id]["qualification_scope"])
            self.assertFalse(lanes[lane_id]["release_gate"])
            self.assertIn("output-size", lanes[lane_id]["metrics"])
            self.assertTrue(
                all((ROOT / reference).is_file() for reference in lanes[lane_id]["evidence_refs"])
            )

    def test_validate_rejects_missing_qualified_test_selectors(self) -> None:
        """Require qualified scenarios to cite the focused tests that produced their evidence."""
        def remove_selector(plan: dict[str, object]) -> None:
            """Remove one incomplete capability's candidate test selection."""
            del plan["scenarios"][0]["test_selectors"]["bsa-069"]  # type: ignore[index]

        self.assert_invalid(
            remove_selector,
            "qualified scenario has no test selectors: bsa-069:decode-entries",
        )

    def test_validate_rejects_missing_evidence_files(self) -> None:
        """Reject candidate evidence references that do not resolve in the repository."""
        def replace_reference(plan: dict[str, object]) -> None:
            """Point one candidate scenario at a nonexistent evidence artifact."""
            plan["scenarios"][0]["evidence_refs"]["bsa-069"] = [  # type: ignore[index]
                "missing/bsa069-evidence.json"
            ]

        self.assert_invalid(
            replace_reference,
            "referenced evidence file does not exist: missing/bsa069-evidence.json",
        )

    def test_validate_rejects_missing_junit_selectors(self) -> None:
        """Reject candidate selectors that name no method in the referenced test class."""
        def replace_selector(plan: dict[str, object]) -> None:
            """Point one candidate scenario at a nonexistent JUnit method."""
            plan["scenarios"][0]["test_selectors"]["bsa-069"] = [  # type: ignore[index]
                "io.github.evildarkarchon.jbsa.verification.Bsa069ConformanceIT#missingMethod"
            ]

        self.assert_invalid(
            replace_selector,
            "referenced test selector does not exist: "
            "io.github.evildarkarchon.jbsa.verification.Bsa069ConformanceIT#missingMethod",
        )

    def test_traceability_uses_active_assurance_and_behavior_requirements(self) -> None:
        """Exclude retired CV1 and performance-v1 requirement IDs from active output."""
        with tempfile.TemporaryDirectory() as temporary:
            output_path = Path(temporary) / "expanded.json"
            result = self.run_command("expand", output_path)

            self.assertEqual(0, result.returncode, result.stderr)
            traceability = json.loads(output_path.read_text(encoding="utf-8"))["traceability"]

        self.assertTrue("JBSA-ASR-003" in traceability)
        self.assertTrue("JBSA-ASR-004" in traceability)
        self.assertTrue("JBSA-ASR-005" in traceability)
        self.assertFalse(
            any(
                requirement.startswith(("JBSA-CONF-", "JBSA-PERF-"))
                for requirement in traceability
            )
        )

    def test_validate_rejects_release_gating_development_performance(self) -> None:
        """Prevent a qualified development checkpoint from becoming a release lane."""
        def promote_lane(plan: dict[str, object]) -> None:
            """Mark the incomplete 0x69 checkpoint as a release gate."""
            lane = next(  # type: ignore[assignment]
                item
                for item in plan["performance"]["lanes"]  # type: ignore[index]
                if item["id"] == "bsa-069-lz4-checkpoint"
            )
            lane["release_gate"] = True

        self.assert_invalid(
            promote_lane,
            "development checkpoint cannot gate release: bsa-069-lz4-checkpoint",
        )

    def test_validate_rejects_missing_qualified_performance_evidence(self) -> None:
        """Require a qualified capability checkpoint to retain its measurement evidence."""
        def remove_evidence(plan: dict[str, object]) -> None:
            """Remove all evidence from the BSA 0x69 development checkpoint."""
            lane = next(  # type: ignore[assignment]
                item
                for item in plan["performance"]["lanes"]  # type: ignore[index]
                if item["id"] == "bsa-069-lz4-checkpoint"
            )
            del lane["evidence_refs"]

        self.assert_invalid(remove_evidence, "candidate scenario has no evidence references")


if __name__ == "__main__":
    unittest.main()
