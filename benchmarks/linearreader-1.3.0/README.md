## Version 1.3.0 Benchmarks
These are the benchmarks I calculated for LinearReader version 1.3.0 vs. Forge, Fabric, and NeoForge
(without LinearReader). The plain loaders can be used as baselines.

> [!NOTE]
> These benchmarks are likely more reliable than the benchmarks collected for versions 1.1.0 and 
> 1.2.0, because for version 1.3.0 I excluded any benchmarks regarding chunk reads that were 
> performed on chunks that had not generated yet.

## Read/Write Benchmarks
### Testing Procedure
Using a tiny mod to log every region read/write performed, I calculated the read/write values shown
in the graphs. In game, my process looked as follows:
* Set render distance to 32 chunks and simulation distance to 12.
* Load into a specific world seed, wait for chunks in front of me to fully generate.
* Spin around to load all the other chunks.
* Teleport to (100000, 100000), then (100000, -100000), then (-100000, 100000), and finally to 
(-100000, -100000).
* Quit the world and rejoin, re-reading all the chunks from the last location.

I then repeated that for 3 total world seeds (which remained constant throughout all tests for version
1.3.0).

### Calculations
"Total Read/Write Time" was calculated as amount of operations x average time for that operation.
(This was done to provide a better comparison, since Fabric performed roughly double the amount of
read operations that all other loaders did).

## Compression Benchmarks
Using the 
[compression benchmarking scripts](https://github.com/Bugfunbug/LinearReader/tree/main/tools/benchmark) 
along with gigabytes of real chunk data, I calculated benchmarks for Zlib level 6, which is what 
vanilla Minecraft uses, Zstd level 4, Zstd level 22, and Brotli level 11. A more detailed 
explanation, along with the compression benchmarks themselves, can be found in the
[COMPRESSION.md](https://github.com/Bugfunbug/LinearReader/tree/main/benchmarks/linearreader-1.3.0/COMPRESSION.md) 
file.

## Testing Specs
I ran these tests on my base model M4 MacBook Air with 8GB of RAM allocated to Minecraft. I let my 
MacBook cool down between each of the three tests per loader.

Version 1.3.0 benchmarking was performed on Minecraft version 26.2, and on Forge version 65.1.1, 
Fabric version 0.19.3, and NeoForge version 26.2.0.59.