/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */
package com.yahoo.omid.notifications.client;

import static com.yahoo.omid.notifications.ZkTreeUtils.ZK_APP_DATA_NODE;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Closeables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.curator.framework.CuratorFramework;
import com.netflix.curator.framework.CuratorFrameworkFactory;
import com.netflix.curator.retry.ExponentialBackoffRetry;
import com.netflix.curator.utils.ZKPaths;
import com.yahoo.omid.notifications.Interest;
import com.yahoo.omid.notifications.NotificationException;
import com.yahoo.omid.notifications.ZkTreeUtils;
import com.yahoo.omid.notifications.comm.ZNRecord;
import com.yahoo.omid.notifications.comm.ZNRecordSerializer;
import com.yahoo.omid.notifications.conf.ClientConfiguration;
import com.yahoo.omid.notifications.metrics.ClientSideAppMetrics;
import com.yahoo.omid.notifications.thrift.generated.Notification;

public class DeltaOmid implements IncrementalApplication {

    private static final Logger logger = LoggerFactory.getLogger(DeltaOmid.class);

    private final CuratorFramework zkClient;
    private final String name;
    private final int port;
    private final ClientSideAppMetrics metrics;
    private final ClientConfiguration conf;
    private final String zkAppInstancePath;
    private final NotificationManager notificationManager;
    public static final int NOTIFICATION_BUFFER_CAPACITY = 100;
    public static final int OBSERVER_PROCESSOR_PARALLELISM = 2;

    // The main structure shared by the InterestRecorder and NotificiationListener services in order to register and
    // notify observers
    // Key: The name of a registered observer
    // Value: The TransactionalObserver infrastructure that delegates on the implementation of the ObserverBehaviour
    private final Map<String, BlockingQueue<Notification>> observerBuffers = new HashMap<String, BlockingQueue<Notification>>();
    private final Map<String, ExecutorService> observerExecutors = new HashMap<String, ExecutorService>();

    public static class AppBuilder {
        // Required parameters
        private final String appName;
        private final int port;
        private final List<Observer> observers = new ArrayList<Observer>();
        // Optional parameters - initialized to default values
        private ClientConfiguration conf = new ClientConfiguration();

        public AppBuilder(String appName, int port) {
            this.appName = appName;
            this.port = port;
        }

        public AppBuilder setConfiguration(ClientConfiguration conf) {
            this.conf = conf;
            return this;
        }

        public AppBuilder addObserver(Observer observer) {
            this.observers.add(observer);
            return this;
        }

        public IncrementalApplication build() throws NotificationException, IOException {
            return new DeltaOmid(this);
        }
    }

    private DeltaOmid(AppBuilder builder) throws NotificationException, IOException {
        this.name = builder.appName;
        this.port = builder.port;
        this.conf = builder.conf;
        this.metrics = new ClientSideAppMetrics(this.name, conf);

        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                logger.error("Uncaught exception in thread {}", t.getName(), e);

            }
        });

        List<String> observersInterests = new ArrayList<String>();
        for (final Observer observer : builder.observers) {
            String obsName = observer.getName();
            BlockingQueue<Notification> observerQueue = new ArrayBlockingQueue<Notification>(
                    NOTIFICATION_BUFFER_CAPACITY);
            this.metrics.addObserver(obsName);
            observerBuffers.put(obsName, observerQueue);
            ExecutorService observersExecutor = Executors.newFixedThreadPool(OBSERVER_PROCESSOR_PARALLELISM,
                    new ThreadFactoryBuilder().setNameFormat("Observer-[" + obsName + "]-processor-%d").build());
            for (int i = 0; i < OBSERVER_PROCESSOR_PARALLELISM; i++) {
                observersExecutor.submit(new ObserverWrapper(observer, conf.getOmidServer(), metrics, observerQueue));
            }
            Interest interest = observer.getInterest();
            if (interest == null) {
                logger.warn("Observer " + obsName + " doesn't have any interest: it will never be notified");
                continue;
            }

            observersInterests.add(obsName + "/" + interest.toZkNodeRepresentation());

        }
        // Create the notification manager for notifying the app observers
        this.notificationManager = new NotificationManager(this, metrics);
        this.notificationManager.start();
        // Finally register the app in the ZK tree
        this.zkClient = CuratorFrameworkFactory.newClient(this.conf.getZkServers(),
                new ExponentialBackoffRetry(3000, 3));
        this.zkClient.start();
        ZNRecord zkData = new ZNRecord(name);
        zkData.putListField(ZK_APP_DATA_NODE, observersInterests);
        try {
            String zkAppPath = createZkSubBranch(ZkTreeUtils.getAppsNodePath(), name,
                    new ZNRecordSerializer().serialize(zkData), false);
            String instanceName = InetAddress.getLocalHost().getHostAddress() + ":" + this.port;
            this.zkAppInstancePath = createZkSubBranch(zkAppPath, instanceName, new byte[0], true);
        } catch (Exception e) {
            throw new NotificationException(e);
        }
        logger.trace("App instance created: " + this);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public Map<String, BlockingQueue<Notification>> getRegisteredObservers() {
        return observerBuffers;
    }

    @Override
    public void close() throws IOException {
        try {
            zkClient.delete().forPath(zkAppInstancePath);

        } catch (Exception e) {
            logger.error("Cannot clean ZooKeeper node {}", zkAppInstancePath, e);
        } finally {
            // notificationManager.stop();
            // appObserverSystem.shutdown();
            Closeables.closeQuietly(zkClient);
        }
        synchronized (observerExecutors) {
            for (ExecutorService executor : observerExecutors.values()) {
                executor.shutdownNow();
            }
        }

        logger.trace(getName() + " instance running in " + InetAddress.getLocalHost() + ":" + getPort() + " finished");
    }

    private String createZkSubBranch(String mainBranchPath, String subBranchPath, byte[] data, boolean ephemeral)
            throws Exception {
        String completeBranchPath = ZKPaths.makePath(mainBranchPath, subBranchPath);
        Stat s = zkClient.checkExists().forPath(completeBranchPath);
        if (s == null) {
            if (data == null) {
                data = new byte[0];
            }
            if (ephemeral) {
                return zkClient.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL)
                        .forPath(completeBranchPath, data);
            } else {
                return zkClient.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT)
                        .forPath(completeBranchPath, data);
            }
        }
        return completeBranchPath;
    }

    @Override
    public String toString() {
        return "DeltaOmid [name=" + name + ", zkAppInstancePath=" + zkAppInstancePath + ", registeredObservers="
                + observerBuffers + "]";
    }
}
