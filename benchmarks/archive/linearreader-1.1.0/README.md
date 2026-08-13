## Version 1.1.0 Benchmarks
These are the benchmarks I calculated for LinearReader version 1.1.0 vs. Forge, Fabric, and NeoForge
(without LinearReader). The plain loaders can be used as baselines.

## Testing Procedure
Using a tiny mod to log every region read/write performed, I calculated the read/write values shown
in the graphs. In game, my process looked as follows:
* Set render distance to 32 chunks and simulation distance to 12.
* Load into a specific world seed, wait for chunks in front of me to fully generate.
* Spin around to load all the other chunks.
* Quit the world and rejoin, re-reading all the chunks.

I then repeated that for 3 total world seeds (which were constant throughout all tests for version 
1.1.0).

## Calculations
"Total Read/Write Time" was calculated as amount of operations x average time for that operation.
(This was done to provide a better comparison, since Fabric performed roughly quadruple the amount 
of read operations that all other loaders did).

To get world size data, I looked at how large my computer said the region files were. I did not
include the files found in the `entities` or `poi` directories. For LinearReader, I first wrote down
the size of the region files right after I finished generating a 32-chunk radius in that world, and
then later I ran manual recompression to see how small they could really become. I also did not
include backup files when measuring world size.

## Testing Specs
I ran these tests on my M4 MacBook Air with 16GB of RAM. I let my MacBook cool down between each of 
the three tests per loader.

Version 1.1.0 testing was performed on Minecraft version 1.20.1.