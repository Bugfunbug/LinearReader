# LinearReader
![CurseForge Downloads](https://lrbadges.onrender.com/curseforge/1494177)
![Modrinth Downloads](https://lrbadges.onrender.com/modrinth/linearreader)

![Minecraft Range](.github/assets/badges/minecraft.svg)
![Loaders](.github/assets/badges/mod-loaders.svg)
![Server-side](.github/assets/badges/server-side.svg)

![Compression](.github/assets/badges/compression.svg)
[![Wiki](.github/assets/badges/wiki.svg)](https://github.com/Bugfunbug/LinearReader/wiki)

LinearReader is a server-side Minecraft mod that reduces your world size without negatively impacting 
gameplay. This is done mainly through using Xymb's 
[Linear Region File Format](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools) in 
place of Minecraft's Anvil file format, and through utilizing Zstd for compression instead of 
the Zlib compression algorithm used by vanilla Minecraft.

## How is World Size Reduced?
Xymb's [Linear Region File Format](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools) 
is already a large step up from vanilla Minecraft's region file format, and greatly reduces 
world sizes.

The other necessary improvement is the compression algorithm and level of compression. Higher levels 
of compression require more resources and more time, which is not ideal for an active server, and 
lower levels obviously do not save as much storage. So the challenge was, how can LinearReader 
provide the speed of low-level compression and the storage savings of high-level compression?

LinearReader solves this by splitting the workload into two separate stages. Live writes are 
compressed quickly with low-level Zstd compression levels, leaving the CPU with plenty of resources 
to run Minecraft. Then later, when the server is idle, LinearReader will safely recompress the 
region files with much deeper compression.

## Compression Benchmarks
To show LinearReader's storage gains and why high level compression is not used during live writes, 
I ran benchmarks for the Anvil file format with Zlib using Java’s default compression level (which is 
what Vanilla Minecraft uses), Zstd level 4 (which is what LinearReader usually uses for live writes), 
and Zstd level 22 (which LinearReader uses for recompression). For a deeper dive on the benchmarks, 
see the `benchmarks/` directory.

| Dataset              | Format                 | Size    | Compression Ratio | Compression Speed | Decompression Speed |
|----------------------|------------------------|---------|-------------------|-------------------|---------------------|
| Overworld            | Anvil/Zlib 6           | 8.38 GB | 5.44x             | 75.7 MB/s         | 1271.3 MB/s         |
| Overworld            | LinearReader (Zstd 4)  | 6.21 GB | 7.39x             | 585.5 MB/s        | 1412.8 MB/s         |
| Overworld            | LinearReader (Zstd 22) | 4.28 GB | 10.71x            | 4.4 MB/s          | 1589.2 MB/s         |
| End (mostly void)    | Anvil/Zlib 6           | 2.16 GB | 3.66x             | 188.4 MB/s        | 1576.4 MB/s         |
| End (mostly void)    | LinearReader (Zstd 4)  | 244 MB  | 33.20x            | 1811.1 MB/s       | 4062.7 MB/s         |
| End (mostly void)    | LinearReader (Zstd 22) | 141 MB  | 57.50x            | 10.1 MB/s         | 6166.9 MB/s         |
| Tectonic + Terralith | Anvil/Zlib 6           | 2.56 GB | 5.97x             | 84.1 MB/s         | 1338.1 MB/s         |
| Tectonic + Terralith | LinearReader (Zstd 4)  | 1.80 GB | 8.49x             | 735.8 MB/s        | 1742.8 MB/s         |
| Tectonic + Terralith | LinearReader (Zstd 22) | 1.24 GB | 12.37x            | 4.4 MB/s          | 1787.3 MB/s         |

As is shown in the above table, LinearReader + Zstd achieves better compression while still 
maintaining fast compression and decompression speeds during normal gameplay, (Zstd 22 is used for 
the recompressor, which is **not** active during normal gameplay). 

## Other Features
LinearReader doesn't just replace Minecraft's file format and compression algorithm, it also 
includes a bunch of other features, such as:
* A backup system: LinearReader can automatically create backups of region files, which can be
used if need be.
* A cache system: A configurable number of recently used region files remain cached in memory, 
reducing disk reads and decompression.
* A chunk pruning system: LinearReader can search for empty, worthless chunks that can safely be 
deleted. Strict rules are in place to ensure that only truly worthless chunks are pruned.
* An adaptive runtime policy: LinearReader can tweak certain settings to keep the game running 
smooth.

## Made with Servers in Mind
Being a server-side mod, LinearReader tries to be as helpful and informative for server admins 
as possible. There are commands, such as `benchmark` and `health`, for seeing benchmarks and 
internal metrics, and a config file so that settings can be tailored to fit the server.

_(LinearReader works on singleplayer too, and players in singleplayer have access to all the 
same features that server admins can access.)_

### Modded Servers
LinearReader strives to be compatible with as many mods as possible. Most mods will just work, 
but any mods that change part of Minecraft's storage system or require reading files on 
disk will likely not work. Mods that read chunk data from RAM will likely work.

A list of compatible/incompatible mods can be found in the 
[wiki](https://github.com/Bugfunbug/LinearReader/wiki/Getting-Started#modpacks).

If you find a mod incompatibility, please create an [issue](https://github.com/Bugfunbug/LinearReader/issues/new).

## Installation and Uninstallation
To install, just download LinearReader off of [CurseForge](https://www.curseforge.com/minecraft/mc-mods/linearreader)
or [Modrinth](https://modrinth.com/mod/linearreader), drop the `.jar` file in the `mods` folder, and 
you're all set. LinearReader will handle everything from there.

To uninstall, run the command `/linearreader export-mca start` to convert your world's `.linear` 
files back to `.mca`, replace your `.linear` region files with the fresh `.mca` files, and remove 
LinearReader.

## Data Safety
It is recommended to create a backup of your world before installing LinearReader, as LinearReader 
changes much about Minecraft's internal storage engine and any storage-related bugs could potentially 
corrupt your world. So please, **make backups of your world**.

## Where to Learn More
This README does not provide a full, in depth explanation of all of LinearReader's features. I highly 
encourage reading the [wiki](https://github.com/Bugfunbug/LinearReader/wiki) to gain a full 
understanding of all LinearReader has to offer.

## Steps to Build from Source
1. Download the whole GitHub repository (including all the gradle wrappers and files).
2. At the project root run `./gradlew buildAll collectJars`.
   - If you want to build specific LinearReader `.jar` files instead of all 20 of them, then delete 
   the undesired target directories under `modules/targets` and remove mentions of the deleted 
   directories from the root `settings.gradle` and `build.gradle`.
3. Wait for the `.jar` files to compile, usually takes a few minutes.
4. The compiled `.jar` files can be found at `build/collected-jars` in the root directory.

_Note: When using this repo's gradle files/wrappers, some of the  `.jar` files will only build on 
MacOS._

## Development Status
I intend for version 1.3.0 to be the last version of LinearReader.

I no longer plan to add to LinearReader anymore. However, I do plan to keep LinearReader up to date 
with the latest Minecraft release for the near future.

I will still try to respond to GitHub issues, and hotfixes may happen if need be, but my intention 
is for serious development of LinearReader (by me) to cease after releasing version 1.3.0.

## AI Disclaimer
AI was used to code this mod. Everything is manually tested before a new release. If you run 
into any issues at all please report them on [GitHub](https://github.com/Bugfunbug/LinearReader/issues) 
and I will try to resolve them as fast as possible.

For more information regarding the use of AI in this mod, please refer to 
[AI_USAGE.md](https://github.com/Bugfunbug/LinearReader/blob/main/AI_USAGE.md).