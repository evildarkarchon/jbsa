"""Exercise approval write boundaries in disposable repositories only."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("activate-interface-candidate-review.py")


class ActivationBoundaryTest(unittest.TestCase):
    """Keep invalid identifiers and unrelated approvals outside activation writes."""

    def invoke(self, identifiers, observer):
        """Run the real activation function with a disposable root and mocked verifier."""
        spec = importlib.util.spec_from_file_location("activation", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory(prefix="jbsa-review-test-") as temporary:
            module.ROOT = Path(temporary)
            module.PACKET = module.ROOT / "packet"
            module.PACKET.mkdir()
            destination = module.ROOT / "tests/conformance/rebaselines"
            destination.mkdir(parents=True)
            (module.ROOT / "tests/conformance/catalog.json").write_text("old")
            (module.PACKET / "catalog.json").write_text("new")
            (module.PACKET / "pending-records.json").write_text(json.dumps([{"golden_id": name} for name in identifiers]))
            stale = module.PACKET / "approved-records"
            stale.mkdir()
            (stale / "unrelated.json").write_text("unrelated")
            review = {"proposed_specification_set": []}
            for key, path in {"active_catalog": "tests/conformance/catalog.json", "proposed_catalog": "packet/catalog.json",
                    "pending_records": "packet/pending-records.json", "generator": "packet/catalog.json",
                    "activation_tool": "packet/catalog.json", "baseline_catalog": "packet/catalog.json"}.items():
                review[key] = {"path": path, "sha256": hashlib.sha256((module.ROOT / path).read_bytes()).hexdigest()}
            raw = json.dumps(review).encode()
            (module.PACKET / "review.json").write_bytes(raw)
            with patch("sys.argv", [str(SCRIPT), "--approver", "TEST-ONLY", "--review-sha256", hashlib.sha256(raw).hexdigest()]), patch.object(module.subprocess, "run") as verifier:
                observer(module, verifier, destination)

    def test_invalid_and_duplicate_ids_write_nothing(self):
        """Reject unsafe and duplicate names before approval or catalog writes."""
        for identifiers in (["../escape"], ["valid", "valid"], ["UPPER"], [None]):
            with self.subTest(identifiers=identifiers):
                def observe(module, verifier, destination):
                    """Check rejection leaves the disposable repository untouched."""
                    with self.assertRaises(ValueError):
                        module.activate()
                    verifier.assert_not_called()
                    self.assertEqual([], list(destination.iterdir()))
                    self.assertEqual("old", (module.ROOT / "tests/conformance/catalog.json").read_text())
                self.invoke(identifiers, observe)

    def test_stale_approvals_are_not_imported(self):
        """Copy only exact packet records after successful verifier return."""
        def observe(module, verifier, destination):
            """Verify a fresh approval directory and the exact publication set."""
            def validate(command, **kwargs):
                """Inspect staged test records without running real approval validation."""
                directory = Path(command[command.index("-RecordsDirectory") + 1])
                self.assertNotEqual(directory, module.PACKET / "approved-records")
                self.assertEqual(["reviewed.json"], [p.name for p in directory.iterdir()])
            verifier.side_effect = validate
            module.activate()
            self.assertEqual(["reviewed.json"], [p.name for p in destination.iterdir()])
            self.assertEqual("new", (module.ROOT / "tests/conformance/catalog.json").read_text())
        self.invoke(["reviewed"], observe)


if __name__ == "__main__":
    unittest.main()
