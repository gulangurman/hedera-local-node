/*
 * Copyright (C) 2021-2022 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hedera.services.throttles;

import static com.hedera.services.legacy.proto.utils.CommonUtils.productWouldOverflow;

import com.swirlds.common.utility.Units;

/**
 * Responsible for throttling transaction by gas limit. Uses a {@link DiscreteLeakyBucket} under the
 * hood. Calculates the amount of gas that should be leaked from the bucket based on the amount of
 * elapsed nanoseconds since the last time {@link GasLimitBucketThrottle#allow(long, long)} was
 * called.
 */
public class GasLimitBucketThrottle {
    private static final long TIME_TO_EMPTY = Units.SECONDS_TO_NANOSECONDS;

    private final DiscreteLeakyBucket bucket;
    private long lastAllowedUnits = 0L;

    /**
     * Creates an instance of the throttle with the specified capacity
     *
     * @param capacity - the capacity for the throttle
     */
    public GasLimitBucketThrottle(long capacity) {
        this.bucket = new DiscreteLeakyBucket(capacity);
    }

    /**
     * Calculates and leaks the amount of gas that should be leaked from the bucket based on the
     * amount of nanoseconds passed as input argument. Verifies whether there is enough capacity to
     * handle a transaction with the specified gas limit. Reserves the capacity needed for the
     * transaction if there is enough free space.
     *
     * @param txGasLimit - the gas limit of the transaction
     * @param elapsedNanos - the amount of time passed since the last call
     * @return true if there is enough capacity, false if the transaction should be throttled
     */
    public boolean allow(final long txGasLimit, final long elapsedNanos) {
        bucket.leak(effectiveLeak(elapsedNanos));
        if (bucket.capacityFree() >= txGasLimit) {
            bucket.useCapacity(txGasLimit);
            lastAllowedUnits += txGasLimit;
            return true;
        } else {
            return false;
        }
    }

    /**
     * Returns the percent of the throttle bucket's capacity that is used, given some number of
     * nanoseconds have elapsed since the last capacity test.
     *
     * @param givenElapsedNanos time since last test
     * @return the percent of the bucket that is used
     */
    double percentUsed(final long givenElapsedNanos) {
        final var used = bucket.capacityUsed();
        return 100.0
                * (used - Math.min(used, effectiveLeak(givenElapsedNanos)))
                / bucket.totalCapacity();
    }

    void resetLastAllowedUse() {
        lastAllowedUnits = 0;
    }

    void reclaimLastAllowedUse() {
        bucket.leak(lastAllowedUnits);
        lastAllowedUnits = 0;
    }

    /**
     * Returns an instance of the {@link DiscreteLeakyBucket} used under the hood.
     *
     * @return an instance of the {@link DiscreteLeakyBucket} used under the hood.
     */
    public DiscreteLeakyBucket bucket() {
        return bucket;
    }

    private long effectiveLeak(final long elapsedNanos) {
        if (elapsedNanos >= TIME_TO_EMPTY) {
            return bucket.totalCapacity();
        } else {
            return productWouldOverflow(elapsedNanos, bucket.totalCapacity())
                    ? Long.MAX_VALUE / TIME_TO_EMPTY
                    : elapsedNanos * bucket.totalCapacity() / TIME_TO_EMPTY;
        }
    }
}
