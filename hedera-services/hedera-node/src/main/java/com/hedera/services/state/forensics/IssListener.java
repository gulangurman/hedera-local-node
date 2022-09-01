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
package com.hedera.services.state.forensics;

import com.hedera.services.ServicesState;
import com.hedera.services.context.domain.trackers.IssEventInfo;
import com.swirlds.common.InvalidSignedStateListener;
import com.swirlds.common.system.NodeId;
import com.swirlds.common.system.Platform;
import com.swirlds.common.system.SwirldState;
import com.swirlds.common.system.address.AddressBook;
import com.swirlds.common.system.events.PlatformEvent;
import com.swirlds.common.utility.CommonUtils;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Singleton
public class IssListener implements InvalidSignedStateListener {
    private static final Logger log = LogManager.getLogger(IssListener.class);

    static final String ISS_ERROR_MSG_PATTERN =
            "In round %d, node %d received a signed state from node %d with a signature different"
                    + " than %s on %s";
    static final String ISS_FALLBACK_ERROR_MSG_PATTERN =
            "In round %d, node %s received a signed state from node %s differing from its local "
                    + "signed state; could not provide all details";

    private final IssEventInfo issEventInfo;

    @Inject
    public IssListener(final IssEventInfo issEventInfo) {
        this.issEventInfo = issEventInfo;
    }

    @Override
    public void notifyError(
            Platform platform,
            AddressBook addressBook,
            SwirldState swirldsState,
            PlatformEvent[] events,
            NodeId self,
            NodeId other,
            long round,
            Instant consensusTime,
            long numConsEvents,
            byte[] sig,
            byte[] hash) {
        try {
            ServicesState issState = (ServicesState) swirldsState;
            issEventInfo.alert(consensusTime);
            if (issEventInfo.shouldLogThisRound()) {
                issEventInfo.decrementRoundsToLog();
                var msg =
                        String.format(
                                ISS_ERROR_MSG_PATTERN,
                                round,
                                self.getId(),
                                other.getId(),
                                CommonUtils.hex(sig),
                                CommonUtils.hex(hash));
                log.error(msg);
                issState.logSummary();
            }
        } catch (Exception any) {
            String fallbackMsg = String.format(ISS_FALLBACK_ERROR_MSG_PATTERN, round, self, other);
            log.warn(fallbackMsg, any);
        }
    }
}
