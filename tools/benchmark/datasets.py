"""
Dataset discovery.

A "dataset" is just a folder containing .mca files somewhere inside it. Any
direct subfolder of --datasets-dir that contains at least one .mca file
is auto-discovered as a dataset. No registration step is needed to add a
5th dataset later - just drop the folder in.

Minecraft world saves split region data into up to three folder types
(region/, entities/, poi/), which hold structurally very different data
(entities and poi files are typically much sparser than region files). If a
dataset folder contains any of these as direct subfolders, each one becomes
its OWN separate dataset (e.g. "vanilla_overworld_region",
"vanilla_overworld_entities", "vanilla_overworld_poi") instead of being
silently blended together - mixing them into one pool would let whichever
subfolder happens to have more files quietly dominate quick-mode's random
sample, and would obscure whether a compression strategy behaves
differently on the very different data each subfolder holds. A dataset
folder with .mca files directly inside it (no region/entities/poi
subfolders) is still discovered exactly as before, as a single dataset.
"""

from __future__ import annotations

import random
from dataclasses import dataclass
from pathlib import Path
from typing import List

KNOWN_SUBFOLDERS = ("region", "entities", "poi")


@dataclass
class Dataset:
    name: str
    root: Path
    files: List[Path]  # all .mca files, sorted for determinism

    @property
    def total_bytes(self) -> int:
        return sum(f.stat().st_size for f in self.files)


def discover_datasets(datasets_dir: Path) -> List[Dataset]:
    if not datasets_dir.is_dir():
        raise FileNotFoundError(f"Datasets directory not found: {datasets_dir}")

    found: List[Dataset] = []
    for child in sorted(p for p in datasets_dir.iterdir() if p.is_dir()):
        subfolders_present = [
            name for name in KNOWN_SUBFOLDERS
            if (child / name).is_dir() and any((child / name).rglob("*.mca"))
        ]

        if subfolders_present:
            for name in subfolders_present:
                sub_path = child / name
                mca_files = sorted(sub_path.rglob("*.mca"))
                found.append(Dataset(name=f"{child.name}_{name}", root=sub_path, files=mca_files))
        else:
            mca_files = sorted(child.rglob("*.mca"))
            if mca_files:
                found.append(Dataset(name=child.name, root=child, files=mca_files))

    return found


def select_datasets(datasets: List[Dataset], names: List[str]) -> List[Dataset]:
    """names may contain 'all', or exact dataset names."""
    if not names or names == ["all"]:
        return datasets

    by_name = {d.name: d for d in datasets}
    selected = []
    missing = []
    for name in names:
        if name in by_name:
            selected.append(by_name[name])
        else:
            missing.append(name)
    if missing:
        available = ", ".join(sorted(by_name)) or "(none found)"
        raise ValueError(
            f"Unknown dataset(s): {', '.join(missing)}. Available: {available}"
        )
    return selected


def split_train_eval(files: List[Path], train_fraction: float, seed: int) -> tuple[List[Path], List[Path]]:
    """
    Deterministically splits a file list into a training set and a held-out
    evaluation set. Used for dictionary training: a dictionary trained on
    the same files it's then benchmarked against would report inflated
    ratios, since the dictionary would literally contain fragments of the
    exact files being "tested" - this makes sure eval files are always ones
    the dictionary has never seen.

    train_fraction is the fraction (0 < f < 1) of files used for training;
    the remainder is the held-out evaluation set. Sorted first, then
    shuffled with a seeded Random, so the split is reproducible regardless
    of filesystem iteration order.
    """
    if not (0.0 < train_fraction < 1.0):
        raise ValueError(f"train_fraction must be between 0 and 1 (exclusive), got {train_fraction}")

    ordered = sorted(files)
    rng = random.Random(seed)
    shuffled = ordered[:]
    rng.shuffle(shuffled)

    split_at = max(1, min(len(shuffled) - 1, round(len(shuffled) * train_fraction)))
    train_files = sorted(shuffled[:split_at])
    eval_files = sorted(shuffled[split_at:])
    return train_files, eval_files


def sample_files(files: List[Path], sample_size: int, seed: int) -> List[Path]:
    """
    Deterministic subset selection for quick mode: sort first (so filesystem
    iteration order never matters), then seed a private Random so repeated
    runs with the same seed always pick the same files, independent of any
    other random usage elsewhere in the program.
    """
    ordered = sorted(files)
    if sample_size >= len(ordered):
        return ordered
    rng = random.Random(seed)
    return sorted(rng.sample(ordered, sample_size))