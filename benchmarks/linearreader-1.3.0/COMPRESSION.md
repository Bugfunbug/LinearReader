## Version 1.3.0 Compression Benchmarks
For LinearReader 1.3.0, I decided to use python scripts to gather compression data instead of 
using the worlds generated for the read/write performance metrics. I used Chunky to pregenerate 
the following datasets of `.mca` files:
- Vanilla Overworld - 1,092 `region` files (9.2 GB), 975 `entities` files (222 MB), 1,078 `poi` 
files (74 MB), for a total of 3,146 files, or 9.51 GB of data.
- Vanilla Nether - 227 `region` files (1.3 GB), 229 `entities` files (28.2 MB), 259 `poi` files 
(12.3 KB), for a total of 766 files, or 1.37 GB of data.
- Vanilla End - 617 `region` files (2.3 GB), 522 `entities` files (4.8 MB), 617 `poi` files (844 KB), 
for a total of 1,757 files, or 2.33 GB of data.
- Terralith + Tectonic Overworld - 401 `region` files (2.8 GB), 351 `entities` files (64.5 MB), 387 
`poi` files (32.1 MB), for a total of 1,140 files, or 2.89 GB of data.

Each of these datasets were individually run through the following compression algorithms:
- Zlib (level 6) + `.mca` format: This is the algorithm (and format) used by vanilla Minecraft.
- Zstd (level 4) + `.linear` format: This is what LinearReader uses for live writes.
- Zstd (level 22) + `.linear` format: This is what LinearReader uses (by default) to recompress 
files.
- Brotli (level 11) + `.linear` format: Another compression algorithm which can be used for 
LinearReader's recompressor instead of Zstd.

The following data was collected:
## Overworld
### Region
| Format                | Size    | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|---------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 8.38 GB | 5.44x             | 75.7 MB/s         | 1271.3 MB/s         |
| Zstd 4 (`.linear`)    | 6.21 GB | 7.39x             | 585.5 MB/s        | 1412.8 MB/s         |
| Zstd 22 (`.linear`)   | 4.28 GB | 10.71x            | 4.4 MB/s          | 1589.2 MB/s         |
| Brotli 11 (`.linear`) | 3.82 GB | 12.022x           | 0.8 MB/s          | 865.8 MB/s          |

### Entities
| Format                | Size      | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|-----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 211.84 MB | 0.452x            | 58.8 MB/s         | 199.8 MB/s          |
| Zstd 4 (`.linear`)    | 7.25 MB   | 13.21x            | 789.6 MB/s        | 5420.7 MB/s         |
| Zstd 22 (`.linear`)   | 6.69 MB   | 14.303x           | 2.5 MB/s          | 3458.9 MB/s         |
| Brotli 11 (`.linear`) | 6.51 MB   | 14.704x           | 0.9 MB/s          | 1188.5 MB/s         |

### Poi
| Format                | Size      | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|-----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 68.49 MB  | 0.16x             | 30.1 MB/s         | 33.5 MB/s           |
| Zstd 4 (`.linear`)    | 469.88 KB | 23.847x           | 800.1 MB/s        | 2045.5 MB/s         |
| Zstd 22 (`.linear`)   | 403.22 KB | 27.789x           | 3.8 MB/s          | 1781.9 MB/s         |
| Brotli 11 (`.linear`) | 379.36 KB | 29.536x           | 2.4 MB/s          | 562.2 MB/s          |

## Nether
### Region
| Format                | Size      | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|-----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 1.23 GB   | 5.536x            | 77.2 MB/s         | 1272.8 MB/s         |
| Zstd 4 (`.linear`)    | 834.15 MB | 8.388x            | 772.7 MB/s        | 1826.1 MB/s         |
| Zstd 22 (`.linear`)   | 567.59 MB | 12.327x           | 4.5 MB/s          | 1887.5 MB/s         |
| Brotli 11 (`.linear`) | 504.91 MB | 13.857x           | 0.9 MB/s          | 873.8 MB/s          |

### Entities
| Format                | Size      | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|-----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 26.91 MB  | 0.438x            | 54.5 MB/s         | 134.3 MB/s          |
| Zstd 4 (`.linear`)    | 900.52 KB | 13.387x           | 1643 MB/s         | 4733.5 MB/s         |
| Zstd 22 (`.linear`)   | 831.38 KB | 14.502x           | 2.2 MB/s          | 2661.8  MB/s        |
| Brotli 11 (`.linear`) | 803.37 KB | 15.008x           | 1.0 MB/s          | 1011.5 MB/s         |

### Poi
| Format                | Size    | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|---------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 2.02 MB | 0.998x            | 35.3 MB/s         | 30.7 MB/s           |
| Zstd 4 (`.linear`)    | 4.92 KB | 419.43x           | 4942.7 MB/s       | 2591.5 MB/s         |
| Zstd 22 (`.linear`)   | 4.67 KB | 442.522x          | 614.8 MB/s        | 2702.1 MB/s         |
| Brotli 11 (`.linear`) | 3.39 KB | 608.325x          | 26.6 MB/s         | 655.1 MB/s          |

