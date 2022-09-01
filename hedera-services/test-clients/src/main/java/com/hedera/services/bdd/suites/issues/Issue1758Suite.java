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
package com.hedera.services.bdd.suites.issues;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultFailingHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Issue1758Suite extends HapiApiSuite {
    private static final Logger log = LogManager.getLogger(Issue1758Suite.class);

    public static void main(String... args) {
        new Issue1758Suite().runSuiteSync();
    }

    @Override
    public List<HapiApiSpec> getSpecsInSuite() {
        return List.of(allowsCryptoCreatePayerToHaveLessThanTwiceFee());
    }

    public static HapiApiSpec allowsCryptoCreatePayerToHaveLessThanTwiceFee() {
        return defaultFailingHapiSpec("AllowsCryptoCreatePayerToHaveLessThanTwiceFee")
                .given(
                        cryptoCreate("payer").via("referenceTxn").balance(0L),
                        UtilVerbs.withOpContext(
                                (spec, ctxLog) -> {
                                    HapiGetTxnRecord subOp = getTxnRecord("referenceTxn");
                                    allRunFor(spec, subOp);
                                    TransactionRecord record = subOp.getResponseRecord();
                                    long fee = record.getTransactionFee();
                                    spec.registry().saveAmount("balance", fee * 2 - 1);
                                }))
                .when(
                        cryptoTransfer(
                                tinyBarsFromTo(
                                        GENESIS,
                                        "payer",
                                        spec -> spec.registry().getAmount("balance"))))
                .then(cryptoCreate("irrelevant").balance(0L).payingWith("payer"));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
