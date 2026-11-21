package io.descoped.lds.loadtest;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the statistics of a load test as files for gnuplot: one data file with a block for every statistic, and
 * the scripts that draw throughput and latency by the number of threads from it.
 */
public final class GnuplotFileGenerator {

    private static final String WIDE = "1920,1080";
    private static final String UNSET_FORMAT_X = "unset format x\n";
    private static final String GRID_YTICS_MYTICS = "set grid ytics mytics\n";
    private static final String STYLE_LINE_1_BLUE_POINTS = "set style line 1 lc rgb '#0060ad' lt 1 lw 1 pt 7 pi 0 ps 0.5\n";
    private static final String YLABEL_THROUGHPUT = "set ylabel \"Throughput (requests/second)\"\n";
    private static final String YLABEL_LATENCY = "set ylabel \"Latency (ms)\"\n";
    private static final String LINESPOINTS_LS_1 = "linespoints ls 1";
    private static final String LINESPOINTS_LS_2 = "linespoints ls 2";
    private static final String SUCCESS = "success";
    private static final String FAILED = "failed";

    /*
     * The latencies that a latency script draws, in the order of its lines.
     */
    private static final List<String> LATENCY_STATISTICS = List.of(
            Statistics.STAT_MEAN, Statistics.STAT_MEDIAN, Statistics.STAT_P90, Statistics.STAT_P95, Statistics.STAT_P99);

    private GnuplotFileGenerator() {
    }

    /**
     * Writes {@code <basename>.dat} and the gnuplot scripts {@code <basename>.gnu} (throughput and mean latency),
     * {@code _total_throughput.gnu}, {@code _read_latency.gnu}, {@code _write_latency.gnu} and {@code _load.gnu}.
     * Each script writes an SVG file of its own name when gnuplot runs it in the folder.
     *
     * @param statistics        the samples to write
     * @param xlabel            the label of the x axis
     * @param plotfilesFolder   the folder to write to
     * @param plotfilesBasename what the names of the files start with
     */
    public static void writeStatisticsGnuplotAndDatafile(Statistics statistics, String xlabel, Path plotfilesFolder, String plotfilesBasename) {
        Map<String, Integer> indexByStatistic = writeDataFile(statistics.statDatasets, plotfilesFolder, plotfilesBasename);
        writeThroughputAndLatencyScript(indexByStatistic, xlabel, plotfilesFolder, plotfilesBasename);
        writeTotalThroughputScript(indexByStatistic, xlabel, plotfilesFolder, plotfilesBasename);
        writeLatencyScript(indexByStatistic, xlabel, plotfilesFolder, plotfilesBasename, "_read_latency", "r-");
        writeLatencyScript(indexByStatistic, xlabel, plotfilesFolder, plotfilesBasename, "_write_latency", "w-");
        writeLoadScript(indexByStatistic, xlabel, plotfilesFolder, plotfilesBasename);
    }

