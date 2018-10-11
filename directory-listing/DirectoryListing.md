# Directory Listing Config 

Directory listing config plugin offers capabilities list files from source directory and iterate over this list with JMeter variable. This is convenient for data-driven testing when you have, for example, 1000 files to upload into server.

This plugin has following options that affect the behavior:
  * "Use full path" - is this flag does not selected plugin will be set sub-path from source path into destination variable;
  * "Random order"  - shuffle listing in random order;
  * "Recursive listing" - meaning, that plugin will be looking for all sub-folders in source directory;
  * "Rewind on end of list" - if the flag is selected and an iteration loop has reached the end, the new loop will be started;
  * "Re-read directory on the end of list" - re-read directory before starting new iteration loop;
  * "Independent list per thread" - determines that listing will be shared for all threads or each thread will be having own local copy.

_Default value of destination variable name is "filename"._

![](directoryListing.png)
