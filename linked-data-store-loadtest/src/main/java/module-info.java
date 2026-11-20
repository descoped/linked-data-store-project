/**
 * The program of the load test of LDS that turns the results of a run into statistics and plot files.
 */
// Commons Math 3 is an automatic module, named after its file.
@SuppressWarnings("requires-automatic")
module io.descoped.lds.loadtest {
    requires tools.jackson.core;
    requires tools.jackson.databind;
    requires commons.math3;
}
