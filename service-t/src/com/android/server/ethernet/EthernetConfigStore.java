/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.net.module.util.DeviceConfigUtils.TETHERING_MODULE_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ApexEnvironment;
import android.content.Context;
import android.net.DnsResolverServiceManager;
import android.net.IDnsResolver;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.NetworkCapabilities;
import android.net.NetworkUtils;
import android.net.ResolverParamsParcel;
import android.net.StaticIpConfiguration;
import android.os.Environment;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.net.IpConfigStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.openfde.Net;

/**
 * This class provides an API to store and manage Ethernet network configuration.
 */
public class EthernetConfigStore {
    private static final String TAG = EthernetConfigStore.class.getSimpleName();
    private static final String CONFIG_FILE = "ipconfig.txt";
    private static final String FILE_PATH = "/misc/ethernet/";
    private static final String LEGACY_IP_CONFIG_FILE_PATH = Environment.getDataDirectory()
            + FILE_PATH;
    private static final String APEX_IP_CONFIG_FILE_PATH = ApexEnvironment.getApexEnvironment(
            TETHERING_MODULE_NAME).getDeviceProtectedDataDir() + FILE_PATH;

    /* Defaults for resolver parameters, keep in sync with DnsManager. */
    private static final int DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS = 1800;
    private static final int DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT = 25;
    private static final int DNS_RESOLVER_DEFAULT_MIN_SAMPLES = 8;
    private static final int DNS_RESOLVER_DEFAULT_MAX_SAMPLES = 64;
    private static final int DNS_RESOLVER_SERVER_QUERY_SIZE = 64;

    private IpConfigStore mStore = new IpConfigStore();
    private final ArrayMap<String, IpConfiguration> mIpConfigurations;
    private final Object mSync = new Object();
    @Nullable private final Context mContext;
    @Nullable private volatile IDnsResolver mDnsResolver;

    public EthernetConfigStore() {
        this(null, null);
    }

    public EthernetConfigStore(@Nullable final Context context) {
        this(context, null);
    }

    @VisibleForTesting
    EthernetConfigStore(@Nullable final Context context, @Nullable final IDnsResolver resolver) {
        mIpConfigurations = new ArrayMap<>(0);
        mContext = context;
        mDnsResolver = resolver;
    }

    private static boolean doesConfigFileExist(final String filepath) {
        return new File(filepath).exists();
    }

    private void writeLegacyIpConfigToApexPath(final String newFilePath, final String oldFilePath,
            final String filename) {
        final File directory = new File(newFilePath);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        // Write the legacy IP config to the apex file path.
        FileOutputStream fos = null;
        final AtomicFile dst = new AtomicFile(new File(newFilePath + filename));
        final AtomicFile src = new AtomicFile(new File(oldFilePath + filename));
        try {
            final byte[] raw = src.readFully();
            if (raw.length > 0) {
                fos = dst.startWrite();
                fos.write(raw);
                fos.flush();
                dst.finishWrite(fos);
            }
        } catch (IOException e) {
            Log.e(TAG, "Fail to sync the legacy IP config to the apex file path.");
            dst.failWrite(fos);
        }
    }

    public void read() {
        read(APEX_IP_CONFIG_FILE_PATH, LEGACY_IP_CONFIG_FILE_PATH, CONFIG_FILE);
    }

    @VisibleForTesting
    void read(final String newFilePath, final String oldFilePath, final String filename) {
        synchronized (mSync) {
            // Attempt to read the IP configuration from apex file path first.
            if (doesConfigFileExist(newFilePath + filename)) {
                loadConfigFileLocked(newFilePath + filename);
                return;
            }

            // If the config file doesn't exist in the apex file path, attempt to read it from
            // the legacy file path, if config file exists, write the legacy IP configuration to
            // apex config file path, this should just happen on the first boot. New or updated
            // config entries are only written to the apex config file later.
            if (!doesConfigFileExist(oldFilePath + filename)) return;
            loadConfigFileLocked(oldFilePath + filename);
            writeLegacyIpConfigToApexPath(newFilePath, oldFilePath, filename);
        }
    }

    private void loadConfigFileLocked(final String filepath) {
        // readIpConfigurations can return null when the version is invalid.
        final ArrayMap<String, IpConfiguration> configs =
                IpConfigStore.readIpConfigurations(filepath);
        if (configs == null) {
            Log.e(TAG, "IpConfigStore#readIpConfigurations() returned null");
            return;
        }
        mIpConfigurations.putAll(configs);
    }

