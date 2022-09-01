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
package com.hedera.services.bdd.suites.throttling;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.resetToDefault;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

public class GasLimitThrottlingSuite extends HapiApiSuite {

    private static final Logger log = LogManager.getLogger(GasLimitThrottlingSuite.class);
    private static final String CONTRACT = "Benchmark";

    @Override
    public List<HapiApiSpec> getSpecsInSuite() {
        return List.of(new HapiApiSpec[] {txsUnderGasLimitAllowed(), txOverGasLimitThrottled()});
    }

    public static void main(String... args) {
        new GasLimitThrottlingSuite().runSuiteSync();
    }

    private HapiApiSpec txsUnderGasLimitAllowed() {
        final var NUM_CALLS = 10;
        return defaultHapiSpec("TXsUnderGasLimitAllowed")
                .given(
                        overridingTwo(
                                "contracts.throttle.throttleByGas", "true",
                                "contracts.maxGasPerSec", "10000000"))
                .when(
                        /* we need the payer account, see SystemPrecheck IS_THROTTLE_EXEMPT */
                        cryptoCreate("payerAccount").balance(ONE_MILLION_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).payingWith("payerAccount"))
                .then(
                        UtilVerbs.inParallel(
                                asOpArray(
                                        NUM_CALLS,
                                        i ->
                                                contractCall(
                                                                CONTRACT,
                                                                "twoSSTOREs",
                                                                Bytes.fromHexString("0x05")
                                                                        .toArray())
                                                        .gas(100_000)
                                                        .payingWith("payerAccount")
                                                        .hasKnownStatusFrom(SUCCESS, OK))),
                        UtilVerbs.sleepFor(1000),
                        contractCall(CONTRACT, "twoSSTOREs", Bytes.fromHexString("0x06").toArray())
                                .gas(1_000_000L)
                                .payingWith("payerAccount")
                                .hasKnownStatusFrom(SUCCESS, OK),
                        resetToDefault(
                                "contracts.throttle.throttleByGas", "contracts.maxGasPerSec"));
    }

    private HapiApiSpec txOverGasLimitThrottled() {

        return defaultHapiSpec("TXOverGasLimitThrottled")
                .given(
                        overridingTwo(
                                "contracts.throttle.throttleByGas", "true",
                                "contracts.maxGasPerSec", "1000001"))
                .when(
                        cryptoCreate("payerAccount").balance(ONE_MILLION_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT))
                .then(
                        contractCall(CONTRACT, "twoSSTOREs", Bytes.fromHexString("0x05").toArray())
                                .gas(1_000_001)
                                .payingWith("payerAccount")
                                .hasPrecheck(BUSY),
                        resetToDefault(
                                "contracts.throttle.throttleByGas", "contracts.maxGasPerSec"));
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }
}
