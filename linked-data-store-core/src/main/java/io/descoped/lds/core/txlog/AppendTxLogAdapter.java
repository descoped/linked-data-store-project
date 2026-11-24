package io.descoped.lds.core.txlog;

/**
 * The step of the saga of a write that puts the write in the transaction log.
 */
public class AppendTxLogAdapter extends PublishTxLogAdapter {

    public static final String ADAPTER_NAME = "TxLog-put-entry";

    public AppendTxLogAdapter(TxlogRawdataPool pool) {
        super(ADAPTER_NAME, pool);
    }
}
