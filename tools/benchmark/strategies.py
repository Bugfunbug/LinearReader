"""
Pluggable compression strategies.

This is the extension point of the whole framework: to benchmark a new idea
(long-distance matching, a trained dictionary, a different algorithm, a
preprocessing pass before compression, etc.) you add ONE new class here that
implements CompressionStrategy. Nothing else in the framework needs to
change - datasets.py, runner.py, and report.py all just call
strategy.compress()/decompress() through this interface.
"""

from __future__ import annotations

from abc import ABC, abstractmethod

import ctypes

import brotli
import zstandard as zstd

import struct
import math
import zlib


class CompressionStrategy(ABC):
    """A named, self-contained compression algorithm/configuration."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Short unique identifier, used in filenames and result JSON. e.g. 'zstd4'."""

    @abstractmethod
    def compress(self, data: bytes) -> bytes:
        ...

    @abstractmethod
    def decompress(self, data: bytes, original_length: int) -> bytes:
        ...


class ZstdLevelStrategy(CompressionStrategy):
    """Plain zstd at a fixed level - this is LinearReader's current baseline behavior."""

    def __init__(self, level: int):
        self.level = level
        self._compressor = zstd.ZstdCompressor(level=level)
        self._decompressor = zstd.ZstdDecompressor()

    @property
    def name(self) -> str:
        return f"zstd{self.level}"

    def compress(self, data: bytes) -> bytes:
        return self._compressor.compress(data)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        return self._decompressor.decompress(data, max_output_size=original_length)


class ZstdLdmStrategy(CompressionStrategy):
    """
    Zstd with long-distance matching enabled - mirrors
    ZSTD_c_enableLongDistanceMatching / ZstdCompressCtx.setLong().

    Benchmarked and rejected for 1.3.0: real but small ratio gains
    (2-11%, worst on vanilla_end which is atypical void-heavy data) at the
    cost of ~40% slower compression at levels 2/4, and an outright
    regression (-0.8% to -5.9%) at level 6. Kept in the tool as a reference
    point / in case future zstd versions change this tradeoff.
    """

    def __init__(self, level: int):
        self.level = level
        params = zstd.ZstdCompressionParameters.from_level(level, enable_ldm=True)
        self._compressor = zstd.ZstdCompressor(compression_params=params)
        self._decompressor = zstd.ZstdDecompressor()

    @property
    def name(self) -> str:
        return f"zstd{self.level}-ldm"

    def compress(self, data: bytes) -> bytes:
        return self._compressor.compress(data)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        return self._decompressor.decompress(data, max_output_size=original_length)


class ZstdTunedStrategy(CompressionStrategy):
    """
    Manually pins zstd's search strategy/chainLog/hashLog/searchLog instead
    of relying on a preset level's defaults.

    Presets above ~level 13 switch to the btopt/btultra "optimal parsing"
    strategy, which is expensive because it near-exhaustively evaluates
    parse decisions, not just because it searches a bigger window. Pinning
    strategy=lazy2 (a much cheaper greedy-ish search) at a *high preset
    level's* target ratio, while optionally trimming chainLog/hashLog to
    shrink the search space further, aims to get most of the ratio a high
    level would give you without paying the optimal-parse tax.

    `level` selects the preset used as the *starting point* for defaults
    (window size, target block size, etc.) - `strategy_name` then overrides
    just the search strategy on top of that, and chain_log/hash_log/
    search_log let you additionally shrink the search space. Any of the log
    parameters left as None uses that level's normal default.
    """

    _STRATEGY_NAMES = {
        "fast": zstd.STRATEGY_FAST,
        "dfast": zstd.STRATEGY_DFAST,
        "greedy": zstd.STRATEGY_GREEDY,
        "lazy": zstd.STRATEGY_LAZY,
        "lazy2": zstd.STRATEGY_LAZY2,
        "btlazy2": zstd.STRATEGY_BTLAZY2,
        "btopt": zstd.STRATEGY_BTOPT,
        "btultra": zstd.STRATEGY_BTULTRA,
        "btultra2": zstd.STRATEGY_BTULTRA2,
    }

    def __init__(
            self,
            level: int,
            strategy_name: "str | None" = "lazy2",
            chain_log=None,
            hash_log=None,
            search_log=None,
            target_length=None,
    ):
        kwargs = {}
        self.strategy_name = None
        if strategy_name is not None:
            strategy_key = strategy_name.strip().lower()
            if strategy_key not in self._STRATEGY_NAMES:
                known = ", ".join(sorted(self._STRATEGY_NAMES))
                raise ValueError(f"Unknown zstd strategy {strategy_name!r}. Known: {known}")
            self.strategy_name = strategy_key
            kwargs["strategy"] = self._STRATEGY_NAMES[strategy_key]

        self.level = level
        self.chain_log = chain_log
        self.hash_log = hash_log
        self.search_log = search_log
        self.target_length = target_length

        if chain_log is not None:
            kwargs["chain_log"] = chain_log
        if hash_log is not None:
            kwargs["hash_log"] = hash_log
        if search_log is not None:
            kwargs["search_log"] = search_log
        if target_length is not None:
            kwargs["target_length"] = target_length

        params = zstd.ZstdCompressionParameters.from_level(level, **kwargs)
        self._compressor = zstd.ZstdCompressor(compression_params=params)
        self._decompressor = zstd.ZstdDecompressor()

    @property
    def name(self) -> str:
        bits = [f"zstd{self.level}" if self.strategy_name is None else f"zstd{self.level}-{self.strategy_name}"]
        if self.chain_log is not None:
            bits.append(f"cl{self.chain_log}")
        if self.hash_log is not None:
            bits.append(f"hl{self.hash_log}")
        if self.search_log is not None:
            bits.append(f"sl{self.search_log}")
        if self.target_length is not None:
            bits.append(f"tl{self.target_length}")
        return "-".join(bits)

    def compress(self, data: bytes) -> bytes:
        return self._compressor.compress(data)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        return self._decompressor.decompress(data, max_output_size=original_length)


