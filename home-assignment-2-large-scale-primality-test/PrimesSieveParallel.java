

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;

public class PrimesSieveParallel {

    static final int RECORD = 9;                            // 8 digits + '\n'
    static final int CHUNK_RECORDS = 8192;
    static final int CHUNK_BYTES = RECORD * CHUNK_RECORDS;  // 73,728, a whole number of records

   
    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2) {
            System.err.println("Usage: java PrimesSieveParallel <input_file> <num_threads>");
            System.exit(1);
        }

        String inputFile = args[0];
        int numThreads = Integer.parseInt(args[1]);

        long fileSize = new File(inputFile).length();

        // The byte range split is only valid on fixed width records. Fail here
        // rather than let every thread compute a plausible but wrong offset.
        if (fileSize % RECORD != 0) {
            throw new IllegalArgumentException(
                    "expected fixed width records of " + RECORD + " bytes, got file size " + fileSize);
        }

        // Line count without reading the file, which is the whole point of fixed
        // width records. Counting them normally would cost a full 450MB pass.
        long records = fileSize / RECORD;

        long start = System.nanoTime();

        // Step 1: Find the maximum number in the input file
        int maxNumber = findMax(inputFile);
        long afterMax = System.nanoTime();

        // Step 2: Build the sieve of Eratosthenes up to the maximum number
        boolean[] sieve = buildSieve(maxNumber);
        long afterSieve = System.nanoTime();

        // Step 3: Count the primes in the input file using the sieve
        long primeCount = countPrimes(inputFile, sieve, records, numThreads);
        long afterCount = System.nanoTime();

        System.out.println("Threads:            " + numThreads);
        System.out.println("Max value:          " + maxNumber);
        System.out.println("Number of primes:   " + primeCount);
        System.out.println("Max scan:           " + ms(start, afterMax) + " ms");
        System.out.println("Sieve build:        " + ms(afterMax, afterSieve) + " ms");
        System.out.println("Count pass:         " + ms(afterSieve, afterCount) + " ms");
        System.out.println("Total:              " + ms(start, afterCount) + " ms");
    }

    static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }

    static long startRecord(long records, int numThreads, int i) {
        return records * i / numThreads;
    }

    static int findMax(String path) throws IOException {
        try(BufferedReader reader = new BufferedReader(new FileReader(path), 1 << 16)) { // 64KB buffer
            String line;
            int max = Integer.MIN_VALUE;
            while ((line = reader.readLine()) != null) {
                int number = Integer.parseInt(line.trim());
                if (number > max) {
                    max = number;
                }
            }
            return max;
        }   
    }

    static boolean[] buildSieve(int limit) {
        boolean[] isPrime = new boolean[limit + 1];
        for (int i = 2; i <= limit; i++) {
            isPrime[i] = true;
        }
        for (int p = 2; p * p <= limit; p++) {
            if (isPrime[p]) {
                for (int multiple = p * p; multiple <= limit; multiple += p) {
                    isPrime[multiple] = false;
                }
            }
        }
        return isPrime;
    }

    /**
     * Splits the file into numThreads contiguous record ranges and counts each in
     * its own thread.
     *
     * There is no queue and no producer here, unlike PrimesParallel. When the work
     * can be partitioned up front by arithmetic, a producer/consumer pipeline is
     * unnecessary: each thread opens the file itself and reads only its own slice.
     */
    static long countPrimes(String path, boolean[] sieve, long records, int numThreads)
            throws IOException, InterruptedException {

        CountTask[] tasks = new CountTask[numThreads];
        Thread[] threads = new Thread[numThreads];

        for (int i = 0; i < numThreads; i++) {
            // Thread i's end is thread i+1's start, so the ranges are contiguous by
            // construction and the remainder distributes itself. No special case for
            // the last thread.
            long first = startRecord(records, numThreads, i);
            long last = startRecord(records, numThreads, i + 1);

            tasks[i] = new CountTask(path, first, last, sieve);
            threads[i] = new Thread(tasks[i]);
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        // A failed task still lets join() return normally, so an unchecked error
        // field would mean summing partial counts and printing a wrong answer.
        for (CountTask task : tasks) {
            if (task.error != null) {
                throw task.error;
            }
        }

        long primeCount = 0;
        for (CountTask task : tasks) {
            primeCount += task.localCount;
        }
        return primeCount;
    }

    /**
     * Counts primes in one contiguous slice of the file, defined as a half open
     * record range [firstRecord, lastRecord).
     *
     * Every record is exactly RECORD bytes, so a slice can be located by
     * arithmetic alone. No thread needs to scan for a newline, and no thread
     * needs to read past its own end to finish a line another thread started.
     *
     * The sieve is shared and never written to after buildSieve returns, so it
     * needs no synchronization. localCount is private to this task and is read
     * by the caller only after join(), which supplies the happens before edge.
     */
    static class CountTask implements Runnable {

        private final String path;
        private final long firstRecord;
        private final long lastRecord;
        private final boolean[] sieve;

        long localCount;        // result, read by the caller after join()
        IOException error;      // non-null if this task failed, checked after join()

        CountTask(String path, long firstRecord, long lastRecord, boolean[] sieve) {
            this.path = path;
            this.firstRecord = firstRecord;
            this.lastRecord = lastRecord;
            this.sieve = sieve;
        }

        @Override
        public void run() {
            try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
                raf.seek(firstRecord * RECORD);

                long recordsLeft = lastRecord - firstRecord;
                byte[] buf = new byte[CHUNK_BYTES];

                while (recordsLeft > 0) {
                    // Never request more than this slice owns, otherwise a thread
                    // reads into the next thread's range and records get counted twice.
                    int recs = (int) Math.min(CHUNK_RECORDS, recordsLeft);
                    int bytes = recs * RECORD;

                    // readFully rather than read: read() is allowed to return fewer
                    // bytes than asked for, which would silently misalign every
                    // record after it.
                    raf.readFully(buf, 0, bytes);

                    // The chunk is a whole number of records, so every offset here
                    // lands on a record boundary.
                    for (int off = 0; off < bytes; off += RECORD) {
                        int n = buf[off] - '0';
                        for (int k = 1; k < 8; k++) {
                            n = n * 10 + (buf[off + k] - '0');
                        }
                        // buf[off + 8] is the newline and is deliberately not read.
                        if (sieve[n]) {
                            localCount++;
                        }
                    }

                    recordsLeft -= recs;
                }
            } catch (IOException e) {
                // run() cannot declare a checked exception. Stashing it here rather
                // than printing it matters: a thread that dies quietly still lets
                // join() return normally, and the caller would sum partial counts
                // and report a wrong answer with no indication anything failed.
                this.error = e;
            }
        }
    }

}
