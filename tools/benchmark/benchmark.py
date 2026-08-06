#!/usr/bin/env python3
"""
LinearReader compression benchmark CLI.

Quick start:

    pip install -r requirements.txt

    # See what datasets it finds
    python benchmark.py list-datasets

    # Fast iteration (default: 40 random files per dataset, seeded)
    python benchmark.py run --mode quick --strategy baseline:4 --strategy ldm:4

    # Full validation run before a release
    python benchmark.py run --mode full --strategy baseline:4 --strategy ldm:4

    # Compare every result you've saved so far
    python benchmark.py compare
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import List

from datasets import discover_datasets, select_datasets
from report import (
    git_commit_hash,
    load_json_result,
    print_comparison,
    print_console_report,
    write_json_result,
)
from runner import run_benchmark, run_dictionary_benchmark
from strategies import build_strategy

THIS_DIR = Path(__file__).resolve().parent
DEFAULT_DATASETS_DIR = THIS_DIR / "datasets"
DEFAULT_RESULTS_DIR = THIS_DIR / "results"
DEFAULT_LINEARREADER_VERSION = "1.3.0-dev"
DEFAULT_STRATEGIES = ["baseline:4"]


def cmd_list_datasets(args: argparse.Namespace) -> int:
    datasets = discover_datasets(args.datasets_dir)
    if not datasets:
        print(f"No datasets found under {args.datasets_dir}")
        print("Each dataset is a subfolder containing .mca files, e.g.:")
        print(f"  {args.datasets_dir}/vanilla_overworld/*.mca")
        return 1

    print(f"Datasets found under {args.datasets_dir}:")
    for d in datasets:
        size_mb = d.total_bytes / (1024 * 1024)
        print(f"  - {d.name:<32} {len(d.files):>5} files   {size_mb:>10.1f} MB")
    return 0


def cmd_dict_bench(args: argparse.Namespace) -> int:
    datasets = discover_datasets(args.datasets_dir)
    if not datasets:
        print(f"No datasets found under {args.datasets_dir}", file=sys.stderr)
        return 1

    by_name = {d.name: d for d in datasets}
    if args.train_dataset not in by_name:
        available = ", ".join(sorted(by_name))
        print(f"Error: unknown --train-dataset {args.train_dataset!r}. Available: {available}",
              file=sys.stderr)
        return 1
    train_dataset = by_name[args.train_dataset]

    eval_name = args.eval_dataset or args.train_dataset
    if eval_name not in by_name:
        available = ", ".join(sorted(by_name))
        print(f"Error: unknown --eval-dataset {eval_name!r}. Available: {available}", file=sys.stderr)
        return 1
    eval_dataset = by_name[eval_name]

    commit = git_commit_hash(args.repo_dir)
    max_train_files = None if args.max_train_files == 0 else args.max_train_files

    try:
        dict_result, baseline_result = run_dictionary_benchmark(
            train_dataset=train_dataset,
            eval_dataset=eval_dataset,
            level=args.level,
            dict_size=args.dict_size,
            train_fraction=args.train_fraction,
            mode=args.mode,
            sample_size=args.sample_size,
            seed=args.seed,
            linearreader_version=args.version,
            git_commit=commit,
            max_train_files=max_train_files,
            verbose=not args.quiet,
        )
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    print_console_report(dict_result)
    print_console_report(baseline_result)

    dict_out = write_json_result(dict_result, args.results_dir)
    base_out = write_json_result(baseline_result, args.results_dir)
    print(f"  -> saved {dict_out}")
    print(f"  -> saved {base_out}")

    from dataclasses import asdict
    print_comparison([asdict(dict_result), asdict(baseline_result)], sort_by=args.sort_by)

    if train_dataset.name != eval_dataset.name:
        print()
        print(f"NOTE: dictionary was trained on '{train_dataset.name}' and evaluated on "
              f"'{eval_dataset.name}' - this is a cross-dataset generalization test, not just "
              f"an in-distribution result.")

    return 0


def cmd_run(args: argparse.Namespace) -> int:
    datasets = discover_datasets(args.datasets_dir)
    if not datasets:
        print(f"No datasets found under {args.datasets_dir}", file=sys.stderr)
        return 1

    try:
        selected = select_datasets(datasets, args.datasets)
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    strategy_specs = args.strategy or DEFAULT_STRATEGIES
    try:
        strategies = [build_strategy(spec) for spec in strategy_specs]
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1

    commit = git_commit_hash(args.repo_dir)
    all_results = []

    for dataset in selected:
        for strategy in strategies:
            result = run_benchmark(
                dataset=dataset,
                strategy=strategy,
                mode=args.mode,
                sample_size=args.sample_size,
                seed=args.seed,
                linearreader_version=args.version,
                git_commit=commit,
                verbose=not args.quiet,
                layout=args.layout,
            )
            print_console_report(result)
            out_path = write_json_result(result, args.results_dir)
            print(f"  -> saved {out_path}")
            all_results.append(result)

    if len(strategies) > 1:
        # Immediate side-by-side comparison of this run's strategies, per dataset.
        from dataclasses import asdict
        print_comparison([asdict(r) for r in all_results], sort_by=args.sort_by)

    return 0


def cmd_compare(args: argparse.Namespace) -> int:
    if args.results:
        paths: List[Path] = [Path(p) for p in args.results]
    else:
        paths = sorted(args.results_dir.glob("*.json"))

    if not paths:
        print(f"No result files found under {args.results_dir}", file=sys.stderr)
        return 1

    results = [load_json_result(p) for p in paths]
    print_comparison(results, sort_by=args.sort_by)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="LinearReader compression benchmark")
    sub = parser.add_subparsers(dest="command", required=True)

    common_dirs = argparse.ArgumentParser(add_help=False)
    common_dirs.add_argument(
        "--datasets-dir", type=Path, default=DEFAULT_DATASETS_DIR,
        help=f"Root folder containing dataset subfolders (default: {DEFAULT_DATASETS_DIR})",
    )

    p_list = sub.add_parser("list-datasets", parents=[common_dirs],
                            help="Show every dataset the tool can find")
    p_list.set_defaults(func=cmd_list_datasets)

    p_run = sub.add_parser("run", parents=[common_dirs], help="Run a benchmark")
    p_run.add_argument("--datasets", nargs="*", default=["all"],
                       help="Dataset name(s) to run, or 'all' (default: all)")
    p_run.add_argument("--mode", choices=["quick", "full"], default="quick")
    p_run.add_argument("--sample-size", type=int, default=40,
                       help="Files per dataset in quick mode (default: 40)")
    p_run.add_argument("--seed", type=int, default=42,
                       help="Seed for deterministic quick-mode sampling (default: 42)")
    p_run.add_argument(
        "--strategy", action="append",
        help="Compression strategy as 'name:level', e.g. 'baseline:4' or 'ldm:19'. "
             "Repeat this flag to benchmark several strategies in one run. "
             "Default: baseline:4",
    )
    p_run.add_argument(
        "--layout", choices=["row_major", "morton", "morton_xor"], default="row_major",
        help="How chunk bytes are arranged in the region body before compression. "
             "'row_major' matches production today. 'morton' (Z-order) reorders chunks so "
             "spatially-adjacent ones are physically closer together in the byte stream. "
             "'morton_xor' additionally XOR-deltas each chunk against the previous one in "
             "that same Z-order traversal, aiming to turn shared NBT structure into runs of "
             "zero bytes. Default: row_major.",
    )
    p_run.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    p_run.add_argument("--version", default=DEFAULT_LINEARREADER_VERSION,
                       help="LinearReader version label to stamp into results")
    p_run.add_argument("--repo-dir", type=Path, default=THIS_DIR,
                       help="Repo path used to look up the current git commit hash")
    p_run.add_argument("--sort-by", choices=["ratio", "compress-speed", "decompress-speed", "size"],
                       default="ratio")
    p_run.add_argument("--quiet", action="store_true", help="Suppress per-file progress lines")
    p_run.set_defaults(func=cmd_run)

    p_compare = sub.add_parser("compare", help="Compare previously saved result JSON files")
    p_compare.add_argument("--results", nargs="*",
                           help="Specific result JSON file(s) to compare. Default: all files in --results-dir")
    p_compare.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    p_compare.add_argument("--sort-by", choices=["ratio", "compress-speed", "decompress-speed", "size"],
                           default="ratio")
    p_compare.set_defaults(func=cmd_compare)

    p_dict = sub.add_parser("dict-bench", parents=[common_dirs],
                            help="Train a Zstd dictionary and benchmark it against a plain baseline")
    p_dict.add_argument("--train-dataset", required=True,
                        help="Dataset to train the dictionary on")
    p_dict.add_argument("--eval-dataset", default=None,
                        help="Dataset to evaluate on (default: same as --train-dataset, using a "
                             "held-out split so the dictionary is never tested on files it trained on)")
    p_dict.add_argument("--level", type=int, default=6,
                        help="Zstd compression level to use for both the dictionary and baseline strategies (default: 6)")
    p_dict.add_argument("--dict-size", type=int, default=112640,
                        help="Target dictionary size in bytes (default: 112640, zstd's typical default)")
    p_dict.add_argument("--train-fraction", type=float, default=0.8,
                        help="Fraction of --train-dataset used for training when --eval-dataset is the "
                             "same dataset; the rest becomes the held-out evaluation set (default: 0.8)")
    p_dict.add_argument("--max-train-files", type=int, default=60,
                        help="Cap on how many files are used for training (default: 60). Zstd's own "
                             "guidance is a training set around 100x --dict-size, and region bodies "
                             "are decompressed (much bigger than the .mca file size on disk), so a "
                             "few dozen files is already generous - using hundreds risks failing "
                             "outright or exhausting memory. Pass a larger value or 0 for unlimited "
                             "if you specifically want to test with more data.")
    p_dict.add_argument("--mode", choices=["quick", "full"], default="quick",
                        help="Controls how many evaluation files are used (training is unaffected by this)")
    p_dict.add_argument("--sample-size", type=int, default=40,
                        help="Evaluation files to use in quick mode (default: 40)")
    p_dict.add_argument("--seed", type=int, default=42)
    p_dict.add_argument("--results-dir", type=Path, default=DEFAULT_RESULTS_DIR)
    p_dict.add_argument("--version", default=DEFAULT_LINEARREADER_VERSION)
    p_dict.add_argument("--repo-dir", type=Path, default=THIS_DIR)
    p_dict.add_argument("--sort-by", choices=["ratio", "compress-speed", "decompress-speed", "size"],
                        default="ratio")
    p_dict.add_argument("--quiet", action="store_true")
    p_dict.set_defaults(func=cmd_dict_bench)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())