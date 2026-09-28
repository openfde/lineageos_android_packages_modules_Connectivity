/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.ethernet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.net.IDnsResolver;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.ProxyInfo;
import android.net.ResolverParamsParcel;
import android.net.StaticIpConfiguration;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.io.File;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class EthernetConfigStoreTest {
    private static final LinkAddress LINKADDR = new LinkAddress("192.168.1.100/25");
    private static final InetAddress GATEWAY = InetAddresses.parseNumericAddress("192.168.1.1");
    private static final InetAddress DNS1 = InetAddresses.parseNumericAddress("8.8.8.8");
    private static final InetAddress DNS2 = InetAddresses.parseNumericAddress("8.8.4.4");
    private static final StaticIpConfiguration STATIC_IP_CONFIG =
            new StaticIpConfiguration.Builder()
                    .setIpAddress(LINKADDR)
                    .setGateway(GATEWAY)
                    .setDnsServers(new ArrayList<InetAddress>(
                            List.of(DNS1, DNS2)))
                    .build();
    private static final ProxyInfo PROXY_INFO = ProxyInfo.buildDirectProxy("test", 8888);
    private static final IpConfiguration APEX_IP_CONFIG =
            new IpConfiguration(IpAssignment.DHCP, ProxySettings.NONE, null, null);
    private static final IpConfiguration LEGACY_IP_CONFIG =
            new IpConfiguration(IpAssignment.STATIC, ProxySettings.STATIC, STATIC_IP_CONFIG,
                    PROXY_INFO);

    private EthernetConfigStore mEthernetConfigStore;
    private File mApexTestDir;
    private File mLegacyTestDir;
    private File mApexConfigFile;
    private File mLegacyConfigFile;

    private void createTestDir() {
        final Context context = InstrumentationRegistry.getContext();
        final File baseDir = context.getFilesDir();
        mApexTestDir = new File(baseDir.getPath() + "/apex");
        mApexTestDir.mkdirs();

        mLegacyTestDir = new File(baseDir.getPath() + "/legacy");
        mLegacyTestDir.mkdirs();
    }

    @Before
    public void setUp() {
        createTestDir();
        mEthernetConfigStore = new EthernetConfigStore();
    }

    @After
    public void tearDown() {
        mApexTestDir.delete();
        mLegacyTestDir.delete();
    }

    private void assertConfigFileExist(final String filepath) {
        assertTrue(new File(filepath).exists());
    }

    /** Wait for the delayed write operation completes. */
    private void waitForMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            fail("Thread was interrupted");
        }
    }

    @Test
    public void testWriteIpConfigToApexFilePathAndRead() throws Exception {
        // Write the config file to the apex file path, pretend the config file exits and
        // check if IP config should be read from apex file path.
        mApexConfigFile = new File(mApexTestDir.getPath(), "test.txt");
        mEthernetConfigStore.write("eth0", APEX_IP_CONFIG, mApexConfigFile.getPath());
        waitForMs(50);

        mEthernetConfigStore.read(mApexTestDir.getPath(), mLegacyTestDir.getPath(), "/test.txt");
        final ArrayMap<String, IpConfiguration> ipConfigurations =
                mEthernetConfigStore.getIpConfigurations();
        assertEquals(APEX_IP_CONFIG, ipConfigurations.get("eth0"));

        mApexConfigFile.delete();
    }

    @Test
    public void testWriteIpConfigToLegacyFilePathAndRead() throws Exception {
        // Write the config file to the legacy file path, pretend the config file exits and
        // check if IP config should be read from legacy file path.
        mLegacyConfigFile = new File(mLegacyTestDir, "test.txt");
        mEthernetConfigStore.write("0", LEGACY_IP_CONFIG, mLegacyConfigFile.getPath());
        waitForMs(50);

        mEthernetConfigStore.read(mApexTestDir.getPath(), mLegacyTestDir.getPath(), "/test.txt");
        final ArrayMap<String, IpConfiguration> ipConfigurations =
                mEthernetConfigStore.getIpConfigurations();
        assertEquals(LEGACY_IP_CONFIG, ipConfigurations.get("0"));

        // Check the same config file in apex file path is created.
        assertConfigFileExist(mApexTestDir.getPath() + "/test.txt");

        final File apexConfigFile = new File(mApexTestDir.getPath() + "/test.txt");
        apexConfigFile.delete();
        mLegacyConfigFile.delete();
    }

    @Test
    public void testSetDnsServersPersistsAndPushesToResolver() throws Exception {
        final IDnsResolver resolver = mock(IDnsResolver.class);
        final EthernetConfigStore store = new EthernetConfigStore(null, resolver);
        final File configFile = new File(mApexTestDir.getPath(), "test.txt");
        store.write("eth0", LEGACY_IP_CONFIG, configFile.getPath());
        waitForMs(50);

        final List<InetAddress> newDns =
                List.of(InetAddresses.parseNumericAddress("1.1.1.1"));
        assertTrue(store.setDnsServers("eth0", 42, newDns, configFile.getPath()));

        // The new servers are persisted into the stored configuration.
        final ArrayMap<String, IpConfiguration> configs = store.getIpConfigurations();
        assertEquals(newDns, configs.get("eth0").getStaticIpConfiguration().dnsServers);

        // And pushed to the resolver with the given netId and DnsManager-compatible defaults.
        final ArgumentCaptor<ResolverParamsParcel> captor =
                ArgumentCaptor.forClass(ResolverParamsParcel.class);
        verify(resolver).setResolverConfiguration(captor.capture());
        final ResolverParamsParcel parcel = captor.getValue();
        assertEquals(42, parcel.netId);
        assertEquals(1, parcel.servers.length);
        assertEquals("1.1.1.1", parcel.servers[0]);
        assertEquals("", parcel.tlsName);
        assertEquals(0, parcel.tlsServers.length);
        assertEquals(1800, parcel.sampleValiditySeconds);
        assertEquals(25, parcel.successThreshold);
        assertEquals(8, parcel.minSamples);
        assertEquals(64, parcel.maxSamples);

        configFile.delete();
    }

    @Test
    public void testSetDnsServersNotConnectedOnlyPersists() throws Exception {
        final IDnsResolver resolver = mock(IDnsResolver.class);
        final EthernetConfigStore store = new EthernetConfigStore(null, resolver);
        final File configFile = new File(mApexTestDir.getPath(), "test.txt");
        store.write("eth0", LEGACY_IP_CONFIG, configFile.getPath());
        waitForMs(50);

        final List<InetAddress> newDns =
                List.of(InetAddresses.parseNumericAddress("1.1.1.1"));
        assertTrue(store.setDnsServers("eth0", -1, newDns, configFile.getPath()));

        // No binder call is made when the interface has no connected network.
        verify(resolver, never()).setResolverConfiguration(any());
        assertEquals(newDns,
                store.getIpConfigurations().get("eth0").getStaticIpConfiguration().dnsServers);

        configFile.delete();
    }

    @Test
    public void testSetDnsServersRejectsEmptyListAndUnknownIface() throws Exception {
        final IDnsResolver resolver = mock(IDnsResolver.class);
        final EthernetConfigStore store = new EthernetConfigStore(null, resolver);
        final File configFile = new File(mApexTestDir.getPath(), "test.txt");
        store.write("eth0", LEGACY_IP_CONFIG, configFile.getPath());
        waitForMs(50);

        assertFalse(store.setDnsServers("eth0", 42, List.of(), configFile.getPath()));
        assertFalse(store.setDnsServers("eth1", 42,
                List.of(InetAddresses.parseNumericAddress("1.1.1.1")), configFile.getPath()));
        verify(resolver, never()).setResolverConfiguration(any());

        configFile.delete();
    }

    @Test
    public void testGetDnsServersFromResolverReturnsServers() throws Exception {
        final IDnsResolver resolver = mock(IDnsResolver.class);
        doAnswer(invocation -> {
            final String[] servers = invocation.getArgument(1);
            servers[0] = "1.1.1.1";
            servers[1] = "8.8.8.8";
            return null;
        }).when(resolver).getResolverInfo(anyInt(), any(), any(), any(), any(), any(), any());

        final EthernetConfigStore store = new EthernetConfigStore(null, resolver);
        assertEquals(List.of("1.1.1.1", "8.8.8.8"), store.getDnsServersFromResolver(42));
    }

    @Test
    public void testGetDnsServersFromResolverReturnsEmptyOnExceptionOrUnavailable() throws Exception {
        final IDnsResolver resolver = mock(IDnsResolver.class);
        doThrow(new RemoteException()).when(resolver)
                .getResolverInfo(anyInt(), any(), any(), any(), any(), any(), any());
        final EthernetConfigStore store = new EthernetConfigStore(null, resolver);
        assertTrue(store.getDnsServersFromResolver(42).isEmpty());

        doThrow(new ServiceSpecificException(1)).when(resolver)
                .getResolverInfo(anyInt(), any(), any(), any(), any(), any(), any());
        assertTrue(store.getDnsServersFromResolver(42).isEmpty());

        final EthernetConfigStore unavailableStore = new EthernetConfigStore(null, null);
        assertTrue(unavailableStore.getDnsServersFromResolver(42).isEmpty());
    }

    @Test
    public void testGetPersistedDnsServersReturnsEmptyForUnknownIface() throws Exception {
        final EthernetConfigStore store = new EthernetConfigStore();
        final File configFile = new File(mApexTestDir.getPath(), "test.txt");
        store.write("eth0", LEGACY_IP_CONFIG, configFile.getPath());
        waitForMs(50);

        assertTrue(store.getPersistedDnsServers("eth1").isEmpty());
        assertEquals(List.of("8.8.8.8", "8.8.4.4"), store.getPersistedDnsServers("eth0"));
        assertNotNull(store.getPersistedDnsServers("eth0"));
        configFile.delete();
    }
}
