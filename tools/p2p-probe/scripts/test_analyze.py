import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


class AnalyzeTests(unittest.TestCase):
    def run_analyzer(self, rows):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "phone-config.json").write_text(json.dumps({
                "rounds": 1, "sockets": 1, "ports": 1,
                "prediction": 0, "timeoutSeconds": 1,
            }), encoding="utf-8")
            (root / "rounds.jsonl").write_text(
                "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8",
            )
            subprocess.run(
                [sys.executable, str(pathlib.Path(__file__).with_name("analyze.py")), str(root)],
                check=True, capture_output=True, text=True,
            )
            return json.loads((root / "summary.json").read_text(encoding="utf-8"))

    def test_empty_observations_have_zero_width(self):
        summary = self.run_analyzer([])
        self.assertEqual(summary["observedPhonePortRangeWidth"], 0)
        self.assertEqual(summary["missingRounds"], [1])

    def test_observed_width_includes_both_endpoints(self):
        rows = []
        for role in ("agent", "phone"):
            rows.append({
                "role": role, "round": 1, "success": True,
                "udpHit": True, "quic": True, "rpc": True,
                "echoBytes": 32768, "path": "direct-ipv4",
                "mappings": [
                    {"server": "local", "mapped": "192.0.2.1:10000"},
                    {"server": "local", "mapped": "192.0.2.1:10099"},
                ],
                "elapsedMs": 1, "punchMs": 1, "probeTx": 1,
                "hitPhase": "candidate",
            })
        summary = self.run_analyzer(rows)
        self.assertEqual(summary["successfulDirectRounds"], 1)
        self.assertEqual(summary["observedPhonePortRangeWidth"], 100)


if __name__ == "__main__":
    unittest.main()
