"""
Metrics collection and reporting: console tables, JSON result files, and
cross-run comparison tables.
"""

from __future__ import annotations

import json
import re
import statistics
import subprocess
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import List, Optional


def format_duration(seconds: float) -> str:
    """
    Format a duration in seconds into a minimal human-readable time string:
      - 5.5 minutes    -> "5:30"
      - 5.5 min + 45ms -> "5:30.045"
      - 1 hr 5m 30s    -> "1:05:30"
      - Under 1 minute -> "0:12.345"
    """
    if seconds is None or seconds < 0:
        return "0:00"

    hours = int(seconds // 3600)
    remainder = seconds % 3600
    minutes = int(remainder // 60)
    secs = remainder % 60

    whole_secs = int(secs)
    ms = int(round((secs - whole_secs) * 1000))

    # Handle rollover from rounding milliseconds
    if ms >= 1000:
        whole_secs += 1
        ms -= 1000
        if whole_secs >= 60:
            whole_secs = 0
            minutes += 1
            if minutes >= 60:
                minutes = 0
                hours += 1

    ms_str = f".{ms:03d}" if ms > 0 else ""

    if hours > 0:
        return f"{hours}:{minutes:02d}:{whole_secs:02d}{ms_str}"
    elif minutes > 0:
        return f"{minutes}:{whole_secs:02d}{ms_str}"
    else:
        return f"0:{whole_secs:02d}{ms_str}"


@dataclass
class RegionMetric:
    file_name: str
    original_size: int
    compressed_size: int
    compress_seconds: float
    decompress_seconds: float


@dataclass
class BenchmarkResult:
    timestamp: str
    linearreader_version: str
    git_commit: Optional[str]
    dataset: str
    mode: str  # "quick" or "full"
    sample_size: int
    seed: Optional[int]
    strategy: str
    region_metrics: List[RegionMetric] = field(default_factory=list)

    # ---- aggregate metrics, filled in by finalize() ----
    total_original_bytes: int = 0
    total_compressed_bytes: int = 0
    compression_ratio: float = 0.0       # original / compressed
    percent_reduction: float = 0.0        # 1 - compressed/original, as %
    total_compress_seconds: float = 0.0
    total_decompress_seconds: float = 0.0
    compress_throughput_mb_s: float = 0.0
    decompress_throughput_mb_s: float = 0.0
    mean_compress_ms: float = 0.0
    median_compress_ms: float = 0.0
    stdev_compress_ms: float = 0.0
    p95_compress_ms: float = 0.0
    p99_compress_ms: float = 0.0
    fastest_region: Optional[str] = None
    fastest_region_ms: float = 0.0
    slowest_region: Optional[str] = None
    slowest_region_ms: float = 0.0

    # ---- optional provenance for dictionary-based strategies ----
    train_dataset: Optional[str] = None
    train_file_count: Optional[int] = None
    dict_size_bytes: Optional[int] = None

    # ---- chunk concatenation order used to build the region body ----
    layout: Optional[str] = None

    def finalize(self) -> "BenchmarkResult":
        if not self.region_metrics:
            return self

        self.total_original_bytes = sum(m.original_size for m in self.region_metrics)
        self.total_compressed_bytes = sum(m.compressed_size for m in self.region_metrics)
        self.total_compress_seconds = sum(m.compress_seconds for m in self.region_metrics)
        self.total_decompress_seconds = sum(m.decompress_seconds for m in self.region_metrics)

        self.compression_ratio = (
            self.total_original_bytes / self.total_compressed_bytes
            if self.total_compressed_bytes else 0.0
        )
        self.percent_reduction = (
            (1.0 - self.total_compressed_bytes / self.total_original_bytes) * 100.0
            if self.total_original_bytes else 0.0
        )

        mb_original = self.total_original_bytes / (1024 * 1024)
        self.compress_throughput_mb_s = (
            mb_original / self.total_compress_seconds if self.total_compress_seconds > 0 else 0.0
        )
        self.decompress_throughput_mb_s = (
            mb_original / self.total_decompress_seconds if self.total_decompress_seconds > 0 else 0.0
        )

        compress_ms = sorted(m.compress_seconds * 1000.0 for m in self.region_metrics)
        self.mean_compress_ms = statistics.mean(compress_ms)
        self.median_compress_ms = statistics.median(compress_ms)
        self.stdev_compress_ms = statistics.pstdev(compress_ms) if len(compress_ms) > 1 else 0.0
        self.p95_compress_ms = _percentile(compress_ms, 95)
        self.p99_compress_ms = _percentile(compress_ms, 99)

        fastest = min(self.region_metrics, key=lambda m: m.compress_seconds)
        slowest = max(self.region_metrics, key=lambda m: m.compress_seconds)
        self.fastest_region = fastest.file_name
        self.fastest_region_ms = fastest.compress_seconds * 1000.0
        self.slowest_region = slowest.file_name
        self.slowest_region_ms = slowest.compress_seconds * 1000.0

        return self


def _percentile(sorted_values: List[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    if len(sorted_values) == 1:
        return sorted_values[0]
    k = (len(sorted_values) - 1) * (pct / 100.0)
    f = int(k)
    c = min(f + 1, len(sorted_values) - 1)
    if f == c:
        return sorted_values[f]
    return sorted_values[f] + (sorted_values[c] - sorted_values[f]) * (k - f)


def git_commit_hash(repo_dir: Path) -> Optional[str]:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=repo_dir, capture_output=True, text=True, timeout=5,
        )
        if out.returncode == 0:
            return out.stdout.strip()
    except Exception:
        pass
    return None


def fmt_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024.0:
            return f"{n:.2f} {unit}"
        n /= 1024.0
    return f"{n:.2f} PB"


def print_console_report(result: BenchmarkResult) -> None:
    print()
    print(f"=== {result.dataset}  |  strategy={result.strategy}  |  mode={result.mode} "
          f"({len(result.region_metrics)} region files) ===")
    if result.train_dataset is not None:
        print(f"  Dictionary trained on: {result.train_dataset} "
              f"({result.train_file_count} file(s), {result.dict_size_bytes} bytes)")
    print(f"  Original size      : {fmt_bytes(result.total_original_bytes)}")
    print(f"  Compressed size    : {fmt_bytes(result.total_compressed_bytes)}")
    print(f"  Compression ratio  : {result.compression_ratio:.3f}x")
    print(f"  Size reduction     : {result.percent_reduction:.2f}%")
    print(f"  Compress time      : {format_duration(result.total_compress_seconds)} "
          f"({result.compress_throughput_mb_s:.1f} MB/s)")
    print(f"  Decompress time    : {format_duration(result.total_decompress_seconds)} "
          f"({result.decompress_throughput_mb_s:.1f} MB/s)")
    print(f"  Per-region compress: mean {result.mean_compress_ms:.2f}ms  "
          f"median {result.median_compress_ms:.2f}ms  stdev {result.stdev_compress_ms:.2f}ms")
    print(f"                       p95 {result.p95_compress_ms:.2f}ms  "
          f"p99 {result.p99_compress_ms:.2f}ms")
    print(f"  Fastest region     : {result.fastest_region} ({result.fastest_region_ms:.2f}ms)")
    print(f"  Slowest region     : {result.slowest_region} ({result.slowest_region_ms:.2f}ms)")


def write_json_result(result: BenchmarkResult, results_dir: Path) -> Path:
    results_dir.mkdir(parents=True, exist_ok=True)
    stamp = result.timestamp.replace(":", "").replace("-", "").replace(" ", "_")
    out_path = results_dir / f"{stamp}_{result.dataset}_{result.strategy}.json"
    payload = asdict(result)
    out_path.write_text(json.dumps(payload, indent=2))
    return out_path


def load_json_result(path: Path) -> dict:
    return json.loads(path.read_text())


SORT_KEYS = {
    "ratio": lambda r: r["compression_ratio"],
    "compress-speed": lambda r: r["compress_throughput_mb_s"],
    "decompress-speed": lambda r: r["decompress_throughput_mb_s"],
    "size": lambda r: -r["total_compressed_bytes"],
}


_PLAIN_BASELINE_NAME = re.compile(r"^(zstd|baseline|zlib|anvil)-?\d+$")


def print_comparison(results: List[dict], sort_by: str = "ratio") -> None:
    if not results:
        print("No results to compare.")
        return

    key_fn = SORT_KEYS.get(sort_by)
    if key_fn is None:
        raise ValueError(f"Unknown sort key {sort_by!r}. Known: {', '.join(SORT_KEYS)}")

    by_dataset: dict = {}
    for r in results:
        by_dataset.setdefault(r["dataset"], []).append(r)

    for dataset, rows in by_dataset.items():
        rows = sorted(rows, key=key_fn, reverse=True)
        baseline = next((r for r in rows if _PLAIN_BASELINE_NAME.match(r["strategy"])), rows[0])

        print()
        print(f"=== Comparison: {dataset}  (sorted by {sort_by}, baseline = {baseline['strategy']}) ===")
        header = f"{'strategy':<20}{'ratio':>8}{'size':>14}{'comp time':>14}{'vs base':>10}{'comp MB/s':>12}{'decomp MB/s':>13}"
        print(header)
        print("-" * len(header))
        for r in rows:
            vs_base = (
                (baseline["total_compressed_bytes"] - r["total_compressed_bytes"])
                / baseline["total_compressed_bytes"] * 100.0
                if baseline["total_compressed_bytes"] else 0.0
            )
            comp_time_str = format_duration(r.get("total_compress_seconds", 0.0))
            print(
                f"{r['strategy']:<20}"
                f"{r['compression_ratio']:>7.3f}x"
                f"{fmt_bytes(r['total_compressed_bytes']):>14}"
                f"{comp_time_str:>14}"
                f"{vs_base:>9.2f}%"
                f"{r['compress_throughput_mb_s']:>12.1f}"
                f"{r['decompress_throughput_mb_s']:>13.1f}"
            )


def now_timestamp() -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S")