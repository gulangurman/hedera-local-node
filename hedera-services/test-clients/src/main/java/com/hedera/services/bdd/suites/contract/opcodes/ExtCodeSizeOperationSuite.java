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
import static com.hedera.services.bdd.spec.HapiPropertySource.asHexedSolidityAddress;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.isLiteralResult;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractBytecode;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.FunctionType.FUNCTION;
import static com.hedera.services.bdd.suites.contract.Utils.getABIFor;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SOLIDITY_ADDRESS;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.suites.HapiApiSuite;
import java.math.BigInteger;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;

public class ExtCodeSizeOperationSuite extends HapiApiSuite {
    private static final Logger LOG = LogManager.getLogger(ExtCodeSizeOperationSuite.class);

    public static void main(String[] args) {
        new ExtCodeSizeOperationSuite().runSuiteAsync();
    }

    @Override
    public List<HapiApiSpec> getSpecsInSuite() {
        return List.of(verifiesExistence());
    }

    @SuppressWarnings("java:S5960")
    HapiApiSpec verifiesExistence() {
        final var contract = "ExtCodeOperationsChecker";
        final var invalidAddress = "0x0000000000000000000000000000000000123456";
        final var sizeOf = "sizeOf";

        return defaultHapiSpec("VerifiesExistence")
                .given(uploadInitCode(contract), contractCreate(contract))
                .when()
                .then(
                        contractCall(contract, sizeOf, invalidAddress)
                                .hasKnownStatus(INVALID_SOLIDITY_ADDRESS),
                        contractCallLocal(contract, sizeOf, invalidAddress)
                                .hasAnswerOnlyPrecheck(INVALID_SOLIDITY_ADDRESS),
                        withOpContext(
                                (spec, opLog) -> {
                                    final var accountID =
                                            spec.registry().getAccountID(DEFAULT_PAYER);
                                    final var contractID = spec.registry().getContractId(contract);
                                    final var accountSolidityAddress =
                                            asHexedSolidityAddress(accountID);
                                    final var contractAddress = asHexedSolidityAddress(contractID);

                                    final var call =
                                            contractCall(contract, sizeOf, accountSolidityAddress)
                                                    .via("callRecord");

                                    final var callRecord =
                                            getTxnRecord("callRecord")
                                                    .hasPriority(
                                                            recordWith()
                                                                    .contractCallResult(
                                                                            resultWith()
                                                                                    .resultThruAbi(
                                                                                            getABIFor(
                                                                                                    FUNCTION,
                                                                                                    sizeOf,
                                                                                                    contract),
                                                                                            isLiteralResult(
                                                                                                    new Object
                                                                                                            [] {
                                                                                                        BigInteger
                                                                                                                .valueOf(
                                                                                                                        0)
                                                                                                    }))));

                                    final var accountCodeSizeCallLocal =
                                            contractCallLocal(
                                                            contract,
                                                            sizeOf,
                                                            accountSolidityAddress)
                                                    .has(
                                                            ContractFnResultAsserts.resultWith()
                                                                    .resultThruAbi(
                                                                            getABIFor(
                                                                                    FUNCTION,
                                                                                    sizeOf,
                                                                                    contract),
                                                                            ContractFnResultAsserts
                                                                                    .isLiteralResult(
                                                                                            new Object
                                                                                                    [] {
                                                                                                BigInteger
                                                                                                        .valueOf(
                                                                                                                0)
                                                                                            })));

                                    final var getBytecode =
                                            getContractBytecode(contract)
                                                    .saveResultTo("contractBytecode");

                                    final var contractCodeSize =
                                            contractCallLocal(contract, sizeOf, contractAddress)
                                                    .saveResultTo("contractCodeSize");

                                    allRunFor(
                                            spec,
                                            call,
                                            callRecord,
                                            accountCodeSizeCallLocal,
                                            getBytecode,
                                            contractCodeSize);

                                    final var contractCodeSizeResult =
                                            spec.registry().getBytes("contractCodeSize");
                                    final var contractBytecode =
                                            spec.registry().getBytes("contractBytecode");

                                    Assertions.assertEquals(
                                            BigInteger.valueOf(contractBytecode.length),
                                            new BigInteger(contractCodeSizeResult));
                                }));
    }

    @Override
    protected Logger getResultsLogger() {
        return LOG;
    }
}
