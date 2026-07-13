#### Graphs:
I performed three tests per platform, using the same seeds for the tests. The data 
used for the graphs was the combined data of the three tests. All the tests were 
done with the LinearReader specified in the names of the directories with the graph 
images. THe tests were performed on my base model M4 MacBook Air. For the tests I 
had my render distance set to 32 chunks and my simulation distance set to 12 chunks. 
(Basically, all the tests were performed under identical conditions.)

As mentioned in the [wiki](https://github.com/Bugfunbug/LinearReader/wiki), 
LinearReader initially compresses to a level much lower than 22 (unless otherwise 
configured). So for LinearReader's disk usage data, I measured both the initial 
size of the region files, and then their final size after recompressing them to 
level 22. Backup files were **not** included when calculating disk usage.

Total read/write time was calculated as amount of operations x average time for that 
operation. (For example, Fabric performed many more read operations than the other 
platforms did, resulting in Fabric having a much larger total read time value.)

LinearReader can show higher max latency spikes, but those occur primarily during the 
initial world load and gc pauses. The p95 and p99 values are much closer to what 
average gameplay is like.