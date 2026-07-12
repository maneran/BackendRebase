import java.io.IOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;

public class Dedup {
    private static final int NUM_BUCKETS = 1000;
    private static final String BUCKET_DIR = "buckets";

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: java Dedup <input_file>");
            System.exit(1);
        }

        // Step 1: Read the input file and create bucket files
        String inputFile = args[0];
        // Implement deduplication logic here
        new File(BUCKET_DIR).mkdirs(); // Create a directory to store bucket files

        BufferedWriter[] writers = new BufferedWriter[NUM_BUCKETS];
        for(int i = 0; i < NUM_BUCKETS; i++) {
            writers[i] = new BufferedWriter(new FileWriter(BUCKET_DIR + "/bucket_" + i + ".txt"));
        }

        // Step 2: Read the input file and distribute lines into buckets
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int bucketIndex = Math.floorMod(line.hashCode(), NUM_BUCKETS);
                writers[bucketIndex].write(line);
                writers[bucketIndex].newLine();
            }
        }  finally {
            for (BufferedWriter writer : writers) {
                if (writer != null) {
                    writer.close();
                }
            }
        }

        // Step 3 gather unique lines from each bucket and write to output file
        try (BufferedWriter outputWriter = new BufferedWriter(new FileWriter("output.txt"))) {
            for (int i = 0; i < NUM_BUCKETS; i++) {
                File bucketFile = new File(BUCKET_DIR + "/bucket_" + i + ".txt");
                if (bucketFile.exists()) {
                    try (BufferedReader bucketReader = new BufferedReader(new FileReader(bucketFile))) {
                        String line;
                        Set<String> uniqueLines = new HashSet<>();
                        while ((line = bucketReader.readLine()) != null) {
                            if (uniqueLines.add(line)) { // Add returns false if the line was already present
                                outputWriter.write(line);
                                outputWriter.newLine();
                            }
                        }
                    }
                    bucketFile.delete(); // Clean up the bucket file after processing
                }
            }
        }
    }
}
