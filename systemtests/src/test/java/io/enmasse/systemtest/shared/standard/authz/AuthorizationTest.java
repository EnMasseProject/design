/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.shared.standard.authz;

import io.enmasse.systemtest.annotations.DefaultMessaging;
import io.enmasse.systemtest.bases.authz.AuthorizationTestBase;
import io.enmasse.systemtest.model.addressspace.AddressSpacePlans;
import io.enmasse.systemtest.model.addressspace.AddressSpaceType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.enmasse.systemtest.TestTag.NON_PR;
import static io.enmasse.systemtest.TestTag.SHARED;

@Tag(SHARED)
@DefaultMessaging(type = AddressSpaceType.STANDARD, plan = AddressSpacePlans.STANDARD_UNLIMITED)
class AuthorizationTest extends AuthorizationTestBase {

    @Test
    void testSendAuthz() throws Exception {
        doTestSendAuthz();
    }

    @Test
    void testReceiveAuthz() throws Exception {
        doTestReceiveAuthz();
    }

    @Test
    void testUserPermissionAfterRemoveAuthz() throws Exception {
        doTestUserPermissionAfterRemoveAuthz();
    }

    @Test
    @Tag(NON_PR)
    void testSendAuthzWithWIldcards() throws Exception {
        doTestSendAuthzWithWIldcards();
    }

    @Test
    @Tag(NON_PR)
    void testReceiveAuthzWithWIldcards() throws Exception {
        doTestReceiveAuthzWithWIldcards();
    }
}
