#!/usr/bin/env python3

from __future__ import annotations

import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parents[1]
TIMING_SUMMARY_SAMPLE = Path("/srv/data/jyd/tmp/mul16-latency1-ooc-20260804/lat1/timing_summary_postroute.rpt")
TIMING_PATH_SAMPLE = Path("/srv/data/jyd/tmp/mul16-latency1-ooc-20260804/lat1/timing_paths_postroute.rpt")
UTILIZATION_SAMPLE = Path("/srv/data/jyd/tmp/jyd-mclass-current/jyd-vivado-proj/digital_twin.runs/impl_1/top_utilization_placed.rpt")
BASELINE_UTILIZATION_SAMPLE = Path("/srv/data/jyd/archive/migration-20260801/exp030_postroute_utilization.rpt")
CONGESTION_SAMPLE = Path("/srv/data/jyd/tmp/base311_congestion.rpt")
CPU_TIMING_ARCHIVE = Path("/srv/data/jyd/archive/300-maxfan40-higherdelay-20260809")
CPU_TIMING_SUMMARY_SAMPLE = CPU_TIMING_ARCHIVE / "top_timing_summary_postroute_physopted.rpt"
CPU_TIMING_PATH_SAMPLE = CPU_TIMING_ARCHIVE / "top_timing_setup_top30.rpt"


