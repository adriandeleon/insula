package com.insula.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two kinds of offline, which must not be collapsed into one. */
class NetworkStateTest {

    @Test
    void askingToStayOfflineIsNotOverriddenByAWorkingConnection() {
        assertEquals(NetworkState.Status.OFFLINE_BY_CHOICE, NetworkState.of(true, true));
    }

    @Test
    void withoutAChoiceTheConnectionDecides() {
        assertEquals(NetworkState.Status.ONLINE, NetworkState.of(true, false));
        assertEquals(NetworkState.Status.OFFLINE, NetworkState.of(false, false));
    }

    @Test
    void onlyOnlineMayMakeARequest() {
        assertTrue(NetworkState.mayUseNetwork(NetworkState.Status.ONLINE));
        assertFalse(NetworkState.mayUseNetwork(NetworkState.Status.OFFLINE));
        assertFalse(NetworkState.mayUseNetwork(NetworkState.Status.OFFLINE_BY_CHOICE));
    }

    @Test
    void aRefusalSaysWhichOfTheTwoOfflinesItWas() {
        // "Offline" on its own leaves the reader looking for a network problem they do not have.
        assertTrue(NetworkState.refusal(NetworkState.Status.OFFLINE_BY_CHOICE, "refreshing the catalog")
                .contains("set to work offline"));
        assertTrue(NetworkState.refusal(NetworkState.Status.OFFLINE, "refreshing the catalog")
                .contains("No connection"));
        assertEquals("", NetworkState.refusal(NetworkState.Status.ONLINE, "anything"));
    }

    @Test
    void theLabelsAreDistinct() {
        assertEquals("Online", NetworkState.label(NetworkState.Status.ONLINE));
        assertEquals("Offline", NetworkState.label(NetworkState.Status.OFFLINE_BY_CHOICE));
        assertEquals("No connection", NetworkState.label(NetworkState.Status.OFFLINE));
    }
}
