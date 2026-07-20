import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class Primes {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java Primes <input_file>");
            System.exit(1);
        }

        // timing - System.nanoTime()
        long start = System.nanoTime();
        int primeCount = readAndCount(args[0]);
        long elapsedMS = (System.nanoTime() - start) / 1_000_000;

        System.out.println("Number of primes found: " + primeCount);
        System.out.println("Time taken: " + elapsedMS + " ms");
    }

    public static int readAndCount(String path) throws IOException {
        // Implementation for reading and counting primes from a file
        // Bump the buffer. new BufferedReader(reader, 1 << 16) - the default is 8KB, meaning ~55,000 syscalls per 450MB. 64KB cuts that by 8×. Cheap, one argument.
        try(BufferedReader reader = new BufferedReader(new FileReader(path), 1 << 16)) { // 64KB buffer
            String line;
            int primeCount = 0;
            while ((line = reader.readLine()) != null) {
                long number = Long.parseLong(line.trim());
                if (isPrime(number)) {
                    primeCount++;
                }
            }
            return primeCount;
        }
    }

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