class ZstdDictStrategy(CompressionStrategy):
    """
    Zstd at a given level, using a pre-trained dictionary.

    Unlike the other strategies, this one can't be built from a bare
    "name:level" CLI spec, because it needs an actual trained dictionary
    (a zstandard.ZstdCompressionDict) to exist first - training requires
    real sample data from a training file set. See runner.train_dictionary()
    and the `dict-bench` CLI command, which handle producing that dictionary
    and then constructing this strategy with it.
    """

    def __init__(self, level: int, dict_data: "zstd.ZstdCompressionDict", label: str = "dict"):
        self.level = level
        self.dict_data = dict_data
        self.label = label
        self._compressor = zstd.ZstdCompressor(level=level, dict_data=dict_data)
        self._decompressor = zstd.ZstdDecompressor(dict_data=dict_data)

    @property
    def name(self) -> str:
        return f"zstd{self.level}-{self.label}"

    def compress(self, data: bytes) -> bytes:
        return self._compressor.compress(data)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        return self._decompressor.decompress(data, max_output_size=original_length)


class BrotliStrategy(CompressionStrategy):
    """
    Google's Brotli, at a given quality level (0-11, unlike Zstd's 1-22 -
    a different scale entirely, so "same number" does not mean "same
    effort" between the two).

    lgwin (window size, base-2 log) defaults to Brotli's maximum, 24
    (16 MB). That's a real, inherent ceiling worth being aware of: unlike
    Zstd level 22's 128 MB window (comfortably larger than any single
    region body), Brotli cannot see further back than 16 MB no matter what
    quality is used - for region bodies bigger than that, Brotli physically
    cannot match against the earliest chunks in the file, which Zstd can.
    """

    def __init__(self, quality: int, lgwin: int = 24):
        if not (0 <= quality <= 11):
            raise ValueError(f"Brotli quality must be 0-11, got {quality}")
        if not (10 <= lgwin <= 24):
            raise ValueError(f"Brotli lgwin must be 10-24, got {lgwin}")
        self.quality = quality
        self.lgwin = lgwin

    @property
    def name(self) -> str:
        base = f"brotli{self.quality}"
        return base if self.lgwin == 24 else f"{base}-lgwin{self.lgwin}"

    def compress(self, data: bytes) -> bytes:
        return brotli.compress(data, quality=self.quality, lgwin=self.lgwin)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        return brotli.decompress(data)


