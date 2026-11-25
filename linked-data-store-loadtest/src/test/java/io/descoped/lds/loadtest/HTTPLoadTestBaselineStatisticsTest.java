package io.descoped.lds.loadtest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The result files of two runs, as HTTPLoadTest-baseline leaves them, become one data file with a value of every
 * statistic for each run, and the gnuplot scripts that draw it.
 */
class HTTPLoadTestBaselineStatisticsTest {

    @TempDir
    Path results;

    @TempDir
    Path plots;

    private static String request(String kind, double duration, boolean success) {
        return "{\"test_id\":\"t\",\"test_duration\":" + duration + ",\"test_success\":" + success
                + ",\"test_deviation_flag\":false,\"worker_concurrency_degree\":2,\"command_concurrency_degree\":1"
                + ",\"test_tags\":\"(" + kind + "-URL: http://lds:9090/ns/Agent/1)\",\"more\":{\"ignored\":[1,2]}}";
    }

    private void run(String name, int threads, int seconds, String... requests) throws IOException {
        Files.writeString(results.resolve(name + ".csv"), "");
        Files.writeString(results.resolve(name + ".json"), "[" + String.join(",", requests) + "]");
        Files.writeString(results.resolve(name + "_load_specififation.json"),
                "{\"test_id\":\"t\",\"test_no_of_threads\":" + threads + ",\"test_duration_in_seconds\":" + seconds + "}");
    }

    /*
     * The data file: a block for each statistic, "#<index> "<name>"", then a line "<x> <y>" for each run.
     */
    private Map<String, Map<String, String>> data() throws IOException {
        Map<String, Map<String, String>> statistics = new LinkedHashMap<>();
        Map<String, String> block = null;
        for (String line : Files.readAllLines(plots.resolve("p.dat"), StandardCharsets.ISO_8859_1)) {
            if (line.startsWith("#")) {
                block = new LinkedHashMap<>();
                statistics.put(line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')), block);
            } else if (!line.isEmpty()) {
                String[] xy = line.split(" ");
                block.put(xy[0], xy[1]);
            }
        }
        return statistics;
    }

    @Test
    void eachRunIsOneSampleAtItsNumberOfThreads() throws IOException {
        run("one", 2, 10,
                request("Read", 10, true), request("Read", 20, true), request("Write", 30, true),
                request("Read", 40, false));
        run("two", 5, 10,
                request("Write", 100, true), request("Write", 50, false));

        HTTPLoadTestBaselineStatistics.main(new String[]{results.toString(), plots.toString(), "p"});

        Map<String, Map<String, String>> data = data();
        // reads that succeeded, writes that succeeded, both
        assertThat(data.get("r-N")).containsExactly(Map.entry("2", "2"), Map.entry("5", "0"));
        assertThat(data.get("r-mean")).containsEntry("2", "15.0");
        assertThat(data.get("r-N/sec")).containsEntry("2", "0.2");
        assertThat(data.get("w-N")).containsExactly(Map.entry("2", "1"), Map.entry("5", "1"));
        assertThat(data.get("w-mean")).containsExactly(Map.entry("2", "30.0"), Map.entry("5", "100.0"));
        assertThat(data.get("N")).containsExactly(Map.entry("2", "3"), Map.entry("5", "1"));
        assertThat(data.get("mean")).containsEntry("2", "20.0");
        assertThat(data.get("max")).containsEntry("2", "30.0");
        assertThat(data.get("sec")).containsExactly(Map.entry("2", "10"), Map.entry("5", "10"));
        // those that failed
        assertThat(data.get("rf-N")).containsExactly(Map.entry("2", "1"), Map.entry("5", "0"));
        assertThat(data.get("wf-N")).containsExactly(Map.entry("2", "0"), Map.entry("5", "1"));
        assertThat(data.get("f-N")).containsExactly(Map.entry("2", "1"), Map.entry("5", "1"));
        assertThat(data.get("f-mean")).containsExactly(Map.entry("2", "40.0"), Map.entry("5", "50.0"));
        // the concurrency of every request, whatever became of it
        assertThat(data.get("wc-N")).containsExactly(Map.entry("2", "4"), Map.entry("5", "2"));
        assertThat(data.get("wc-mean")).containsEntry("2", "2.0");
        assertThat(data.get("cc-mean")).containsEntry("5", "1.0");
        // 23 statistics of 8 kinds
        assertThat(data).hasSize(23 * 8);
    }

    @Test
    void theScriptsDrawTheDataFileIntoAnSvgFileOfTheirOwnName() throws IOException {
        run("one", 1, 10, request("Read", 10, true), request("Write", 30, true));

        HTTPLoadTestBaselineStatistics.main(new String[]{results.toString(), plots.toString(), "p"});

        try (var files = Files.list(plots)) {
            assertThat(files.map(file -> file.getFileName().toString())).containsExactlyInAnyOrder(
                    "p.dat", "p.gnu", "p_total_throughput.gnu", "p_read_latency.gnu", "p_write_latency.gnu", "p_load.gnu");
        }
        List<String> index = List.copyOf(data().keySet());
        assertThat(Files.readString(plots.resolve("p_read_latency.gnu"), StandardCharsets.ISO_8859_1))
                .contains("set output 'p_read_latency.svg'")
                .contains("plot 'p.dat' \\\n  i " + index.indexOf("r-mean") + "  t \"r-mean\" w linespoints ls 1, \\\n")
                .contains("  '' i " + index.indexOf("r-99%") + "  t \"r-99%\" w linespoints ls 5\n");
        assertThat(Files.readString(plots.resolve("p.gnu"), StandardCharsets.ISO_8859_1))
                .contains("  i " + index.indexOf("N/sec") + "  t \"success\" w linespoints ls 1, \\\n")
                .contains("  '' i " + index.indexOf("f-N/sec") + "  t \"failed\" w linespoints ls 2\n");
    }

    @Test
    void aRunWithoutRequestsIsASampleOfNothing() throws IOException {
        run("none", 3, 10);

        HTTPLoadTestBaselineStatistics.main(new String[]{results.toString(), plots.toString(), "p"});

        assertThat(data().get("N")).containsExactly(Map.entry("3", "0"));
        assertThat(data().get("mean")).containsExactly(Map.entry("3", "NaN"));
    }

    @Test
    void nothingIsWrittenWithoutTheThreeArgumentsOrWithAFolderThatIsNone() throws IOException {
        assertThat(HTTPLoadTestBaselineStatistics.run(new String[]{results.toString(), plots.toString()})).isEqualTo(2);
        assertThat(HTTPLoadTestBaselineStatistics.run(
                new String[]{results.resolve("missing").toString(), plots.toString(), "p"})).isEqualTo(2);
        assertThat(HTTPLoadTestBaselineStatistics.run(
                new String[]{results.toString(), plots.resolve("missing").toString(), "p"})).isEqualTo(2);

        try (var files = Files.list(plots)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void theProgramEndsWithAStatusThatIsNotZeroWhenItsArgumentsAreWrong() throws IOException, InterruptedException {
        // the script that runs it (functions.sh) stops on that status, before gnuplot is asked for a file not made
        Process program = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                HTTPLoadTestBaselineStatistics.class.getName(), results.toString(), plots.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(program.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(program.waitFor(1, TimeUnit.MINUTES)).isTrue();
        assertThat(program.exitValue()).as(output).isEqualTo(2);
        assertThat(output).contains("Must provide the following three arguments: INFOLDER OUTFOLDER OUTBASENAME");
    }
}
