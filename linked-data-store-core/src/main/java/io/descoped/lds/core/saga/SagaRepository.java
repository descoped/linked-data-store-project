package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.IndexQueue;
import io.descoped.lds.api.specification.Specification;
import io.descoped.lds.core.persistence.PersistenceCreateOrOverwriteSagaAdapter;
import io.descoped.lds.core.persistence.PersistenceDeleteSagaAdapter;
import io.descoped.lds.core.search.DeleteIndexSagaAdapter;
import io.descoped.lds.core.search.UpdateIndexSagaAdapter;
import io.descoped.lds.core.txlog.AppendTxLogAdapter;
import io.descoped.lds.core.txlog.DeleteTxLogAdapter;
import io.descoped.lds.core.txlog.TxlogRawdataPool;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.adapter.AdapterLoader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SagaRepository {

    public static final String SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE = "Create or update managed resource";
    public static final String SAGA_DELETE_MANAGED_RESOURCE = "Delete managed resource";

    /* The ids of the nodes of both sagas: the transaction log, then the store, then the index queue when there is search. */
    private static final String TXLOG = "txlog";
    private static final String PERSISTENCE = "persistence";
    private static final String INDEX_QUEUE = "index-queue";

    final Map<String, Saga> sagaByName = new ConcurrentHashMap<>();

    final AdapterLoader adapterLoader;

    private SagaRepository(Specification specification, RxJsonPersistence persistence, IndexQueue indexQueue, TxlogRawdataPool txLogPool) {
        adapterLoader = new AdapterLoader();
        adapterLoader.register(new PersistenceCreateOrOverwriteSagaAdapter(persistence, specification));
        adapterLoader.register(new PersistenceDeleteSagaAdapter(persistence));
        adapterLoader.register(new AppendTxLogAdapter(txLogPool));
        adapterLoader.register(new DeleteTxLogAdapter(txLogPool));
        if (indexQueue != null) {
            adapterLoader.register(new UpdateIndexSagaAdapter(indexQueue));
            adapterLoader.register(new DeleteIndexSagaAdapter(indexQueue));
        }

        register(buildCreateOrUpdateSaga(indexQueue != null));
        register(buildDeleteSaga(indexQueue != null));
    }

    /*
     * The transaction log, then the store, then the index queue: the queue holds only what the store has, so the
     * worker that sends it finds each document at its version.
     */
    private Saga buildCreateOrUpdateSaga(boolean indexed) {
        Saga.SagaBuilder createSagaBuilder = Saga.start(SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE)
                .linkTo(TXLOG);
        createSagaBuilder.id(TXLOG).adapter(AppendTxLogAdapter.ADAPTER_NAME).linkTo(PERSISTENCE);
        if (indexed) {
            createSagaBuilder.id(PERSISTENCE).adapter(PersistenceCreateOrOverwriteSagaAdapter.ADAPTER_NAME).linkTo(INDEX_QUEUE);
            createSagaBuilder.id(INDEX_QUEUE).adapter(UpdateIndexSagaAdapter.ADAPTER_NAME).linkToEnd();
        } else {
            createSagaBuilder.id(PERSISTENCE).adapter(PersistenceCreateOrOverwriteSagaAdapter.ADAPTER_NAME).linkToEnd();
        }
        return createSagaBuilder.end();
    }

    private Saga buildDeleteSaga(boolean indexed) {
        Saga.SagaBuilder deleteSagaBuilder = Saga.start(SAGA_DELETE_MANAGED_RESOURCE)
                .linkTo(TXLOG);
        deleteSagaBuilder.id(TXLOG).adapter(DeleteTxLogAdapter.ADAPTER_NAME).linkTo(PERSISTENCE);
        if (indexed) {
            deleteSagaBuilder.id(PERSISTENCE).adapter(PersistenceDeleteSagaAdapter.ADAPTER_NAME).linkTo(INDEX_QUEUE);
            deleteSagaBuilder.id(INDEX_QUEUE).adapter(DeleteIndexSagaAdapter.ADAPTER_NAME).linkToEnd();
        } else {
            deleteSagaBuilder.id(PERSISTENCE).adapter(PersistenceDeleteSagaAdapter.ADAPTER_NAME).linkToEnd();
        }
        return deleteSagaBuilder.end();
    }

    public AdapterLoader getAdapterLoader() {
        return adapterLoader;
    }

    SagaRepository register(Saga saga) {
        sagaByName.put(saga.name, saga);
        return this;
    }

    public Saga get(String sagaName) {
        return sagaByName.get(sagaName);
    }

    public static class Builder {

        Specification specification;
        RxJsonPersistence persistence;
        IndexQueue indexQueue;
        TxlogRawdataPool txlogRawdataPool;

        public Builder specification(Specification specification) {
            this.specification = specification;
            return this;
        }

        public Builder persistence(RxJsonPersistence persistence) {
            this.persistence = persistence;
            return this;
        }

        public Builder indexQueue(IndexQueue indexQueue) {
            this.indexQueue = indexQueue;
            return this;
        }

        public Builder txLogRawdataPool(TxlogRawdataPool txlogRawdataPool) {
            this.txlogRawdataPool = txlogRawdataPool;
            return this;
        }

        public SagaRepository build() {
            return new SagaRepository(specification, persistence, indexQueue, txlogRawdataPool);
        }
    }
}
