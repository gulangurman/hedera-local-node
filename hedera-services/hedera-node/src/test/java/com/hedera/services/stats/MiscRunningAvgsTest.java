/*
 * Copyright (C) 2020-2022 Hedera Hashgraph, LLC
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
package com.hedera.services.stats;

import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

import com.swirlds.common.metrics.RunningAverageMetric;
import com.swirlds.common.system.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MiscRunningAvgsTest {
    private static final double halfLife = 10.0;

    @Mock private Platform platform;

    @Mock private RunningAverageMetric gasPerSec;
    @Mock private RunningAverageMetric waitMs;
    @Mock private RunningAverageMetric retries;
    @Mock private RunningAverageMetric submitSizes;
    @Mock private RunningAverageMetric queueSize;
    @Mock private RunningAverageMetric hashS;
    private MiscRunningAvgs subject;

    @BeforeEach
    void setup() {
        platform = mock(Platform.class);

        subject = new MiscRunningAvgs(halfLife);
    }

    @Test
    void registersExpectedStatEntries() {
        setMocks();

        subject.registerWith(platform);

        verify(platform).addAppMetrics(gasPerSec, waitMs, retries, submitSizes, queueSize, hashS);
    }

    @Test
    void recordsToExpectedAvgs() {
        setMocks();

        subject.recordAccountLookupRetries(1);
        subject.recordAccountRetryWaitMs(2.0);
        subject.recordHandledSubmitMessageSize(3);
        subject.writeQueueSizeRecordStream(4);
        subject.hashQueueSizeRecordStream(5);
        subject.recordGasPerConsSec(6L);

        verify(retries).recordValue(1.0);
        verify(waitMs).recordValue(2.0);
        verify(submitSizes).recordValue(3.0);
        verify(queueSize).recordValue(4.0);
        verify(hashS).recordValue(5);
        verify(gasPerSec).recordValue(6L);
    }

    private void setMocks() {
        subject.setAccountLookupRetries(retries);
        subject.setAccountRetryWaitMs(waitMs);
        subject.setHandledSubmitMessageSize(submitSizes);
        subject.setWriteQueueSizeRecordStream(queueSize);
        subject.setHashQueueSizeRecordStream(hashS);
        subject.setGasPerConsSec(gasPerSec);
    }
}