    public void write(String iface, IpConfiguration config) {
        final File directory = new File(APEX_IP_CONFIG_FILE_PATH);
        if (!directory.exists()) {
            directory.mkdirs();
        }
        write(iface, config, APEX_IP_CONFIG_FILE_PATH + CONFIG_FILE);
    }

    @VisibleForTesting
    void write(String iface, IpConfiguration config, String filepath) {
        boolean modified;

        synchronized (mSync) {
            if (config == null) {
                modified = mIpConfigurations.remove(iface) != null;
            } else {
                IpConfiguration oldConfig = mIpConfigurations.put(iface, config);
                modified = !config.equals(oldConfig);
            }

            if (modified) {
                mStore.writeIpConfigurations(filepath, mIpConfigurations);
            }
        }
    }

    public ArrayMap<String, IpConfiguration> getIpConfigurations() {
        synchronized (mSync) {
            return new ArrayMap<>(mIpConfigurations);
        }
    }

    /**
     * Set DNS servers for the given interface only, without touching the rest of its
     * configuration. The new servers are persisted and, if the interface currently has a
     * connected network, immediately pushed to the DNS resolver via
     * {@link IDnsResolver#setResolverConfiguration}.
     *
     * @param iface interface name.
     * @param netId netId of the network currently running on the interface, or a negative
     *        value if the interface is not connected; in that case the new servers are only
     *        persisted and will be applied on the next provisioning.
     * @param servers the DNS servers to use, must not be empty.
     * @return true if the configuration was updated and, when netId >= 0, successfully pushed
     *         to the resolver.
     */
    public boolean setDnsServers(@NonNull final String iface, final int netId,
            @NonNull final List<InetAddress> servers) {
        return setDnsServers(iface, netId, servers, APEX_IP_CONFIG_FILE_PATH + CONFIG_FILE);
    }