## End
_Note: The End dimension is mostly void, leading to some really fast and small compression._
### Region
| Format                | Size      | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|-----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 2.16 GB   | 3.655x            | 188.4 MB/s        | 2769.4 MB/s         |
| Zstd 4 (`.linear`)    | 243.76 MB | 33.197x           | 1811.1 MB/s       | 6166.9 MB/s         |
| Zstd 22 (`.linear`)   | 140.73 MB | 57.502x           | 10.1 MB/s         | 4062.7 MB/s         |
| Brotli 11 (`.linear`) | 122.7 MB  | 65.954x           | 2.3 MB/s          | 1576.4 MB/s         |

### Entities
| Format                | Size      | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|-----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 7.62 MB   | 0.715x            | 39.3 MB/s         | 38.3 MB/s           |
| Zstd 4 (`.linear`)    | 170.45 KB | 32.718x           | 1930.4 MB/s       | 29.48.5 MB/s        |
| Zstd 22 (`.linear`)   | 163.13 KB | 34.187x           | 9.3 MB/s          | 2542.4 MB/s         |
| Brotli 11 (`.linear`) | 152.29 KB | 36.621x           | 2.8 MB/s          | 618.7 MB/s          |

### Poi
| Format                | Size     | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 5.04 MB  | 0.956x            | 35.4 MB/s         | 31.4 MB/s           |
| Zstd 4 (`.linear`)    | 18.63 KB | 264.979x          | 3568.7 MB/s       | 2680.1 MB/s         |
| Zstd 22 (`.linear`)   | 17.81 KB | 277.171x          | 210 MB/s          | 2741.9 MB/s         |
| Brotli 11 (`.linear`) | 14.52 KB | 339.989x          | 24.2 MB/s         | 645 MB/s            |

## Terralith + Tectonic
### Region
| Format                | Size    | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|---------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 2.56 GB | 5.967x            | 84.1 MB/s         | 1338.1 MB/s         |
| Zstd 4 (`.linear`)    | 1.8 GB  | 8.492x            | 735.8 MB/s        | 1742.8 MB/s         |
| Zstd 22 (`.linear`)   | 1.24 GB | 12.371x           | 4.4 MB/s          | 1787.3 MB/s         |
| Brotli 11 (`.linear`) | 1.1 GB  | 13.916x           | 0.9 MB/s          | 951.3 MB/s          |

### Entities
| Format                | Size     | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 61.61 MB | 0.424x            | 55.4 MB/s         | 169.7 MB/s          |
| Zstd 4 (`.linear`)    | 2.02 MB  | 12.945x           | 693.4 MB/s        | 4734.1 MB/s         |
| Zstd 22 (`.linear`)   | 1.87 MB  | 14.009x           | 2.6 MB/s          | 3240.3 MB/s         |
| Brotli 11 (`.linear`) | 1.81 MB  | 14.446x           | 1 MB/s            | 1114.4 MB/s         |

### Poi
| Format                | Size      | Compression Ratio | Compression Speed | Decompression Speed |
|-----------------------|-----------|-------------------|-------------------|---------------------|
| Zlib 6 (`.mca`)       | 29.94 MB  | 0.151x            | 32.6 MB/s         | 39.3 MB/s           |
| Zstd 4 (`.linear`)    | 239.37 KB | 19.342x           | 810.5 MB/s        | 2040.1 MB/s         |
| Zstd 22 (`.linear`)   | 198.12 KB | 23.369x           | 3.3 MB/s          | 1666.7 MB/s         |
| Brotli 11 (`.linear`) | 186.17 KB | 24.87x            | 2 MB/s            | 600.7 MB/s          |

## What the  Data Shows
- LinearReader can destroy vanilla Minecraft in both size and compression/decompression speed.
- Zstd compresses really well quickly, Brotli compresses even further but is slower.
- Decompression is rather quick for both Brotli and Zstd.

## Benchmarking Time
To further highlight the speed difference between Brotli and Zstd, here is how long each 
benchmarking test took to complete:

### Vanilla Overworld
| Dataset                        | Format                | Compression Time  |
|--------------------------------|-----------------------|-------------------|
| Vanilla Overworld (`Region`)   | Zlib 6 (`.mca`)       | 10m 21s 71ms      |
| Vanilla Overworld (`Region`)   | Zstd 4 (`.linear`)    | 1m 20s 248ms      |
| Vanilla Overworld (`Region`)   | Zstd 22 (`.linear`)   | 2h 56m 41s 294ms  |
| Vanilla Overworld (`Region`)   | Brotli 11 (`.linear`) | 15h 33m 40s 331ms |
| Vanilla Overworld (`Entities`) | Zlib 6 (`.mca`)       | 1s 627ms          |
| Vanilla Overworld (`Entities`) | Zstd 4 (`.linear`)    | 121ms             |
| Vanilla Overworld (`Entities`) | Zstd 22 (`.linear`)   | 38s 714ms         |
| Vanilla Overworld (`Entities`) | Brotli 11 (`.linear`) | 1m 47s 916ms      |
| Vanilla Overworld (`Poi`)      | Zlib 6 (`.mca`)       | 364ms             |
| Vanilla Overworld (`Poi`)      | Zstd 4 (`.linear`)    | 14ms              |
| Vanilla Overworld (`Poi`)      | Zstd 22 (`.linear`)   | 2s 905ms          |
| Vanilla Overworld (`Poi`)      | Brotli 11 (`.linear`) | 4s 638ms          |

