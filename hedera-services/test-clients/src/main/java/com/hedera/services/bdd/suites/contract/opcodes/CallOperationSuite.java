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
package com.hedera.services.bdd.suites.contract.opcodes;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.suites.HapiApiSuite;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CallOperationSuite extends HapiApiSuite {
    private static final Logger log = LogManager.getLogger(CallOperationSuite.class);

    public static void main(String... args) {
        new CallOperationSuite().runSuiteAsync();
    }

    @Override
    public List<HapiApiSpec> getSpecsInSuite() {
        return List.of(
                new HapiApiSpec[] {
                    callingContract(), verifiesExistence(),
                });
    }

    HapiApiSpec verifiesExistence() {
        final var contract = "CallOperationsChecker";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        final var ACCOUNT = "account";
        final var EXPECTED_BALANCE = 10;

        return defaultHapiSpec("VerifiesExistence")
                .given(
                        cryptoCreate(ACCOUNT).balance(0L),
                        uploadInitCode(contract),
                        contractCreate(contract))
                .when()
                .then(
                        contractCall(contract, "call", INVALID_ADDRESS)
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                        withOpContext(
                                (spec, opLog) -> {
                                    final var id = spec.registry().getAccountID(ACCOUNT);
                                    final var solidityAddress =
                                            HapiPropertySource.asHexedSolidityAddress(id);

                                    final var contractCall =
                                            contractCall(contract, "call", solidityAddress)
                                                    .sending(EXPECTED_BALANCE);

                                    final var balance =
                                            getAccountBalance(ACCOUNT)
                                                    .hasTinyBars(EXPECTED_BALANCE);

                                    allRunFor(spec, contractCall, balance);
                                }));
    }

    HapiApiSpec callingContract() {
        final var contract = "CallingContract";
        final var INVALID_ADDRESS = "0x0000000000000000000000000000000000123456";
        return defaultHapiSpec("CallingContract")
                .given(uploadInitCode(contract), contractCreate(contract))
                .when(
                        contractCall(contract, "setVar1", 35),
                        contractCallLocal(contract, "getVar1").logged(),
                        contractCall(contract, "callContract", INVALID_ADDRESS, 222)
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS))
                .then(contractCallLocal(contract, "getVar1").logged());
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