# ---------------------------------------------------------------------------
# Large Window Brotli
#
# RFC 7932 (the standard Brotli format) hard-caps the sliding window at
# 1<<24 - 16 bytes (~16 MB) - Section 9.1 defines an explicit, enumerated
# bit pattern for the header's WBITS field covering only values 10-24, with
# nothing larger defined anywhere in the spec. "Large Window Brotli" (up to
# 1<<30 bytes, ~1 GB) is a real but NON-STANDARD extension Google added to
# their own reference implementation - streams built with it are not RFC
# 7932 streams, and can only be read by a decoder that also has the
# extension explicitly enabled (confirmed empirically: a standard decoder
# rejects such a stream with _ERROR_FORMAT_WINDOW_BITS, not a generic
# error).
#
# Neither the `brotli` package's high-level API nor `brotlicffi`'s exposes
# this - both cap `lgwin` at 24, and `brotlicffi`'s compiled cffi bindings
# don't expose BrotliDecoderSetParameter at all (confirmed by inspecting its
# declared C symbols directly), even though the real libbrotli C API has
# supported this since large-window support was added upstream. This class
# bypasses both convenience wrappers and drives the real, lower-level C API
# via ctypes instead - loading brotlicffi's OWN compiled extension file
# (resolved through Python's normal import machinery, not a hardcoded
# filename, so this works whatever the actual compiled filename is on a
# given platform/Python version) rather than depending on any separate
# system-installed libbrotlienc/libbrotlidec library, which may not exist
# on every machine (e.g. a plain macOS install with only this tool's pip
# dependencies).
# ---------------------------------------------------------------------------

_BROTLI_OPERATION_FINISH = 2
_BROTLI_PARAM_MODE = 0
_BROTLI_MODE_GENERIC = 0
_BROTLI_PARAM_QUALITY = 1
_BROTLI_PARAM_LGWIN = 2
_BROTLI_PARAM_LGBLOCK = 3
_BROTLI_PARAM_SIZE_HINT = 5
_BROTLI_PARAM_LARGE_WINDOW = 6
_BROTLI_DECODER_PARAM_LARGE_WINDOW = 1
_BROTLI_STREAM_BUFFER_SIZE = 1 << 20  # 1 MB working buffer for the streaming API


def _load_large_window_brotli_lib() -> ctypes.CDLL:
    try:
        import brotlicffi._brotlicffi as _raw_ext
    except ImportError as exc:
        raise ImportError(
            "Large-window Brotli requires the 'brotlicffi' package (pip install brotlicffi) "
            "in addition to 'Brotli' - it's the only one of the two whose compiled extension "
            "exports the low-level C functions this strategy needs."
        ) from exc

    lib = ctypes.CDLL(_raw_ext.__file__)

    lib.BrotliEncoderCreateInstance.restype = ctypes.c_void_p
    lib.BrotliEncoderCreateInstance.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    lib.BrotliEncoderSetParameter.restype = ctypes.c_int
    lib.BrotliEncoderSetParameter.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_uint32]
    lib.BrotliEncoderCompressStream.restype = ctypes.c_int
    lib.BrotliEncoderCompressStream.argtypes = [
        ctypes.c_void_p, ctypes.c_int,
        ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte)),
        ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte)),
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.BrotliEncoderIsFinished.restype = ctypes.c_int
    lib.BrotliEncoderIsFinished.argtypes = [ctypes.c_void_p]
    lib.BrotliEncoderDestroyInstance.argtypes = [ctypes.c_void_p]

    lib.BrotliDecoderCreateInstance.restype = ctypes.c_void_p
    lib.BrotliDecoderCreateInstance.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    lib.BrotliDecoderSetParameter.restype = ctypes.c_int
    lib.BrotliDecoderSetParameter.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_uint32]
    lib.BrotliDecoderDecompressStream.restype = ctypes.c_int
    lib.BrotliDecoderDecompressStream.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte)),
        ctypes.POINTER(ctypes.c_size_t), ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte)),
        ctypes.POINTER(ctypes.c_size_t),
    ]
    lib.BrotliDecoderDestroyInstance.argtypes = [ctypes.c_void_p]
    return lib


_large_window_lib: "ctypes.CDLL | None" = None


def _large_window_lib_instance() -> ctypes.CDLL:
    global _large_window_lib
    if _large_window_lib is None:
        _large_window_lib = _load_large_window_brotli_lib()
    return _large_window_lib


