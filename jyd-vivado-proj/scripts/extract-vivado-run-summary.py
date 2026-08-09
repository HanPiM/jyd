#!/usr/bin/env python3
"""Extract a compact, auditable summary from a Vivado run."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path
from typing import Any, Iterable


SCHEMA_NAME = "jyd-vivado-run-summary"
SCHEMA_VERSION = 2
ANALYSIS_SCHEMA_NAME = "jyd-vivado-timing-analysis"
ANALYSIS_SCHEMA_VERSION = 2
POSTROUTE_REPORT_NAME = "top_timing_summary_postroute_physopted.rpt"
ROUTED_REPORT_NAME = "top_timing_summary_routed.rpt"
REPORT_NAMES = (POSTROUTE_REPORT_NAME, ROUTED_REPORT_NAME)
PATH_REPORT_PATTERNS = (
    "timing_paths*.rpt",
    "timing_path*.rpt",
    "top_timing_paths*.rpt",
    "top_timing_path*.rpt",
    "*timing*path*.rpt",
    "*timing_setup*.rpt",
    "path*.rpt",
)
UTILIZATION_REPORT_PATTERNS = ("*utilization*.rpt",)
CONGESTION_REPORT_PATTERNS = ("*congestion*.rpt",)
RESOURCE_NAMES = ("RAM64M", "RAMD64E", "LUT", "FF", "BRAM")
DEFAULT_RUN_PATH = Path("digital_twin.runs") / "impl_1"
GOAL_WNS_NS = -0.3
GOAL_RUNTIME_S = 10.75
PER_PATH_NET_LIMIT = 5
TIMING_COLUMNS = (
    "wns_ns",
    "tns_ns",
    "tns_failing_endpoints",
    "tns_total_endpoints",
    "whs_ns",
    "ths_ns",
    "ths_failing_endpoints",
    "ths_total_endpoints",
    "wpws_ns",
    "tpws_ns",
    "tpws_failing_endpoints",
    "tpws_total_endpoints",
)
PHASE_NAMES = (
    "synth_design",
    "link_design",
    "opt_design",
    "place_design",
    "phys_opt_design",
    "route_design",
    "write_bitstream",
)
LOG_NAMES = {
    "runme.log",
    "vivado.log",
    "vivado.jou",
    "impl.log",
    "impl_1.log",
    "runner.log",
}
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
COMMIT_RE = re.compile(r"^[0-9a-fA-F]{7,64}$")
COMMAND_RE = re.compile(r"\bCommand:\s+(?P<command>.+?)\s*$")
COMPLETION_RE = re.compile(r"\b(?P<phase>[a-z_]+) completed successfully\b")
VERSION_RE = re.compile(r"\bVivado v(?P<version>[0-9][^\s(]*)")
ERROR_RE = re.compile(
    r"\b(?P<severity>ERROR|CRITICAL WARNING):\s*"
    r"(?:\[(?P<code>[^\]]+)\]\s*)?(?P<message>.*)$"
)
SLACK_RE = re.compile(
    r"^\s*Slack(?:\s*\((?P<status>[^)]+)\))?\s*:\s*"
    r"(?P<value>[-+]?\d+(?:\.\d+)?)ns\b"
)
FIELD_RE = re.compile(r"^\s{2,}(?P<name>[A-Za-z][A-Za-z ()]+):\s+(?P<value>.+?)\s*$")
CLOCK_RE = re.compile(
    r"^\s*(?P<name>\S+)\s+\{[^}]*\}\s+"
    r"(?P<period>[-+]?\d+(?:\.\d+)?)\s+"
    r"(?P<frequency>[-+]?\d+(?:\.\d+)?)\s*$"
)
DATA_DELAY_RE = re.compile(
    r"(?P<total>[-+]?\d+(?:\.\d+)?)ns\s+\(logic\s+"
    r"(?P<logic>[-+]?\d+(?:\.\d+)?)ns\s+\((?P<logic_pct>[-+]?\d+(?:\.\d+)?)%\)\s+"
    r"route\s+(?P<route>[-+]?\d+(?:\.\d+)?)ns\s+\((?P<route_pct>[-+]?\d+(?:\.\d+)?)%\)"
)
DELAY_VALUE_RE = re.compile(r"(?P<value>[-+]?\d+(?:\.\d+)?)\s*ns\b")
NET_ROW_RE = re.compile(
    r"^\s*net\s+\(fo=(?P<fanout>\d+)(?:,\s*[^)]*)?\)\s+"
    r"(?P<delay>[-+]?\d+(?:\.\d+)?)\s+[-+]?\d+(?:\.\d+)?\s+(?P<name>.+?)\s*$"
)
PRIMITIVE_TOKEN_RE = re.compile(r"^[A-Z][A-Z0-9_]+$")
TABLE_NUMBER_RE = re.compile(r"^[-+]?\d+(?:\.\d+)?$")
CLOCKED_BY_RE = re.compile(r"\bclocked by\s+(?P<clock>\S+)")
RESOURCE_TABLE_SEPARATOR_RE = re.compile(r"^\s*-{20,}\s+-{10,}\s*$")
BIT_INDEX_RE = re.compile(r"\[\d+\]")


def parse_number(value: str) -> float | None:
    value = value.strip().rstrip(",")
    if value.upper() in {"NA", "N/A", "-"}:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def parse_count(value: str) -> int | None:
    number = parse_number(value)
    return None if number is None else int(number)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def artifact_info(path: Path, expected_sha256: str | None = None, kind: str | None = None) -> dict[str, Any]:
    result: dict[str, Any] = {"path": str(path), "kind": kind, "size_bytes": path.stat().st_size}
    actual = sha256_file(path)
    result["sha256"] = actual
    if expected_sha256:
        result["expected_sha256"] = expected_sha256
        result["hash_status"] = "verified" if actual.lower() == expected_sha256.lower() else "mismatch"
    else:
        result["hash_status"] = "observed"
    return result


def load_metadata(path: Path | None) -> tuple[dict[str, Any], Path | None]:
    if path is None:
        return {}, None
    with path.open(encoding="utf-8") as file:
        value = json.load(file)
    if not isinstance(value, dict):
        raise ValueError(f"metadata must be a JSON object: {path}")
    if isinstance(value.get("identity"), dict):
        value = dict(value["identity"])
    return value, path.parent


def report_directory(path: Path) -> Path:
    if path.is_file():
        return path.parent
    if any((path / name).is_file() for name in REPORT_NAMES):
        return path
    nested = path / "digital_twin.runs" / "impl_1"
    if any((nested / name).is_file() for name in REPORT_NAMES):
        return nested
    return path


def resolve_report(path: Path, explicit_report: Path | None) -> Path | None:
    if explicit_report is not None:
        return explicit_report
    if path.is_file():
        return path
    directory = report_directory(path)
    for name in REPORT_NAMES:
        candidate = directory / name
        if candidate.is_file():
            return candidate
    return None


def log_role(path: Path) -> str:
    name = path.name.lower()
    if name == "runme.log":
        return "runme"
    if name in {"vivado.log", "vivado.jou"}:
        return "vivado"
    if "runner" in name:
        return "runner"
    if "impl" in name:
        return "impl"
    return "other"


def resolve_logs(path: Path, report_path: Path | None, explicit_logs: list[Path]) -> list[Path]:
    if explicit_logs:
        return list(dict.fromkeys(explicit_logs))
    roots: list[Path] = []
    seeds = [path if path.is_dir() else path.parent, report_path.parent if report_path else None]
    for seed in seeds:
        if seed is None:
            continue
        for candidate in (seed, seed.parent, seed.parent.parent):
            if candidate not in roots:
                roots.append(candidate)
    results: list[Path] = []
    for root in roots:
        for candidate in sorted(root.glob("*")):
            if candidate.is_file() and (
                candidate.name.lower() in LOG_NAMES
                or "runner" in candidate.name.lower()
                or candidate.name.lower().startswith("impl") and candidate.suffix.lower() == ".log"
            ):
                if candidate not in results:
                    results.append(candidate)
    return results


def analysis_roots(input_path: Path, report_path: Path | None) -> list[Path]:
    roots: list[Path] = []
    for candidate in (
        report_path.parent if report_path else None,
        input_path if input_path.is_dir() else input_path.parent,
        input_path / "digital_twin.runs" / "impl_1" if input_path.is_dir() else None,
    ):
        if candidate is not None and candidate.is_dir() and candidate not in roots:
            roots.append(candidate)
    return roots


def resolve_analysis_reports(
    input_path: Path,
    report_path: Path | None,
    explicit: list[Path],
    patterns: tuple[str, ...],
) -> list[Path]:
    if explicit:
        return list(dict.fromkeys(explicit))
    results: list[Path] = []
    for root in analysis_roots(input_path, report_path):
        for pattern in patterns:
            for candidate in sorted(root.glob(pattern)):
                if candidate.is_file() and candidate not in results:
                    results.append(candidate)
    return results


def utilization_report_rank(path: Path) -> tuple[int, str]:
    name = path.name.lower()
    if "clock_utilization" in name:
        phase = 5
    elif "postroute" in name:
        phase = 0
    elif "routed" in name:
        phase = 1
    elif "placed" in name:
        phase = 2
    elif "synth" in name:
        phase = 3
    else:
        phase = 4
    return phase, name


def pipe_row(line: str) -> list[str]:
    if "|" not in line:
        return []
    return [part.strip() for part in line.strip().strip("|").split("|")]


def parse_utilization_report(path: Path) -> dict[str, Any]:
    primitive_histogram: dict[str, int] = {}
    primitive_table_seen = False
    in_primitive_table = False
    primitive_table_rows = 0
    primitive_table_closed = False
    with path.open(encoding="utf-8", errors="replace") as file:
        for raw_line in file:
            line = raw_line.rstrip("\n")
            if "Ref Name" in line and "Functional Category" in line and "Used" in line:
                primitive_table_seen = True
                in_primitive_table = True
                continue
            if in_primitive_table and re.match(r"^\s*\d+\.\s+", line):
                in_primitive_table = False
                primitive_table_closed = primitive_table_rows > 0
                continue
            if in_primitive_table and primitive_table_rows and line.lstrip().startswith("+"):
                in_primitive_table = False
                primitive_table_closed = True
                continue
            if not in_primitive_table:
                continue
            parts = pipe_row(line)
            if len(parts) < 2 or parts[0] in {"Ref Name", ""}:
                continue
            count = parse_count(parts[1])
            if count is not None and PRIMITIVE_TOKEN_RE.fullmatch(parts[0]):
                primitive_histogram[parts[0]] = count
                primitive_table_rows += 1

    lut_count = sum(count for name, count in primitive_histogram.items() if re.fullmatch(r"LUT\d+", name))
    ff_count = sum(count for name, count in primitive_histogram.items() if name.startswith(("FD", "LD")))
    bram_count = sum(count for name, count in primitive_histogram.items() if name.startswith("RAMB"))
    counts = {
        "RAM64M": primitive_histogram.get("RAM64M", 0) if primitive_table_seen else None,
        "RAMD64E": primitive_histogram.get("RAMD64E", 0) if primitive_table_seen else None,
        "LUT": lut_count if primitive_table_seen else None,
        "FF": ff_count if primitive_table_seen else None,
        "BRAM": bram_count if primitive_table_seen else None,
    }
    coverage = "complete" if primitive_table_seen and primitive_table_rows and primitive_table_closed else "partial" if primitive_table_seen else "unknown"
    return {
        "coverage": coverage,
        "status": coverage,
        "report": artifact_info(path, kind="utilization_report"),
        "primitive_histogram": dict(sorted(primitive_histogram.items())),
        "counts": counts,
        "items": [{"name": name, "count": count, "coverage": coverage} for name, count in counts.items()],
    }


def resource_counts_from_summary(value: Any) -> dict[str, int | None] | None:
    resources = value.get("timing", {}).get("resources", {}) if isinstance(value, dict) else {}
    counts = resources.get("counts") if isinstance(resources, dict) else None
    if not isinstance(counts, dict):
        return None
    result: dict[str, int | None] = {}
    for name in RESOURCE_NAMES:
        raw = counts.get(name)
        if isinstance(raw, dict):
            raw = raw.get("count")
        result[name] = int(raw) if isinstance(raw, (int, float)) else None
    return result


def resource_baseline(
    baseline_summary_path: Path | None,
    baseline_utilization_paths: list[Path],
) -> dict[str, Any] | None:
    if baseline_summary_path is not None and not baseline_summary_path.is_file():
        return {
            "source": {"path": str(baseline_summary_path), "kind": "baseline_summary", "missing": True, "hash_status": "missing"},
            "coverage": "partial",
            "counts": {name: None for name in RESOURCE_NAMES},
        }
    if baseline_summary_path is not None and baseline_summary_path.is_file():
        try:
            with baseline_summary_path.open(encoding="utf-8") as file:
                value = json.load(file)
            counts = resource_counts_from_summary(value)
            if counts is not None:
                return {
                    "source": artifact_info(baseline_summary_path, kind="baseline_summary"),
                    "coverage": value.get("timing", {}).get("resources", {}).get("coverage", "unknown"),
                    "counts": counts,
                }
        except (OSError, ValueError, json.JSONDecodeError):
            return {"source": artifact_info(baseline_summary_path, kind="baseline_summary"), "coverage": "partial", "counts": {name: None for name in RESOURCE_NAMES}}
    existing_baseline_paths = [path for path in baseline_utilization_paths if path.is_file()]
    if existing_baseline_paths:
        current = parse_utilization_report(min(existing_baseline_paths, key=utilization_report_rank))
        return {
            "source": current["report"],
            "coverage": current["coverage"],
            "counts": current["counts"],
        }
    return None


def resource_analysis(
    utilization_paths: list[Path],
    baseline_summary_path: Path | None,
    baseline_utilization_paths: list[Path],
) -> dict[str, Any]:
    if not utilization_paths:
        return {
            "status": "unknown",
            "coverage": "unknown",
            "reports": [],
            "primitive_histogram": {},
            "counts": {name: None for name in RESOURCE_NAMES},
            "items": [],
            "baseline": None,
        }
    parsed = [parse_utilization_report(path) for path in utilization_paths if path.is_file()]
    if not parsed:
        return {
            "status": "unknown",
            "coverage": "unknown",
            "reports": [],
            "primitive_histogram": {},
            "counts": {name: None for name in RESOURCE_NAMES},
            "items": [],
            "baseline": None,
        }
    selected = min(parsed, key=lambda value: utilization_report_rank(Path(value["report"]["path"])))
    baseline = resource_baseline(baseline_summary_path, baseline_utilization_paths)
    baseline_counts = baseline.get("counts") if baseline else None
    changes: dict[str, Any] = {}
    for name, current in selected["counts"].items():
        base = baseline_counts.get(name) if isinstance(baseline_counts, dict) else None
        if isinstance(current, (int, float)) and isinstance(base, (int, float)):
            changes[name] = {
                "current": current,
                "baseline": base,
                "delta": current - base,
                "relative_change": (current - base) / base if base else None,
                "coverage": "complete" if selected["coverage"] == "complete" and baseline.get("coverage") == "complete" else "partial",
            }
        else:
            changes[name] = {
                "current": current,
                "baseline": base,
                "delta": None,
                "relative_change": None,
                "coverage": "partial" if baseline else "unknown",
            }
    selected = {
        **selected,
        "reports": [parsed_item["report"] for parsed_item in parsed],
        "baseline": {**baseline, "changes": changes} if baseline else None,
    }
    return selected


def parse_congestion_report(path: Path) -> dict[str, Any]:
    rows: list[dict[str, Any]] = []
    table_kind: str | None = None
    headers: list[str] = []
    with path.open(encoding="utf-8", errors="replace") as file:
        for raw_line in file:
            line = raw_line.rstrip("\n")
            parts = pipe_row(line)
            if not parts:
                continue
            if "Direction" in parts and "Level" in parts and "Cell Names" in parts:
                headers = parts
                table_kind = "placer" if "Congestion" in parts else "router_estimate"
                continue
            if table_kind is None or len(parts) < len(headers) or parts[0] in {"Direction", ""}:
                continue
            if not any(parts):
                continue
            item: dict[str, Any] = {name.lower().replace(" ", "_"): value for name, value in zip(headers, parts)}
            level = parse_count(item.get("level", ""))
            if level is None:
                continue
            for key in ("level", "congestion", "percentage_tiles", "combined_luts", "avg_lut_input", "lut", "lutram", "flop", "muxf", "ramb", "dsp", "carry", "srl"):
                if key in item:
                    item[key] = parse_number(item[key])
            item["table"] = table_kind
            rows.append(item)
    return {
        "status": "partial" if headers else "unknown",
        "coverage": "partial" if headers else "unknown",
        "report": artifact_info(path, kind="congestion_report"),
        "items": rows,
        "sample_limit": None,
    }


def timing_summary_row(tokens: list[str]) -> dict[str, Any] | None:
    if len(tokens) < len(TIMING_COLUMNS):
        return None
    if parse_number(tokens[0]) is None and tokens[0].upper() not in {"NA", "N/A"}:
        return None
    count_columns = {
        "tns_failing_endpoints",
        "tns_total_endpoints",
        "ths_failing_endpoints",
        "ths_total_endpoints",
        "tpws_failing_endpoints",
        "tpws_total_endpoints",
    }
    return {
        name: parse_count(token) if name in count_columns else parse_number(token)
        for name, token in zip(TIMING_COLUMNS, tokens)
    }


def field_value(fields: dict[str, str], prefix: str) -> str:
    for name, value in fields.items():
        if name.strip().lower().startswith(prefix.lower()):
            return value
    return ""


def delay_field_value(fields: dict[str, str], prefix: str) -> float | None:
    match = DELAY_VALUE_RE.search(field_value(fields, prefix))
    return parse_number(match.group("value")) if match else None


def timing_type(path_type: str) -> str | None:
    lower = path_type.lower()
    for name in ("setup", "hold", "recovery", "removal", "pulse_width"):
        if name.replace("_", " ") in lower or name in lower:
            return name
    return None


def endpoint_kind(endpoint: str | None) -> str | None:
    if not endpoint or endpoint.strip().lower() in {"<hidden>", "n/a", "unknown"}:
        return None
    lower = endpoint.lower()
    if "(in)" in lower or lower.startswith("in[") or lower.startswith("input"):
        return "input_port"
    if "(out)" in lower or lower.startswith("out[") or lower.startswith("output"):
        return "output_port"
    if any(token in lower for token in ("/q", "/d", "/c", "/ce", "/s", "/r")):
        return "register"
    return "internal"


def semantic_path_family(
    path_type: str | None,
    source: str | None,
    destination: str | None,
    from_clock: str | None,
    to_clock: str | None,
    net_names: Iterable[str],
) -> tuple[str, str]:
    type_name = path_type or "timing"
    source_name = (source or "").lower()
    destination_name = (destination or "").lower()
    context = " ".join((source_name, destination_name, *(name.lower() for name in net_names)))
    if "/btb/" in context or "querymem_" in context:
        return f"{type_name}:btb_lookup_to_fetch_prediction", "structural"
    if "prevexufwdrs" in source_name and any(token in destination_name for token in ("exuwriteback", "gpr_data")):
        return f"{type_name}:exu_forward_to_writeback", "structural"
    if "prevexufwdrs" in source_name and "redirectusetarget" in destination_name:
        return f"{type_name}:exu_forward_to_redirect", "structural"
    if "redirect" in context and "prednext" in context:
        return f"{type_name}:redirect_to_fetch", "structural"
    source_kind = endpoint_kind(source)
    destination_kind = endpoint_kind(destination)
    if source_kind and destination_kind:
        source_stem = endpoint_stem(source)
        destination_stem = endpoint_stem(destination)
        if source_stem and destination_stem:
            return f"{type_name}:{source_stem}_to_{destination_stem}", "endpoint"
    if from_clock and to_clock:
        return f"{type_name}:unknown", "unknown"
    return f"{type_name}:unknown", "unknown"


def endpoint_stem(endpoint: str | None) -> str | None:
    if not endpoint or endpoint.strip().lower() in {"<hidden>", "n/a", "unknown"}:
        return None
    value = BIT_INDEX_RE.sub("", endpoint.strip().lower())
    value = re.sub(r"/(?:c|d|q|ce|s|r)$", "", value)
    value = re.sub(r"_reg$", "", value)
    component = value.rsplit("/", 1)[-1]
    component = re.sub(r"[^a-z0-9]+", "_", component).strip("_")
    return component or None


def path_resource_details(lines: Iterable[str]) -> dict[str, Any]:
    primitive_histograms: dict[str, dict[str, int]] = {
        "source_clock": {},
        "data": {},
        "destination_clock": {},
    }
    delay_nets: dict[str, list[dict[str, Any]]] = {
        "source_clock": [],
        "data": [],
        "destination_clock": [],
    }
    phase: str | None = None
    separator_count = 0
    for line in lines:
        if RESOURCE_TABLE_SEPARATOR_RE.match(line):
            separator_count += 1
            phase = {1: "source_clock", 2: "data", 3: "destination_clock"}.get(separator_count)
            continue
        if phase is None:
            continue
        net_match = NET_ROW_RE.match(line)
        if net_match:
            delay_nets[phase].append(
                {
                    "name": net_match.group("name").strip(),
                    "fanout": int(net_match.group("fanout")),
                    "delay_ns": parse_number(net_match.group("delay")),
                }
            )
            continue
        tokens = line.strip().split()
        if len(tokens) < 2 or tokens[0].lower() in {"net", "clock", "location"}:
            continue
        primitive = tokens[1]
        if PRIMITIVE_TOKEN_RE.fullmatch(primitive) and ("_X" in tokens[0] or tokens[0].startswith("<")):
            histogram = primitive_histograms[phase]
            histogram[primitive] = histogram.get(primitive, 0) + 1
    for items in delay_nets.values():
        items.sort(key=lambda item: item["delay_ns"] if item["delay_ns"] is not None else float("-inf"), reverse=True)
    return {
        "primitive_histograms": {name: dict(sorted(items.items())) for name, items in primitive_histograms.items()},
        "delay_nets": delay_nets,
    }


def path_summary(path: dict[str, Any]) -> dict[str, Any]:
    fields = path["fields"]
    path_type = fields.get("Path Type", "")
    path_type_name = timing_type(path_type)
    source = fields.get("Source")
    destination = fields.get("Destination")
    details = path_resource_details(path.get("lines", []))
    primitive_histograms = details["primitive_histograms"]
    delay_nets = details["delay_nets"]
    from_clock = path.get("from_clock") or path.get("source_clock")
    to_clock = path.get("to_clock") or path.get("destination_clock")
    family, family_classification = semantic_path_family(
        path_type_name,
        source,
        destination,
        from_clock,
        to_clock,
        (item["name"] for item in delay_nets["data"]),
    )
    result: dict[str, Any] = {
        "line": path["line"],
        "slack_ns": path["slack_ns"],
        "status": path["status"],
        "source": source,
        "destination": destination,
        "path_group": fields.get("Path Group"),
        "path_type": path_type,
        "path_family": family,
        "semantic_family": family,
        "semantic_family_classification": family_classification,
        "source_kind": endpoint_kind(source),
        "destination_kind": endpoint_kind(destination),
        "requirement_ns": parse_number(fields.get("Requirement", "").split("ns", 1)[0]),
        "logic_levels": parse_count(fields.get("Logic Levels", "").split(" ", 1)[0]),
        "source_clock_delay_ns": delay_field_value(fields, "Source Clock Delay"),
        "destination_clock_delay_ns": delay_field_value(fields, "Destination Clock Delay"),
        "clock_skew_ns": delay_field_value(fields, "Clock Path Skew"),
        "primitive_histogram": primitive_histograms["data"],
        "top_delay_nets": delay_nets["data"][:PER_PATH_NET_LIMIT],
        "delay_net_count": len(delay_nets["data"]),
        "_all_delay_nets": delay_nets["data"],
    }
    clock_pair = [value for value in (from_clock, to_clock) if value]
    if clock_pair:
        result["clock_pair"] = clock_pair
    delay_match = DATA_DELAY_RE.search(fields.get("Data Path Delay", ""))
    for name, group in (
        ("data_path_delay_ns", "total"),
        ("logic_delay_ns", "logic"),
        ("logic_delay_pct", "logic_pct"),
        ("route_delay_ns", "route"),
        ("route_delay_pct", "route_pct"),
    ):
        result[name] = parse_number(delay_match.group(group)) if delay_match else None
    return result


def parse_timing_group_rows(lines: Iterable[str]) -> list[dict[str, Any]]:
    section: str | None = None
    rows: list[dict[str, Any]] = []
    for raw_line in lines:
        line = raw_line.strip().strip("|").strip()
        if "Intra Clock Table" in raw_line:
            section = "intra"
            continue
        if "Inter Clock Table" in raw_line:
            section = "inter"
            continue
        if "Other Path Groups Table" in raw_line:
            section = "other"
            continue
        if section is None or not line or line.startswith("-") or line.startswith("*"):
            continue
        tokens = line.split()
        number_index = next((index for index, token in enumerate(tokens) if TABLE_NUMBER_RE.fullmatch(token)), None)
        if number_index is None:
            continue
        metric_count = 12 if section == "intra" else 8
        metric_tokens = tokens[number_index : number_index + metric_count]
        if len(metric_tokens) != metric_count or any(not TABLE_NUMBER_RE.fullmatch(token) for token in metric_tokens):
            continue
        metrics = {
            name: parse_count(token) if name in {
                "tns_failing_endpoints", "tns_total_endpoints", "ths_failing_endpoints", "ths_total_endpoints",
                "tpws_failing_endpoints", "tpws_total_endpoints",
            } else parse_number(token)
            for name, token in zip(TIMING_COLUMNS, metric_tokens)
        }
        prefix = tokens[:number_index]
        if section == "intra" and len(prefix) >= 1:
            path_group = prefix[0]
            from_clock = prefix[0]
            to_clock = prefix[0]
        elif section == "inter" and len(prefix) >= 2:
            path_group = None
            from_clock, to_clock = prefix[:2]
        elif section == "other" and len(prefix) >= 3:
            path_group, from_clock, to_clock = prefix[:3]
        else:
            continue
        group_name = f"setup:{from_clock}->{to_clock}"
        rows.append(
            {
                "name": group_name,
                "path_group": path_group,
                "from_clock": from_clock,
                "to_clock": to_clock,
                "path_type": "setup",
                "worst_slack_ns": metrics.get("wns_ns"),
                "tns_ns": metrics.get("tns_ns"),
                "endpoints": metrics.get("tns_failing_endpoints"),
                "failing_endpoints": metrics.get("tns_failing_endpoints"),
                "total_endpoints": metrics.get("tns_total_endpoints"),
                "hold_worst_slack_ns": metrics.get("whs_ns"),
                "hold_tns_ns": metrics.get("ths_ns"),
                "hold_failing_endpoints": metrics.get("ths_failing_endpoints"),
                "coverage": "complete",
                "metric_coverage": {
                    "worst_slack_ns": "complete",
                    "tns_ns": "complete",
                    "endpoints": "complete",
                },
                "source": "timing_group_table",
            }
        )
    return rows


def sampled_path_family_items(paths: list[dict[str, Any]]) -> list[dict[str, Any]]:
    groups: dict[str, list[dict[str, Any]]] = {}
    for path in paths:
        groups.setdefault(path.get("path_family") or "timing:unknown", []).append(path)
    items: list[dict[str, Any]] = []
    for name, group in sorted(groups.items()):
        endpoint_slacks: dict[tuple[str | None, str | None], float | None] = {}
        for path in group:
            pair = (path.get("source"), path.get("destination"))
            slack = path.get("slack_ns")
            previous = endpoint_slacks.get(pair)
            if previous is None or isinstance(slack, (int, float)) and slack < previous:
                endpoint_slacks[pair] = slack
        slacks = [slack for slack in endpoint_slacks.values() if slack is not None]
        negative_slacks = [slack for slack in slacks if slack < 0]
        endpoints = len(endpoint_slacks)
        sampled_path_count = sum(path.get("occurrences", 1) for path in group)
        first = group[0]
        representative_paths = []
        seen_pairs: set[tuple[str | None, str | None]] = set()
        for path in group:
            pair = (path.get("source"), path.get("destination"))
            if pair in seen_pairs:
                continue
            seen_pairs.add(pair)
            representative_paths.append({"source": pair[0], "destination": pair[1], "slack_ns": path.get("slack_ns")})
            if len(representative_paths) == 3:
                break
        items.append(
            {
                "name": name,
                "semantic_family": name,
                "classification": first.get("semantic_family_classification", "unknown"),
                "path_group": first.get("path_group"),
                "from_clock": (first.get("clock_pair") or [None, None])[0],
                "to_clock": (first.get("clock_pair") or [None, None])[-1],
                "path_type": first.get("path_type"),
                "worst_slack_ns": min(slacks) if slacks else None,
                "tns_ns": sum(negative_slacks) if negative_slacks else 0.0,
                "endpoints": endpoints,
                "failing_endpoints": len(negative_slacks),
                "total_endpoints": endpoints,
                "path_count": sampled_path_count,
                "sampled_path_count": sampled_path_count,
                "sampled_endpoint_count": endpoints,
                "representative_paths": representative_paths,
                "coverage": "sampled",
                "metric_coverage": {
                    "worst_slack_ns": "sampled",
                    "tns_ns": "sampled",
                    "endpoints": "sampled",
                },
                "source": "timing_path_sample",
            }
        )
    return items


def timing_path_analysis(paths: list[dict[str, Any]], lines: Iterable[str], path_limit: int) -> dict[str, Any]:
    group_rows = parse_timing_group_rows(lines)
    family_items = sampled_path_family_items(paths)
    primitive_histogram: dict[str, int] = {}
    delay_nets: dict[str, dict[str, Any]] = {}
    clock_delays: dict[tuple[Any, ...], dict[str, Any]] = {}
    for path in paths:
        path_occurrences = path.get("occurrences", 1)
        for primitive, count in path.get("primitive_histogram", {}).items():
            primitive_histogram[primitive] = primitive_histogram.get(primitive, 0) + count * path_occurrences
        for net in path.get("_all_delay_nets", path.get("top_delay_nets", [])):
            name = net["name"]
            current = delay_nets.get(name)
            if current is None:
                delay_nets[name] = {**net, "occurrences": path_occurrences, "path_line": path.get("line")}
            else:
                current["occurrences"] += path_occurrences
                if net.get("delay_ns") is not None and (
                    current.get("delay_ns") is None or net["delay_ns"] > current["delay_ns"]
                ):
                    current.update({**net, "path_line": path.get("line")})
        if any(path.get(name) is not None for name in ("source_clock_delay_ns", "destination_clock_delay_ns", "clock_skew_ns")):
            item = {
                "path_line": path.get("line"),
                "from_clock": (path.get("clock_pair") or [None, None])[0],
                "to_clock": (path.get("clock_pair") or [None, None])[-1],
                "source_clock_delay_ns": path.get("source_clock_delay_ns"),
                "destination_clock_delay_ns": path.get("destination_clock_delay_ns"),
                "clock_skew_ns": path.get("clock_skew_ns"),
                "occurrences": path_occurrences,
            }
            key = tuple(item.get(name) for name in (
                "from_clock",
                "to_clock",
                "source_clock_delay_ns",
                "destination_clock_delay_ns",
                "clock_skew_ns",
            ))
            if key in clock_delays:
                clock_delays[key]["occurrences"] += 1
            else:
                clock_delays[key] = item
    sorted_delay_nets = sorted(
        delay_nets.values(),
        key=lambda item: item["delay_ns"] if item.get("delay_ns") is not None else float("-inf"),
        reverse=True,
    )
    family_coverage = "sampled" if paths else "unknown"
    clock_group_coverage = "complete" if group_rows else "unknown"
    sampled_path_count = sum(path.get("occurrences", 1) for path in paths)
    return {
        "coverage": family_coverage,
        "clock_groups": {
            "status": clock_group_coverage,
            "coverage": clock_group_coverage,
            "items": group_rows,
            "source": "timing_group_table" if group_rows else None,
        },
        "path_families": {
            "status": family_coverage,
            "coverage": family_coverage,
            "items": family_items,
            "sample_limit": path_limit,
            "sampled_path_count": sampled_path_count,
            "unique_path_count": len(paths),
            "source": "timing_path_sample" if paths else None,
        },
        "primitive_histogram": {
            "coverage": "sampled" if paths else "unknown",
            "items": dict(sorted(primitive_histogram.items())),
            "sampled_path_count": sampled_path_count,
            "unique_path_count": len(paths),
            "scope": "data_path",
        },
        "top_delay_nets": {
            "coverage": "sampled" if paths else "unknown",
            "items": sorted_delay_nets[:path_limit],
            "sample_limit": path_limit,
            "sampled_path_count": sampled_path_count,
            "unique_path_count": len(paths),
            "scope": "data_path",
        },
        "clock_delays": {
            "coverage": "sampled" if clock_delays else "unknown",
            "items": list(clock_delays.values())[:path_limit],
            "sample_limit": path_limit,
            "sampled_path_count": sampled_path_count,
            "unique_path_count": len(paths),
            "unique_sample_count": len(clock_delays),
        },
    }


def deduplicate_paths(paths: list[dict[str, Any]]) -> list[dict[str, Any]]:
    unique: dict[str, dict[str, Any]] = {}
    for path in paths:
        signature = json.dumps(
            {
                name: path.get(name)
                for name in (
                    "slack_ns",
                    "source",
                    "destination",
                    "path_group",
                    "path_type",
                    "semantic_family",
                    "data_path_delay_ns",
                    "logic_delay_ns",
                    "route_delay_ns",
                    "source_clock_delay_ns",
                    "destination_clock_delay_ns",
                    "clock_skew_ns",
                    "primitive_histogram",
                    "_all_delay_nets",
                )
            },
            sort_keys=True,
        )
        if signature in unique:
            unique[signature]["occurrences"] += 1
        else:
            unique[signature] = {**path, "occurrences": 1}
    return list(unique.values())


def parse_timing_report(path: Path, path_limit: int) -> dict[str, Any]:
    summary: dict[str, Any] | None = None
    clocks: list[dict[str, Any]] = []
    paths: list[dict[str, Any]] = []
    report_lines: list[str] = []
    in_design_summary = False
    in_clock_summary = False
    current_path: dict[str, Any] | None = None
    from_clock = ""
    to_clock = ""
    unconstrained_count: int | None = None

    def finish_path() -> None:
        nonlocal current_path
        if current_path is None:
            return
        path_type = current_path["fields"].get("Path Type", "")
        if not path_type or "setup" in path_type.lower():
            paths.append(path_summary(current_path))
        current_path = None

    with path.open(encoding="utf-8", errors="replace") as file:
        for line_number, line in enumerate(file, start=1):
            stripped = line.rstrip("\n")
            report_lines.append(stripped)
            lower = stripped.lower()
            if "no unconstrained" in lower:
                unconstrained_count = 0
            elif unconstrained_match := re.search(r"(?:unconstrained(?:\s+paths)?|paths\s+unconstrained)\s*[:=]\s*(\d+)", lower):
                unconstrained_count = int(unconstrained_match.group(1))
            if "Design Timing Summary" in stripped:
                in_design_summary = True
                in_clock_summary = False
                continue
            if in_design_summary and summary is None:
                row = timing_summary_row(stripped.strip().split())
                if row is not None:
                    summary = row
                    in_design_summary = False
            if "Clock Summary" in stripped:
                in_clock_summary = True
                continue
            if in_clock_summary:
                clock_match = CLOCK_RE.match(stripped)
                if clock_match:
                    clocks.append(
                        {
                            "name": clock_match.group("name"),
                            "period_ns": parse_number(clock_match.group("period")),
                            "frequency_mhz": parse_number(clock_match.group("frequency")),
                        }
                    )
                elif clocks and stripped.startswith("|"):
                    in_clock_summary = False
            if from_match := re.match(r"^From Clock:\s+(?P<clock>.+?)\s*$", stripped):
                from_clock = from_match.group("clock")
                to_clock = ""
                continue
            if to_match := re.match(r"^\s+To Clock:\s+(?P<clock>.+?)\s*$", stripped):
                to_clock = to_match.group("clock")
                continue
            slack_match = SLACK_RE.match(stripped)
            if slack_match:
                finish_path()
                current_path = {
                    "line": line_number,
                    "slack_ns": parse_number(slack_match.group("value")),
                    "status": (slack_match.group("status") or "").upper(),
                    "from_clock": from_clock,
                    "to_clock": to_clock,
                    "source_clock": "",
                    "destination_clock": "",
                    "last_endpoint": "",
                    "fields": {},
                    "lines": [],
                }
                continue
            if current_path is not None:
                current_path["lines"].append(stripped)
                field_match = FIELD_RE.match(stripped)
                if field_match:
                    field_name = field_match.group("name").strip()
                    current_path["fields"][field_name] = field_match.group("value").strip()
                    if field_name == "Source":
                        current_path["last_endpoint"] = "source"
                    elif field_name == "Destination":
                        current_path["last_endpoint"] = "destination"
                clocked_by_match = CLOCKED_BY_RE.search(stripped)
                if clocked_by_match and current_path.get("last_endpoint") in {"source", "destination"}:
                    current_path[f'{current_path["last_endpoint"]}_clock'] = clocked_by_match.group("clock")
    finish_path()
    paths.sort(key=lambda item: item["slack_ns"] if item["slack_ns"] is not None else float("inf"))
    paths = deduplicate_paths(paths)[:path_limit]
    def negative(value: Any) -> bool:
        return isinstance(value, (int, float)) and value < 0

    setup_violated = bool(summary and (negative(summary.get("wns_ns")) or negative(summary.get("tns_ns")) or summary.get("tns_failing_endpoints")))
    hold_violated = bool(summary and (negative(summary.get("whs_ns")) or negative(summary.get("ths_ns")) or summary.get("ths_failing_endpoints")))
    path_analysis = timing_path_analysis(paths, report_lines, path_limit)
    for item in paths:
        item.pop("_all_delay_nets", None)
    return {
        "status": "complete" if summary is not None else "missing",
        "report_kind": "postroute_physopted" if path.name == POSTROUTE_REPORT_NAME else "routed",
        "summary": summary,
        "clocks": clocks,
        "critical_paths": paths[:path_limit],
        "clock_groups": path_analysis["clock_groups"],
        "path_families": path_analysis["path_families"],
        "primitive_histogram": path_analysis["primitive_histogram"],
        "top_delay_nets": path_analysis["top_delay_nets"],
        "clock_delays": path_analysis["clock_delays"],
        "resources": {"status": "unknown", "items": []},
        "congestion": {"status": "unknown", "items": []},
        "unconstrained_count": unconstrained_count,
        "setup_violated": setup_violated if summary is not None else None,
        "hold_violated": hold_violated if summary is not None else None,
        "violated": (setup_violated or hold_violated) if summary is not None else None,
    }


def parse_log(paths: Iterable[Path]) -> dict[str, Any]:
    phase_events: dict[str, list[dict[str, Any]]] = {phase: [] for phase in PHASE_NAMES}
    phase_completions: dict[str, int] = {phase: 0 for phase in PHASE_NAMES}
    errors: list[dict[str, Any]] = []
    warnings = 0
    vivado_version: str | None = None
    sources: list[dict[str, Any]] = []
    hard_constraint_errors: list[dict[str, Any]] = []
    drc_error_count = 0
    unconstrained_count: int | None = None
    ip_stale = False
    ip_locked = False
    positive_drc = False
    positive_unconstrained = False
    positive_ip_clean = False

    for path in paths:
        sources.append({**artifact_info(path, kind="log"), "role": log_role(path)})
        with path.open(encoding="utf-8", errors="replace") as file:
            for line_number, raw_line in enumerate(file, start=1):
                line = raw_line.rstrip("\n")
                lower = line.lower()
                if vivado_version is None:
                    version_match = VERSION_RE.search(line)
                    if version_match:
                        vivado_version = version_match.group("version")
                command_match = COMMAND_RE.search(line)
                if command_match:
                    raw_command = command_match.group("command")
                    phase = raw_command.split(None, 1)[0]
                    if phase in phase_events:
                        directive_match = re.search(r"\s-directive\s+(\S+)", raw_command)
                        phase_events[phase].append(
                            {"command": raw_command, "directive": directive_match.group(1) if directive_match else None, "line": line_number, "source": str(path)}
                        )
                completion_match = COMPLETION_RE.search(line)
                if completion_match and completion_match.group("phase") in phase_completions:
                    phase_completions[completion_match.group("phase")] += 1
                error_match = ERROR_RE.search(line)
                if error_match:
                    code = error_match.group("code")
                    item = {
                        "severity": error_match.group("severity").lower().replace(" ", "_"),
                        "code": code,
                        "message": error_match.group("message")[:500],
                        "source": str(path),
                        "line": line_number,
                    }
                    errors.append(item)
                    if code in {"Designutils 20-1307", "Constraints 18-513"}:
                        item["classification"] = "constraint_hard_error"
                        hard_constraint_errors.append(item)
                    if code and code.upper().startswith("DRC"):
                        drc_error_count += 1
                elif re.search(r"\bWARNING:\s*", line):
                    warnings += 1
                if re.search(r"\bdrc\b.*\b(?:error|violation)s?\s*[:=]\s*0\b", lower):
                    positive_drc = True
                if re.search(r"\bdrc\b.*\b(?:error|violation)s?\s*[:=]\s*(\d+)\b", lower):
                    match = re.search(r"\bdrc\b.*\b(?:error|violation)s?\s*[:=]\s*(\d+)\b", lower)
                    if match:
                        drc_error_count = max(drc_error_count, int(match.group(1)))
                if "no unconstrained" in lower or re.search(r"unconstrained(?:\s+paths)?\s*[:=]\s*0\b", lower):
                    unconstrained_count = 0
                    positive_unconstrained = True
                elif match := re.search(r"unconstrained(?:\s+paths)?\s*[:=]\s*(\d+)\b", lower):
                    unconstrained_count = int(match.group(1))
                if re.search(r"(?:ip|core).*\b(stale|locked)\b", lower) or re.search(r"\b(stale|locked)\b.*(?:ip|core)", lower):
                    ip_stale = ip_stale or "stale" in lower
                    ip_locked = ip_locked or "locked" in lower
                if re.search(r"ip\s+(?:status|check)\s*[:=]\s*(?:clean|ok|unlocked)", lower):
                    positive_ip_clean = True

    phases: list[dict[str, Any]] = []
    for phase in PHASE_NAMES:
        invocations = phase_events[phase]
        completed = phase_completions[phase]
        status = "missing" if not invocations else "complete" if completed >= len(invocations) else "incomplete"
        phases.append(
            {
                "name": phase,
                "status": status,
                "invocations": invocations,
                "completed_invocations": completed,
                "directives": sorted({item["directive"] for item in invocations if item["directive"]}),
            }
        )
    roles = {source["role"] for source in sources}
    if drc_error_count:
        drc_status = "fail"
    elif positive_drc:
        drc_status = "pass"
    else:
        drc_status = "unknown"
    if unconstrained_count is not None:
        unconstrained_status = "fail" if unconstrained_count else "pass"
    else:
        unconstrained_status = "unknown"
    ip_status = "fail" if ip_stale or ip_locked else "pass" if positive_ip_clean else "unknown"
    return {
        "vivado_version": vivado_version,
        "sources": sources,
        "roles": {role: role in roles for role in ("runme", "vivado", "impl", "runner")},
        "phases": phases,
        "errors": errors,
        "error_count": len(errors),
        "fatal_error_count": sum(item["severity"] == "error" for item in errors),
        "critical_warning_count": sum(item["severity"] == "critical_warning" for item in errors),
        "warning_count": warnings,
        "checks": {
            "constraint_hard_error": {"status": "fail" if hard_constraint_errors else "pass", "items": hard_constraint_errors},
            "drc": {"status": drc_status, "error_count": drc_error_count},
            "unconstrained": {"status": unconstrained_status, "count": unconstrained_count},
            "ip": {"status": ip_status, "stale": ip_stale, "locked": ip_locked},
        },
    }


def inferred_mode(run_dir: Path, log: dict[str, Any], requested: str) -> str:
    if requested != "auto":
        return requested
    if run_dir.name.startswith("synth"):
        return "synth"
    if any(phase["name"] == "write_bitstream" and phase["status"] != "missing" for phase in log["phases"]):
        return "bitstream"
    return "impl"


def phase_status(log: dict[str, Any], mode: str) -> tuple[str, list[str]]:
    expected = ["synth_design"] if mode == "synth" else ["link_design", "opt_design", "place_design", "phys_opt_design", "route_design"]
    if mode == "bitstream":
        expected.append("write_bitstream")
    by_name = {phase["name"]: phase for phase in log["phases"]}
    missing = [name for name in expected if by_name[name]["status"] != "complete"]
    if log["fatal_error_count"]:
        return "error", missing
    return ("partial" if missing else "complete"), missing


def metadata_value(metadata: dict[str, Any], *names: str) -> Any:
    for name in names:
        if name in metadata and metadata[name] not in (None, ""):
            return metadata[name]
    return None


def resolve_path(value: Any, base_dir: Path | None) -> Path | None:
    if not isinstance(value, (str, Path)):
        return None
    path = Path(value)
    if not path.is_absolute() and base_dir is not None:
        path = base_dir / path
    return path


def artifact_reference(value: Any, base_dir: Path | None, kind: str) -> dict[str, Any] | None:
    expected: str | None = None
    source = value
    if isinstance(value, dict):
        source = value.get("path")
        expected_value = value.get("sha256")
        expected = str(expected_value) if expected_value else None
    path = resolve_path(source, base_dir)
    if path is None:
        return None
    result: dict[str, Any] = {"path": str(path), "kind": kind}
    if expected:
        result["expected_sha256"] = expected
        if not SHA256_RE.fullmatch(expected):
            result["hash_status"] = "invalid_expected"
            return result
    if path.is_file():
        result.update(artifact_info(path, expected, kind))
    else:
        result["missing"] = True
        result["hash_status"] = "missing"
    return result


def default_dcp(run_dir: Path) -> Path | None:
    preferred = run_dir / "top_postroute_physopt.dcp"
    if preferred.is_file():
        return preferred
    candidates = sorted(run_dir.glob("*.dcp"))
    return candidates[0] if len(candidates) == 1 else None


def bitstream_reference(metadata: dict[str, Any], run_dir: Path, explicit: Path | None, base_dir: Path | None) -> dict[str, Any] | None:
    metadata_value = metadata.get("bitstream")
    value: Any = explicit
    if explicit is not None and isinstance(metadata_value, dict) and metadata_value.get("sha256"):
        value = {"path": explicit, "sha256": metadata_value["sha256"]}
    if value is None:
        value = metadata_value
    if value is None:
        candidates = sorted(run_dir.glob("*.bit"))
        value = candidates[0] if len(candidates) == 1 else None
    return artifact_reference(value, base_dir, "bitstream")


def manifest_values(path: Path | None) -> dict[str, Any]:
    if path is None or path.suffix.lower() != ".json" or path.stat().st_size > 10 * 1024 * 1024:
        return {}
    try:
        with path.open(encoding="utf-8") as file:
            value = json.load(file)
    except (OSError, ValueError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {}


def artifact_consistency(artifacts: dict[str, Any], identity: dict[str, Any], metadata: dict[str, Any]) -> dict[str, Any]:
    mismatches: list[str] = []
    conflicts: list[str] = []
    unknown: list[str] = []
    for name, artifact in artifacts.items():
        if artifact is None:
            unknown.append(name)
        elif artifact.get("hash_status") in {"mismatch", "invalid_expected", "missing"}:
            mismatches.append(name)
        elif artifact.get("hash_status") == "observed":
            unknown.append(f"{name}.expected_sha256")
    source_manifest = artifacts.get("source_manifest")
    source_values = manifest_values(Path(source_manifest["path"])) if source_manifest and source_manifest.get("sha256") else {}
    source_commit = source_values.get("code_commit") or source_values.get("commit")
    if source_commit and identity.get("code_commit") and source_commit != identity["code_commit"]:
        conflicts.append("source_manifest.code_commit")
    input_manifest = artifacts.get("input_manifest")
    input_values = manifest_values(Path(input_manifest["path"])) if input_manifest and input_manifest.get("sha256") else {}
    input_sha = identity.get("input", {}).get("sha256") if isinstance(identity.get("input"), dict) else None
    manifest_input_sha = input_values.get("sha256") or input_values.get("input_sha256")
    if manifest_input_sha and input_sha and manifest_input_sha != input_sha:
        conflicts.append("input_manifest.sha256")
    dcp = artifacts.get("dcp")
    bitstream_metadata = metadata.get("bitstream")
    expected_dcp = bitstream_metadata.get("dcp_sha256") if isinstance(bitstream_metadata, dict) else None
    if expected_dcp and dcp and dcp.get("sha256") != expected_dcp:
        conflicts.append("bitstream.dcp_sha256")
    status = "mismatch" if mismatches or conflicts else "unknown" if unknown else "verified"
    return {"status": status, "mismatches": mismatches, "conflicts": conflicts, "unknown": unknown}


def merge_check(metadata_check: Any, detected: dict[str, Any]) -> dict[str, Any]:
    if isinstance(metadata_check, str):
        return {**detected, "status": metadata_check}
    if isinstance(metadata_check, dict):
        return {**detected, **metadata_check}
    return detected


def goal_checks(timing: dict[str, Any], metadata: dict[str, Any]) -> dict[str, Any]:
    runtime = metadata_value(metadata, "runtime_s", "runtime_seconds", "runtime")
    if isinstance(runtime, dict):
        runtime = runtime.get("value")
    wns = timing.get("summary", {}).get("wns_ns") if timing.get("summary") else None
    wns_pass = wns is not None and wns > GOAL_WNS_NS
    runtime_pass = isinstance(runtime, (int, float)) and runtime < GOAL_RUNTIME_S
    return {
        "wns": {"value": wns, "threshold": GOAL_WNS_NS, "operator": ">", "passed": wns_pass if wns is not None else None},
        "runtime": {"value": runtime, "threshold": GOAL_RUNTIME_S, "operator": "<", "passed": runtime_pass if isinstance(runtime, (int, float)) else None},
        "passed": wns_pass and runtime_pass if wns is not None and isinstance(runtime, (int, float)) else None,
    }


def build_identity(
    metadata: dict[str, Any],
    log: dict[str, Any],
    timing: dict[str, Any],
    run_dir: Path,
    bitstream: Path | None,
    base_dir: Path | None,
    mode: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    input_value = metadata.get("input")
    if input_value is None and metadata_value(metadata, "input_id") is not None:
        input_value = {"id": metadata_value(metadata, "input_id")}
    elif isinstance(input_value, str):
        input_value = {"id": input_value}
    artifacts = {
        "source_manifest": artifact_reference(metadata.get("source_manifest"), base_dir, "source_manifest"),
        "input_manifest": artifact_reference(metadata.get("input_manifest"), base_dir, "input_manifest"),
        "dcp": artifact_reference(metadata.get("dcp") or default_dcp(run_dir), base_dir, "dcp"),
        "bitstream": bitstream_reference(metadata, run_dir, bitstream, base_dir),
    }
    frequency = metadata_value(metadata, "frequency_mhz", "target_frequency_mhz")
    identity: dict[str, Any] = {
        "vivado_version": log["vivado_version"],
        "run_name": run_dir.name,
        "code_commit": metadata_value(metadata, "code_commit", "commit", "git_commit"),
        "vivado_commit": metadata_value(metadata, "vivado_commit"),
        "frequency_mhz": frequency,
        "input": input_value,
        "strategy": metadata.get("strategy"),
        "artifacts": artifacts,
    }
    if isinstance(identity["strategy"], str):
        identity["strategy"] = {"name": identity["strategy"]}
    if identity["strategy"] is None:
        identity["strategy"] = {}
    identity["strategy"] = {
        **identity["strategy"],
        "directives": {
            phase["name"]: phase["directives"] for phase in log["phases"] if phase["directives"]
        },
    }
    if identity["frequency_mhz"] is None and timing.get("clocks"):
        frequencies = [clock["frequency_mhz"] for clock in timing["clocks"] if clock["frequency_mhz"] is not None]
        if frequencies:
            identity["reported_max_frequency_mhz"] = max(frequencies)
    return identity, artifacts


def apply_metadata_checks(metadata: dict[str, Any], checks: dict[str, Any]) -> dict[str, Any]:
    overrides = metadata.get("checks")
    if not isinstance(overrides, dict):
        return checks
    return {name: merge_check(overrides.get(name), check) for name, check in checks.items()}


def missing_fields(identity: dict[str, Any], timing: dict[str, Any], log: dict[str, Any], mode: str) -> list[str]:
    missing: list[str] = []
    if mode in {"impl", "bitstream"} and timing["summary"] is None:
        missing.append("timing.summary")
    for field in ("vivado_version", "code_commit", "frequency_mhz"):
        if identity.get(field) is None:
            missing.append(f"identity.{field}")
    if not isinstance(identity.get("input"), dict) or not identity["input"].get("id"):
        missing.append("identity.input.id")
    if not identity.get("strategy", {}).get("name"):
        missing.append("identity.strategy.name")
    artifacts = identity.get("artifacts", {})
    for name in ("source_manifest", "input_manifest"):
        if not artifacts.get(name) or artifacts[name].get("missing"):
            missing.append(f"identity.artifacts.{name}")
    if mode in {"impl", "bitstream"} and (not artifacts.get("dcp") or artifacts["dcp"].get("missing")):
        missing.append("identity.artifacts.dcp")
    if mode == "bitstream" and (not artifacts.get("bitstream") or artifacts["bitstream"].get("missing")):
        missing.append("identity.artifacts.bitstream")
    if not log["sources"]:
        missing.append("implementation.logs")
    if mode in {"impl", "bitstream"} and timing["report_kind"] == "routed":
        missing.append("timing.postroute_report")
    return missing


def evidence_quality(timing: dict[str, Any], audit_issues: list[str]) -> str:
    if any("unknown" in issue or "missing" in issue for issue in audit_issues):
        return "unknown"
    coverages = {
        timing["path_families"].get("coverage", timing["path_families"].get("status")),
        timing["resources"].get("coverage", timing["resources"].get("status")),
        timing["congestion"].get("coverage", timing["congestion"].get("status")),
    }
    if "unknown" in coverages:
        return "unknown"
    if "partial" in coverages:
        return "partial"
    if "sampled" in coverages:
        return "sampled"
    if audit_issues:
        return "partial"
    return "complete"


def build_summary(
    input_path: Path,
    report_path: Path | None,
    log_paths: list[Path],
    metadata: dict[str, Any],
    requested_mode: str,
    path_limit: int,
    bitstream: Path | None,
    metadata_base_dir: Path | None = None,
    path_report: Path | None = None,
    utilization_reports: list[Path] | None = None,
    congestion_reports: list[Path] | None = None,
    baseline_summary: Path | None = None,
    baseline_utilization_reports: list[Path] | None = None,
) -> dict[str, Any]:
    if path_report is None:
        discovered_path_reports = resolve_analysis_reports(input_path, report_path, [], PATH_REPORT_PATTERNS)
        path_report = discovered_path_reports[0] if discovered_path_reports else None
    if utilization_reports is None:
        utilization_reports = resolve_analysis_reports(input_path, report_path, [], UTILIZATION_REPORT_PATTERNS)
    if congestion_reports is None:
        congestion_reports = resolve_analysis_reports(input_path, report_path, [], CONGESTION_REPORT_PATTERNS)
    run_dir = report_directory(input_path if input_path.is_dir() else report_path.parent if report_path else input_path)
    timing = parse_timing_report(report_path, path_limit) if report_path else {
        "status": "missing", "report_kind": None, "summary": None, "clocks": [], "critical_paths": [],
        "clock_groups": {"status": "unknown", "coverage": "unknown", "items": []},
        "path_families": {"status": "unknown", "coverage": "unknown", "items": [], "sample_limit": path_limit},
        "primitive_histogram": {"coverage": "unknown", "items": {}, "scope": "data_path"},
        "top_delay_nets": {"coverage": "unknown", "items": [], "sample_limit": path_limit},
        "clock_delays": {"coverage": "unknown", "items": [], "sample_limit": path_limit},
        "resources": {"status": "unknown", "items": []}, "congestion": {"status": "unknown", "items": []},
        "unconstrained_count": None, "setup_violated": None, "hold_violated": None, "violated": None,
    }
    path_report_value = path_report if path_report is not None and path_report.is_file() else None
    path_timing = parse_timing_report(path_report_value, path_limit) if path_report_value else None
    if path_timing is not None and path_timing["critical_paths"]:
        timing["critical_paths"] = path_timing["critical_paths"]
        timing["primitive_histogram"] = path_timing["primitive_histogram"]
        timing["top_delay_nets"] = path_timing["top_delay_nets"]
        timing["clock_delays"] = path_timing["clock_delays"]
        timing["path_families"] = path_timing["path_families"]
    utilization = resource_analysis(
        utilization_reports or [],
        baseline_summary,
        baseline_utilization_reports or [],
    )
    congestion_values = [parse_congestion_report(path) for path in (congestion_reports or []) if path.is_file()]
    congestion = congestion_values[0] if congestion_values else {
        "status": "unknown",
        "coverage": "unknown",
        "reports": [],
        "items": [],
        "sample_limit": None,
    }
    if congestion_values:
        congestion = {
            **congestion,
            "reports": [value["report"] for value in congestion_values],
        }
    timing["resources"] = utilization
    timing["congestion"] = congestion
    timing["analysis_reports"] = {
        "timing_summary": artifact_info(report_path, kind="timing_report") if report_path else None,
        "timing_paths": artifact_info(path_report_value, kind="timing_path_report") if path_report_value else None,
        "utilization": utilization.get("reports", []),
        "congestion": congestion.get("reports", []),
        "baseline": (
            [utilization["baseline"]["source"]]
            if isinstance(utilization.get("baseline"), dict) and utilization["baseline"].get("source")
            else []
        ),
    }
    log = parse_log(log_paths)
    mode = inferred_mode(run_dir, log, requested_mode)
    identity, artifacts = build_identity(metadata, log, timing, run_dir, bitstream, metadata_base_dir, mode)
    consistency = artifact_consistency(artifacts, identity, metadata)
    checks = apply_metadata_checks(metadata, log["checks"])
    timing["unconstrained_count"] = timing.get("unconstrained_count") if timing.get("unconstrained_count") is not None else checks["unconstrained"].get("count")
    phase_state, missing_phases = phase_status(log, mode)
    missing = missing_fields(identity, timing, log, mode)
    goals = goal_checks(timing, metadata)
    issues = list(missing)
    if log["errors"]:
        issues.append("vivado_log_errors")
    if log["critical_warning_count"]:
        issues.append("critical_warnings")
    if consistency["status"] != "verified":
        issues.append(f"artifact_consistency_{consistency['status']}")
    if goals["passed"] is not True:
        issues.append("goal_checks_failed" if goals["passed"] is False else "goal_checks_unknown")
    for name, check in checks.items():
        if check.get("status") != "pass":
            issues.append(f"{name}_{check.get('status', 'unknown')}")
    if timing["path_families"]["status"] == "unknown":
        issues.append("timing.path_families_unknown")
    audit_status = "error" if log["fatal_error_count"] or any(check.get("status") == "fail" for check in checks.values()) or consistency["status"] == "mismatch" or goals["passed"] is False else "partial" if issues else "ok"
    quality = evidence_quality(timing, issues)
    review_reasons = [
        issue for issue in issues
        if "unknown" in issue or "partial" in issue or "error" in issue or "conflict" in issue or "mismatch" in issue
    ]
    return {
        "schema": SCHEMA_NAME,
        "schema_version": SCHEMA_VERSION,
        "analysis_schema": ANALYSIS_SCHEMA_NAME,
        "analysis_schema_version": ANALYSIS_SCHEMA_VERSION,
        "status": phase_state,
        "mode": mode,
        "identity": identity,
        "timing": {"report": artifact_info(report_path, kind="timing_report") if report_path else None, **timing},
        "performance": {"runtime_s": metadata_value(metadata, "runtime_s", "runtime_seconds", "runtime")},
        "implementation": {
            "status": phase_state,
            "run_name": run_dir.name,
            "phases": log["phases"],
            "missing_phases": missing_phases,
            "errors": log["errors"],
            "error_count": log["error_count"],
            "fatal_error_count": log["fatal_error_count"],
            "critical_warning_count": log["critical_warning_count"],
            "warning_count": log["warning_count"],
            "log_sources": log["sources"],
            "log_coverage": log["roles"],
            "checks": checks,
        },
        "goal_checks": goals,
        "evidence": {
            "quality": quality,
            "artifact_consistency": consistency,
            "selection_eligible": timing["report_kind"] == "postroute_physopted" and audit_status == "ok" and quality == "complete",
        },
        "audit": {
            "status": audit_status,
            "missing_fields": missing,
            "raw_text_review_required": audit_status != "ok" or bool(review_reasons),
            "review_reasons": review_reasons,
            "issues": issues,
        },
    }


def positive_int(value: str) -> int:
    try:
        result = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if result <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return result


def write_json(value: dict[str, Any], output: Path | None) -> None:
    rendered = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if output is None:
        print(rendered, end="")
    else:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered, encoding="utf-8")


def optimization_view(summary: dict[str, Any]) -> dict[str, Any]:
    timing = summary.get("timing", {})
    resources = timing.get("resources", {})
    baseline = resources.get("baseline") if isinstance(resources, dict) else None
    changes = baseline.get("changes") if isinstance(baseline, dict) else None
    return {
        "schema": "jyd-vivado-optimization-view",
        "schema_version": 1,
        "source_schema_version": summary.get("schema_version"),
        "analysis_schema_version": summary.get("analysis_schema_version"),
        "identity": {
            name: summary.get("identity", {}).get(name)
            for name in ("run_name", "code_commit", "frequency_mhz", "input", "strategy")
        },
        "goal_checks": summary.get("goal_checks"),
        "timing_summary": timing.get("summary"),
        "coverage": {
            "clock_groups": timing.get("clock_groups", {}).get("coverage", "unknown"),
            "path_families": timing.get("path_families", {}).get("coverage", "unknown"),
            "primitive_histogram": timing.get("primitive_histogram", {}).get("coverage", "unknown"),
            "top_delay_nets": timing.get("top_delay_nets", {}).get("coverage", "unknown"),
            "clock_delays": timing.get("clock_delays", {}).get("coverage", "unknown"),
            "resources": resources.get("coverage", "unknown") if isinstance(resources, dict) else "unknown",
            "congestion": timing.get("congestion", {}).get("coverage", "unknown"),
        },
        "clock_groups": [
            {
                name: item.get(name)
                for name in (
                    "name",
                    "worst_slack_ns",
                    "tns_ns",
                    "failing_endpoints",
                    "total_endpoints",
                    "hold_worst_slack_ns",
                )
            }
            for item in timing.get("clock_groups", {}).get("items", [])
        ],
        "path_families": [
            {
                name: item.get(name)
                for name in (
                    "semantic_family",
                    "classification",
                    "sampled_path_count",
                    "sampled_endpoint_count",
                    "worst_slack_ns",
                    "tns_ns",
                )
            }
            for item in timing.get("path_families", {}).get("items", [])
        ],
        "critical_paths": [
            {
                name: path.get(name)
                for name in (
                    "slack_ns",
                    "source",
                    "destination",
                    "semantic_family",
                    "semantic_family_classification",
                    "occurrences",
                    "data_path_delay_ns",
                    "logic_delay_ns",
                    "route_delay_ns",
                    "route_delay_pct",
                    "logic_levels",
                    "primitive_histogram",
                    "source_clock_delay_ns",
                    "destination_clock_delay_ns",
                    "clock_skew_ns",
                    "line",
                )
            } | {"top_delay_nets": path.get("top_delay_nets", [])[:3]}
            for path in timing.get("critical_paths", [])
        ],
        "primitive_histogram": timing.get("primitive_histogram", {}).get("items", {}),
        "top_delay_nets": timing.get("top_delay_nets", {}).get("items", [])[:5],
        "clock_delays": timing.get("clock_delays", {}).get("items", []),
        "resources": {
            "counts": resources.get("counts") if isinstance(resources, dict) else None,
            "deltas": changes if isinstance(changes, dict) else None,
        },
        "congestion": timing.get("congestion", {}).get("items", [])[:10],
        "audit": {
            "status": summary.get("audit", {}).get("status"),
            "issues": summary.get("audit", {}).get("issues", []),
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Extract a compact versioned JSON audit summary from a Vivado run.")
    parser.add_argument("path", nargs="?", default=str(DEFAULT_RUN_PATH))
    parser.add_argument("--report", type=Path)
    parser.add_argument("--log", type=Path, action="append", default=[])
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--mode", choices=("auto", "synth", "impl", "bitstream"), default="auto")
    parser.add_argument("--path-limit", type=positive_int, default=10)
    parser.add_argument("--path-report", type=Path, help="Timing path report used for primitive, net, and clock-delay extraction.")
    parser.add_argument("--utilization-report", type=Path, action="append", default=[], help="Vivado utilization report; may be repeated.")
    parser.add_argument("--congestion-report", type=Path, action="append", default=[], help="Vivado congestion report; may be repeated.")
    parser.add_argument("--baseline-summary", type=Path, help="Existing JSON summary used for resource deltas.")
    parser.add_argument("--baseline-utilization-report", type=Path, action="append", default=[], help="Baseline utilization report; may be repeated.")
    parser.add_argument("--bitstream", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--view", choices=("audit", "optimization"), default="audit", help="Select full audit JSON or a compact optimization view.")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero unless the full audit passes.")
    args = parser.parse_args(argv)
    input_path = Path(args.path)
    report_path = resolve_report(input_path, args.report)
    log_paths = resolve_logs(input_path, report_path, args.log)
    path_report = args.path_report
    if path_report is None:
        path_reports = resolve_analysis_reports(input_path, report_path, [], PATH_REPORT_PATTERNS)
        path_report = path_reports[0] if path_reports else None
    utilization_reports = resolve_analysis_reports(
        input_path, report_path, args.utilization_report, UTILIZATION_REPORT_PATTERNS
    )
    congestion_reports = resolve_analysis_reports(
        input_path, report_path, args.congestion_report, CONGESTION_REPORT_PATTERNS
    )
    try:
        metadata, metadata_base_dir = load_metadata(args.metadata)
        summary = build_summary(
            input_path,
            report_path,
            log_paths,
            metadata,
            args.mode,
            args.path_limit,
            args.bitstream,
            metadata_base_dir,
            path_report,
            utilization_reports,
            congestion_reports,
            args.baseline_summary,
            args.baseline_utilization_report,
        )
        write_json(optimization_view(summary) if args.view == "optimization" else summary, args.output)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"vivado summary extraction failed: {exc}", file=sys.stderr)
        return 1
    return 2 if args.strict and summary["audit"]["status"] != "ok" else 0


if __name__ == "__main__":
    raise SystemExit(main())
