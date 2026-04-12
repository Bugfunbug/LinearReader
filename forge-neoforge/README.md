DISCLAIMER: THIS MOD WAS CODED ENTIRELY WITH AI.
DO WITH THAT INFORMATION WHAT YOU WISH.
-----

LinearReader is a server-side mod for Forge/NeoForge/Fabric (1.20.1)
that draws heavily from Xymb-endcrystalme's Linear file format (which uses Zstd instead of Zlib).
(For more information about the Linear format see
https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools.)
This mod completely replaces Minecraft's Anvil file format with the Linear
file format, and Minecraft now reads and writes to `.linear` files.
LinearReader also comes with some other features further explained below.

### WARNING:
I personally am not worried about this mod corrupting region files,
however it may happen. I am not responsible for any world corruption,
and I am warning you now that it is most likely a good idea to back up any
existing region files before adding LinearReader to any of your worlds.

### So What Does LinearReader Do?:
The main goal of LinearReader is to reduce the amount of storage a
Minecraft world occupies. This is accomplished by entirely replacing the
vanilla Anvil file format with the Linear file format, allowing for files
to be compressed much further than before. Additionally, when I/O is idle
for some time, or when afk compression is started manually via a command,
LinearReader will compress all `.linear` files to the maximum level,
reducing the world's storage footprint without lagging the world while
people are online.


### Backup System:
Currently the backup system saves a compressed snapshot of each region
file as a `.bak` file. These files are compressed at level 22 so they are
as small as they can be, but they still take up space. If you would rather
forego this feature to save on disk space, you may disable the backup
system in the config.


### Commands:
LinearReader comes with a bunch of commands (`/linearreader`):
- `cache_info`: returns how many regions are open and held in memory,
  how many of those regions have unsaved changes, and an estimate of how
  much RAM the cache is using.
- `storage`: returns `.linear` and `.bak` file count and their size on disk.
- `pos`: returns current block/chunk/region coordinates and whether that
  region is cached.
- `verify`: scans all `.linear` files and reports corrupt ones.
- `bench`: shows chunk I/O stats, region I/O stats, compression ratio,
  cache hit rate, and the uptime of the window.
- `bench reset`: starts a fresh benchmark window.
- `afk-compress start`: starts compressing all `.linear` files to the highest
  level (22), saving you more storage while you are afk.
- `afk-compress stop`: stops the afk-compression started by the previous
  command.
- `afk-compress`: returns the current status of the afk-compression.
- `pin [rx rz]`: pins either the region you are standing in, or the region
  provided with the command in the cache, keeping that region always open.
- `unpin [rx rz]`: unpins from the cache either the region you are standing
  in, or the region provided with the command.
- `pins`: list all current pinned regions.
- `export-mca start`: begins exporting all `.linear` files to `.mca`,
  outputting the files into `<worldname>_mca_export/` next to the world folder.
  It will skip previously exported files if interrupted. Does not modify
  the `.linear` region files.
- `export-mca stop`: stops exporting the `.linear` files to `.mca`.
- `export-mca`: returns the status of the exportation.
- `prune-chunks`: scans all `.linear` files and reports how many chunks are
  deemed unnecessary (those chunks have a `InhabitedTime` value of 0, have
  no structures or tile entities, and the server isn't doing anything with
  that chunk currently) and can safely be deleted. This command can save a
  couple of MBs of storage, but don't expect too much from it. In order for
  the chunk pruning to run, `/linearreader prune-chunks confirm` must be run
  after running `/linearreader prune-chunks`. This command does not touch `.bak`
  files.
- `sync-backups`: scans all `.bak` files and will sync them to the world's
  current `.linear` files by creating missing backups files and editing current
  backup files. Must run `/linearreader sync-backups confirm` after running
  `/linearreader sync-backups`. This command will lag the server.


### MCA Conversion:
If LinearReader is installed on a world with existing `.mca` region files,
it will convert those files to `.linear` and will delete the old `.mca`
files (so back them up first).


### Config:
LinearReader also has a config file which allows for a lot of features to be
adjusted however you'd like. Older config files will update/migrate to the new
(post-1.1.2) config format automatically. The following can be changed through
the config:
- `compressionLevel`: at what level should LinearReader initially compress
  files to?
- `regionCacheSize`: how large should the cache be for open regions?
- `backupEnabled`: should `.bak` files be created?
- `backupUpdateInterval`: how many successful saves of a region before its
  `.bak` is refreshed?
- `regionsPerSaveTick`: how many regions should LinearReader try to save in
  one tick?
- `pressureFlushMinDirtyRegions`/`pressureFlushMaxDirtyRegions`: what is the
  minimum and maximum amount of dirty regions that can be pressure flushed?
- `slowIoThresholdMs`: what is the minimum amount of milliseconds a file
  should take to save for a warning message to be sent to the log?
- `diskSpaceWarnGb`: what amount of disk space should be left for a warning
  to be sent to the log?
- `autoRecompressEnabled`: should `.linear` files automatically recompress
  further when disk I/O is idle?
- `idleThresholdMinutes`: how many minutes must disk I/O be idle for the
  auto-recompressor to kick in?
- `recompressMinFreeRamPercent`: what is the minimum amount of JVM heap
  headroom required while auto-recompression runs?


### Compatibility:
LinearReader should be compatible with most, if not all, other mods.
I have tested it with C2ME (both on Fabric and on Forge with the Connector),
Lithium (and Radium/Canary), Chunky, Distant Horizons, and a lot of other
mods. If you find a mod incompatibility please let me know.


### Dependencies:
Only LinearReader-fabric-1.1.1 and below needs Cloth Config API.
(The Fabric version of LinearReader needs Fabric API.)
Everything else has no dependencies.