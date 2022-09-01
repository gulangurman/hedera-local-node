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
package com.hedera.services.ledger;

import static com.hedera.services.ledger.properties.AccountProperty.BALANCE;
import static com.hedera.services.ledger.properties.AccountProperty.CRYPTO_ALLOWANCES;
import static com.hedera.services.ledger.properties.AccountProperty.FUNGIBLE_TOKEN_ALLOWANCES;
import static com.hedera.services.ledger.properties.AccountProperty.NUM_ASSOCIATIONS;
import static com.hedera.services.ledger.properties.AccountProperty.NUM_NFTS_OWNED;
import static com.hedera.services.ledger.properties.AccountProperty.NUM_POSITIVE_BALANCES;
import static com.hedera.services.ledger.properties.AccountProperty.NUM_TREASURY_TITLES;
import static com.hedera.services.ledger.properties.AccountProperty.USED_AUTOMATIC_ASSOCIATIONS;
import static com.hedera.services.ledger.properties.NftProperty.SPENDER;
import static com.hedera.services.state.submerkle.EntityId.MISSING_ENTITY_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.records.RecordsHistorian;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.submerkle.FcTokenAllowanceId;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.txns.crypto.AutoCreationLogic;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.EntityNum;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.lang3.tuple.Pair;

@Singleton
public class TransferLogic {
    public static final List<AccountProperty> TOKEN_TRANSFER_SIDE_EFFECTS =
            List.of(
                    NUM_POSITIVE_BALANCES,
                    NUM_ASSOCIATIONS,
                    NUM_NFTS_OWNED,
                    USED_AUTOMATIC_ASSOCIATIONS,
                    NUM_TREASURY_TITLES);

    private final TokenStore tokenStore;
    private final AutoCreationLogic autoCreationLogic;
    private final SideEffectsTracker sideEffectsTracker;
    private final RecordsHistorian recordsHistorian;
    private final GlobalDynamicProperties dynamicProperties;
    private final MerkleAccountScopedCheck scopedCheck;
    private final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger;
    private final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger;
    private final TransactionalLedger<
                    Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus>
            tokenRelsLedger;

    @Inject
    public TransferLogic(
            final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger,
            final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger,
            final TransactionalLedger<
                            Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus>
                    tokenRelsLedger,
            final TokenStore tokenStore,
            final SideEffectsTracker sideEffectsTracker,
            final GlobalDynamicProperties dynamicProperties,
            final OptionValidator validator,
            final @Nullable AutoCreationLogic autoCreationLogic,
            final RecordsHistorian recordsHistorian) {
        this.tokenStore = tokenStore;
        this.nftsLedger = nftsLedger;
        this.accountsLedger = accountsLedger;
        this.tokenRelsLedger = tokenRelsLedger;
        this.recordsHistorian = recordsHistorian;
        this.autoCreationLogic = autoCreationLogic;
        this.dynamicProperties = dynamicProperties;
        this.sideEffectsTracker = sideEffectsTracker;

        scopedCheck = new MerkleAccountScopedCheck(validator, nftsLedger);
    }

    public void doZeroSum(final List<BalanceChange> changes) {
        var validity = OK;
        var autoCreationFee = 0L;
        for (var change : changes) {
            if (change.isForHbar()) {
                if (change.hasNonEmptyAlias()) {
                    if (autoCreationLogic == null) {
                        throw new IllegalStateException(
                                "Cannot auto-create account from "
                                        + change
                                        + " with null autoCreationLogic");
                    }
                    final var result = autoCreationLogic.create(change, accountsLedger);
                    validity = result.getKey();
                    autoCreationFee += result.getValue();
                } else {
                    validity =
                            accountsLedger.validate(
                                    change.accountId(), scopedCheck.setBalanceChange(change));
                }
            } else {
                validity =
                        accountsLedger.validate(
                                change.accountId(), scopedCheck.setBalanceChange(change));

                if (validity == OK) {
                    validity = tokenStore.tryTokenChange(change);
                }
            }
            if (validity != OK) {
                break;
            }
        }

        if (validity == OK) {
            adjustBalancesAndAllowances(changes);
            if (autoCreationFee > 0) {
                payFunding(autoCreationFee);
                autoCreationLogic.submitRecordsTo(recordsHistorian);
            }
        } else {
            dropTokenChanges(sideEffectsTracker, nftsLedger, accountsLedger, tokenRelsLedger);
            if (autoCreationLogic != null && autoCreationLogic.reclaimPendingAliases()) {
                accountsLedger.undoCreations();
            }
            throw new InvalidTransactionException(validity);
        }
    }

