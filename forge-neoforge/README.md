LinearReader replaces Minecraft's region format in order to reduce
world size and improve storage efficiency. Chunk data is compressed to
the `.linear` format, instead of vanilla Minecraft's `.mca` format. The
`.linear` format was created by Xymb-endcrystalme, and can be found on 
[Github](https://github.com/xymb-endcrystalme/LinearRegionFileFormatTools).

LinearReader:
* has an automatic region file backup system
* uses a region cache to improve performance
* has a built-in backup system
* can convert both ways between the `.mca` and the `.linear` formats.

**For a full description of all of LinearReader's features, see the 
[wiki](https://github.com/Bugfunbug/LinearReader/wiki).**

**WARNING:** LinearReader modifies the way chunk data is saved and makes
it more complex. Corruption may occur due to bugs or in unexpected
edge cases. **Backup your worlds before installing LinearReader.**

**DISCLAIMER:** AI was used to code this mod. I have  manually tested 
and verified that it all works as intended. If you run into any 
issues please report them on my [GitHub](https://github.com/Bugfunbug/LinearReader/issues) 
and I will try to resolve them as fast as possible.