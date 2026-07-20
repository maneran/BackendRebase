import java.util.concurrent.ArrayBlockingQueue;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Counts prime numbers in a file that holds one number per line, using a
 * producer/consumer split: main reads and parses, N worker threads test.
 *
 * The single reader is deliberate. Measured separately, reading and parsing all
 * 50 million lines takes ~3.4s against ~300s of primality testing, so the reader
 * has plenty of slack and parallelising it would buy nothing.
 */
public class PrimesParallel {

    // ~60ms of primality work per batch against ~1us of queue handoff, so the
    // coordination cost is invisible. Per number tasks would spend a sixth of
    // the runtime on queue and lock overhead instead.
    private static final int BATCH_SIZE = 10_000;

    // 2 batches per worker: enough slack to absorb uneven batch cost, small
    // enough that in flight memory stays at ~640KB regardless of input size.
    private static final int QUEUE_CAPACITY = 8;

    // Shutdown signal. Matched by reference identity below, not by length, so a
    // genuinely empty batch could never be mistaken for "stop".
    private static final long[] POISON_PILL = new long[0];

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length != 2) {
            System.err.println("Usage: java PrimesParallel <input_file> <num_threads>");
            System.exit(1);
        }

        String inputFile = args[0];
        int numThreads = Integer.parseInt(args[1]);

        ArrayBlockingQueue<long[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
        Worker[] workers = new Worker[numThreads];
        Thread[] threads = new Thread[numThreads];

        // Thread creation is part of what is being measured, so the clock starts
        // before any of it.
        long start = System.nanoTime();

        // Workers must be running and blocked on take() before main starts
        // producing. Reading first would fill the bounded queue and deadlock main
        // in put() with no consumer alive to drain it.
        for (int i = 0; i < numThreads; i++) {
            workers[i] = new Worker(queue);
            threads[i] = new Thread(workers[i]);
            threads[i].start();
        }

        // main doubles as the reader thread. No separate thread is created for it.
        readAndQueue(inputFile, queue, numThreads);

        // readAndQueue returning only means everything was handed off. The joins
        // are what mean everything was finished, so both the clock and the sum
        // have to wait for them.
        for (Thread thread : threads) {
            thread.join();
        }

        long elapsedMS = (System.nanoTime() - start) / 1_000_000;

        // Safe without synchronization: join() establishes happens before, so every
        // write a worker made to its count field is visible here.
        long totalPrimes = 0;
        for (Worker worker : workers) {
            totalPrimes += worker.count;
        }

        System.out.println("Number of threads: " + numThreads);
        System.out.println("Number of workers: " + workers.length);
        System.out.println("Number of primes found: " + totalPrimes);
        System.out.println("Time taken: " + elapsedMS + " ms");
    }

    /**
     * Reads the file, parses each line, and hands full batches to the workers.
     * Finishes by queueing one poison pill per worker so every one of them exits.
     *
     * Runs on the calling thread, which is main.
     */
    static void readAndQueue(String path, ArrayBlockingQueue<long[]> queue, int numWorkers)
            throws IOException, InterruptedException {
        try (BufferedReader reader = new BufferedReader(new FileReader(path), 1 << 16)) { // 64KB buffer
            String line;
            long[] batch = new long[BATCH_SIZE];
            int index = 0;

            while ((line = reader.readLine()) != null) {
                batch[index++] = Long.parseLong(line.trim());
                if (index == BATCH_SIZE) {
                    queue.put(batch); // blocks while the queue is full, which is the backpressure
                    // A fresh array every time. Reusing this one would mutate data a
                    // worker is already reading, producing counts that are wrong and
                    // that vary between runs.
                    batch = new long[BATCH_SIZE];
                    index = 0;
                }
            }

            // The final batch is usually short. Trimming it to its real length keeps
            // countPrimes free of any special case for unfilled slots. Both sample
            // files divide evenly by BATCH_SIZE, so this branch stays untested there.
            if (index > 0) {
                long[] lastBatch = new long[index];
                System.arraycopy(batch, 0, lastBatch, 0, index);
                queue.put(lastBatch);
            }

            // Exactly one pill per worker. Queue fewer and a worker blocks in take()
            // forever, hanging the program at join() with no error output.
            for (int i = 0; i < numWorkers; i++) {
                queue.put(POISON_PILL);
            }
        }
    }

    /**
     * Pulls batches off the shared queue until it receives the poison pill.
     *
     * Accumulates into a private field rather than a shared atomic counter. The
     * field is read by main after join(), so there is no contention on the hot
     * path and no concurrency primitive needed for the result.
     */
    static class Worker implements Runnable {
        private final ArrayBlockingQueue<long[]> queue;
        int count;

        Worker(ArrayBlockingQueue<long[]> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                while (true) {
                    long[] batch = queue.take();
                    if (batch == POISON_PILL) {
                        break;
                    }
                    count += countPrimes(batch);
                }
            } catch (InterruptedException e) {
                // run() cannot declare a checked exception. Restore the interrupt flag
                // so the caller can still observe it, then stop.
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * The unit of work handed to a thread. Batching exists so that one queue
     * handoff covers thousands of tests instead of one.
     */
    static int countPrimes(long[] numbers) {
        int primeCount = 0;
        for (long number : numbers) {
            if (isPrime(number)) {
                primeCount++;
            }
        }
        return primeCount;
    }

    /**
     * Trial division on a 6k +/- 1 wheel.
     *
     * Every integer is 6k, 6k+1, 6k+2, 6k+3, 6k+4 or 6k+5. The 2 and 3 checks above
     * eliminate four of those six shapes, so the loop only has to test the two that
     * survive: i walks the 6k-1 family and i+2 walks the 6k+1 family. That is a third
     * of the divisions naive trial division would do.
     *
     * The loop stops at sqrt(number) because any composite has a factor at or below
     * its square root. i * i is used rather than Math.sqrt to avoid a sqrt call per
     * iteration.
     */
    public static boolean isPrime(long number) {
        if (number <= 1) return false;
        if (number <= 3) return true;
        if (number % 2 == 0 || number % 3 == 0) return false;
        for (long i = 5; i * i <= number; i += 6) {
            if (number % i == 0 || number % (i + 2) == 0) return false;
        }
        return true;
    }

}