    @VisibleForTesting
    boolean setDnsServers(@NonNull final String iface, final int netId,
            @NonNull final List<InetAddress> servers, @NonNull final String filepath) {
        if (servers.isEmpty()) {
            Log.e(TAG, "setDnsServers: empty server list for " + iface);
            return false;
        }

        // Persist the new servers into the stored configuration.
        synchronized (mSync) {
            final IpConfiguration oldConfig = mIpConfigurations.get(iface);
            if (oldConfig == null) {
                Log.e(TAG, "setDnsServers: no configuration for " + iface);
                return false;
            }
            final IpConfiguration newConfig = new IpConfiguration(oldConfig);
            if (newConfig.getStaticIpConfiguration() == null) {
                newConfig.setStaticIpConfiguration(new StaticIpConfiguration());
            }
            newConfig.getStaticIpConfiguration().dnsServers.clear();
            newConfig.getStaticIpConfiguration().dnsServers.addAll(servers);
            if (!newConfig.equals(oldConfig)) {
                mIpConfigurations.put(iface, newConfig);
                final File directory = new File(filepath).getParentFile();
                if (directory != null && !directory.exists()) {
                    directory.mkdirs();
                }
                mStore.writeIpConfigurations(filepath, mIpConfigurations);
            }
        }

        // The binder call must not be made while holding mSync.
        if (netId < 0) return true;

        final IDnsResolver resolver = getDnsResolver();
        if (resolver == null) {
            Log.e(TAG, "setDnsServers: dnsresolver service not available");
            return false;
        }
        final ResolverParamsParcel parcel = new ResolverParamsParcel();
        parcel.netId = netId;
        parcel.sampleValiditySeconds = DNS_RESOLVER_DEFAULT_SAMPLE_VALIDITY_SECONDS;
        parcel.successThreshold = DNS_RESOLVER_DEFAULT_SUCCESS_THRESHOLD_PERCENT;
        parcel.minSamples = DNS_RESOLVER_DEFAULT_MIN_SAMPLES;
        parcel.maxSamples = DNS_RESOLVER_DEFAULT_MAX_SAMPLES;
        parcel.servers = new String[servers.size()];
        for (int i = 0; i < servers.size(); i++) {
            parcel.servers[i] = servers.get(i).getHostAddress();
        }
        parcel.domains = new String[0];
        parcel.tlsName = "";
        parcel.tlsServers = new String[0];
        parcel.transportTypes = new int[] { NetworkCapabilities.TRANSPORT_ETHERNET };
        parcel.meteredNetwork = false;
        try {
            resolver.setResolverConfiguration(parcel);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "setDnsServers: error setting DNS configuration", e);
            return false;
        }
        return true;
    }

    @Nullable
    private IDnsResolver getDnsResolver() {
        if (mDnsResolver == null && mContext != null) {
            final DnsResolverServiceManager dsm =
                    mContext.getSystemService(DnsResolverServiceManager.class);
            if (dsm != null) {
                mDnsResolver = IDnsResolver.Stub.asInterface(dsm.getService());
            }
        }
        return mDnsResolver;
    }

    @NonNull
    public List<String> getDnsServersFromResolver(final int netId) {
        if (netId < 0) return Collections.emptyList();

        final IDnsResolver resolver = getDnsResolver();
        if (resolver == null) return Collections.emptyList();
        final String[] servers = new String[DNS_RESOLVER_SERVER_QUERY_SIZE];
        final String[] domains = new String[DNS_RESOLVER_SERVER_QUERY_SIZE];
        final String[] tlsServers = new String[DNS_RESOLVER_SERVER_QUERY_SIZE];
        final int[] params = new int[IDnsResolver.RESOLVER_PARAMS_COUNT];
        final int[] stats =
                new int[DNS_RESOLVER_SERVER_QUERY_SIZE * IDnsResolver.RESOLVER_STATS_COUNT];
        final int[] waitForPendingReqTimeoutCount = new int[1];
        try {
            resolver.getResolverInfo(netId, servers, domains, tlsServers, params, stats,
                    waitForPendingReqTimeoutCount);
        } catch (RemoteException | ServiceSpecificException e) {
            Log.e(TAG, "getDnsServersFromResolver: failed for netId " + netId, e);
            return Collections.emptyList();
        }
        final ArrayList<String> result = new ArrayList<>();
        for (final String server : servers) {
            if (server != null && !server.isEmpty()) {
                result.add(server);
            }
        }
        if (result.size() == DNS_RESOLVER_SERVER_QUERY_SIZE) {
            Log.e(TAG, "getDnsServersFromResolver: resolver server list may be truncated");
        }
        return result;
    }

    @NonNull
    public List<String> getPersistedDnsServers(@NonNull final String iface) {
        synchronized (mSync) {
            final IpConfiguration config = mIpConfigurations.get(iface);
            if (config == null || config.getStaticIpConfiguration() == null) {
                return Collections.emptyList();
            }
            final List<InetAddress> dnsServers = config.getStaticIpConfiguration().dnsServers;
            if (dnsServers == null || dnsServers.isEmpty()) {
                return Collections.emptyList();
            }
            final ArrayList<String> result = new ArrayList<>(dnsServers.size());
            for (final InetAddress server : dnsServers) {
                if (server != null) {
                    result.add(server.getHostAddress());
                }
            }
            return result;
        }
    }

    public ArrayMap<String, IpConfiguration> readIpConfigurations(){
        ArrayMap<String, IpConfiguration> networks = new ArrayMap<>();
        Net net = Net.getInstance(null);
        String ipConfigurationsFromHost = net.getLanWlanBridgeIpConfigurations();
        if (ipConfigurationsFromHost == null || ipConfigurationsFromHost.isEmpty()) {
            return networks;
        }
        String[] interfaceInfo = ipConfigurationsFromHost.split(";");
        for (String i : interfaceInfo) {
            String[] info = i.split("#");
            if (info.length <= 2) {
                continue;
            }
            String interfaceName = info[0];
            StaticIpConfiguration staticIpConfiguration = new StaticIpConfiguration();
            String ipHasPrefixLength = info[1];
            if (ipHasPrefixLength != null) {
                String[] ipAndPrefixLength = ipHasPrefixLength.split("/");
                LinkAddress linkAddr = new LinkAddress(NetworkUtils.numericToInetAddress(ipAndPrefixLength[0]), Integer.parseInt(ipAndPrefixLength[1]));
                staticIpConfiguration.ipAddress = linkAddr;
            }
            InetAddress gateway = NetworkUtils.numericToInetAddress(info[2]);
            staticIpConfiguration.gateway = gateway;
            String dnss = info.length > 3 ? info[3] : null;
            if (dnss == null || dnss.isEmpty()) {
                dnss = "114.114.114.114";
            }
            String[] dnssL = dnss.split(" \\| ");
            for (String d : dnssL) {
                staticIpConfiguration.dnsServers.add(NetworkUtils.numericToInetAddress(d));
            }
            IpConfiguration config = new IpConfiguration();
            networks.put(interfaceName, config);
            config.staticIpConfiguration = staticIpConfiguration;
            config.ipAssignment = IpAssignment.STATIC;
            config.proxySettings = ProxySettings.NONE;
        }
        return networks;
    }

    public void constructIpConfigurations() {
        synchronized (mSync) {
            mIpConfigurations.clear();
            mIpConfigurations.putAll(readIpConfigurations());
            //mStore.writeIpConfigurations(APEX_IP_CONFIG_FILE_PATH, mIpConfigurations);
        }
    }
}