def load_script(name: str):
    path = SCRIPT_DIR / name
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot import {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


SUMMARY = load_script("extract-vivado-run-summary.py")
ITERATION = load_script("iteration-audit.py")


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def clean_log(path: Path) -> None:
    path.write_text(
        """****** Vivado v2024.2 (64-bit)
Command: link_design -top top
link_design completed successfully
Command: opt_design -directive Explore
opt_design completed successfully
Command: place_design -directive Auto_1
place_design completed successfully
Command: phys_opt_design -directive AggressiveExplore
phys_opt_design completed successfully
Command: route_design -directive NoTimingRelaxation
route_design completed successfully
Command: write_bitstream -force top.bit
write_bitstream completed successfully
DRC errors: 0
Unconstrained Paths: 0
IP status: clean
""",
        encoding="utf-8",
    )


def copy_sample(source: Path, destination: Path) -> None:
    if not source.is_file():
        raise unittest.SkipTest(f"archived Vivado sample is unavailable: {source}")
    shutil.copyfile(source, destination)


def create_fixture(root: Path, runtime: float = 6.5) -> dict[str, object]:
    run_dir = root / "impl_1"
    run_dir.mkdir(parents=True)
    report = run_dir / "top_timing_summary_postroute_physopted.rpt"
    copy_sample(TIMING_SUMMARY_SAMPLE, report)
    path_report_path = run_dir / "timing_paths_postroute.rpt"
    copy_sample(TIMING_PATH_SAMPLE, path_report_path)
    utilization_path = run_dir / "top_utilization_placed.rpt"
    copy_sample(UTILIZATION_SAMPLE, utilization_path)
    congestion_path = run_dir / "top_congestion.rpt"
    copy_sample(CONGESTION_SAMPLE, congestion_path)
    runme = run_dir / "runme.log"
    clean_log(runme)
    vivado = root / "vivado.log"
    clean_log(vivado)
    dcp = run_dir / "top_postroute_physopt.dcp"
    dcp.write_bytes(b"dcp")
    bitstream = root / "top.bit"
    bitstream.write_bytes(b"bitstream")
    input_data = root / "coremark.bin"
    input_data.write_bytes(b"input")
    input_manifest = root / "input-manifest.json"
    input_manifest.write_text(json.dumps({"sha256": digest(input_data)}), encoding="utf-8")
    source_manifest = root / "source-manifest.json"
    source_manifest.write_text(json.dumps({"code_commit": "a" * 40}), encoding="utf-8")
    baseline_utilization_path = BASELINE_UTILIZATION_SAMPLE
    metadata = {
        "code_commit": "a" * 40,
        "frequency_mhz": 300,
        "runtime_s": runtime,
        "strategy": {"name": "default"},
        "input": {"id": "coremark-test", "sha256": digest(input_data)},
        "source_manifest": {"path": str(source_manifest), "sha256": digest(source_manifest)},
        "input_manifest": {"path": str(input_manifest), "sha256": digest(input_manifest)},
        "dcp": {"path": str(dcp), "sha256": digest(dcp)},
        "bitstream": {"path": str(bitstream), "sha256": digest(bitstream)},
    }
    summary = SUMMARY.build_summary(
        root,
        report,
        [runme, vivado],
        metadata,
        "bitstream",
        10,
        bitstream,
        root,
        baseline_utilization_reports=[baseline_utilization_path],
    )
    summary_path = root / "vivado-run-summary.json"
    summary_path.write_text(json.dumps(summary, sort_keys=True), encoding="utf-8")
    return {
        "root": root,
        "run_dir": run_dir,
        "report": report,
        "runme": runme,
        "vivado": vivado,
        "metadata": metadata,
        "summary": summary,
        "summary_path": summary_path,
        "dcp": dcp,
        "bitstream": bitstream,
        "path_report": path_report_path,
        "utilization": utilization_path,
        "congestion": congestion_path,
        "baseline_utilization": baseline_utilization_path,
    }


def sidecar_for(fixture: dict[str, object], experiment_id: str = "EXP-001") -> dict[str, object]:
    root = fixture["root"]
    metadata = fixture["metadata"]
    summary = fixture["summary"]
    bitstream = fixture["bitstream"]
    dcp = fixture["dcp"]
    source_manifest = root / "source-manifest.json"
    input_manifest = root / "input-manifest.json"
    wns = summary["timing"]["summary"]["wns_ns"]
    runtime = summary["performance"]["runtime_s"]
    bitstream_sha = digest(bitstream)
    return {
        "schema": "jyd-optimization-iteration-sidecar",
        "schema_version": 2,
        "experiment": {"id": experiment_id, "title": "fixture"},
        "status": "accepted",
        "identity": {
            "code_commit": metadata["code_commit"],
            "vivado_commit": "b" * 40,
            "frequency_mhz": 300,
            "strategy": {"name": "default"},
            "input": metadata["input"],
            "source_manifest": {"path": source_manifest.name, "sha256": digest(source_manifest)},
            "input_manifest": {"path": input_manifest.name, "sha256": digest(input_manifest)},
            "dcp": {"path": str(Path("impl_1") / dcp.name), "sha256": digest(dcp)},
            "bitstream": {"path": bitstream.name, "sha256": bitstream_sha},
        },
        "results": {
            "vivado_summary": fixture["summary_path"].name,
            "vivado_summary_sha256": digest(fixture["summary_path"]),
            "wns_ns": wns,
            "tns_ns": summary["timing"]["summary"]["tns_ns"],
            "whs_ns": summary["timing"]["summary"]["whs_ns"],
            "ths_ns": summary["timing"]["summary"]["ths_ns"],
            "violated": summary["timing"]["violated"],
            "runtime_s": runtime,
            "report_kind": "postroute_physopted",
        },
        "goal_checks": {
            "wns": {"value": wns, "threshold": -0.3, "operator": ">", "passed": wns > -0.3},
            "runtime": {"value": runtime, "threshold": 6.8, "operator": "<", "passed": runtime < 6.8},
            "passed": wns > -0.3 and runtime < 6.8,
        },
        "evidence_quality": summary["evidence"]["quality"],
        "promotion_level": "candidate",
        "supersedes": None,
        "baseline_id": "BASE-000",
        "method_class": "structural_rtl",
        "decision": "accepted",
        "validation_debt": ["full path-family report"],
        "board": {
            "valid": True,
            "bitstream_sha256": bitstream_sha,
            "samples": [
                {"id": "board-1", "valid": True, "runtime_s": runtime, "bitstream_sha256": bitstream_sha},
                {"id": "board-2", "valid": True, "runtime_s": runtime, "bitstream_sha256": bitstream_sha},
            ],
        },
    }


class WorkflowAuditTest(unittest.TestCase):
    def test_extracts_timing_paths_phases_and_same_origin_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = create_fixture(Path(temporary))
            result = fixture["summary"]
            self.assertEqual(result["schema_version"], 2)
            self.assertEqual(result["timing"]["summary"]["wns_ns"], 0.293)
            self.assertFalse(result["timing"]["violated"])
            self.assertEqual(result["timing"]["critical_paths"][0]["destination"], "<hidden>")
            self.assertEqual(result["timing"]["critical_paths"][0]["route_delay_pct"], 53.854)
            self.assertEqual(result["analysis_schema_version"], 2)
            self.assertEqual(result["timing"]["critical_paths"][0]["source_clock_delay_ns"], 0.537)
            self.assertEqual(result["timing"]["critical_paths"][0]["destination_clock_delay_ns"], 0.51)
            self.assertEqual(result["timing"]["critical_paths"][0]["clock_skew_ns"], -0.027)
            self.assertEqual(result["timing"]["critical_paths"][0]["primitive_histogram"], {"DSP48E1": 1, "FDRE": 1})
            self.assertEqual(result["timing"]["critical_paths"][0]["top_delay_nets"][0]["name"], "<hidden>")
            self.assertEqual(result["timing"]["critical_paths"][0]["top_delay_nets"][0]["fanout"], 1)
            self.assertEqual(result["timing"]["critical_paths"][0]["top_delay_nets"][0]["delay_ns"], 0.302)
            self.assertEqual(result["timing"]["path_families"]["coverage"], "sampled")
            self.assertEqual(result["timing"]["path_families"]["items"][0]["semantic_family"], "setup:unknown")
            self.assertEqual(result["timing"]["clock_groups"]["coverage"], "complete")
            clock_group = result["timing"]["clock_groups"]["items"][0]
            self.assertEqual(clock_group["worst_slack_ns"], 0.293)
            self.assertEqual(clock_group["tns_ns"], 0.0)
            self.assertEqual(clock_group["endpoints"], 0)
            self.assertEqual(clock_group["total_endpoints"], 64)
            self.assertEqual(result["timing"]["resources"]["coverage"], "complete")
            self.assertEqual(result["timing"]["resources"]["counts"]["RAMD64E"], 608)
            self.assertEqual(result["timing"]["resources"]["counts"]["LUT"], 4270)
            self.assertEqual(result["timing"]["resources"]["counts"]["FF"], 2072)
            self.assertEqual(result["timing"]["resources"]["counts"]["BRAM"], 73)
            self.assertEqual(result["timing"]["congestion"]["coverage"], "partial")
            self.assertEqual(result["timing"]["congestion"]["items"], [])
            self.assertEqual(result["implementation"]["status"], "complete")
            self.assertEqual(result["implementation"]["checks"]["drc"]["status"], "pass")
            self.assertEqual(result["implementation"]["checks"]["unconstrained"]["status"], "pass")
            self.assertEqual(result["implementation"]["checks"]["ip"]["status"], "pass")
            self.assertEqual(result["audit"]["status"], "ok")
            self.assertEqual(result["evidence"]["artifact_consistency"]["status"], "verified")
            self.assertEqual(result["identity"]["artifacts"]["bitstream"]["sha256"], digest(fixture["bitstream"]))

    def test_resource_baseline_delta_and_explicit_partial_coverage(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture = create_fixture(root)
            result = SUMMARY.build_summary(
                fixture["root"],
                fixture["report"],
                [fixture["runme"], fixture["vivado"]],
                fixture["metadata"],
                "bitstream",
                10,
                fixture["bitstream"],
                fixture["root"],
                fixture["path_report"],
                [fixture["utilization"]],
                [fixture["congestion"]],
                None,
                [fixture["baseline_utilization"]],
            )
            changes = result["timing"]["resources"]["baseline"]["changes"]
            self.assertEqual(changes["RAMD64E"]["delta"], 128)
            self.assertEqual(changes["RAMD64E"]["relative_change"], 128 / 480)
            self.assertEqual(changes["BRAM"]["delta"], 4)
            parsed = SUMMARY.parse_utilization_report(Path("/srv/data/jyd/tmp/jyd-mclass-current/jyd-vivado-proj/digital_twin.runs/impl_1/top_clock_utilization_routed.rpt"))
            self.assertEqual(parsed["coverage"], "unknown")
            self.assertIsNone(parsed["counts"]["RAMD64E"])
            output = root / "cli-summary.json"
            command = [
                sys.executable,
                str(SCRIPT_DIR / "extract-vivado-run-summary.py"),
                str(root),
                "--report",
                str(fixture["report"]),
                "--path-report",
                str(fixture["path_report"]),
                "--utilization-report",
                str(fixture["utilization"]),
                "--congestion-report",
                str(fixture["congestion"]),
                "--mode",
                "synth",
                "--output",
                str(output),
            ]
            cli = subprocess.run(command, check=False, capture_output=True, text=True)
            self.assertEqual(cli.returncode, 0, cli.stderr)
            cli_summary = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(cli_summary["timing"]["resources"]["counts"]["RAMD64E"], 608)

    def test_cpu_paths_are_compact_structural_data_path_evidence(self) -> None:
        if not CPU_TIMING_SUMMARY_SAMPLE.is_file() or not CPU_TIMING_PATH_SAMPLE.is_file():
            self.skipTest("archived 300 MHz CPU timing samples are unavailable")
        result = SUMMARY.build_summary(
            CPU_TIMING_ARCHIVE,
            CPU_TIMING_SUMMARY_SAMPLE,
            [],
            {},
            "impl",
            10,
            None,
            path_report=CPU_TIMING_PATH_SAMPLE,
            utilization_reports=[],
            congestion_reports=[],
        )
        self.assertEqual(len(result["timing"]["critical_paths"]), 2)
        paths = {path["semantic_family"]: path for path in result["timing"]["critical_paths"]}
        self.assertEqual(paths["setup:btb_lookup_to_fetch_prediction"]["occurrences"], 16)
        self.assertEqual(paths["setup:exu_forward_to_writeback"]["occurrences"], 14)
        self.assertEqual(
            paths["setup:btb_lookup_to_fetch_prediction"]["primitive_histogram"],
            {"FDRE": 2, "LUT5": 2, "LUT6": 4, "MUXF7": 1, "RAMD64E": 1},
        )
        self.assertNotIn("BUFG", result["timing"]["primitive_histogram"]["items"])
        self.assertNotIn("PLLE2_ADV", result["timing"]["primitive_histogram"]["items"])
        btb_net = next(
            item for item in result["timing"]["top_delay_nets"]["items"] if "/btb/" in item["name"]
        )
        self.assertEqual((btb_net["fanout"], btb_net["delay_ns"], btb_net["occurrences"]), (110, 0.74, 16))
        self.assertEqual(result["timing"]["clock_delays"]["unique_sample_count"], 2)
        self.assertEqual(result["timing"]["clock_groups"]["coverage"], "complete")
        self.assertEqual(result["timing"]["path_families"]["coverage"], "sampled")
        self.assertTrue(all(len(path["top_delay_nets"]) <= 5 for path in result["timing"]["critical_paths"]))
        view = SUMMARY.optimization_view(result)
        self.assertEqual(view["schema"], "jyd-vivado-optimization-view")
        self.assertEqual(
            {item["semantic_family"] for item in view["path_families"]},
            {"setup:btb_lookup_to_fetch_prediction", "setup:exu_forward_to_writeback"},
        )
        self.assertLessEqual(len(view["top_delay_nets"]), 5)
        self.assertTrue(all(len(path["top_delay_nets"]) <= 3 for path in view["critical_paths"]))
        self.assertLess(len(json.dumps(view, sort_keys=True)), 12_000)

    def test_goal_boundaries_fail_strictly(self) -> None:
        at_boundary = SUMMARY.goal_checks({"summary": {"wns_ns": -0.3}}, {"runtime_s": 6.8})
        self.assertFalse(at_boundary["passed"])
        self.assertFalse(at_boundary["wns"]["passed"])
        self.assertFalse(at_boundary["runtime"]["passed"])
        passing = SUMMARY.goal_checks({"summary": {"wns_ns": -0.299}}, {"runtime_s": 6.799})
        self.assertTrue(passing["passed"])

    def test_dual_logs_and_hard_constraint_error_are_audited(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            fixture = create_fixture(Path(temporary))
            fixture["vivado"].write_text(
                "ERROR: [Designutils 20-1307] malformed constraint\n"
                "ERROR: [Constraints 18-513] invalid startpoint\n",
                encoding="utf-8",
            )
            result = SUMMARY.build_summary(
                fixture["root"], fixture["report"], [fixture["runme"], fixture["vivado"]], fixture["metadata"], "bitstream", 1, fixture["bitstream"], fixture["root"]
            )
            self.assertEqual(result["implementation"]["log_sources"][0]["role"], "runme")
            self.assertEqual(result["implementation"]["log_sources"][1]["role"], "vivado")
            self.assertEqual(result["implementation"]["checks"]["constraint_hard_error"]["status"], "fail")
            self.assertEqual(
                {item["code"] for item in result["implementation"]["checks"]["constraint_hard_error"]["items"]},
                {"Designutils 20-1307", "Constraints 18-513"},
            )
            self.assertTrue(all(item["classification"] == "constraint_hard_error" for item in result["implementation"]["checks"]["constraint_hard_error"]["items"]))
            self.assertIn("constraint_hard_error_fail", result["audit"]["issues"])
            self.assertEqual(result["audit"]["status"], "error")

    def test_sidecar_summary_conflict_board_hash_duplicate_and_query(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            fixture = create_fixture(root)
            sidecar = sidecar_for(fixture)
            self.assertEqual(ITERATION.validate_sidecar(sidecar, root), [])
            sidecar["results"]["wns_ns"] = -0.1
            self.assertTrue(any("summary conflict: results.wns_ns" in error for error in ITERATION.validate_sidecar(sidecar, root)))
            sidecar = sidecar_for(fixture)
            sidecar["board"]["samples"][1]["bitstream_sha256"] = "c" * 64
            self.assertTrue(any("board.bitstream_sha256" in error for error in ITERATION.validate_sidecar(sidecar, root)))
            sidecar = sidecar_for(fixture)
            sidecar_path = root / "EXP-001.sidecar.json"
            sidecar_path.write_text(json.dumps(sidecar), encoding="utf-8")
            duplicate_path = root / "EXP-002.sidecar.json"
            duplicate = sidecar_for(fixture, "EXP-001")
            duplicate_path.write_text(json.dumps(duplicate), encoding="utf-8")
            index_path = root / "index.jsonl"
            build = subprocess.run(
                [sys.executable, str(SCRIPT_DIR / "iteration-audit.py"), "build-index", str(root), "--output", str(index_path)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(build.returncode, 2)
            duplicate_path.unlink()
            sidecar_path.write_text(json.dumps(sidecar), encoding="utf-8")
            build = subprocess.run(
                [sys.executable, str(SCRIPT_DIR / "iteration-audit.py"), "build-index", str(root), "--output", str(index_path)],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(build.returncode, 0, build.stderr)
            index_entry = json.loads(index_path.read_text(encoding="utf-8").splitlines()[0])
            self.assertEqual(index_entry["clock_groups_coverage"], "complete")
            self.assertEqual(index_entry["path_families_coverage"], "sampled")
            self.assertEqual(index_entry["resources_coverage"], "complete")
            query = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPT_DIR / "iteration-audit.py"),
                    "query",
                    str(index_path),
                    "--frequency",
                    "300",
                    "--wns-gt",
                    "-0.3",
                    "--runtime-lt",
                    "6.8",
                    "--audit-status",
                    "ok",
                    "--evidence-quality",
                    "partial",
                    "--report-kind",
                    "postroute_physopted",
                    "--path-families-coverage",
                    "sampled",
                    "--clock-groups-coverage",
                    "complete",
                    "--resources-coverage",
                    "complete",
                    "--path-family",
                    "setup:unknown",
                    "--has-primitive",
                    "DSP48E1",
                    "--resource-delta-gt",
                    "RAMD64E=100",
                    "--resource-delta-lt",
                    "FF=0",
                    "--board-valid",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            self.assertEqual(query.returncode, 0, query.stderr)
            self.assertEqual(json.loads(query.stdout)["count"], 1)

    def test_run_vivado_flow_defaults_to_strict_audit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "digital_twin.xpr").write_text("fixture", encoding="utf-8")
            fake_vivado = root / "fake-vivado"
            fake_vivado.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
            fake_vivado.chmod(0o755)
            result_dir = root / "result"
            command = [
                str(SCRIPT_DIR / "run-vivado-flow.sh"),
                "--project-root",
                str(root),
                "--mode",
                "impl",
                "--vivado",
                str(fake_vivado),
                "--result-dir",
                str(result_dir),
                "--sample",
                "fixture",
                "--skip-project-update",
            ]
            result = subprocess.run(command, check=False, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue((result_dir / "vivado-run-summary.json").is_file())


if __name__ == "__main__":
    unittest.main()