class BrotliLargeWindowStrategy(CompressionStrategy):
    """
    Brotli with the non-standard "Large Window" extension enabled, allowing
    lgwin up to 30 (1 GB) instead of RFC 7932's standard 24 (16 MB) cap.
    Both encoder and decoder must have this enabled - streams produced this
    way are not readable by a standard-compliant Brotli decoder.

    Benchmarked and rejected: enabling BROTLI_DECODER_PARAM_LARGE_WINDOW
    costs a real ~6-15x DECOMPRESSION slowdown versus standard-window
    Brotli, confirmed by isolating it from this class's own ctypes overhead
    (same window size, flag on vs off - the flag alone accounts for the
    regression, not the ctypes glue code). On real region data this bought
    only ~0.3 percentage points of extra size reduction beyond what
    standard-window `brotli:` already gets - nowhere near enough to justify
    the cost, especially since decompression happens on ordinary chunk
    reads (LinearRegionFile.loadFromDisk decompresses the whole region body
    on any non-cached read), not just in a background/idle thread the way
    compression speed can be. Kept in the tool as a reference point.

    IMPORTANT, empirically discovered constraint (not documented anywhere
    we found - confirmed by directly testing every quality/lgwin
    combination, since a crash here is a native segfault, not a catchable
    Python exception): this library build crashes outright when quality>=10
    (Brotli's two highest, "Zopfli-style" optimal-parsing quality levels)
    is combined with lgwin>=29. quality<=9 works fine at any lgwin up to 30;
    quality 10-11 work fine up to lgwin=28 (256 MB - already far bigger than
    any realistic region body). That combination is rejected here up front
    with a clear error instead of segfaulting.

    Two more parameters, always applied:

    - BROTLI_PARAM_MODE is explicitly pinned to BROTLI_MODE_GENERIC (0).
      This is the default anyway, but pinned explicitly rather than left
      implicit - MODE_TEXT/MODE_FONT are tuned for UTF-8/WOFF2 and would
      actively hurt compression on binary NBT data.
    - BROTLI_PARAM_SIZE_HINT is always set to the exact input length (which
      LinearRegionFile already knows upfront, via bodySize, before
      compressing). Verified empirically (not just taken on claim) to
      produce byte-identical output to leaving it unset - it's purely an
      internal preallocation hint, not a behavior change - so there's no
      reason to make it optional.

    `lgblock` (BROTLI_PARAM_LGBLOCK, log2 of the max input block size used
    for block-switching) is exposed as an optional tunable, left at
    Brotli's own default (0, meaning "let the encoder choose") unless set.
    """

    _UNSAFE_MIN_QUALITY_FOR_MAX_WINDOW = 10
    _MAX_SAFE_LGWIN_AT_HIGH_QUALITY = 28

    def __init__(self, quality: int, lgwin: int = 28, lgblock: int = 0):
        if not (0 <= quality <= 11):
            raise ValueError(f"Brotli quality must be 0-11, got {quality}")
        if not (10 <= lgwin <= 30):
            raise ValueError(f"Large-window lgwin must be 10-30, got {lgwin}")
        if lgblock != 0 and not (16 <= lgblock <= 24):
            raise ValueError(f"Brotli lgblock must be 0 (auto) or 16-24, got {lgblock}")
        if (quality >= self._UNSAFE_MIN_QUALITY_FOR_MAX_WINDOW
                and lgwin > self._MAX_SAFE_LGWIN_AT_HIGH_QUALITY):
            raise ValueError(
                f"quality={quality} with lgwin={lgwin} is a combination that crashes this "
                f"library build outright (confirmed by direct testing - quality>=10 only "
                f"works safely up to lgwin={self._MAX_SAFE_LGWIN_AT_HIGH_QUALITY}, which is "
                f"already a 256 MB window, far bigger than any realistic region body). "
                f"Use lgwin<={self._MAX_SAFE_LGWIN_AT_HIGH_QUALITY} at this quality, or drop "
                f"quality to 9 or below if you specifically need a bigger window."
            )
        self.quality = quality
        self.lgwin = lgwin
        self.lgblock = lgblock
        self._lib = _large_window_lib_instance()

    @property
    def name(self) -> str:
        base = f"brotli{self.quality}-lw{self.lgwin}"
        return base if self.lgblock == 0 else f"{base}-lb{self.lgblock}"

    def compress(self, data: bytes) -> bytes:
        lib = self._lib
        enc = lib.BrotliEncoderCreateInstance(None, None, None)
        try:
            lib.BrotliEncoderSetParameter(enc, _BROTLI_PARAM_MODE, _BROTLI_MODE_GENERIC)
            lib.BrotliEncoderSetParameter(enc, _BROTLI_PARAM_QUALITY, self.quality)
            lib.BrotliEncoderSetParameter(enc, _BROTLI_PARAM_LARGE_WINDOW, 1)
            lib.BrotliEncoderSetParameter(enc, _BROTLI_PARAM_LGWIN, self.lgwin)
            if self.lgblock:
                lib.BrotliEncoderSetParameter(enc, _BROTLI_PARAM_LGBLOCK, self.lgblock)
            lib.BrotliEncoderSetParameter(enc, _BROTLI_PARAM_SIZE_HINT, len(data))

            input_buf = (ctypes.c_ubyte * max(1, len(data))).from_buffer_copy(data or b"\x00")
            next_in = ctypes.cast(input_buf, ctypes.POINTER(ctypes.c_ubyte))
            avail_in = ctypes.c_size_t(len(data))

            out_chunks = []
            out_buf = (ctypes.c_ubyte * _BROTLI_STREAM_BUFFER_SIZE)()
            while True:
                next_out = ctypes.cast(out_buf, ctypes.POINTER(ctypes.c_ubyte))
                avail_out = ctypes.c_size_t(_BROTLI_STREAM_BUFFER_SIZE)
                ok = lib.BrotliEncoderCompressStream(
                    enc, _BROTLI_OPERATION_FINISH,
                    ctypes.byref(avail_in), ctypes.byref(next_in),
                    ctypes.byref(avail_out), ctypes.byref(next_out), None)
                if not ok:
                    raise RuntimeError("BrotliEncoderCompressStream reported failure")
                produced = _BROTLI_STREAM_BUFFER_SIZE - avail_out.value
                if produced > 0:
                    out_chunks.append(bytes(out_buf[:produced]))
                if lib.BrotliEncoderIsFinished(enc):
                    break
            return b"".join(out_chunks)
        finally:
            lib.BrotliEncoderDestroyInstance(enc)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        lib = self._lib
        dec = lib.BrotliDecoderCreateInstance(None, None, None)
        try:
            lib.BrotliDecoderSetParameter(dec, _BROTLI_DECODER_PARAM_LARGE_WINDOW, 1)

            input_buf = (ctypes.c_ubyte * max(1, len(data))).from_buffer_copy(data or b"\x00")
            next_in = ctypes.cast(input_buf, ctypes.POINTER(ctypes.c_ubyte))
            avail_in = ctypes.c_size_t(len(data))

            out_size = max(1, original_length)
            out_buf = (ctypes.c_ubyte * out_size)()
            next_out = ctypes.cast(out_buf, ctypes.POINTER(ctypes.c_ubyte))
            avail_out = ctypes.c_size_t(out_size)

            result = lib.BrotliDecoderDecompressStream(
                dec, ctypes.byref(avail_in), ctypes.byref(next_in),
                ctypes.byref(avail_out), ctypes.byref(next_out), None)
            if result != 1:  # BROTLI_DECODER_RESULT_SUCCESS
                raise RuntimeError(f"BrotliDecoderDecompressStream failed with result code {result}")
            produced = out_size - avail_out.value
            return bytes(out_buf[:produced])
        finally:
            lib.BrotliDecoderDestroyInstance(dec)



