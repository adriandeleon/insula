package com.insula.net;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/**
 * Whether this machine has a network at all.
 *
 * <p>Answered from the local interface list, not by contacting anybody. Insula is an offline
 * reader: an app whose whole point is that it does not need the internet has no business
 * periodically phoning a server to ask whether the internet is there. Enumerating interfaces costs
 * nothing, sends nothing, and tells the truth about the case that actually matters — the laptop on
 * a train with the wifi off.
 *
 * <p>What it cannot tell you is whether a reachable network can reach <em>kiwix.org</em>: a captive
 * portal, or a firewall, both look like a live interface. That gap is closed from the other end —
 * a request that fails reports it, and the indicator follows.
 */
public final class Reachability {

    private Reachability() {}

    /** True when any non-loopback interface is up. */
    public static boolean hasNetwork() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic.isUp()
                        && !nic.isLoopback()
                        && !nic.isVirtual()
                        && nic.getInetAddresses().hasMoreElements()) {
                    return true;
                }
            }
            return false;
        } catch (SocketException | RuntimeException e) {
            // Unable to ask is not the same as offline, and guessing "offline" would disable the
            // catalog on a machine that is perfectly well connected.
            return true;
        }
    }
}
