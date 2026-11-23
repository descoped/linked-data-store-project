package io.descoped.lds.core.health;

import io.reactivex.rxjava3.core.Single;

import java.util.Map;

/**
 * One part of LDS that the readiness asks.
 *
 * @param name      the name of the check in the answer
 * @param probe     asks the part; an error, or no answer in time, is the state {@code whenItFails}
 * @param whenItFails what it means for LDS when the part does not answer
 */
public record ReadinessCheck(String name, Single<Result> probe, Health whenItFails) {

    /**
     * What a probe found.
     *
     * @param health  the state
     * @param details what else the answer says of the part, in order
     */
    public record Result(Health health, Map<String, Object> details) {

        /**
         * @return the part answers, and there is nothing more to say
         */
        public static Result up() {
            return new Result(Health.UP, Map.of());
        }
    }
}
