package io.descoped.lds.api.persistence.reactivex;

import io.reactivex.rxjava3.core.Emitter;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.functions.BiConsumer;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class RxGroupByTools {

    private RxGroupByTools() {
    }

    /**
     * Assumes that the upstream is pre-ordered by group, so that all upstream items belonging to the same group
     * will come immediately after one-another. This allows the grouped-flows to be immediately completed when an item
     * belonging to a different group is observed upstream.
     * <p>
     * The returned stream honors downstream back-pressure at the group-level, and will buffer all items in each group
     * until they are subscribed to and consumed. This allows more control over memory-usage than the built-in group-by
     * operations currently supported by RxJava.
     *
     * @param upstream         the upstream as a flow of upstream-items.
     * @param groupKeyFunction the function that will be applied to an upstream item to determine which group it
     *                         belongs to. This function must be idempotent (cannot have side effect as it might be
     *                         applied more than once).
     * @param <T>              the generic type of the upstream and downstream items.
     * @param <K>              the generic type of the group-key. This type must implement the equals and hashCode
     *                         methods so that the group-key of two different upstream-items (as provided by the
     *                         groupKeyFunction) that belong in the same group will be considered equal, otherwise (if
     *                         they belong in different groups will be considered not-equals.
     * @return a flow of groups where each group is a separate subflow of upstream items.
     */
    public static <T, K> Flowable<MyGroupedFlowable<T, K>> groupByOrdered(final Flowable<? extends T> upstream, final Function<? super T, ? extends K> groupKeyFunction) {
        final GroupedItemWrapper<T> groupCompleteItem = new GroupedItemWrapper<>((T) null);
        final GroupedItemWrapper<T> cancelledItem = new GroupedItemWrapper<>((T) null);

        final AtomicReference<K> groupKeyRef = new AtomicReference<>();
        final Map<K, BlockingQueue<GroupedItemWrapper<T>>> itemsByGroup = new LinkedHashMap<>();

        return upstream
                .filter(item -> addToGroup(item, groupKeyFunction, groupKeyRef, itemsByGroup, groupCompleteItem))
                .doOnSubscribe(subscription -> {
                    groupKeyRef.set(null);
                    itemsByGroup.clear();
                })
                .doOnComplete(() -> putInCurrentGroup(groupKeyRef, itemsByGroup, groupCompleteItem))
                .doOnError(throwable -> putInCurrentGroup(groupKeyRef, itemsByGroup, new GroupedItemWrapper<>(throwable)))
                .doOnCancel(() -> putInCurrentGroup(groupKeyRef, itemsByGroup, cancelledItem))
                .map(item -> {
                    final K groupKey = groupKeyRef.get();
                    final BlockingQueue<GroupedItemWrapper<T>> blockingQueue = itemsByGroup.get(groupKey);
                    final BiConsumer<BlockingQueue<GroupedItemWrapper<T>>, Emitter<T>> generator =
                            (queue, emitter) -> emitNext(queue, emitter, groupCompleteItem, cancelledItem);
                    Flowable<T> innerGroupFlow = Flowable.generate(() -> blockingQueue, generator, BlockingQueue::clear);
                    return new MyGroupedFlowable<>(groupKey, innerGroupFlow.subscribeOn(Schedulers.newThread()));
                });
    }

    /*
     * Adds an upstream item to its group. True when the item starts a new group, which completes the previous one.
     */
    private static <T, K> boolean addToGroup(T item, Function<? super T, ? extends K> groupKeyFunction, AtomicReference<K> groupKeyRef,
                                             Map<K, BlockingQueue<GroupedItemWrapper<T>>> itemsByGroup,
                                             GroupedItemWrapper<T> groupCompleteItem) {
        // Group by object determined by groupKeyFunction
        K groupKey = groupKeyFunction.apply(item);

        if (groupKeyRef.get() == null) {
            // very first upstream item, add item to new group
            groupKeyRef.set(groupKey);
            itemsByGroup.computeIfAbsent(groupKey, k -> new LinkedBlockingQueue<>()).add(new GroupedItemWrapper<>(item));
            return true;
        }
        if (groupKey.equals(groupKeyRef.get())) {
            // group already exists, add item to existing group
            itemsByGroup.computeIfAbsent(groupKey, k -> new LinkedBlockingQueue<>()).add(new GroupedItemWrapper<>(item));
            return false;
        }

        // new group, signal completion of previous group and add item to new group
        itemsByGroup.get(groupKeyRef.get()).add(groupCompleteItem);
        groupKeyRef.set(groupKey);
        itemsByGroup.computeIfAbsent(groupKey, k -> new LinkedBlockingQueue<>()).add(new GroupedItemWrapper<>(item));
        return true;
    }

    /*
     * Puts a signal in the queue of the current group, if there is one.
     */
    private static <T, K> void putInCurrentGroup(AtomicReference<K> groupKeyRef, Map<K, BlockingQueue<GroupedItemWrapper<T>>> itemsByGroup,
                                                 GroupedItemWrapper<T> wrapper) throws InterruptedException {
        K groupKey = groupKeyRef.get();
        if (groupKey == null) {
            return;
        }
        itemsByGroup.get(groupKey).put(wrapper);
    }

    /*
     * Gives the next item of a group to its subscriber, or ends the group.
     */
    private static <T> void emitNext(BlockingQueue<GroupedItemWrapper<T>> queue, Emitter<T> emitter,
                                     GroupedItemWrapper<T> groupCompleteItem, GroupedItemWrapper<T> cancelledItem) throws InterruptedException {
        GroupedItemWrapper<T> wrapper = queue.take();
        if (wrapper == groupCompleteItem) {
            emitter.onComplete();
            return;
        }
        if (wrapper == cancelledItem) {
            emitter.onError(new RuntimeException("cancelled"));
        }
        if (wrapper.throwable != null) {
            emitter.onError(wrapper.throwable);
        }
        emitter.onNext(wrapper.item);
    }

    public static class MyGroupedFlowable<T, K> {
        private final K groupKey;
        private final Flowable<T> flowable;

        private MyGroupedFlowable(K groupKey, Flowable<T> flowable) {
            this.groupKey = groupKey;
            this.flowable = flowable;
        }

        public K key() {
            return groupKey;
        }

        public Flowable<T> flowable() {
            return flowable;
        }
    }

    private static class GroupedItemWrapper<T> {
        final T item;
        final Throwable throwable;

        GroupedItemWrapper(T item) {
            this.item = item;
            this.throwable = null;
        }

        GroupedItemWrapper(Throwable t) {
            this.item = null;
            this.throwable = t;
        }
    }
}