class ZlibStrategy(CompressionStrategy):
    """
    Applies Zlib to the entire Linear region body.
    This shows how Zlib performs compared to Zstd on a single concatenated stream.
    """
    def __init__(self, level: int = 6):
        self.level = level

    @property
    def name(self) -> str:
        return f"zlib{self.level}"

    def compress(self, data: bytes) -> bytes:
        return zlib.compress(data, level=self.level)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        return zlib.decompress(data)



class VanillaAnvilStrategy(CompressionStrategy):
    """
    Simulates true Vanilla Minecraft behavior: per-chunk Zlib compression
    and 4KB sector padding with an 8KB Anvil header.
    Note: Only compatible with '--layout row_major' (the default) since Anvil
    forces a specific chunk layout.
    """
    def __init__(self, level: int = 6):
        self.level = level

    @property
    def name(self) -> str:
        return f"anvil{self.level}"

    def compress(self, data: bytes) -> bytes:
        header = data[:8192]
        payload = data[8192:]

        anvil_header = bytearray(8192)
        for i in range(1024):
            # Copy timestamp to Anvil header (bytes 4096..8191)
            anvil_header[4096 + i * 4 : 4096 + i * 4 + 4] = header[i * 8 + 4 : i * 8 + 8]

        out_sectors = []
        current_sector = 2  # Sectors 0 and 1 are used by the 8KB header
        offset = 0

        for i in range(1024):
            size, = struct.unpack_from(">I", header, i * 8)
            if size == 0:
                continue

            chunk_data = payload[offset : offset + size]
            offset += size

            compressed = zlib.compress(chunk_data, level=self.level)

            # Anvil format: [4-byte length][1-byte compression type (2=zlib)]
            # Note: length includes the 1 byte for compression type
            sector_data = struct.pack(">IB", len(compressed) + 1, 2) + compressed

            # Pad to the nearest 4096-byte sector
            sector_count = math.ceil(len(sector_data) / 4096)
            padded_sector = sector_data.ljust(sector_count * 4096, b'\x00')
            out_sectors.append(padded_sector)

            # Write location to Anvil header (3 bytes offset, 1 byte sector count)
            loc_entry = (current_sector << 8) | (sector_count & 0xFF)
            struct.pack_into(">I", anvil_header, i * 4, loc_entry)

            current_sector += sector_count

        return bytes(anvil_header) + b"".join(out_sectors)

    def decompress(self, data: bytes, original_length: int) -> bytes:
        linear_header = bytearray(8192)
        linear_payload = []

        for i in range(1024):
            loc_entry, = struct.unpack_from(">I", data, i * 4)
            sector_offset = loc_entry >> 8
            sector_count = loc_entry & 0xFF

            ts = data[4096 + i * 4 : 4096 + i * 4 + 4]

            if sector_offset == 0 or sector_count == 0:
                linear_header[i*8 : i*8+8] = b'\x00\x00\x00\x00' + ts
                continue

            byte_offset = sector_offset * 4096
            length, comp_type = struct.unpack_from(">IB", data, byte_offset)

            if comp_type != 2:
                raise ValueError(f"VanillaAnvilStrategy expected zlib (2), got {comp_type}")

            compressed_chunk = data[byte_offset + 5 : byte_offset + 4 + length]
            uncompressed_chunk = zlib.decompress(compressed_chunk)

            struct.pack_into(">I", linear_header, i * 8, len(uncompressed_chunk))
            linear_header[i*8+4 : i*8+8] = ts

            linear_payload.append(uncompressed_chunk)

        return bytes(linear_header) + b"".join(linear_payload)