### Vanilla Nether
| Dataset                     | Format                | Compression Time |
|-----------------------------|-----------------------|------------------|
| Vanilla Nether (`Region`)   | Zlib 6 (`.mca`)       | 1m 30s 682ms     |
| Vanilla Nether (`Region`)   | Zstd 4 (`.linear`)    | 9s 55ms          |
| Vanilla Nether (`Region`)   | Zstd 22 (`.linear`)   | 25m 44s 38ms     |
| Vanilla Nether (`Region`)   | Brotli 11 (`.linear`) | 2h 5m 30s 509ms  |
| Vanilla Nether (`Entities`) | Zlib 6 (`.mca`)       | 216ms            |
| Vanilla Nether (`Entities`) | Zstd 4 (`.linear`)    | 7ms              |
| Vanilla Nether (`Entities`) | Zstd 22 (`.linear`)   | 5s 338ms         |
| Vanilla Nether (`Entities`) | Brotli 11 (`.linear`) | 11s 345ms        |
| Vanilla Nether (`Poi`)      | Zlib 6 (`.mca`)       | 57ms             |
| Vanilla Nether (`Poi`)      | Zstd 4 (`.linear`)    | ~0ms             |
| Vanilla Nether (`Poi`)      | Zstd 22 (`.linear`)   | 3ms              |
| Vanilla Nether (`Poi`)      | Brotli 11 (`.linear`) | 76ms             |

### Vanilla End
| Dataset                  | Format                | Compression Time |
|--------------------------|-----------------------|------------------|
| Vanilla End (`Region`)   | Zlib 6 (`.mca`)       | 42s 951ms        |
| Vanilla End (`Region`)   | Zstd 4 (`.linear`)    | 4s 468ms         |
| Vanilla End (`Region`)   | Zstd 22 (`.linear`)   | 13m 24s 727ms    |
| Vanilla End (`Region`)   | Brotli 11 (`.linear`) | 59m 47s 560ms    |
| Vanilla End (`Entities`) | Zlib 6 (`.mca`)       | 139ms            |
| Vanilla End (`Entities`) | Zstd 4 (`.linear`)    | 3ms              |
| Vanilla End (`Entities`) | Zstd 22 (`.linear`)   | 587ms            |
| Vanilla End (`Entities`) | Brotli 11 (`.linear`) | 1s 941ms         |
| Vanilla End (`Poi`)      | Zlib 6 (`.mca`)       | 136ms            |
| Vanilla End (`Poi`)      | Zstd 4 (`.linear`)    | 1ms              |
| Vanilla End (`Poi`)      | Zstd 22 (`.linear`)   | 23ms             |
| Vanilla End (`Poi`)      | Brotli 11 (`.linear`) | 199ms            |

### Terralith + Tectonic
| Dataset                           | Format                | Compression Time |
|-----------------------------------|-----------------------|------------------|
| Terralith + Tectonic (`Region`)   | Zlib 6 (`.mca`)       | 3m 6s 116ms      |
| Terralith + Tectonic (`Region`)   | Zstd 4 (`.linear`)    | 21s 267ms        |
| Terralith + Tectonic (`Region`)   | Zstd 22 (`.linear`)   | 58m 39s 609ms    |
| Terralith + Tectonic (`Region`)   | Brotli 11 (`.linear`) | 4h 53m 36s 140ms |
| Terralith + Tectonic (`Entities`) | Zlib 6 (`.mca`)       | 472ms            |
| Terralith + Tectonic (`Entities`) | Zstd 4 (`.linear`)    | 38ms             |
| Terralith + Tectonic (`Entities`) | Zstd 22 (`.linear`)   | 10s 179ms        |
| Terralith + Tectonic (`Entities`) | Brotli 11 (`.linear`) | 27s 213ms        |
| Terralith + Tectonic (`Poi`)      | Zlib 6 (`.mca`)       | 139ms            |
| Terralith + Tectonic (`Poi`)      | Zstd 4 (`.linear`)    | 6ms              |
| Terralith + Tectonic (`Poi`)      | Zstd 22 (`.linear`)   | 1s 376ms         |
| Terralith + Tectonic (`Poi`)      | Brotli 11 (`.linear`) | 2s 236ms         |

### Note
For these benchmarks, files were compressed and decompressed one file at a time.