    /*
     * Writes the data file, a block for each statistic, and answers the index of the block of each statistic.
     */
    private static Map<String, Integer> writeDataFile(Map<String, Map<Number, Number>> statDatasets, Path plotfilesFolder, String plotfilesBasename) {
        Map<String, Integer> indexByStatistic = new LinkedHashMap<>();
        try (BufferedWriter bw = open(plotfilesFolder, plotfilesBasename + ".dat")) {
            int i = 0;
            for (Map.Entry<String, Map<Number, Number>> e : statDatasets.entrySet()) {
                String stat = e.getKey();
                indexByStatistic.put(stat, i);
                bw.write("#");
                bw.write(String.valueOf(i));
                bw.write(" \"");
                bw.write(stat);
                bw.write("\"\n");
                Map<Number, Number> statYbyX = e.getValue();
                // x is the number of threads of a run, an Integer
                TreeMap<Number, Number> sortedYbyX = new TreeMap<>(Comparator.comparingDouble(Number::doubleValue));
                sortedYbyX.putAll(statYbyX);
                for (Map.Entry<Number, Number> xy : sortedYbyX.entrySet()) {
                    bw.write(String.valueOf(xy.getKey()));
                    bw.write(" ");
                    bw.write(String.valueOf(xy.getValue()));
                    bw.write("\n");
                }
                bw.write("\n");
                bw.write("\n");
                i++;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return indexByStatistic;
    }

    private static void writeThroughputAndLatencyScript(Map<String, Integer> indexByStatistic, String xlabel, Path plotfilesFolder, String plotfilesBasename) {
        try (BufferedWriter bw = open(plotfilesFolder, plotfilesBasename + ".gnu")) {
            writeAxes(bw, WIDE, plotfilesBasename);
            bw.write("set grid ytics\n");
            bw.write(STYLE_LINE_1_BLUE_POINTS);
            bw.write("set style line 2 lc rgb '#dd181f' lt 1 lw 1 pt 7 pi 0 ps 0.5\n");
            writeMargins(bw);
            bw.write("set multiplot layout 2,1\n");
            writeBoxes(bw);
            bw.write(YLABEL_THROUGHPUT);
            writePlot(bw, plotfilesBasename);
            writePlotLine(bw, 0, SUCCESS, String.valueOf(indexByStatistic.get(Statistics.STAT_N_PER_SEC)), 2, LINESPOINTS_LS_1);
            writePlotLine(bw, 1, FAILED, String.valueOf(indexByStatistic.get("f-" + Statistics.STAT_N_PER_SEC)), 2, LINESPOINTS_LS_2);
            bw.write(YLABEL_LATENCY);
            writeXlabel(bw, xlabel);
            bw.write(UNSET_FORMAT_X);
            writePlot(bw, plotfilesBasename);
            writePlotLine(bw, 0, "r-" + Statistics.STAT_MEAN, String.valueOf(indexByStatistic.get("r-" + Statistics.STAT_MEAN)), 2, LINESPOINTS_LS_1);
            writePlotLine(bw, 1, "w-" + Statistics.STAT_MEAN, String.valueOf(indexByStatistic.get("w-" + Statistics.STAT_MEAN)), 2, LINESPOINTS_LS_2);
            bw.write("unset multiplot\n");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeTotalThroughputScript(Map<String, Integer> indexByStatistic, String xlabel, Path plotfilesFolder, String plotfilesBasename) {
        String name = plotfilesBasename + "_total_throughput";
        try (BufferedWriter bw = open(plotfilesFolder, name + ".gnu")) {
            writeAxes(bw, WIDE, name);
            bw.write("set grid ytics\n");
            bw.write(STYLE_LINE_1_BLUE_POINTS);
            bw.write("set style line 2 lc rgb '#dd181f' lt 1 lw 1 pt 13 pi 0 ps 0.5\n");
            writeBoxes(bw);
            bw.write(YLABEL_THROUGHPUT);
            writeXlabel(bw, xlabel);
            bw.write(UNSET_FORMAT_X);
            writePlot(bw, plotfilesBasename);
            writePlotLine(bw, 0, SUCCESS, String.valueOf(indexByStatistic.get(Statistics.STAT_N_PER_SEC)), 2, LINESPOINTS_LS_1);
            writePlotLine(bw, 1, FAILED, String.valueOf(indexByStatistic.get("f-" + Statistics.STAT_N_PER_SEC)), 2, LINESPOINTS_LS_2);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /*
     * The latency script of reads (prefix "r-") or of writes ("w-"): the mean, the median and three percentiles.
     */
    private static void writeLatencyScript(Map<String, Integer> indexByStatistic, String xlabel, Path plotfilesFolder, String plotfilesBasename,
                                           String suffix, String prefix) {
        String name = plotfilesBasename + suffix;
        try (BufferedWriter bw = open(plotfilesFolder, name + ".gnu")) {
            writeAxes(bw, WIDE, name);
            bw.write(GRID_YTICS_MYTICS);
            bw.write(STYLE_LINE_1_BLUE_POINTS);
            bw.write("set style line 2 lc rgb '#ffc400' lt 1 lw 1 pt 9 pi 0 ps 0.5\n");
            bw.write("set style line 3 lc rgb '#00ff92' lt 1 lw 1 pt 11 pi 0 ps 0.5\n");
            bw.write("set style line 4 lc rgb '#00caff' lt 1 lw 1 pt 13 pi 0 ps 0.5\n");
            bw.write("set style line 5 lc rgb '#dd181f' lt 1 lw 1 pt 15 pi 0 ps 0.5\n");
            writeMargins(bw);
            writeBoxes(bw);
            bw.write("set logscale y\n");
            bw.write(YLABEL_LATENCY);
            writeXlabel(bw, xlabel);
            bw.write(UNSET_FORMAT_X);
            writePlot(bw, plotfilesBasename);
            for (int i = 0; i < LATENCY_STATISTICS.size(); i++) {
                String stat = prefix + LATENCY_STATISTICS.get(i);
                writePlotLine(bw, i, stat, String.valueOf(indexByStatistic.get(stat)), LATENCY_STATISTICS.size(), "linespoints ls " + (i + 1));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeLoadScript(Map<String, Integer> indexByStatistic, String xlabel, Path plotfilesFolder, String plotfilesBasename) {
        String name = plotfilesBasename + "_load";
        try (BufferedWriter bw = open(plotfilesFolder, name + ".gnu")) {
            writeAxes(bw, "720,720", name);
            bw.write(GRID_YTICS_MYTICS);
            bw.write("set style line 1 lc rgb '#3f704d' lt 1 lw 1\n");
            bw.write("set style line 2 lc rgb '#0060ad' lt 1 lw 1\n");
            bw.write("set style line 3 lc rgb '#dd181f' lt 1 lw 1\n");
            writeMargins(bw);
            writeBoxes(bw);
            bw.write("set ylabel \"concurrency-degree\"\n");
            writeXlabel(bw, xlabel);
            bw.write("set size square\n");
            bw.write(UNSET_FORMAT_X);
            writePlot(bw, plotfilesBasename);
            writePlotLine(bw, 0, "u 1:($1)", "ideal", String.valueOf(indexByStatistic.get("wc-" + Statistics.STAT_MEAN)), 3, "lines ls 1");
            writePlotLine(bw, 1, "worker", String.valueOf(indexByStatistic.get("wc-" + Statistics.STAT_MEAN)), 3, "lines ls 2");
            writePlotLine(bw, 2, "command", String.valueOf(indexByStatistic.get("cc-" + Statistics.STAT_MEAN)), 3, "lines ls 3");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static BufferedWriter open(Path plotfilesFolder, String fileName) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(plotfilesFolder.resolve(fileName).toFile()), StandardCharsets.ISO_8859_1));
    }

    /*
     * The start of every script: an SVG of the size given, written to <name>.svg, and the ranges and tics of the axes.
     */
    private static void writeAxes(BufferedWriter bw, String terminalSize, String name) throws IOException {
        bw.write("set terminal svg size " + terminalSize + " font 'Verdana,20'\n");
        bw.write("set output '" + name + ".svg'\n");
        bw.write("set xrange [0:*]\n");
        bw.write("set yrange [0:*]\n");
        bw.write("set xtics rotate\n");
        bw.write("set ytics nomirror\n");
        bw.write("set tics out scale 1.5,0.5 font \",16\" nomirror\n");
        bw.write("set mxtics\n");
        bw.write("set mytics\n");
    }

    private static void writeMargins(BufferedWriter bw) throws IOException {
        bw.write("set lmargin 12 # align x-axes at the left side across plots\n");
        bw.write("set bmargin 5 # ensure plots have same height\n");
    }

    private static void writeBoxes(BufferedWriter bw) throws IOException {
        bw.write("set format x \"\"\n");
        bw.write("set style data boxes\n");
        bw.write("set key box opaque inside bottom right\n");
    }

    private static void writeXlabel(BufferedWriter bw, String xlabel) throws IOException {
        bw.write("set xlabel \"" + xlabel + "\"\n");
    }

    private static void writePlot(BufferedWriter bw, String plotfilesBasename) throws IOException {
        bw.write("plot '" + plotfilesBasename + ".dat' \\\n");
    }

    static void writePlotLine(BufferedWriter bw, int i, String stat, String datasetIndex, int size, String w) throws IOException {
        writePlotLine(bw, i, "", stat, datasetIndex, size, w);
    }

    static void writePlotLine(BufferedWriter bw, int i, String u, String stat, String datasetIndex, int size, String w) throws IOException {
        bw.write("  ");
        if (i > 0) {
            bw.write("'' ");
        }
        bw.write("i ");
        bw.write(datasetIndex);
        bw.write(" ");
        bw.write(u);
        bw.write(" t \"");
        bw.write(stat);
        bw.write("\" w ");
        bw.write(w);
        if (i < size - 1) {
            bw.write(", \\");

        }
        bw.write("\n");
    }
}
