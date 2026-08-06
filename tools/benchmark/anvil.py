"""
Pure-Python reader for vanilla Minecraft Anvil (.mca) region files.

This intentionally does NOT depend on Minecraft, Java, or LinearReader's own
code. The Anvil format is simple enough to parse directly:

  - Bytes 0..4095      : 1024 location entries (3-byte sector offset + 1-byte
                          sector count), one per chunk, in (localX, localZ)
                          order where localX = i % 32, localZ = i // 32.
  - Bytes 4096..8191   : 1024 big-endian 4-byte "last modified" timestamps,
                          same ordering.
  - After that         : 4096-byte sectors. Each present chunk lives at
                          (sector_offset * 4096) and starts with:
                              [4-byte big-endian length][1-byte compression
                              type][ (length - 1) bytes of compressed data ]
                          Compression type: 1 = gzip, 2 = zlib, 3 = raw,
                          type | 0x80 means the chunk is stored externally in
                          a sibling c.<chunkX>.<chunkZ>.mcc file (rare; only
                          used for chunks too big to fit "normally").

We only care about extracting the raw (decompressed) NBT bytes for every
present chunk, plus its stored timestamp, so LinearReader's own body format
can be reproduced faithfully for benchmarking.
"""

from __future__ import annotations

import gzip
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional

REGION_DIM = 32
CHUNK_COUNT = REGION_DIM * REGION_DIM
SECTOR_SIZE = 4096


class AnvilFormatError(Exception):
    """Raised when a .mca (or its .mcc sidecar) does not parse as expected."""


@dataclass(frozen=True)
class AnvilChunk:
    local_index: int  # 0..1023, = local_x + local_z * 32
    data: bytes        # raw (decompressed) chunk NBT bytes
    timestamp: int      # big-endian 4-byte value straight from the header


def parse_region_coords(mca_path: Path) -> tuple[int, int]:
    """Extracts (regionX, regionZ) from a filename like r.-1.3.mca."""
    parts = mca_path.name.split(".")
    if len(parts) != 4 or parts[0] != "r" or parts[3] != "mca":
        raise AnvilFormatError(f"Not a region filename: {mca_path.name}")
    try:
        return int(parts[1]), int(parts[2])
    except ValueError as exc:
        raise AnvilFormatError(f"Bad region coordinates in: {mca_path.name}") from exc


def _decompress(comp_type: int, raw: bytes, path: Path, local_index: int) -> bytes:
    if comp_type == 1:
        return gzip.decompress(raw)
    if comp_type == 2:
        return zlib.decompress(raw)
    if comp_type == 3:
        return raw
    raise AnvilFormatError(
        f"Unsupported chunk compression type {comp_type} in {path.name} "
        f"at local index {local_index}"
    )


def read_region(mca_path: Path) -> Dict[int, AnvilChunk]:
    """
    Reads every present chunk out of a .mca file.

    Returns a dict keyed by local_index (0..1023) -> AnvilChunk. Chunks with
    no data (never generated) are simply absent from the dict.
    """
    region_x, region_z = parse_region_coords(mca_path)
    raw = mca_path.read_bytes()
    if len(raw) < SECTOR_SIZE * 2:
        # Empty/near-empty region file - valid, just nothing to read.
        return {}

    chunks: Dict[int, AnvilChunk] = {}

    for local_index in range(CHUNK_COUNT):
        loc_off = local_index * 4
        entry = raw[loc_off:loc_off + 4]
        sector_offset = (entry[0] << 16) | (entry[1] << 8) | entry[2]
        sector_count = entry[3]
        if sector_offset == 0 or sector_count == 0:
            continue  # chunk never generated

        ts_off = SECTOR_SIZE + local_index * 4
        (timestamp,) = struct.unpack(">I", raw[ts_off:ts_off + 4])

        byte_offset = sector_offset * SECTOR_SIZE
        if byte_offset + 5 > len(raw):
            raise AnvilFormatError(
                f"Chunk location out of bounds in {mca_path.name} at local index {local_index}"
            )

        (length,) = struct.unpack(">I", raw[byte_offset:byte_offset + 4])
        comp_type = raw[byte_offset + 4]
        payload_start = byte_offset + 5
        payload_len = length - 1

        if comp_type & 0x80:
            # Externally stored chunk (c.<chunkX>.<chunkZ>.mcc sidecar).
            local_x = local_index % REGION_DIM
            local_z = local_index // REGION_DIM
            chunk_x = region_x * REGION_DIM + local_x
            chunk_z = region_z * REGION_DIM + local_z
            mcc_path = mca_path.parent / f"c.{chunk_x}.{chunk_z}.mcc"
            if not mcc_path.exists():
                raise AnvilFormatError(
                    f"External chunk file missing: {mcc_path.name} "
                    f"(referenced by {mca_path.name})"
                )
            compressed = mcc_path.read_bytes()
            real_type = comp_type & 0x7F
            data = _decompress(real_type, compressed, mca_path, local_index)
        else:
            if payload_start + payload_len > len(raw):
                raise AnvilFormatError(
                    f"Chunk payload out of bounds in {mca_path.name} at local index {local_index}"
                )
            compressed = raw[payload_start:payload_start + payload_len]
            data = _decompress(comp_type, compressed, mca_path, local_index)

        chunks[local_index] = AnvilChunk(local_index=local_index, data=data, timestamp=timestamp)

    return chunks