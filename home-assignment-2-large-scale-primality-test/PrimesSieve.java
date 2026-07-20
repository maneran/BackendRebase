

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class PrimesSieve {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java PrimesSieve <input_file>");
            System.exit(1);
        }

        String inputFile = args[0];

        long start = System.nanoTime();

        // Step 1: Find the maximum number in the input file
        int maxNumber = findMax(inputFile);

        // Step 2: Build the sieve of Eratosthenes up to the maximum number
        boolean[] sieve = buildSieve(maxNumber);

        // Step 3: Count the primes in the input file using the sieve
        long primeCount = countPrimes(inputFile, sieve);

        long elapsedMS = (System.nanoTime() - start) / 1_000_000;
        System.out.println("Number of primes found: " + primeCount);
        System.out.println("Time taken: " + elapsedMS + " ms");
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

    static long countPrimes(String path, boolean[] sieve) throws IOException {
        try(BufferedReader reader = new BufferedReader(new FileReader(path), 1 << 16)) { // 64KB buffer
            String line;
            long primeCount = 0;
            while ((line = reader.readLine()) != null) {
                int number = Integer.parseInt(line.trim());
                if (number >= 0 && number < sieve.length && sieve[number]) {
                    primeCount++;
                }
            }
            return primeCount;
        }
    }

}
