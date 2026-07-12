# Large-Scale Line Deduplication

Removes duplicate lines from a very large text file (up to 5,000,000 lines,
~5 GB) on a machine with only **100 MB of RAM**, **1 CPU**, and disk to spare.
Output contains each unique line exactly once, in no particular order.

## How it works

The input is far too big to load into memory at once, so the work is split into
two disk-based phases:

1. **Scatter** — read the input one line at a time and append each line to one of
   1,000 bucket files, chosen by `Math.floorMod(line.hashCode(), 1000)`. Because
   identical lines always produce the same hash, every copy of a line lands in the
   same bucket.
2. **Gather** — process buckets one at a time: load a single bucket into an
   in-memory `HashSet` (which drops duplicates), append the unique lines to
   `output.txt`, then delete the bucket. Only one bucket is ever in RAM.

This keeps peak memory at roughly one bucket's worth of data, well under 100 MB,
regardless of total input size.

## Requirements

- JDK 8+ (for the plain-Java route), or Docker (for the container route).

## Run it (plain Java)

```bash
javac Dedup.java
java -Xmx48m -XX:+UseSerialGC Dedup input.txt
```

Reads `input.txt`, writes `output.txt` in the current directory.

## Run it (Docker)

```bash
docker build -f Dockerfile.dedup -t dedup .
docker run --rm --memory=100m --cpus=1 -v "$(pwd)":/data dedup input.txt
```

The `--memory=100m --cpus=1` flags make Docker enforce the assignment's limits at
the kernel level: if the process exceeds 100 MB it is killed. A clean run proves
the memory constraint is respected.
## Verify the output

```bash
# every line is unique — prints nothing if there are no duplicates
sort output.txt | uniq -d | head

# output's unique set matches the input's — prints 0 if identical
diff <(sort -u input.txt) <(sort -u output.txt) | wc -l
```

## Why the JVM flags

- `-Xmx48m` caps the Java heap at 48 MB.
- `-XX:+UseSerialGC` uses the low-overhead Serial garbage collector (designed for
  small heaps and single-CPU machines) instead of the default G1.

Together they keep the whole process around ~91 MB — under the 100 MB budget.
The 48 MB heap still comfortably holds the largest expected bucket (~25 MB live)
with room for GC to work.