    private void payFunding(final long autoCreationFee) {
        final var funding = dynamicProperties.fundingAccount();
        final var fundingBalance = (long) accountsLedger.get(funding, BALANCE);
        final var newFundingBalance = fundingBalance + autoCreationFee;
        accountsLedger.set(funding, BALANCE, newFundingBalance);
    }

    private void adjustBalancesAndAllowances(final List<BalanceChange> changes) {
        for (var change : changes) {
            final var accountId = change.accountId();
            if (change.isForHbar()) {
                final var newBalance = change.getNewBalance();
                accountsLedger.set(accountId, BALANCE, newBalance);
                if (change.isApprovedAllowance()) {
                    adjustCryptoAllowance(change, accountId);
                }
            } else if (change.isApprovedAllowance() && change.isForFungibleToken()) {
                adjustFungibleTokenAllowance(change, accountId);
            } else if (change.isForNft()) {
                // wipe the allowance on this uniqueToken
                nftsLedger.set(change.nftId(), SPENDER, MISSING_ENTITY_ID);
            }
        }
    }

    public static void dropTokenChanges(
            final SideEffectsTracker sideEffectsTracker,
            final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger,
            final TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger,
            final TransactionalLedger<
                            Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus>
                    tokenRelsLedger) {
        if (tokenRelsLedger.isInTransaction()) {
            tokenRelsLedger.rollback();
        }
        if (nftsLedger.isInTransaction()) {
            nftsLedger.rollback();
        }
        accountsLedger.undoChangesOfType(TOKEN_TRANSFER_SIDE_EFFECTS);
        sideEffectsTracker.resetTrackedTokenChanges();
    }

    @SuppressWarnings("unchecked")
    private void adjustCryptoAllowance(BalanceChange change, AccountID ownerID) {
        final var payerNum = EntityNum.fromAccountId(change.getPayerID());
        final var hbarAllowances =
                new TreeMap<>(
                        (Map<EntityNum, Long>) accountsLedger.get(ownerID, CRYPTO_ALLOWANCES));
        final var currentAllowance = hbarAllowances.get(payerNum);
        final var newAllowance = currentAllowance + change.getAllowanceUnits();
        if (newAllowance != 0) {
            hbarAllowances.put(payerNum, newAllowance);
        } else {
            hbarAllowances.remove(payerNum);
        }
        accountsLedger.set(ownerID, CRYPTO_ALLOWANCES, hbarAllowances);
    }

    @SuppressWarnings("unchecked")
    private void adjustFungibleTokenAllowance(final BalanceChange change, final AccountID ownerID) {
        final var allowanceId =
                FcTokenAllowanceId.from(
                        change.getToken().asEntityNum(),
                        EntityNum.fromAccountId(change.getPayerID()));
        final var fungibleAllowances =
                new TreeMap<>(
                        (Map<FcTokenAllowanceId, Long>)
                                accountsLedger.get(ownerID, FUNGIBLE_TOKEN_ALLOWANCES));
        final var currentAllowance = fungibleAllowances.get(allowanceId);
        final var newAllowance = currentAllowance + change.getAllowanceUnits();
        if (newAllowance == 0) {
            fungibleAllowances.remove(allowanceId);
        } else {
            fungibleAllowances.put(allowanceId, newAllowance);
        }
        accountsLedger.set(ownerID, FUNGIBLE_TOKEN_ALLOWANCES, fungibleAllowances);
    }
}
