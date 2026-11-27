package com.limelight.computers;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.SystemClock;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.ComputerDetails.AddressTuple;
import com.limelight.nvstream.http.PairingManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ComputerRoutesTest {
    private ComputerManagerService service;
    private ConnectivityManager connectivity;
    private Network lan;

    @Before public void setUp() throws Exception {
        service = spy(Robolectric.buildService(ComputerManagerService.class).get());
        connectivity = mock(ConnectivityManager.class);
        doReturn(connectivity).when(service).getSystemService(Context.CONNECTIVITY_SERVICE);
        lan = mock(Network.class);
        when(lan.toString()).thenReturn("wifi");
        when(connectivity.getAllNetworks()).thenReturn(new Network[]{lan});
        NetworkCapabilities caps = mock(NetworkCapabilities.class);
        when(connectivity.getNetworkCapabilities(lan)).thenReturn(caps);
        LinkProperties links = mock(LinkProperties.class);
        RouteInfo route = ReflectionHelpers.callConstructor(RouteInfo.class,
                ReflectionHelpers.ClassParameter.from(IpPrefix.class,
                        new IpPrefix(java.net.InetAddress.getByName("192.168.4.0"), 22)),
                ReflectionHelpers.ClassParameter.from(java.net.InetAddress.class, java.net.InetAddress.getByName("0.0.0.0")),
                ReflectionHelpers.ClassParameter.from(String.class, "wlan0"));
        when(links.getRoutes()).thenReturn(java.util.Collections.singletonList(route));
        when(connectivity.getLinkProperties(lan)).thenReturn(links);
        when(connectivity.bindProcessToNetwork(lan)).thenReturn(true);
        doReturn("203.0.113.10").when(service).findExternalAddress();
    }

    private ComputerDetails host() {
        ComputerDetails c = new ComputerDetails();
        c.uuid = "host";
        c.name = "torre";
        c.localAddress = new AddressTuple("192.168.4.27", 47989);
        c.activeAddress = c.localAddress;
        c.state = ComputerDetails.State.ONLINE;
        c.pairState = PairingManager.PairState.PAIRED;
        return c;
    }

    @Test public void normalWifiDiscoversPublicIpAndRefreshesAfterFiveMinutes() {
        ComputerDetails c = host();
        service.refreshExternalAddress(c);
        assertEquals(new AddressTuple("203.0.113.10", 47989), c.remoteAddress);
        service.refreshExternalAddress(c);
        verify(service, times(1)).findExternalAddress();
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(6));
        doReturn("203.0.113.11").when(service).findExternalAddress();
        service.refreshExternalAddress(c);
        assertEquals(new AddressTuple("203.0.113.11", 47989), c.remoteAddress);
        verify(connectivity, times(2)).bindProcessToNetwork(lan);
        verify(connectivity, times(2)).bindProcessToNetwork(null);
    }

    @Test public void failedStunKeepsSavedPublicIpAndRetries() {
        ComputerDetails c = host();
        c.remoteAddress = new AddressTuple("203.0.113.9", 47989);
        doReturn(null).when(service).findExternalAddress();
        service.refreshExternalAddress(c);
        assertEquals("203.0.113.9", c.remoteAddress.address);
        service.refreshExternalAddress(c);
        verify(service, times(1)).findExternalAddress();
        SystemClock.setCurrentTimeMillis(System.currentTimeMillis() + 31000);
        service.refreshExternalAddress(c);
        verify(service, times(2)).findExternalAddress();
        verify(connectivity, times(2)).bindProcessToNetwork(null);
    }

    @Test public void vpnMustUseOnLinkWifiAndFailedBindingNeverRunsStun() {
        Network vpn = mock(Network.class);
        NetworkCapabilities caps = mock(NetworkCapabilities.class);
        when(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).thenReturn(true);
        when(connectivity.getAllNetworks()).thenReturn(new Network[]{vpn, lan});
        when(connectivity.getNetworkCapabilities(vpn)).thenReturn(caps);
        when(connectivity.getLinkProperties(vpn)).thenReturn(new LinkProperties());
        when(connectivity.bindProcessToNetwork(lan)).thenReturn(false);
        service.refreshExternalAddress(host());
        verify(connectivity).bindProcessToNetwork(lan);
        verify(service, never()).findExternalAddress();
        verify(connectivity, never()).bindProcessToNetwork(vpn);
        verify(connectivity, never()).bindProcessToNetwork(null);
    }

    @Test public void publicAndTailscaleRoutesNeverLearnThePhonesPublicIp() {
        ComputerDetails c = host();
        for (String address : new String[]{"203.0.113.10", "100.97.8.59"}) {
            c.activeAddress = new AddressTuple(address, 47989);
            service.refreshExternalAddress(c);
        }
        verify(service, never()).findExternalAddress();
    }

    @Test public void aSavedLanOnlyHostLearnsAndPersistsPublicAddressOnRegularPoll() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("computers4.db");
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        ComputerDetails c = host();
        db.updateComputer(c);
        ReflectionHelpers.setField(service, "dbManager", db);
        ((AtomicInteger)ReflectionHelpers.getField(service, "dbRefCount")).set(1);
        doAnswer(invocation -> host()).when(service).tryPollIp(any(), any());
        boolean result = ReflectionHelpers.callInstanceMethod(service, "runPoll",
                ReflectionHelpers.ClassParameter.from(ComputerDetails.class, c),
                ReflectionHelpers.ClassParameter.from(boolean.class, false),
                ReflectionHelpers.ClassParameter.from(int.class, 0));
        assertTrue(result);
        assertEquals(new AddressTuple("203.0.113.10", 47989), db.getComputerByUUID(c.uuid).remoteAddress);
        db.close();
    }

    @Test public void addMagicDnsMergesByUuidAndKeepsLanPublicAndPairing() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("computers4.db");
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        ComputerDetails saved = host();
        saved.remoteAddress = new AddressTuple("203.0.113.10", 47989);
        db.updateComputer(saved);
        ReflectionHelpers.setField(service, "dbManager", db);
        ((AtomicInteger)ReflectionHelpers.getField(service, "dbRefCount")).set(1);
        ReflectionHelpers.callInstanceMethod(service, "addTuple",
                ReflectionHelpers.ClassParameter.from(ComputerDetails.class, saved));
        doAnswer(invocation -> {
            ComputerDetails response = host();
            response.localAddress = new AddressTuple("100.97.8.59", 47989);
            response.activeAddressIsTailscale = true;
            return response;
        }).when(service).tryPollIp(any(), any());
        ComputerDetails manual = new ComputerDetails();
        manual.manualAddress = new AddressTuple("torre", 47989);
        assertTrue(service.addComputerBlocking(manual));
        ComputerDetails result = db.getComputerByUUID(saved.uuid);
        assertEquals(1, db.getAllComputers().size());
        assertEquals(saved.localAddress, result.localAddress);
        assertEquals(saved.remoteAddress, result.remoteAddress);
        assertEquals(manual.manualAddress, result.manualAddress);
        assertEquals(saved.localAddress, manual.localAddress);
        assertTrue(manual.activeAddressIsTailscale);
        assertEquals(PairingManager.PairState.PAIRED, manual.pairState);
        db.close();
    }

    @Test public void fasterPublicResponseDoesNotBeatLocalOrManual() throws Exception {
        ComputerDetails c = host();
        c.manualAddress = new AddressTuple("100.97.8.59", 47989);
        c.remoteAddress = new AddressTuple("203.0.113.10", 47989);
        for (boolean localAvailable : new boolean[]{true, false}) {
            CountDownLatch publicAnswered = new CountDownLatch(1);
            doAnswer(invocation -> {
                AddressTuple address = invocation.getArgument(1);
                if (address.equals(c.remoteAddress)) publicAnswered.countDown();
                else assertTrue(publicAnswered.await(5, TimeUnit.SECONDS));
                return address.equals(c.localAddress) && !localAvailable ? null : host();
            }).when(service).tryPollIp(any(), any());
            assertEquals(localAvailable ? c.localAddress : c.manualAddress,
                    service.parallelPollPc(c).activeAddress);
        }
    }

    @Test public void publicFallbackWorksWhenLocalAndManualFail() throws Exception {
        ComputerDetails c = host();
        c.manualAddress = new AddressTuple("100.97.8.59", 47989);
        c.remoteAddress = new AddressTuple("203.0.113.10", 47989);
        doAnswer(invocation -> c.remoteAddress.equals(invocation.getArgument(1)) ? host() : null)
                .when(service).tryPollIp(any(), any());
        assertEquals(c.remoteAddress, service.parallelPollPc(c).activeAddress);
    }
}
