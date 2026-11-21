package io.descoped.lds.loadtest;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Turns the result files of a load test into statistics and plot files.
 * <p>
 * HTTPLoadTest-baseline, which drives the load, leaves three files for each run in its results folder:
 * {@code <run>.csv}, {@code <run>.json} with one object for each request it sent, and
 * {@code <run>_load_specififation.json} with the load it was asked for (the name is spelled that way by the
 * tool). Each run becomes one sample, at the number of threads of the run: how many reads and writes succeeded
 * and failed, and how long they took. The samples are written as one data file and the gnuplot files that draw
 * it ({@link GnuplotFileGenerator}).
 */
public final class HTTPLoadTestBaselineStatistics {

    private static final Pattern CSV_FILE_PATTERN = Pattern.compile("(.*)\\.csv");
    private static final Pattern READ_PATTERN = Pattern.compile("\\(Read-URL:");
    private static final Pattern WRITE_PATTERN = Pattern.compile("\\(Write-URL:");

    private HTTPLoadTestBaselineStatistics() {
    }

    /**
     * Reads every run in a folder and writes the plot files.
     *
     * @param args the folder with the result files, the folder to write the plot files to, and the name the plot
     *             files start with
     * @throws IOException when a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        if (!argumentsAreValid(args)) {
            return;
        }
        Path resultsPath = Path.of(args[0]);
        Path plotfilesFolder = Path.of(args[1]);
        String outBasename = args[2];

        Statistics statistics = new Statistics();
        ObjectMapper mapper = new ObjectMapper();
        List<Path> csvFiles;
        try (Stream<Path> files = Files.list(resultsPath)) {
            csvFiles = files.filter(path -> CSV_FILE_PATTERN.matcher(path.getFileName().toString().toLowerCase()).matches()).toList();
        }

        for (Path csvFile : csvFiles) {
            Matcher csvFilenameMatcher = CSV_FILE_PATTERN.matcher(csvFile.getFileName().toString().toLowerCase());
            csvFilenameMatcher.matches();
            String testBasename = csvFilenameMatcher.group(1);

            Run run = new Run(statistics);
            run.readRequests(mapper, resultsPath.resolve(testBasename + ".json"));
            run.report(testBasename);

            Path loadConfigurationFile = resultsPath.resolve(testBasename + "_load_specififation.json");
            JsonNode loadConfigurationNode = mapper.readTree(loadConfigurationFile.toFile());
            int threads = loadConfigurationNode.get("test_no_of_threads").asInt();
            int testDurationSeconds = loadConfigurationNode.get("test_duration_in_seconds").asInt();
            statistics.sample(threads, testDurationSeconds);
        }

        GnuplotFileGenerator.writeStatisticsGnuplotAndDatafile(statistics, "clients", plotfilesFolder, outBasename);
    }

    /*
     * Whether there are three arguments and the folders are folders, the one to write to writable; tells the user
     * what is wrong when not. The messages are for the person who runs the program, on its standard error.
     */
    private static boolean argumentsAreValid(String[] args) {
        if (args.length != 3) {
            System.err.println("Must provide the following three arguments: INFOLDER OUTFOLDER OUTBASENAME");
            return false;
        }
        String inFolder = args[0];
        if (!Files.isDirectory(Path.of(inFolder))) {
            System.err.println("Invalid INFOLDER argument, not a directory: " + inFolder);
            return false;
        }
        String outFolder = args[1];
        Path plotfilesFolder = Path.of(outFolder);
        if (!Files.isDirectory(plotfilesFolder)) {
            System.err.println("Invalid OUTFOLDER argument, not a directory: " + outFolder);
            return false;
        }
        if (!Files.isWritable(plotfilesFolder)) {
            System.err.println("Invalid OUTFOLDER argument, invalid permissions: " + outFolder);
            return false;
        }
        return true;
    }

    /*
     * The requests of one run, recorded in the statistics, and what of them could not be counted.
     */
    private static final class Run {

        private final Statistics statistics;
        private int deviations;
        private int undeterminedReadWrite;

        Run(Statistics statistics) {
            this.statistics = statistics;
        }

        void readRequests(ObjectMapper mapper, Path testResultsFile) {
            try (JsonParser jParser = mapper.createParser(testResultsFile.toFile())) {
                if (jParser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("Expected an array");
                }
                while (jParser.nextToken() == JsonToken.START_OBJECT) {
                    // one request at a time: a file of a run holds tens of megabytes
                    record(jParser.readValueAsTree());
                }
            }
        }

        private void record(JsonNode node) {
            double duration = node.get("test_duration").asDouble();
            boolean success = node.get("test_success").asBoolean();
            boolean deviation = node.get("test_deviation_flag").asBoolean();
            int workerConcurrencyDegree = node.get("worker_concurrency_degree").asInt();
            statistics.recordWorkerConcurrency(workerConcurrencyDegree);
            int commandConcurrencyDegree = node.get("command_concurrency_degree").asInt();
            statistics.recordCommandConcurrency(commandConcurrencyDegree);

            String tags = node.get("test_tags").asString();
            if (READ_PATTERN.matcher(tags).find()) {
                if (success) {
                    statistics.recordReadLatency(duration);
                } else {
                    statistics.recordReadFailedLatency(duration);
                }
            } else if (WRITE_PATTERN.matcher(tags).find()) {
                if (success) {
                    statistics.recordWriteLatency(duration);
                } else {
                    statistics.recordWriteFailedLatency(duration);
                }
            } else {
                undeterminedReadWrite++;
                return;
            }
            if (deviation) {
                // NOTE: Deviation counts are reported and then discarded
                deviations++;
            }
        }

        /*
         * Tells the person who runs the program, on its standard error, of requests that could not be counted.
         */
        void report(String testBasename) {
            if (deviations > 0) {
                System.err.println(testBasename + " Number of deviations: " + deviations);
            }
            if (undeterminedReadWrite > 0) {
                System.err.println(testBasename + " Number of test_tags that match neither read nor write pattern: " + undeterminedReadWrite);
            }
        }
    }
}
