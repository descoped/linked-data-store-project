package io.descoped.lds.core.saga;

import io.descoped.lds.api.persistence.reactivex.RxJsonPersistence;
import io.descoped.lds.api.search.SearchIndex;
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

    /* The ids of the two nodes both sagas have: the transaction log, then the store. */
    private static final String TXLOG = "txlog";
    private static final String PERSISTENCE = "persistence";

    final Map<String, Saga> sagaByName = new ConcurrentHashMap<>();

    final AdapterLoader adapterLoader;

    private SagaRepository(Specification specification, RxJsonPersistence persistence, SearchIndex indexer, TxlogRawdataPool txLogPool) {
        adapterLoader = new AdapterLoader();
        adapterLoader.register(new PersistenceCreateOrOverwriteSagaAdapter(persistence, specification));
        adapterLoader.register(new PersistenceDeleteSagaAdapter(persistence));
        adapterLoader.register(new AppendTxLogAdapter(txLogPool));
        adapterLoader.register(new DeleteTxLogAdapter(txLogPool));
        if (indexer != null) {
            adapterLoader.register(new UpdateIndexSagaAdapter(indexer, specification));
            adapterLoader.register(new DeleteIndexSagaAdapter(indexer, specification));
        }

        register(buildCreateOrUpdateSaga(indexer));
        register(buildDeleteSaga(indexer));
    }

    private Saga buildCreateOrUpdateSaga(SearchIndex indexer) {
        Saga.SagaBuilder createSagaBuilder = Saga.start(SAGA_CREATE_OR_UPDATE_MANAGED_RESOURCE)
                .linkTo(TXLOG);
        if (indexer != null) {
            createSagaBuilder.id(TXLOG).adapter(AppendTxLogAdapter.ADAPTER_NAME).linkTo(PERSISTENCE, "search-index-update");
            createSagaBuilder.id("search-index-update").adapter(UpdateIndexSagaAdapter.ADAPTER_NAME).linkToEnd();
        } else {
            createSagaBuilder.id(TXLOG).adapter(AppendTxLogAdapter.ADAPTER_NAME).linkTo(PERSISTENCE);
        }
        createSagaBuilder.id(PERSISTENCE).adapter(PersistenceCreateOrOverwriteSagaAdapter.ADAPTER_NAME).linkToEnd();
        return createSagaBuilder.end();
    }

    private Saga buildDeleteSaga(SearchIndex indexer) {
        Saga.SagaBuilder deleteSagaBuilder = Saga.start(SAGA_DELETE_MANAGED_RESOURCE)
                .linkTo(TXLOG);
        if (indexer != null) {
            deleteSagaBuilder.id(TXLOG).adapter(DeleteTxLogAdapter.ADAPTER_NAME).linkTo(PERSISTENCE, "search-index-delete");
            deleteSagaBuilder.id("search-index-delete").adapter(DeleteIndexSagaAdapter.ADAPTER_NAME).linkToEnd();
        } else {
            deleteSagaBuilder.id(TXLOG).adapter(DeleteTxLogAdapter.ADAPTER_NAME).linkTo(PERSISTENCE);
        }
        deleteSagaBuilder.id(PERSISTENCE).adapter(PersistenceDeleteSagaAdapter.ADAPTER_NAME).linkToEnd();
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
        SearchIndex indexer;
        TxlogRawdataPool txlogRawdataPool;

        public Builder specification(Specification specification) {
            this.specification = specification;
            return this;
        }

        public Builder persistence(RxJsonPersistence persistence) {
            this.persistence = persistence;
            return this;
        }

        public Builder indexer(SearchIndex indexer) {
            this.indexer = indexer;
            return this;
        }

        public Builder txLogRawdataPool(TxlogRawdataPool txlogRawdataPool) {
            this.txlogRawdataPool = txlogRawdataPool;
            return this;
        }

        public SagaRepository build() {
            return new SagaRepository(specification, persistence, indexer, txlogRawdataPool);
        }
    }
}
