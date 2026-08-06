"""
Reproduces the exact uncompressed "body" byte layout that
LinearRegionFile.serializeRegionBody() builds in the real mod
(modules/core/.../linear/LinearRegionFile.java), so this benchmark measures
compression against the real thing LinearReader compresses - not raw .mca
bytes, which would understate the format's whole-region single-stream
compression advantage.

Default layout (matches LinearRegionFile / INNER_HEADER_SIZE):

    bytes 0 .. 8191   : 1024 x [4-byte big-endian chunk size]
                                [4-byte big-endian timestamp]
                        one entry per local chunk index (0..1023), in the
                        same local_x + local_z*32 order Anvil uses. Chunk
                        size 0 = chunk not present. This table's SLOT order
                        never changes regardless of `order` below - it's
                        always addressable by local_index, exactly like
                        production. Declared chunk sizes always match the
                        ORIGINAL (undelta'd) chunk length, even under
                        "morton_xor" below, since XOR never changes length.
    bytes 8192 ..     : present chunks' bytes, arranged according to
                        `order` (no padding, no gaps). Today's production
                        format always uses row-major (ascending
                        local_index), untransformed bytes here.

Three `order` values, each testing a different hypothesis about the DATA
section only - none of them parse or modify chunk NBT contents, so all stay
compatible with the "opaque byte pipe" constraint the
exporter/Voxy-compat/c2me paths rely on:

  - "row_major": today's production behavior.
  - "morton": reorders chunks (Z-order/Morton traversal) so
    spatially-adjacent chunks end up physically closer together in the byte
    stream, without altering any chunk's bytes.
  - "morton_xor": on top of the Morton traversal, each chunk (after the
    first) is stored as a byte-wise XOR delta against the PREVIOUS chunk's
    ORIGINAL bytes in that same traversal - the idea being that structurally
    similar neighboring chunks (repeated NBT tag names/palette structure at
    the same byte offsets) should XOR down to long runs of zero bytes,
    which compress extremely well. This is purely a reversible per-chunk
    byte transform - decoding only needs the previous chunk's original
    bytes and the stored delta.
"""

from __future__ import annotations

import struct
from typing import Dict, List, Optional

from anvil import AnvilChunk, CHUNK_COUNT, REGION_DIM

INNER_HEADER_SIZE = CHUNK_COUNT * 8  # 8192 bytes


def _morton_code(x: int, z: int) -> int:
    """Interleaves the bits of two 5-bit coordinates (0-31) into a 10-bit Z-order code."""
    code = 0
    for bit in range(5):
        code |= ((x >> bit) & 1) << (2 * bit)
        code |= ((z >> bit) & 1) << (2 * bit + 1)
    return code


def _compute_morton_order() -> List[int]:
    """
    Returns a permutation of local_index values (0..1023), ordered by
    Z-order/Morton code of their (local_x, local_z) position - i.e. the
    traversal order in which spatially-adjacent chunks end up physically
    close together in the concatenated byte stream.
    """
    return sorted(
        range(CHUNK_COUNT),
        key=lambda idx: _morton_code(idx % REGION_DIM, idx // REGION_DIM),
    )


ROW_MAJOR_ORDER: List[int] = list(range(CHUNK_COUNT))
MORTON_ORDER: List[int] = _compute_morton_order()

# Orders that are a pure reordering of untransformed chunk bytes.
REORDER_ONLY_TRAVERSALS = {
    "row_major": ROW_MAJOR_ORDER,
    "morton": MORTON_ORDER,
}

KNOWN_ORDERS = set(REORDER_ONLY_TRAVERSALS) | {"morton_xor"}


def _xor_delta(current: bytes, previous: bytes) -> bytes:
    """
    XORs `current` against `previous` over their overlapping length;
    any trailing bytes of `current` beyond len(previous) are kept as-is.
    Always the same length as `current`, so declared chunk sizes in the
    header are unaffected by this transform.
    """
    min_len = min(len(current), len(previous))
    out = bytearray(current)
    for i in range(min_len):
        out[i] ^= previous[i]
    return bytes(out)


def _build_morton_xor_data(chunks: Dict[int, AnvilChunk]) -> List[bytes]:
    data_parts: List[bytes] = []
    previous_raw: Optional[bytes] = None
    for local_index in MORTON_ORDER:
        chunk = chunks.get(local_index)
        if chunk is None or len(chunk.data) == 0:
            continue
        if previous_raw is None:
            data_parts.append(chunk.data)
        else:
            data_parts.append(_xor_delta(chunk.data, previous_raw))
        previous_raw = chunk.data  # chain against the ORIGINAL bytes, never the delta
    return data_parts


def build_body(chunks: Dict[int, AnvilChunk], order: str = "row_major") -> bytes:
    """
    Builds the region body. `order` selects how chunk DATA bytes are
    arranged (the header table's slot layout is always keyed by
    local_index regardless of `order`):

      - "row_major" (default): identical to production.
      - "morton": Z-order traversal, bytes otherwise untouched.
      - "morton_xor": Z-order traversal, each chunk (after the first)
        XOR-delta'd against the previous chunk's original bytes.
    """
    if order not in KNOWN_ORDERS:
        raise ValueError(f"Unknown chunk order {order!r}. Known: {', '.join(sorted(KNOWN_ORDERS))}")

    header = bytearray(INNER_HEADER_SIZE)
    for local_index in range(CHUNK_COUNT):
        chunk = chunks.get(local_index)
        size = len(chunk.data) if chunk is not None else 0
        timestamp = chunk.timestamp if chunk is not None else 0
        struct.pack_into(">II", header, local_index * 8, size, timestamp)

    if order == "morton_xor":
        data_parts = _build_morton_xor_data(chunks)
    else:
        traversal = REORDER_ONLY_TRAVERSALS[order]
        data_parts = [
            chunks[local_index].data
            for local_index in traversal
            if local_index in chunks and len(chunks[local_index].data) > 0
        ]

    return bytes(header) + b"".join(data_parts)