# Registry used by the CLI's --strategy flag. Add new strategies here so
# `--strategy name:level` can find them by name.
STRATEGY_FACTORIES = {
    "baseline": ZstdLevelStrategy,
    "zstd": ZstdLevelStrategy,
    "ldm": ZstdLdmStrategy,
    "zlib": ZlibStrategy,
    "anvil": VanillaAnvilStrategy,
}


def build_strategy(spec: str) -> CompressionStrategy:
    """
    Parses a CLI strategy spec into a CompressionStrategy instance.

    Three forms are supported:

      - "name:level" for the simple strategies, e.g. "baseline:4" or
        "ldm:19" or "baseline:-3" (negative levels are Zstd's dedicated
        fast mode - real feature, not a typo).

      - "tuned:level[:strategy[:chainLog[:hashLog[:searchLog]]]]" for
        ZstdTunedStrategy, e.g.:
          "tuned:19"                    -> level 19's defaults, but lazy2 search
          "tuned:19:lazy2"              -> same, explicit
          "tuned:19:lazy2:22"           -> also caps chainLog at 22
          "tuned:19:lazy2:22:22"        -> also caps hashLog at 22
          "tuned:19:lazy2:22:22:22"     -> also caps searchLog at 22
        Any trailing field can be left off to keep that level's default.

      - "targetlen:level:length" - unlike "tuned", this leaves the level's
        own default search strategy alone (so btopt/btultra stays on at
        levels that normally use it) and only overrides targetLength, e.g.
        "targetlen:19:64".

      - "brotli:quality[:lgwin]" for BrotliStrategy - quality is 0-11 (NOT
        the same scale as Zstd's 1-22 levels), lgwin (window size log,
        10-24) defaults to Brotli's max of 24 if omitted, e.g. "brotli:11"
        or "brotli:11:22".

      - "brotli_lw:quality[:lgwin[:lgblock]]" for BrotliLargeWindowStrategy -
        same quality scale as "brotli", but lgwin can go up to 30 (1 GB)
        instead of the RFC-standard cap of 24, using Brotli's non-standard
        "Large Window" extension (see BrotliLargeWindowStrategy's docstring
        for the compatibility caveat this implies, AND for an important
        crash constraint at quality>=10 with lgwin>=29). Defaults to
        lgwin=28 if omitted (safe at every quality level, and already a
        256 MB window - far bigger than any realistic region body).
        lgblock (0 = auto, or 16-24) optionally tunes the max block size
        used for block-switching. e.g. "brotli_lw:11", "brotli_lw:9:30",
        or "brotli_lw:11:28:18".
    """
    if ":" not in spec:
        raise ValueError(
            f"Strategy spec must be 'name:level' (e.g. 'baseline:4'), got: {spec!r}"
        )
    name, rest = spec.split(":", 1)
    name = name.strip().lower()

    if name == "brotli":
        parts = rest.split(":")
        try:
            quality = int(parts[0].strip())
        except ValueError as exc:
            raise ValueError(f"Bad quality in strategy spec {spec!r}") from exc

        if len(parts) > 1 and parts[1].strip():
            try:
                lgwin = int(parts[1].strip())
            except ValueError as exc:
                raise ValueError(f"Bad lgwin in strategy spec {spec!r}") from exc
        else:
            lgwin = 24

        return BrotliStrategy(quality=quality, lgwin=lgwin)

    if name == "brotli_lw":
        parts = rest.split(":")
        try:
            quality = int(parts[0].strip())
        except ValueError as exc:
            raise ValueError(f"Bad quality in strategy spec {spec!r}") from exc

        if len(parts) > 1 and parts[1].strip():
            try:
                lgwin = int(parts[1].strip())
            except ValueError as exc:
                raise ValueError(f"Bad lgwin in strategy spec {spec!r}") from exc
        else:
            lgwin = 28  # safe at every quality level 0-11 - see BrotliLargeWindowStrategy docstring

        if len(parts) > 2 and parts[2].strip():
            try:
                lgblock = int(parts[2].strip())
            except ValueError as exc:
                raise ValueError(f"Bad lgblock in strategy spec {spec!r}") from exc
        else:
            lgblock = 0

        return BrotliLargeWindowStrategy(quality=quality, lgwin=lgwin, lgblock=lgblock)

    if name == "tuned":
        parts = rest.split(":")
        try:
            level = int(parts[0].strip())
        except ValueError as exc:
            raise ValueError(f"Bad compression level in strategy spec {spec!r}") from exc

        strategy_name = parts[1].strip() if len(parts) > 1 and parts[1].strip() else "lazy2"

        def _opt_int(idx: int):
            if len(parts) > idx and parts[idx].strip():
                try:
                    return int(parts[idx].strip())
                except ValueError as exc:
                    raise ValueError(f"Bad integer in strategy spec {spec!r}") from exc
            return None

        return ZstdTunedStrategy(
            level=level,
            strategy_name=strategy_name,
            chain_log=_opt_int(2),
            hash_log=_opt_int(3),
            search_log=_opt_int(4),
        )

    if name == "targetlen":
        parts = rest.split(":")
        if len(parts) != 2:
            raise ValueError(
                f"Strategy spec 'targetlen' must be 'targetlen:level:length' (e.g. "
                f"'targetlen:19:64'), got: {spec!r}"
            )
        try:
            level = int(parts[0].strip())
            length = int(parts[1].strip())
        except ValueError as exc:
            raise ValueError(f"Bad integer in strategy spec {spec!r}") from exc

        return ZstdTunedStrategy(level=level, strategy_name=None, target_length=length)

    try:
        level = int(rest.strip())
    except ValueError as exc:
        raise ValueError(f"Bad compression level in strategy spec {spec!r}") from exc

    factory = STRATEGY_FACTORIES.get(name)
    if factory is None:
        known = ", ".join(sorted(STRATEGY_FACTORIES)) + ", tuned, targetlen, brotli, brotli_lw"
        raise ValueError(f"Unknown strategy {name!r}. Known strategies: {known}")
    return factory(level)