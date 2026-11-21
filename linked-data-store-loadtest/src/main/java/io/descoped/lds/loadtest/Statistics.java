package io.descoped.lds.loadtest;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The statistics of a load test: latencies and concurrency are recorded as they are read, and {@link #sample}
 * closes a run by keeping one value of every statistic at the x of the run (the number of threads).
 * <p>
 * A statistic is kept under its name with a prefix that says what it is of: {@code r-} reads that succeeded,
 * {@code w-} writes that succeeded, none for both, {@code rf-}, {@code wf-} and {@code f-} for those that
 * failed, {@code wc-} and {@code cc-} for the concurrency of workers and of commands.
 */
public class Statistics {

    /** The number of values. */
    public static final String STAT_N = "N";
    /** The length of the run in seconds. */
    public static final String STAT_SECONDS = "sec";
    /** The number of values for each second of the run. */
    public static final String STAT_N_PER_SEC = "N/sec";
    /** The smallest value. */
    public static final String STAT_MIN = "min";
    /** The mean. */
    public static final String STAT_MEAN = "mean";
    /** The largest value. */
    public static final String STAT_MAX = "max";
    /** The standard deviation. */
    public static final String STAT_STDDEV = "stddev";
    /** The median. */
    public static final String STAT_MEDIAN = "median";
    /** The 25th percentile. */
    public static final String STAT_P25 = "25%";
    /** The 10th percentile. */
    public static final String STAT_P10 = "10%";
    /** The 5th percentile. */
    public static final String STAT_P5 = "5%";
    /** The 1st percentile. */
    public static final String STAT_P1 = "1%";
    /** The 0.1th percentile. */
    public static final String STAT_P0_1 = "0.1%";
    /** The 75th percentile. */
    public static final String STAT_P75 = "75%";
    /** The 90th percentile. */
    public static final String STAT_P90 = "90%";
    /** The 95th percentile. */
    public static final String STAT_P95 = "95%";
    /** The 99th percentile. */
    public static final String STAT_P99 = "99%";
    /** The 99.9th percentile. */
    public static final String STAT_P99_9 = "99.9%";
    /** The kurtosis. */
    public static final String STAT_KURTOSIS = "kurtosis";
    /** The skewness. */
    public static final String STAT_SKEWNESS = "skewness";
    /** The geometric mean. */
    public static final String STAT_GMEAN = "gmean";
    /** The sum. */
    public static final String STAT_SUM = "sum";
    /** The variance of the sample. */
    public static final String STAT_SAMPLE_VARIANCE = "sample-variance";

    final Map<String, Map<Number, Number>> statDatasets = new LinkedHashMap<>();
    private final DescriptiveStatistics readLatencyStatistics = new DescriptiveStatistics();
    private final DescriptiveStatistics writeLatencyStatistics = new DescriptiveStatistics();
    private final DescriptiveStatistics readFailedLatencyStatistics = new DescriptiveStatistics();
    private final DescriptiveStatistics writeFailedLatencyStatistics = new DescriptiveStatistics();
    private final DescriptiveStatistics workerConcurrencyStatistics = new DescriptiveStatistics();
    private final DescriptiveStatistics commandConcurrencyStatistics = new DescriptiveStatistics();

    /**
     * Statistics with nothing recorded.
     */
    public Statistics() {
        // every statistic starts empty, in the fields above
    }

    /**
     * A read that succeeded.
     *
     * @param latencyMs how long it took, in milliseconds
     */
    public void recordReadLatency(double latencyMs) {
        synchronized (readLatencyStatistics) {
            readLatencyStatistics.addValue(latencyMs);
        }
    }

    /**
     * A write that succeeded.
     *
     * @param latencyMs how long it took, in milliseconds
     */
    public void recordWriteLatency(double latencyMs) {
        synchronized (writeLatencyStatistics) {
            writeLatencyStatistics.addValue(latencyMs);
        }
    }

    /**
     * A read that failed.
     *
     * @param latencyMs how long it took, in milliseconds
     */
    public void recordReadFailedLatency(double latencyMs) {
        synchronized (readFailedLatencyStatistics) {
            readFailedLatencyStatistics.addValue(latencyMs);
        }
    }

    /**
     * A write that failed.
     *
     * @param latencyMs how long it took, in milliseconds
     */
    public void recordWriteFailedLatency(double latencyMs) {
        synchronized (writeFailedLatencyStatistics) {
            writeFailedLatencyStatistics.addValue(latencyMs);
        }
    }

    /**
     * The number of workers that were busy when a request was sent.
     *
     * @param workerConcurrency the number of workers
     */
    public void recordWorkerConcurrency(double workerConcurrency) {
        synchronized (workerConcurrencyStatistics) {
            workerConcurrencyStatistics.addValue(workerConcurrency);
        }
    }

    /**
     * The number of commands that were running when a request was sent.
     *
     * @param commandConcurrency the number of commands
     */
    public void recordCommandConcurrency(double commandConcurrency) {
        synchronized (commandConcurrencyStatistics) {
            commandConcurrencyStatistics.addValue(commandConcurrency);
        }
    }

    /**
     * Closes a run: every statistic of what was recorded since the last sample is kept at x, and the recorded
     * values are forgotten.
     *
     * @param x                   where the run is on the x axis: its number of threads
     * @param sampleWindowSeconds the length of the run in seconds
     */
    public void sample(int x, int sampleWindowSeconds) {
        DescriptiveStatistics combinedSuccessStats;
        synchronized (readLatencyStatistics) {
            sample(readLatencyStatistics, "r-", x, sampleWindowSeconds);
            combinedSuccessStats = readLatencyStatistics.copy();
            readLatencyStatistics.clear();
        }
        synchronized (writeLatencyStatistics) {
            sample(writeLatencyStatistics, "w-", x, sampleWindowSeconds);
            for (double value : writeLatencyStatistics.getValues()) {
                combinedSuccessStats.addValue(value);
            }
            writeLatencyStatistics.clear();
        }
        sample(combinedSuccessStats, "", x, sampleWindowSeconds);

        DescriptiveStatistics combinedFailedStats;
        synchronized (readFailedLatencyStatistics) {
            sample(readFailedLatencyStatistics, "rf-", x, sampleWindowSeconds);
            combinedFailedStats = readFailedLatencyStatistics.copy();
            readFailedLatencyStatistics.clear();
        }
        synchronized (writeFailedLatencyStatistics) {
            sample(writeFailedLatencyStatistics, "wf-", x, sampleWindowSeconds);
            for (double value : writeFailedLatencyStatistics.getValues()) {
                combinedFailedStats.addValue(value);
            }
            writeFailedLatencyStatistics.clear();
        }
        sample(combinedFailedStats, "f-", x, sampleWindowSeconds);

        synchronized (workerConcurrencyStatistics) {
            sample(workerConcurrencyStatistics, "wc-", x, sampleWindowSeconds);
            for (double value : workerConcurrencyStatistics.getValues()) {
                combinedSuccessStats.addValue(value);
            }
            workerConcurrencyStatistics.clear();
        }
        synchronized (commandConcurrencyStatistics) {
            sample(commandConcurrencyStatistics, "cc-", x, sampleWindowSeconds);
            for (double value : commandConcurrencyStatistics.getValues()) {
                combinedSuccessStats.addValue(value);
            }
            commandConcurrencyStatistics.clear();
        }
    }

    private void sample(DescriptiveStatistics stats, String prefix, int x, int sampleWindowSeconds) {
        statDatasets.computeIfAbsent(prefix + STAT_N, k -> new LinkedHashMap<>()).put(x, stats.getN());
        statDatasets.computeIfAbsent(prefix + STAT_SECONDS, k -> new LinkedHashMap<>()).put(x, sampleWindowSeconds);
        statDatasets.computeIfAbsent(prefix + STAT_N_PER_SEC, k -> new LinkedHashMap<>()).put(x, (double) stats.getN() / sampleWindowSeconds);
        statDatasets.computeIfAbsent(prefix + STAT_MIN, k -> new LinkedHashMap<>()).put(x, stats.getMin());
        statDatasets.computeIfAbsent(prefix + STAT_GMEAN, k -> new LinkedHashMap<>()).put(x, stats.getGeometricMean());
        statDatasets.computeIfAbsent(prefix + STAT_MEAN, k -> new LinkedHashMap<>()).put(x, stats.getMean());
        statDatasets.computeIfAbsent(prefix + STAT_P0_1, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(0.1));
        statDatasets.computeIfAbsent(prefix + STAT_P1, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(1));
        statDatasets.computeIfAbsent(prefix + STAT_P5, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(5));
        statDatasets.computeIfAbsent(prefix + STAT_P10, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(10));
        statDatasets.computeIfAbsent(prefix + STAT_P25, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(25));
        statDatasets.computeIfAbsent(prefix + STAT_MEDIAN, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(50));
        statDatasets.computeIfAbsent(prefix + STAT_P75, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(75));
        statDatasets.computeIfAbsent(prefix + STAT_P90, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(90));
        statDatasets.computeIfAbsent(prefix + STAT_P95, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(95));
        statDatasets.computeIfAbsent(prefix + STAT_P99, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(99));
        statDatasets.computeIfAbsent(prefix + STAT_P99_9, k -> new LinkedHashMap<>()).put(x, stats.getPercentile(99.9));
        statDatasets.computeIfAbsent(prefix + STAT_MAX, k -> new LinkedHashMap<>()).put(x, stats.getMax());
        statDatasets.computeIfAbsent(prefix + STAT_STDDEV, k -> new LinkedHashMap<>()).put(x, stats.getStandardDeviation());
        statDatasets.computeIfAbsent(prefix + STAT_KURTOSIS, k -> new LinkedHashMap<>()).put(x, stats.getKurtosis());
        statDatasets.computeIfAbsent(prefix + STAT_SKEWNESS, k -> new LinkedHashMap<>()).put(x, stats.getSkewness());
        statDatasets.computeIfAbsent(prefix + STAT_SUM, k -> new LinkedHashMap<>()).put(x, stats.getSum());
        statDatasets.computeIfAbsent(prefix + STAT_SAMPLE_VARIANCE, k -> new LinkedHashMap<>()).put(x, stats.getVariance());
    }

}
