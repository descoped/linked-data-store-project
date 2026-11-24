package io.descoped.lds.core.txlog;

/**
 * The step of the saga of a deletion that puts the deletion in the transaction log.
 */
public class DeleteTxLogAdapter extends PublishTxLogAdapter {

    public static final String ADAPTER_NAME = "TxLog-delete-entry";

    public DeleteTxLogAdapter(TxlogRawdataPool pool) {
        super(ADAPTER_NAME, pool);
    }
}
