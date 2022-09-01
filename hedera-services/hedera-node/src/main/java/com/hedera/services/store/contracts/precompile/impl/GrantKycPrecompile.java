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
package com.hedera.services.store.contracts.precompile.impl;

import static com.hedera.services.exceptions.ValidationUtils.validateTrue;
import static com.hedera.services.store.contracts.precompile.utils.PrecompilePricingUtils.GasCostType.GRANT_KYC;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

import com.hedera.services.context.SideEffectsTracker;
import com.hedera.services.contracts.sources.EvmSigsVerifier;
import com.hedera.services.ledger.accounts.ContractAliases;
import com.hedera.services.store.contracts.WorldLedgers;
import com.hedera.services.store.contracts.precompile.InfrastructureFactory;
import com.hedera.services.store.contracts.precompile.SyntheticTxnFactory;
import com.hedera.services.store.contracts.precompile.codec.DecodingFacade;
import com.hedera.services.store.contracts.precompile.utils.PrecompilePricingUtils;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.token.GrantKycLogic;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class GrantKycPrecompile extends AbstractGrantRevokeKycPrecompile {

    public GrantKycPrecompile(
            WorldLedgers ledgers,
            DecodingFacade decoder,
            ContractAliases aliases,
            EvmSigsVerifier sigsVerifier,
            SideEffectsTracker sideEffects,
            SyntheticTxnFactory syntheticTxnFactory,
            InfrastructureFactory infrastructureFactory,
            PrecompilePricingUtils pricingUtils) {
        super(
                ledgers,
                decoder,
                aliases,
                sigsVerifier,
                sideEffects,
                syntheticTxnFactory,
                infrastructureFactory,
                pricingUtils);
    }

    @Override
    public void run(MessageFrame frame) {
        initialise(frame);

        final var grantKycLogic = infrastructureFactory.newGrantKycLogic(accountStore, tokenStore);
        executeForGrant(grantKycLogic, tokenId, accountId);
    }

    @Override
    public TransactionBody.Builder body(Bytes input, UnaryOperator<byte[]> aliasResolver) {
        grantRevokeOp = decoder.decodeGrantTokenKyc(input, aliasResolver);
        transactionBody = syntheticTxnFactory.createGrantKyc(grantRevokeOp);
        return transactionBody;
    }

    @Override
    public long getMinimumFeeInTinybars(Timestamp consensusTime) {
        Objects.requireNonNull(grantRevokeOp);
        return pricingUtils.getMinimumPriceInTinybars(GRANT_KYC, consensusTime);
    }

    private void executeForGrant(GrantKycLogic grantKycLogic, Id tokenId, Id accountId) {
        validateLogic(grantKycLogic.validate(transactionBody.build()));
        grantKycLogic.grantKyc(tokenId, accountId);
    }

    private void validateLogic(ResponseCodeEnum validity) {
        validateTrue(validity == OK, validity);
    }
}
