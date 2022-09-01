/*
 * Copyright (C) 2022 Hedera Hashgraph, LLC
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
package com.hedera.services.ledger.interceptors;

import static com.hedera.services.ledger.properties.NftProperty.OWNER;
import static com.hedera.services.state.submerkle.EntityId.MISSING_ENTITY_ID;

import com.hedera.services.ledger.CommitInterceptor;
import com.hedera.services.ledger.EntityChangeSet;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.validation.UsageLimits;
import com.hedera.services.store.models.NftId;
import java.util.Objects;

/** Manages the "map value linked list" of each account's owned non-treasury NFTs. */
public class LinkAwareUniqueTokensCommitInterceptor
        implements CommitInterceptor<NftId, MerkleUniqueToken, NftProperty> {
    private boolean burnOrMint;

    private final UsageLimits usageLimits;
    private final UniqueTokensLinkManager uniqueTokensLinkManager;

    public LinkAwareUniqueTokensCommitInterceptor(
            final UsageLimits usageLimits, final UniqueTokensLinkManager uniqueTokensLinkManager) {
        this.usageLimits = usageLimits;
        this.uniqueTokensLinkManager = uniqueTokensLinkManager;
    }

    /** {@inheritDoc} */
    @Override
    public void preview(
            final EntityChangeSet<NftId, MerkleUniqueToken, NftProperty> pendingChanges) {
        burnOrMint = false;
        final var n = pendingChanges.size();
        if (n == 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            final var entity = pendingChanges.entity(i);
            final var changes = pendingChanges.changes(i);
            if (entity != null) {
                final var fromAccount = entity.getOwner();
                if (changes == null) {
                    burnOrMint = true;
                    if (!MISSING_ENTITY_ID.equals(fromAccount)) {
                        // Non-treasury-owned NFT wiped (or burned via a multi-stage contract
                        // operation)
                        uniqueTokensLinkManager.updateLinks(
                                fromAccount.asNum(), null, entity.getKey());
                    }
                } else if (changes.containsKey(OWNER)) {
                    final var toAccount = (EntityId) changes.get(OWNER);
                    if (!Objects.equals(fromAccount, toAccount)) {
                        // NFT owner changed (could be a treasury exit or return)
                        uniqueTokensLinkManager.updateLinks(
                                fromAccount.asNum(), toAccount.asNum(), entity.getKey());
                    }
                }
            } else if (changes != null) {
                burnOrMint = true;
                final var newOwner = (EntityId) changes.get(OWNER);
                if (!MISSING_ENTITY_ID.equals(newOwner)) {
                    // Non-treasury-owned NFT minted via a multi-stage contract operation
                    final var nftKey = pendingChanges.id(i).asEntityNumPair();
                    final var mintedNft =
                            uniqueTokensLinkManager.updateLinks(null, newOwner.asNum(), nftKey);
                    pendingChanges.cacheEntity(i, mintedNft);
                }
            }
        }
    }

    @Override
    public void postCommit() {
        if (burnOrMint) {
            usageLimits.refreshNfts();
        }
    }
}
