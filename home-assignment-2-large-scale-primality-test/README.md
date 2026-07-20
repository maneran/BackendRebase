# Home Assignment 2: Large Scale Primality Test

Count the primes in a file holding one number per line, and time it.

Four implementations, each isolating one change.

| File | Approach | 50M file |
|---|---|---|
| `Primes.java` | Trial division, 1 thread | 301,932 ms |
| `PrimesParallel.java` | Trial division, producer/consumer, N threads | 146,582 ms |
| `PrimesSieve.java` | Sieve of Eratosthenes, 1 thread | 7,918 ms |
| `PrimesSieveParallel.java` | Sieve, byte range split, N threads | 6,990 ms |

```bash
javac *.java
java Primes              <input_file>
java PrimesParallel      <input_file> <num_threads>
java PrimesSieve         <input_file>
java PrimesSieveParallel <input_file> <num_threads>
```

## Input

Two properties, measured first, drove everything after.

**Every line is exactly 9 bytes** (8 digits + newline). 450,000,000 / 50,000,000 = 9, and
the 200M file matches. So the file splits into byte ranges by arithmetic, and the line count
comes from `File.length() / 9` instead of a full pass.

**Max value is 32,452,855.** A sieve over that range is 32 MB as a `boolean[]`, inside the
500 MB budget.

The range spans the 1,000,000th prime (15,485,863) to the 2,000,000th (32,452,843), so the
file is prime dense and heavily duplicated.

## Correctness

Validated against `factor` from coreutils, not against my own reasoning:

```bash
head -1000 nums_50_mil.txt > slice_1k.txt
factor < slice_1k.txt | awk 'NF==2' | wc -l    # 662
```

A prime factors into only itself, so its `factor` output line has exactly two fields.

All four implementations give 662 on the slice and 16,912,305 on the full file, at every
thread count.

The slice is 66% prime while the full file is 33.8%. Sampling the head of a file is not
sampling the file.

## Results

Intel i5-3210M: 2 physical cores, hyperthreaded to 4 logical. `nproc` says 4, which misleads
for this workload.

| Implementation | Threads | Time | Speedup |
|---|---|---|---|
| `Primes` | 1 | 301,932 ms | 1.0x |
| `PrimesParallel` | 2 | 234,669 ms | 1.3x |
| `PrimesParallel` | 4 | 146,582 ms | 2.1x |
| `PrimesSieve` | 1 | 7,918 ms | 38.1x |
| `PrimesSieveParallel` | 1 | 7,945 ms | 38.0x |
| `PrimesSieveParallel` | 4 | 6,990 ms | 43.2x |

`PrimesSieveParallel` stages:

| Stage | 1 thread | 4 threads |
|---|---|---|
| Max scan (`readLine`, single threaded in both) | 5,998 ms | 5,627 ms |
| Sieve build (single threaded in both) | 566 ms | 582 ms |
| Count pass (byte range, threaded) | 1,379 ms | 779 ms |
| Total | 7,945 ms | 6,990 ms |

## Findings

**Algorithm beat threading by 20x.** Four threads on trial division: 2.1x. Switching to a
sieve: 38x on one thread. The catch is that the sieve depends on a 32 million ceiling. At
10^15 it would need petabytes, while the threading result would still hold.

**The sieve build is 8% of runtime.** 566 ms against roughly 7,000 ms of file reading. The
stage that sounds expensive is a rounding error.

**String allocation costs 4.3x.** Compare the two file passes in the 1 thread column: both
read 450 MB, both parse 50 million 8 digit numbers, both single threaded. `readLine` plus
`Integer.parseInt` takes 5,998 ms and allocates 50 million String objects. Reading into a
reused `byte[]` and building each number with `n = n * 10 + (buf[k] - '0')` takes 1,379 ms
and allocates nothing.

This fell out of leaving `findMax` on the old code path while converting the count pass.

**Threading the count pass gave 1.77x**, matching the 2.1x from `PrimesParallel` and
consistent with 2 physical cores. Going from 2 to 4 threads in `PrimesParallel` still gained
22%, which is more than I expected from hyperthreading on code whose inner loop is only
integer division.

## Two concurrency designs

`PrimesParallel` uses producer/consumer: one reader parses lines into `long[]` batches and
pushes them onto a bounded `ArrayBlockingQueue`, N workers take and count. It needs batching,
queue capacity, backpressure and poison pill shutdown.

All of that exists because line oriented reading cannot be partitioned in advance. With
variable length lines you cannot know where line 12,500,000 starts without reading
everything before it.

`PrimesSieveParallel` partitions up front. Thread `i` of `n` owns records
`[records*i/n, records*(i+1)/n)` and seeks to `firstRecord * 9`. Each thread opens the file
itself. No queue, no producer, no batching, no poison pills. Using `records * i / n` rather
than size plus remainder makes thread `i`'s end equal thread `i+1`'s start, so the ranges are
contiguous with no special case for the last thread.

Details in both:

- Queue capacity 8 caps in flight memory at 640 KB regardless of input size. Unbounded, the
  reader would pull the whole file into the heap.
- `long[]`, never `Long[]`. Boxing allocates per number and turns a contiguous scan into a
  pointer chase. Arrays are also the only way to pass primitives through a generic queue.
- No shared counters. Each worker accumulates privately, read after `join()`, which supplies
  the happens before edge. No atomics on the hot path.
- `run()` cannot throw, so each task stores its `IOException` in a field the caller checks
  after joining. Catching and printing would let `join()` return normally and the caller sum
  partial counts into a wrong answer with no error.
- `readFully`, not `read`. A short read would misalign every record after it.
- `PrimesSieveParallel` throws if `fileSize % 9 != 0`. The byte range design is invalid on a
  ragged file and should fail rather than miscount.


