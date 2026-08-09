#!/usr/bin/env python3
"""Validate, aggregate, and query JYD optimization iteration sidecars."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any


SCHEMA_NAME = "jyd-optimization-iteration-sidecar"
SCHEMA_VERSION = 2
ANALYSIS_SCHEMA_NAME = "jyd-vivado-timing-analysis"
ANALYSIS_SCHEMA_VERSION = 2
INDEX_SCHEMA_NAME = "jyd-optimization-iteration-index"
INDEX_VERSION = 3
GOAL_WNS_NS = -0.3
GOAL_RUNTIME_S = 10.75
STATUSES = {"planned", "in_progress", "complete", "accepted", "rejected", "partial", "failed"}
EVIDENCE_QUALITIES = {"complete", "partial", "sampled", "unknown", "error"}
PROMOTION_LEVELS = {"none", "candidate", "interim", "baseline", "final"}
DECISIONS = {"planned", "accepted", "rejected", "hold", "final"}
METHOD_CLASSES = {"structural_rtl", "architecture", "measurement", "floorplan", "placement", "route", "synthesis_directive", "other"}
METRICS = ("wns_ns", "tns_ns", "whs_ns", "ths_ns", "violated", "runtime_s")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-fA-F]{7,64}$")
RESOURCE_NAMES = {"RAM64M", "RAMD64E", "LUT", "FF", "BRAM"}


def load_json(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, dict):
        raise ValueError(f"expected a JSON object: {path}")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_sha(value: Any) -> str | None:
    if isinstance(value, dict):
        candidate = value.get("sha256")
        return candidate if isinstance(candidate, str) else None
    if isinstance(value, str) and SHA256_RE.fullmatch(value):
        return value
    return None


def validate_artifact(
    errors: list[str], name: str, value: Any, base_dir: Path | None, required: bool = True
) -> None:
    if value is None:
        if required:
            errors.append(f"identity.{name} is required")
        return
    if not isinstance(value, dict):
        errors.append(f"identity.{name} must be an object")
        return
    path = value.get("path")
    digest = value.get("sha256")
    if not isinstance(path, str) or not path.strip():
        errors.append(f"identity.{name}.path must be a non-empty string")
    if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
        errors.append(f"identity.{name}.sha256 must be a 64-character hex digest")
    if base_dir is None or not isinstance(path, str):
        return
    artifact_path = Path(path)
    if not artifact_path.is_absolute():
        artifact_path = base_dir / artifact_path
    if not artifact_path.is_file():
        errors.append(f"identity.{name}.path does not exist: {path}")
    elif isinstance(digest, str) and SHA256_RE.fullmatch(digest) and sha256_file(artifact_path).lower() != digest.lower():
        errors.append(f"identity.{name}.sha256 does not match the artifact")


def artifact_path(value: Any, base_dir: Path | None) -> Path | None:
    if not isinstance(value, dict) or not isinstance(value.get("path"), str):
        return None
    path = Path(value["path"])
    if not path.is_absolute() and base_dir is not None:
        path = base_dir / path
    return path


def manifest_json(value: Any, base_dir: Path | None) -> dict[str, Any]:
    path = artifact_path(value, base_dir)
    if path is None or path.suffix.lower() != ".json" or not path.is_file() or path.stat().st_size > 10 * 1024 * 1024:
        return {}
    try:
        loaded = load_json(path)
    except (OSError, ValueError, json.JSONDecodeError):
        return {}
    return loaded


def check_goal(value: Any, threshold: float, operator: str, name: str, errors: list[str]) -> bool | None:
    if not isinstance(value, dict):
        errors.append(f"goal_checks.{name} must be an object")
        return None
    actual = value.get("value")
    passed = value.get("passed")
    expected: bool | None
    if actual is None:
        expected = None
    elif not isinstance(actual, (int, float)):
        errors.append(f"goal_checks.{name}.value must be numeric or null")
        expected = None
    elif operator == ">":
        expected = actual > threshold
    else:
        expected = actual < threshold
    if value.get("threshold") != threshold:
        errors.append(f"goal_checks.{name}.threshold must be {threshold}")
    if value.get("operator") != operator:
        errors.append(f"goal_checks.{name}.operator must be {operator!r}")
    if passed is not expected:
        errors.append(f"goal_checks.{name}.passed does not match the strict gate")
    return expected


def summary_path_for(sidecar_path: Path | None, value: Any) -> Path | None:
    if not isinstance(value, str) or not value.strip() or sidecar_path is None:
        return None
    path = Path(value)
    return path if path.is_absolute() else sidecar_path / path


def summary_conflicts(
    errors: list[str], sidecar: dict[str, Any], summary: dict[str, Any], summary_path: Path
) -> None:
    results = sidecar.get("results", {})
    identity = sidecar.get("identity", {})
    summary_identity = summary.get("identity", {})
    summary_timing = summary.get("timing", {})
    summary_metrics = summary_timing.get("summary") if isinstance(summary_timing, dict) else None
    if summary.get("schema") != "jyd-vivado-run-summary" or summary.get("schema_version") != 2:
        errors.append("results.vivado_summary has an unsupported schema")
    if summary.get("analysis_schema") != ANALYSIS_SCHEMA_NAME or summary.get("analysis_schema_version") != ANALYSIS_SCHEMA_VERSION:
        errors.append("results.vivado_summary has an unsupported timing analysis schema")
    summary_hash = results.get("vivado_summary_sha256")
    if not isinstance(summary_hash, str) or not SHA256_RE.fullmatch(summary_hash):
        errors.append("results.vivado_summary_sha256 must be a 64-character hex digest")
    elif sha256_file(summary_path).lower() != summary_hash.lower():
        errors.append("results.vivado_summary_sha256 does not match the summary")
    for field in ("code_commit", "frequency_mhz"):
        expected = identity.get(field)
        actual = summary_identity.get(field)
        if expected is not None and actual is not None and expected != actual:
            errors.append(f"summary conflict: identity.{field}")
    expected_input = identity.get("input")
    actual_input = summary_identity.get("input")
    if isinstance(expected_input, dict) and isinstance(actual_input, dict):
        for field in ("id", "sha256"):
            if expected_input.get(field) is not None and actual_input.get(field) is not None and expected_input[field] != actual_input[field]:
                errors.append(f"summary conflict: identity.input.{field}")
    for metric in ("wns_ns", "tns_ns", "whs_ns", "ths_ns"):
        sidecar_value = results.get(metric)
        summary_value = summary_metrics.get(metric) if isinstance(summary_metrics, dict) else None
        if sidecar_value is not None and summary_value is not None and sidecar_value != summary_value:
            errors.append(f"summary conflict: results.{metric}")
    runtime = results.get("runtime_s")
    summary_runtime = summary.get("performance", {}).get("runtime_s") if isinstance(summary.get("performance"), dict) else None
    if runtime is not None and summary_runtime is not None and runtime != summary_runtime:
        errors.append("summary conflict: results.runtime_s")
    sidecar_report_kind = results.get("report_kind")
    actual_report_kind = summary_timing.get("report_kind") if isinstance(summary_timing, dict) else None
    if sidecar_report_kind is not None and actual_report_kind is not None and sidecar_report_kind != actual_report_kind:
        errors.append("summary conflict: results.report_kind")
    summary_artifacts = summary_identity.get("artifacts", {})
    if isinstance(summary_artifacts, dict):
        for artifact_name in ("source_manifest", "input_manifest", "dcp", "bitstream"):
            sidecar_digest = artifact_sha(identity.get(artifact_name))
            summary_digest = artifact_sha(summary_artifacts.get(artifact_name))
            if sidecar_digest and summary_digest and sidecar_digest.lower() != summary_digest.lower():
                errors.append(f"summary conflict: identity.{artifact_name}.sha256")


def validate_board(board: Any, errors: list[str]) -> None:
    if not isinstance(board, dict):
        errors.append("board must be an object")
        return
    samples = board.get("samples")
    if not isinstance(samples, list):
        errors.append("board.samples must be an array")
        return
    seen: set[str] = set()
    valid_samples: list[dict[str, Any]] = []
    for index, sample in enumerate(samples):
        prefix = f"board.samples[{index}]"
        if not isinstance(sample, dict):
            errors.append(f"{prefix} must be an object")
            continue
        sample_id = sample.get("id")
        if not isinstance(sample_id, str) or not sample_id.strip():
            errors.append(f"{prefix}.id must be a non-empty string")
        elif sample_id in seen:
            errors.append(f"duplicate board sample id: {sample_id}")
        else:
            seen.add(sample_id)
        digest = sample.get("bitstream_sha256")
        if not isinstance(digest, str) or not SHA256_RE.fullmatch(digest):
            errors.append(f"{prefix}.bitstream_sha256 must be a 64-character hex digest")
        runtime = sample.get("runtime_s")
        if runtime is not None and (not isinstance(runtime, (int, float)) or runtime >= GOAL_RUNTIME_S):
            errors.append(f"{prefix}.runtime_s must be below {GOAL_RUNTIME_S}")
        if sample.get("valid") is True:
            valid_samples.append(sample)
    valid_hashes = {sample.get("bitstream_sha256", "").lower() for sample in valid_samples}
    calculated_valid = len(valid_samples) >= 2 and len(valid_hashes) == 1 and "" not in valid_hashes
    if board.get("valid") is not calculated_valid:
        errors.append("board.valid does not match the valid sample set")
    if board.get("valid") is True and len(valid_samples) < 2:
        errors.append("board.valid requires at least two valid samples")
    board_hash = board.get("bitstream_sha256")
    if board_hash is not None:
        if not isinstance(board_hash, str) or not SHA256_RE.fullmatch(board_hash):
            errors.append("board.bitstream_sha256 must be a 64-character hex digest")
        elif valid_hashes and (len(valid_hashes) != 1 or board_hash.lower() not in valid_hashes):
            errors.append("board.bitstream_sha256 does not match valid samples")


def validate_sidecar(
    value: dict[str, Any], base_dir: Path | None = None, known_ids: set[str] | None = None
) -> list[str]:
    errors: list[str] = []
    if value.get("schema") != SCHEMA_NAME:
        errors.append(f"schema must be {SCHEMA_NAME!r}")
    if value.get("schema_version") != SCHEMA_VERSION:
        errors.append(f"schema_version must be {SCHEMA_VERSION}")
    experiment = value.get("experiment")
    if not isinstance(experiment, dict):
        errors.append("experiment must be an object")
        experiment = {}
    for field in ("id", "title"):
        if not isinstance(experiment.get(field), str) or not experiment[field].strip():
            errors.append(f"experiment.{field} must be a non-empty string")
    experiment_id = experiment.get("id")
    if known_ids is not None and isinstance(experiment_id, str):
        if experiment_id in known_ids:
            errors.append(f"duplicate experiment ID: {experiment_id}")
        known_ids.add(experiment_id)
    if value.get("status") not in STATUSES:
        errors.append(f"status must be one of {sorted(STATUSES)}")
    if value.get("method_class") not in METHOD_CLASSES:
        errors.append(f"method_class must be one of {sorted(METHOD_CLASSES)}")
    if value.get("decision") not in DECISIONS:
        errors.append(f"decision must be one of {sorted(DECISIONS)}")
    if value.get("promotion_level") not in PROMOTION_LEVELS:
        errors.append(f"promotion_level must be one of {sorted(PROMOTION_LEVELS)}")
    if value.get("evidence_quality") not in EVIDENCE_QUALITIES:
        errors.append(f"evidence_quality must be one of {sorted(EVIDENCE_QUALITIES)}")
    if not isinstance(value.get("validation_debt"), list) or not all(isinstance(item, str) for item in value["validation_debt"]):
        errors.append("validation_debt must be an array of strings")
    for field in ("supersedes", "baseline_id"):
        if value.get(field) is not None and (not isinstance(value[field], str) or not value[field].strip()):
            errors.append(f"{field} must be a non-empty string or null")
    identity = value.get("identity")
    if not isinstance(identity, dict):
        errors.append("identity must be an object")
        identity = {}
    commit = identity.get("code_commit")
    if not isinstance(commit, str) or not COMMIT_RE.fullmatch(commit):
        errors.append("identity.code_commit must be a hexadecimal commit")
    if identity.get("vivado_commit") is not None and (
        not isinstance(identity.get("vivado_commit"), str) or not COMMIT_RE.fullmatch(identity["vivado_commit"])
    ):
        errors.append("identity.vivado_commit must be a hexadecimal commit")
    if not isinstance(identity.get("frequency_mhz"), (int, float)):
        errors.append("identity.frequency_mhz must be a number")
    if not isinstance(identity.get("strategy"), (str, dict)):
        errors.append("identity.strategy is required")
    if not isinstance(identity.get("input"), (str, dict)):
        errors.append("identity.input is required")
    for name in ("source_manifest", "input_manifest", "dcp", "bitstream"):
        validate_artifact(errors, name, identity.get(name), base_dir)
    input_value = identity.get("input")
    input_sha = input_value.get("sha256") if isinstance(input_value, dict) else None
    if input_sha is not None and (not isinstance(input_sha, str) or not SHA256_RE.fullmatch(input_sha)):
        errors.append("identity.input.sha256 must be a 64-character hex digest")
    source_values = manifest_json(identity.get("source_manifest"), base_dir)
    source_commit = source_values.get("code_commit") or source_values.get("commit")
    if source_commit is not None and source_commit != commit:
        errors.append("source manifest commit conflicts with identity.code_commit")
    input_values = manifest_json(identity.get("input_manifest"), base_dir)
    manifest_input_sha = input_values.get("sha256") or input_values.get("input_sha256")
    if manifest_input_sha is not None and input_sha is not None and manifest_input_sha != input_sha:
        errors.append("input manifest hash conflicts with identity.input.sha256")
    bitstream_value = identity.get("bitstream")
    dcp_value = identity.get("dcp")
    expected_dcp = bitstream_value.get("dcp_sha256") if isinstance(bitstream_value, dict) else None
    actual_dcp = artifact_sha(dcp_value)
    if expected_dcp is not None and expected_dcp != actual_dcp:
        errors.append("identity.bitstream.dcp_sha256 conflicts with identity.dcp.sha256")
    results = value.get("results")
    if not isinstance(results, dict):
        errors.append("results must be an object")
        results = {}
    summary_name = results.get("vivado_summary")
    if not isinstance(summary_name, str) or not summary_name.strip():
        errors.append("results.vivado_summary must be a non-empty string")
    for metric in METRICS:
        metric_value = results.get(metric)
        if metric_value is not None and not isinstance(metric_value, (int, float, bool)):
            errors.append(f"results.{metric} must be numeric, boolean, or null")
    if results.get("runtime_s") is not None and not isinstance(results.get("runtime_s"), (int, float)):
        errors.append("results.runtime_s must be numeric or null")
    goals = value.get("goal_checks")
    if not isinstance(goals, dict):
        errors.append("goal_checks must be an object")
    else:
        wns_pass = check_goal(goals.get("wns"), GOAL_WNS_NS, ">", "wns", errors)
        runtime_pass = check_goal(goals.get("runtime"), GOAL_RUNTIME_S, "<", "runtime", errors)
        expected_all = wns_pass and runtime_pass if wns_pass is not None and runtime_pass is not None else None
        if goals.get("passed") is not expected_all:
            errors.append("goal_checks.passed does not match the strict gates")
        if isinstance(goals.get("wns"), dict) and goals["wns"].get("value") != results.get("wns_ns"):
            errors.append("goal_checks.wns.value conflicts with results.wns_ns")
        if isinstance(goals.get("runtime"), dict) and goals["runtime"].get("value") != results.get("runtime_s"):
            errors.append("goal_checks.runtime.value conflicts with results.runtime_s")
    board = value.get("board")
    validate_board(board, errors)
    summary_path = summary_path_for(base_dir, summary_name) if base_dir is not None else None
    summary: dict[str, Any] | None = None
    if summary_path is not None:
        if not summary_path.is_file():
            errors.append(f"results.vivado_summary does not exist: {summary_name}")
        else:
            try:
                summary = load_json(summary_path)
            except (OSError, ValueError, json.JSONDecodeError) as exc:
                errors.append(f"results.vivado_summary cannot be read: {exc}")
    if summary is not None:
        summary_conflicts(errors, value, summary, summary_path)
        summary_quality = summary.get("evidence", {}).get("quality") if isinstance(summary.get("evidence"), dict) else None
        if summary_quality is not None and value.get("evidence_quality") != summary_quality:
            errors.append("summary conflict: evidence_quality")
        summary_audit = summary.get("audit", {}).get("status") if isinstance(summary.get("audit"), dict) else None
        if value.get("promotion_level") in {"baseline", "final"} and summary_audit != "ok":
            errors.append("formal promotion requires summary audit status ok")
        report_kind = summary.get("timing", {}).get("report_kind") if isinstance(summary.get("timing"), dict) else None
        if report_kind == "routed" and value.get("decision") in {"accepted", "final"}:
            errors.append("routed fallback cannot be used for formal selection")
    if value.get("decision") == "final" or value.get("promotion_level") == "final":
        if value.get("status") == "accepted" or value.get("decision") != "final" or value.get("promotion_level") != "final":
            errors.append("accepted is not final; final requires decision=final and promotion_level=final")
        if value.get("evidence_quality") != "complete":
            errors.append("final promotion requires complete evidence")
        final_goals = value.get("goal_checks")
        if not isinstance(final_goals, dict) or final_goals.get("passed") is not True:
            errors.append("final promotion requires passing goal checks")
    if value.get("promotion_level") in {"baseline", "final"} and value.get("evidence_quality") in {"unknown", "sampled", "error"}:
        errors.append("baseline/final promotion cannot use unknown, sampled, or error evidence")
    return errors


def json_report(path: Path, valid: bool, errors: list[str]) -> dict[str, Any]:
    return {"path": str(path), "schema": SCHEMA_NAME, "schema_version": SCHEMA_VERSION, "valid": valid, "errors": errors}


def input_id(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for field in ("id", "name", "sha256", "hash"):
            if value.get(field):
                return str(value[field])
    return None


def strategy_name(value: Any) -> str | None:
    if isinstance(value, str):
        return value
    return value.get("name") if isinstance(value, dict) else None


def summary_metric(sidecar_path: Path, sidecar: dict[str, Any], metric: str) -> Any:
    results = sidecar.get("results", {})
    if results.get(metric) is not None:
        return results[metric]
    summary_name = results.get("vivado_summary")
    if not isinstance(summary_name, str):
        return None
    summary_path = sidecar_path.parent / summary_name
    if not summary_path.is_file():
        return None
    try:
        summary = load_json(summary_path)
    except (OSError, ValueError, json.JSONDecodeError):
        return None
    if metric == "violated":
        return summary.get("timing", {}).get("violated")
    if metric == "runtime_s":
        return summary.get("performance", {}).get("runtime_s")
    return summary.get("timing", {}).get("summary", {}).get(metric)


def index_entry(root: Path, path: Path, sidecar: dict[str, Any]) -> dict[str, Any]:
    identity = sidecar["identity"]
    experiment = sidecar["experiment"]
    board = sidecar["board"]
    valid_samples = [sample for sample in board.get("samples", []) if sample.get("valid") is True]
    summary_path = path.parent / sidecar["results"]["vivado_summary"]
    summary: dict[str, Any] = load_json(summary_path)
    timing = summary.get("timing", {})
    path_families = timing.get("path_families", {})
    resources = timing.get("resources", {})
    baseline = resources.get("baseline") if isinstance(resources, dict) else None
    resource_changes = baseline.get("changes") if isinstance(baseline, dict) else None
    return {
        "schema": INDEX_SCHEMA_NAME,
        "schema_version": INDEX_VERSION,
        "experiment_id": experiment["id"],
        "title": experiment["title"],
        "status": sidecar["status"],
        "sidecar": str(path.relative_to(root)),
        "record_path": experiment.get("record_path"),
        "code_commit": identity.get("code_commit"),
        "vivado_commit": identity.get("vivado_commit"),
        "frequency_mhz": identity.get("frequency_mhz"),
        "strategy": strategy_name(identity.get("strategy")),
        "method_class": sidecar.get("method_class"),
        "input_id": input_id(identity.get("input")),
        "bitstream_sha256": artifact_sha(identity.get("bitstream")),
        "source_manifest_sha256": artifact_sha(identity.get("source_manifest")),
        "input_manifest_sha256": artifact_sha(identity.get("input_manifest")),
        "dcp_sha256": artifact_sha(identity.get("dcp")),
        "wns_ns": summary_metric(path, sidecar, "wns_ns"),
        "tns_ns": summary_metric(path, sidecar, "tns_ns"),
        "whs_ns": summary_metric(path, sidecar, "whs_ns"),
        "ths_ns": summary_metric(path, sidecar, "ths_ns"),
        "violated": summary_metric(path, sidecar, "violated"),
        "runtime_s": sidecar.get("results", {}).get("runtime_s"),
        "report_kind": timing.get("report_kind"),
        "clock_groups_coverage": timing.get("clock_groups", {}).get("coverage", timing.get("clock_groups", {}).get("status")),
        "path_families_coverage": path_families.get("coverage", path_families.get("status")),
        "path_family_names": sorted(
            item["semantic_family"]
            for item in path_families.get("items", [])
            if isinstance(item, dict) and isinstance(item.get("semantic_family"), str)
        ),
        "critical_path_primitives": timing.get("primitive_histogram", {}).get("items", {}),
        "top_delay_nets": timing.get("top_delay_nets", {}).get("items", [])[:10],
        "clock_delay_samples": timing.get("clock_delays", {}).get("items", [])[:10],
        "resources_coverage": resources.get("coverage", resources.get("status")),
        "resource_counts": resources.get("counts"),
        "resource_deltas": resource_changes if isinstance(resource_changes, dict) else None,
        "audit_status": summary.get("audit", {}).get("status"),
        "evidence_quality": sidecar.get("evidence_quality"),
        "promotion_level": sidecar.get("promotion_level"),
        "supersedes": sidecar.get("supersedes"),
        "baseline_id": sidecar.get("baseline_id"),
        "decision": sidecar.get("decision"),
        "validation_debt": sidecar.get("validation_debt"),
        "goal_checks": sidecar.get("goal_checks"),
        "board_valid": board.get("valid") is True,
        "board_sample_count": len(valid_samples),
        "board_bitstream_sha256": board.get("bitstream_sha256"),
    }


def command_validate(args: argparse.Namespace) -> int:
    try:
        value = load_json(args.sidecar)
        errors = validate_sidecar(value, args.sidecar.parent)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        errors = [str(exc)]
    report = json_report(args.sidecar, not errors, errors)
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if not errors else 2


def command_build_index(args: argparse.Namespace) -> int:
    root = args.source if args.source.is_dir() else args.source.parent
    paths = [args.source] if args.source.is_file() else sorted(args.source.rglob("*.sidecar.json"))
    entries: list[dict[str, Any]] = []
    failures: list[str] = []
    seen_ids: set[str] = set()
    for path in paths:
        try:
            sidecar = load_json(path)
            errors = validate_sidecar(sidecar, path.parent, seen_ids)
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            failures.append(f"{path}: {exc}")
            continue
        if errors:
            failures.extend(f"{path}: {error}" for error in errors)
            continue
        entries.append(index_entry(root, path, sidecar))
    if failures:
        for failure in failures:
            print(failure, file=sys.stderr)
        return 2
    rendered = "".join(json.dumps(entry, ensure_ascii=False, sort_keys=True) + "\n" for entry in entries)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(rendered, encoding="utf-8")
    print(json.dumps({"output": str(args.output), "entries": len(entries)}, sort_keys=True))
    return 0


def read_index(path: Path) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as file:
        for line_number, line in enumerate(file, start=1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"index line {line_number} is not an object")
            entries.append(value)
    return entries


def command_query(args: argparse.Namespace) -> int:
    try:
        entries = read_index(args.index)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"index query failed: {exc}", file=sys.stderr)
        return 1
    filtered: list[dict[str, Any]] = []
    for entry in entries:
        if args.status and entry.get("status") != args.status:
            continue
        if args.strategy and entry.get("strategy") != args.strategy:
            continue
        if args.input_id and entry.get("input_id") != args.input_id:
            continue
        if args.frequency is not None and entry.get("frequency_mhz") != args.frequency:
            continue
        if args.audit_status and entry.get("audit_status") != args.audit_status:
            continue
        if args.evidence_quality and entry.get("evidence_quality") != args.evidence_quality:
            continue
        if args.report_kind and entry.get("report_kind") != args.report_kind:
            continue
        if args.path_families_coverage and entry.get("path_families_coverage") != args.path_families_coverage:
            continue
        if args.resources_coverage and entry.get("resources_coverage") != args.resources_coverage:
            continue
        if args.clock_groups_coverage and entry.get("clock_groups_coverage") != args.clock_groups_coverage:
            continue
        if args.path_family and not all(name in entry.get("path_family_names", []) for name in args.path_family):
            continue
        if args.has_primitive and not all(
            entry.get("critical_path_primitives", {}).get(name, 0) > 0 for name in args.has_primitive
        ):
            continue
        if args.resource_delta_lt and not all(
            resource_delta_matches(entry, name, threshold, "lt") for name, threshold in args.resource_delta_lt
        ):
            continue
        if args.resource_delta_gt and not all(
            resource_delta_matches(entry, name, threshold, "gt") for name, threshold in args.resource_delta_gt
        ):
            continue
        if args.board_valid and entry.get("board_valid") is not True:
            continue
        if args.wns_gt is not None and (entry.get("wns_ns") is None or entry["wns_ns"] <= args.wns_gt):
            continue
        if args.runtime_lt is not None and (entry.get("runtime_s") is None or entry["runtime_s"] >= args.runtime_lt):
            continue
        if args.min_wns is not None and (entry.get("wns_ns") is None or entry["wns_ns"] < args.min_wns):
            continue
        if args.max_runtime_s is not None and (entry.get("runtime_s") is None or entry["runtime_s"] > args.max_runtime_s):
            continue
        filtered.append(entry)
    if args.sort:
        filtered.sort(key=lambda entry: (entry.get(args.sort) is None, entry.get(args.sort)))
    if args.limit:
        filtered = filtered[: args.limit]
    rendered_items = filtered if args.full else [compact_index_entry(entry) for entry in filtered]
    if args.jsonl:
        for entry in rendered_items:
            print(json.dumps(entry, ensure_ascii=False, sort_keys=True))
    else:
        print(json.dumps({"count": len(rendered_items), "items": rendered_items}, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def resource_delta_argument(value: str) -> tuple[str, float]:
    name, separator, threshold = value.partition("=")
    if separator != "=" or name not in RESOURCE_NAMES:
        raise argparse.ArgumentTypeError(f"expected RESOURCE=NUMBER with RESOURCE in {sorted(RESOURCE_NAMES)}")
    try:
        return name, float(threshold)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("resource delta threshold must be numeric") from exc


def resource_delta_matches(entry: dict[str, Any], name: str, threshold: float, operator: str) -> bool:
    changes = entry.get("resource_deltas")
    item = changes.get(name) if isinstance(changes, dict) else None
    delta = item.get("delta") if isinstance(item, dict) else None
    if not isinstance(delta, (int, float)):
        return False
    return delta < threshold if operator == "lt" else delta > threshold


def compact_index_entry(entry: dict[str, Any]) -> dict[str, Any]:
    return {
        name: entry.get(name)
        for name in (
            "experiment_id",
            "title",
            "status",
            "sidecar",
            "record_path",
            "code_commit",
            "frequency_mhz",
            "strategy",
            "method_class",
            "input_id",
            "wns_ns",
            "tns_ns",
            "whs_ns",
            "runtime_s",
            "report_kind",
            "clock_groups_coverage",
            "path_families_coverage",
            "path_family_names",
            "critical_path_primitives",
            "resources_coverage",
            "resource_counts",
            "resource_deltas",
            "audit_status",
            "evidence_quality",
            "decision",
            "board_valid",
            "board_sample_count",
        )
    } | {"top_delay_nets": entry.get("top_delay_nets", [])[:3]}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate and query JYD iteration sidecars and index.jsonl files.")
    subparsers = parser.add_subparsers(dest="command", required=True)
    validate = subparsers.add_parser("validate", help="Validate one sidecar JSON file.")
    validate.add_argument("sidecar", type=Path)
    validate.set_defaults(handler=command_validate)
    build_index = subparsers.add_parser("build-index", help="Aggregate sidecar files into deterministic index.jsonl.")
    build_index.add_argument("source", type=Path)
    build_index.add_argument("--output", type=Path, required=True)
    build_index.set_defaults(handler=command_build_index)
    query = subparsers.add_parser("query", help="Query index.jsonl without reading Markdown or Vivado logs.")
    query.add_argument("index", type=Path)
    query.add_argument("--status", choices=sorted(STATUSES))
    query.add_argument("--strategy")
    query.add_argument("--input-id")
    query.add_argument("--frequency", type=float)
    query.add_argument("--wns-gt", type=float)
    query.add_argument("--runtime-lt", type=float)
    query.add_argument("--audit-status", choices=("ok", "partial", "error"))
    query.add_argument("--evidence-quality", choices=sorted(EVIDENCE_QUALITIES))
    query.add_argument("--report-kind", choices=("postroute_physopted", "routed"))
    query.add_argument("--path-families-coverage", choices=("complete", "sampled", "partial", "unknown"))
    query.add_argument("--clock-groups-coverage", choices=("complete", "sampled", "partial", "unknown"))
    query.add_argument("--resources-coverage", choices=("complete", "sampled", "partial", "unknown"))
    query.add_argument("--path-family", action="append", help="Require a sampled semantic path family; may be repeated.")
    query.add_argument("--has-primitive", action="append", help="Require a primitive in sampled critical data paths; may be repeated.")
    query.add_argument("--resource-delta-lt", action="append", type=resource_delta_argument, help="Require RESOURCE delta below NUMBER.")
    query.add_argument("--resource-delta-gt", action="append", type=resource_delta_argument, help="Require RESOURCE delta above NUMBER.")
    query.add_argument("--board-valid", action="store_true")
    query.add_argument("--min-wns", type=float)
    query.add_argument("--max-runtime-s", type=float)
    query.add_argument("--sort", choices=("experiment_id", "wns_ns", "runtime_s", "frequency_mhz"))
    query.add_argument("--limit", type=int)
    query.add_argument("--jsonl", action="store_true")
    query.add_argument("--full", action="store_true", help="Include full index entries instead of compact optimization fields.")
    query.set_defaults(handler=command_query)
    args = parser.parse_args(argv)
    return args.handler(args)


if __name__ == "__main__":
    raise SystemExit(main())
