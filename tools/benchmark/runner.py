"""
Runs a compression strategy against a list of region files and produces a
BenchmarkResult. Correctness (decompressed output == original body,
byte-for-byte) is checked for every single region file; any mismatch aborts
the whole run immediately, per the framework's correctness requirement.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import List, Optional

import zstandard as zstd

from anvil import read_region
from datasets import Dataset, sample_files, split_train_eval
from linear_format import build_body
from report import BenchmarkResult, RegionMetric, now_timestamp
from strategies import CompressionStrategy, ZstdDictStrategy, ZstdLevelStrategy


class VerificationError(Exception):
    """Raised when a decompressed region body does not match the original."""


def select_files(dataset: Dataset, mode: str, sample_size: int, seed: int) -> List[Path]:
    return select_files_from_pool(dataset.files, mode, sample_size, seed)


def select_files_from_pool(pool: List[Path], mode: str, sample_size: int, seed: int) -> List[Path]:
    if mode == "quick":
        return sample_files(pool, sample_size, seed)
    if mode == "full":
        return sorted(pool)
    raise ValueError(f"Unknown mode {mode!r} (expected 'quick' or 'full')")


def run_benchmark_on_files(
        files: List[Path],
        strategy: CompressionStrategy,
        dataset_name: str,
        mode: str,
        seed: Optional[int],
        linearreader_version: str,
        git_commit: Optional[str],
        verbose: bool = True,
        extra_fields: Optional[dict] = None,
        layout: str = "row_major",
) -> BenchmarkResult:
    # Non-default layouts get a "+morton" style suffix on the reported
    # strategy name (but NOT on strategy.name itself, which stays whatever
    # the compression strategy actually is) so row-major and reordered runs
    # of the *same* compression strategy never collide in result filenames
    # or the comparison table, while the baseline-detection regex in
    # report.py still matches the unsuffixed row-major name correctly.
    display_name = strategy.name if layout == "row_major" else f"{strategy.name}+{layout}"

    result = BenchmarkResult(
        timestamp=now_timestamp(),
        linearreader_version=linearreader_version,
        git_commit=git_commit,
        dataset=dataset_name,
        mode=mode,
        sample_size=len(files),
        seed=seed,
        strategy=display_name,
    )
    result.layout = layout
    if extra_fields:
        for key, value in extra_fields.items():
            setattr(result, key, value)

    total = len(files)
    for i, mca_path in enumerate(files, start=1):
        if verbose:
            print(f"  [{i}/{total}] {dataset_name}/{mca_path.name} ({display_name}) ...", end="", flush=True)

        chunks = read_region(mca_path)
        body = build_body(chunks, order=layout)

        t0 = time.perf_counter()
        compressed = strategy.compress(body)
        t1 = time.perf_counter()

        decompressed = strategy.decompress(compressed, len(body))
        t2 = time.perf_counter()

        if decompressed != body:
            raise VerificationError(
                f"Round-trip mismatch for {mca_path} under strategy {display_name}: "
                f"decompressed output does not match the original body byte-for-byte."
            )

        compress_seconds = t1 - t0
        decompress_seconds = t2 - t1

        result.region_metrics.append(RegionMetric(
            file_name=mca_path.name,
            original_size=len(body),
            compressed_size=len(compressed),
            compress_seconds=compress_seconds,
            decompress_seconds=decompress_seconds,
        ))

        if verbose:
            ratio = len(body) / len(compressed) if compressed else 0.0
            print(f" {ratio:.2f}x  ({compress_seconds * 1000:.1f}ms)")

    return result.finalize()


def run_benchmark(
        dataset: Dataset,
        strategy: CompressionStrategy,
        mode: str,
        sample_size: int,
        seed: int,
        linearreader_version: str,
        git_commit: Optional[str],
        verbose: bool = True,
        layout: str = "row_major",
) -> BenchmarkResult:
    files = select_files(dataset, mode, sample_size, seed)
    return run_benchmark_on_files(
        files=files,
        strategy=strategy,
        dataset_name=dataset.name,
        mode=mode,
        seed=seed if mode == "quick" else None,
        linearreader_version=linearreader_version,
        git_commit=git_commit,
        verbose=verbose,
        layout=layout,
    )


def train_dictionary(
        train_files: List[Path],
        dict_size: int,
        verbose: bool = True,
) -> "zstd.ZstdCompressionDict":
    """
    Trains a Zstd dictionary from the given region files' LinearReader-shape
    bodies (same body each region would actually be compressed as - see
    linear_format.build_body). Each region body is used as one training
    sample.

    Two real constraints from zstd's trainer, both guarded against here
    with an actionable error rather than letting a cryptic native error
    (or an out-of-memory crash) happen partway through:

    - Too few samples: zstd's trainer fails outright ("Src size is
      incorrect") with fewer than roughly 8 distinct samples, regardless of
      their size.
    - Too much total training data: zstd's dictionary trainer is designed
      to work well with a training set around 100x the target dictionary
      size (so ~11 MB total for a 110 KB dictionary is already generous) -
      not gigabytes. Region *bodies* are the decompressed form of each
      chunk, so they're much larger than the .mca file's size on disk
      (often 4-5x bigger once decompressed) - it's easy to accidentally
      hand the trainer tens of GB without meaning to. Beyond a few GB this
      also risks failing with the same "Src size is incorrect" error (an
      internal size limit in zstd's trainer), after already spending the
      time and RAM to read everything into memory. This function checks
      the running total as it reads, so it fails fast instead of reading
      everything first.
    """
    if not train_files:
        raise ValueError("No training files provided.")

    min_samples = 16
    if len(train_files) < min_samples:
        raise ValueError(
            f"Dictionary training needs at least {min_samples} training files (got "
            f"{len(train_files)}). zstd's trainer fails outright with too few samples. "
            f"Use a larger --train-fraction, a bigger dataset, or point --train-dataset "
            f"at a dataset with more files."
        )

    # zstd's own guidance is ~100x the dictionary size; this ceiling is far
    # more generous than that (roughly 1500x a typical 110KB dictionary) to
    # comfortably cover larger --dict-size values, while still stopping well
    # before the multi-GB range where the trainer itself starts failing.
    max_total_bytes = 2 * 1024 * 1024 * 1024  # 2 GiB

    samples: List[bytes] = []
    total_bytes = 0
    for i, mca_path in enumerate(train_files, start=1):
        if verbose:
            print(f"  [train {i}/{len(train_files)}] reading {mca_path.name} ...", flush=True)
        chunks = read_region(mca_path)
        body = build_body(chunks)
        samples.append(body)
        total_bytes += len(body)

        if total_bytes > max_total_bytes:
            raise ValueError(
                f"Training data reached {total_bytes / (1024 ** 3):.2f} GB after only "
                f"{i}/{len(train_files)} file(s), which is far more than a dictionary "
                f"trainer needs (zstd's own guidance is roughly 100x --dict-size, i.e. "
                f"only tens of MB for a typical dictionary) and risks failing outright or "
                f"exhausting memory. Use --max-train-files to cap how many files are used "
                f"for training - a few dozen region files is already generous training data."
            )

    if verbose:
        print(f"  Training dictionary: {len(samples)} sample(s), "
              f"{total_bytes / (1024 * 1024):.1f} MB total, target size {dict_size} bytes ...")

    t0 = time.perf_counter()
    try:
        dict_data = zstd.train_dictionary(dict_size, samples)
    except zstd.ZstdError as exc:
        raise ValueError(
            f"zstd dictionary training failed ({exc}). This usually means either too few "
            f"training samples, or --dict-size is too large relative to the training data "
            f"available. Try more training files or a smaller --dict-size."
        ) from exc
    elapsed = time.perf_counter() - t0

    if verbose:
        actual_size = len(dict_data.as_bytes())
        print(f"  Dictionary trained in {elapsed:.1f}s -> {actual_size} bytes "
              f"(dict_id={dict_data.dict_id()})")

    return dict_data


def run_dictionary_benchmark(
        train_dataset: Dataset,
        eval_dataset: Dataset,
        level: int,
        dict_size: int,
        train_fraction: float,
        mode: str,
        sample_size: int,
        seed: int,
        linearreader_version: str,
        git_commit: Optional[str],
        max_train_files: Optional[int] = None,
        verbose: bool = True,
) -> "tuple[BenchmarkResult, BenchmarkResult]":
    """
    Trains a dictionary and benchmarks it against a baseline (no dictionary)
    strategy at the same level, over the identical held-out evaluation file
    list, so the two results are directly and fairly comparable.

    - If train_dataset is the same dataset as eval_dataset: the dataset is
      split into a training portion and a held-out evaluation portion (see
      datasets.split_train_eval) so the dictionary is never evaluated
      against files it was trained on.
    - If they're different datasets: the dictionary is trained on
      train_dataset (optionally capped by max_train_files) and evaluated
      against eval_dataset's files directly - this is the "does this
      generalize to a world type the dictionary has never seen" test.

    Returns (dict_result, baseline_result).
    """
    if train_dataset.name == eval_dataset.name:
        train_files, eval_pool = split_train_eval(train_dataset.files, train_fraction, seed)
    else:
        train_files = sorted(train_dataset.files)
        eval_pool = sorted(eval_dataset.files)

    if max_train_files is not None and len(train_files) > max_train_files:
        train_files = sample_files(train_files, max_train_files, seed)

    eval_files = select_files_from_pool(eval_pool, mode, sample_size, seed)

    dict_data = train_dictionary(train_files, dict_size, verbose=verbose)

    dict_strategy = ZstdDictStrategy(level=level, dict_data=dict_data, label="dict")
    baseline_strategy = ZstdLevelStrategy(level)

    extra = {
        "train_dataset": train_dataset.name,
        "train_file_count": len(train_files),
        "dict_size_bytes": len(dict_data.as_bytes()),
    }

    dict_result = run_benchmark_on_files(
        files=eval_files,
        strategy=dict_strategy,
        dataset_name=eval_dataset.name,
        mode=mode,
        seed=seed,
        linearreader_version=linearreader_version,
        git_commit=git_commit,
        verbose=verbose,
        extra_fields=extra,
    )
    baseline_result = run_benchmark_on_files(
        files=eval_files,
        strategy=baseline_strategy,
        dataset_name=eval_dataset.name,
        mode=mode,
        seed=seed,
        linearreader_version=linearreader_version,
        git_commit=git_commit,
        verbose=verbose,
    )
    return dict_result, baseline_result