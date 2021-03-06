/*
 * Copyright (c) 2014-2016 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.common;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import com.vmware.xenon.common.Operation.CompletionHandler;
import com.vmware.xenon.common.Service.Action;
import com.vmware.xenon.common.Service.ServiceOption;
import com.vmware.xenon.common.ServiceClient.ConnectionPoolMetrics;
import com.vmware.xenon.common.ServiceHost.ServiceHostState;
import com.vmware.xenon.common.ServiceHost.ServiceHostState.MemoryLimitType;
import com.vmware.xenon.common.ServiceStats.ServiceStat;
import com.vmware.xenon.common.ServiceStats.TimeSeriesStats.AggregationType;
import com.vmware.xenon.services.common.ServiceHostManagementService;

/**
 * Monitors service resources, and takes action, during periodic maintenance
 */
class ServiceResourceTracker {

    /**
     * This class is used for keeping cached transactional state of services under
     * active optimistic transactions.
     */
    private static final class CachedServiceStateKey {
        private final String servicePath;
        private final String transactionId;

        CachedServiceStateKey(String servicePath, String transactionId) {
            this.servicePath = servicePath;
            this.transactionId = transactionId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            CachedServiceStateKey that = (CachedServiceStateKey) o;

            if (this.servicePath != null ? !this.servicePath.equals(that.servicePath) : that.servicePath != null) {
                return false;
            }

            return this.transactionId != null ?
                    this.transactionId.equals(that.transactionId) :
                    that.transactionId == null;
        }

        @Override
        public int hashCode() {
            int result = this.servicePath != null ? this.servicePath.hashCode() : 0;
            result = 31 * result + (this.transactionId != null ? this.transactionId.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return String.format("CachedServiceStateKey{servicePath: %s, transactionId: %s}",
                    this.servicePath, this.transactionId);
        }
    }

    /**
     * For performance reasons, this map is owned and directly operated by the host
     */
    private final Map<String, Service> attachedServices;

    /**
     * Tracks cached service state. Cleared periodically during maintenance
     */
    private final ConcurrentMap<String, ServiceDocument> cachedServiceStates = new ConcurrentHashMap<>();

    /**
     * Tracks last access time for PERSISTENT services. The access time is used for a few things:
     * 1. Deciding if the cache state of the service needs to be cleared based
     *    on {@link ServiceHost#getServiceCacheClearDelayMicros()}.
     * 2. Deciding if the service needs to be stopped when memory pressure is high.
     *
     * We don't bother tracking access time for StatefulServices that are non-persistent.
     * This is because the cached state for non-persistent stateful services is never cleared.
     */
    private final ConcurrentMap<String, Long> persistedServiceLastAccessTimes = new ConcurrentHashMap<>();

    /**
     * Tracks cached service state. Cleared periodically during maintenance
     */
    private final ConcurrentMap<CachedServiceStateKey, ServiceDocument> cachedTransactionalServiceStates = new ConcurrentHashMap<>();

    private final ServiceHost host;

    private boolean isServiceStateCaching = true;

    private long startTimeMicros;

    private ThreadMXBean threadBean;

    private Service mgmtService;

    public static ServiceResourceTracker create(ServiceHost host, Map<String, Service> services) {
        ServiceResourceTracker srt = new ServiceResourceTracker(host, services);
        return srt;
    }

    public ServiceResourceTracker(ServiceHost host, Map<String, Service> services) {
        this.attachedServices = services;
        this.host = host;
    }

    private void checkAndInitializeStats() {
        if (this.startTimeMicros > 0) {
            return;
        }
        this.startTimeMicros = Utils.getNowMicrosUtc();
        if (this.host.getManagementService() == null) {
            this.host.log(Level.WARNING, "Management service not found, stats will not be available");
            return;
        }

        long inUseMem = this.host.getState().systemInfo.totalMemoryByteCount
                - this.host.getState().systemInfo.freeMemoryByteCount;
        long freeMem = this.host.getState().systemInfo.maxMemoryByteCount - inUseMem;
        long freeDisk = this.host.getState().systemInfo.freeDiskByteCount;

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_MEMORY_BYTES_PREFIX,
                freeMem);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_DISK_BYTES_PREFIX,
                freeDisk);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_CPU_USAGE_PCT_PREFIX,
                0);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_HTTP11_CONNECTION_COUNT_PREFIX,
                0);

        createTimeSeriesStat(
                ServiceHostManagementService.STAT_NAME_HTTP2_CONNECTION_COUNT_PREFIX,
                0);

        getManagementService().setStat(ServiceHostManagementService.STAT_NAME_THREAD_COUNT,
                Utils.DEFAULT_THREAD_COUNT);

    }

    void createTimeSeriesStat(String name, double v) {
        Service service = getManagementService();
        EnumSet<AggregationType> types = EnumSet.of(AggregationType.AVG);
        ServiceStat dayStat = ServiceStatUtils.getOrCreateDailyTimeSeriesStat(service, name, types);
        ServiceStat hourStat = ServiceStatUtils.getOrCreateHourlyTimeSeriesHistogramStat(service, name, types);
        service.setStat(dayStat, v);
        service.setStat(hourStat, v);
    }

    private void updateStats(long now) {
        this.host.updateMemoryAndDiskInfo();
        ServiceHostState hostState = this.host.getStateNoCloning();
        SystemHostInfo shi = hostState.systemInfo;

        Service mgmtService = getManagementService();
        if (mgmtService == null) {
            return;
        }

        checkAndInitializeStats();
        mgmtService.setStat(ServiceHostManagementService.STAT_NAME_SERVICE_COUNT,
                hostState.serviceCount);

        // The JVM reports free memory in a indirect way, relative to the current "total". But the
        // true free memory is the estimated used memory subtracted from the JVM heap max limit
        long freeMemory = shi.maxMemoryByteCount
                - (shi.totalMemoryByteCount - shi.freeMemoryByteCount);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_MEMORY_BYTES_PER_HOUR,
                freeMemory);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_MEMORY_BYTES_PER_DAY,
                freeMemory);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_DISK_BYTES_PER_HOUR,
                shi.freeDiskByteCount);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_AVAILABLE_DISK_BYTES_PER_DAY,
                shi.freeDiskByteCount);

        if (this.threadBean == null) {
            this.threadBean = ManagementFactory.getThreadMXBean();
        }
        if (!this.threadBean.isCurrentThreadCpuTimeSupported()) {
            return;
        }

        long totalTime = 0;
        // we assume a low number of threads since the runtime uses just a thread per core, plus
        // a small multiple of that dedicated to I/O threads. So the thread CPU usage calculation
        // should have a small overhead
        long[] threadIds = this.threadBean.getAllThreadIds();
        for (long threadId : threadIds) {
            totalTime += this.threadBean.getThreadCpuTime(threadId);
        }

        double runningTime = now - this.startTimeMicros;
        if (runningTime <= 0) {
            return;
        }

        createTimeSeriesStat(ServiceHostManagementService.STAT_NAME_JVM_THREAD_COUNT_PREFIX,
                threadIds.length);

        totalTime = TimeUnit.NANOSECONDS.toMicros(totalTime);
        double pctUse = totalTime / runningTime;
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_CPU_USAGE_PCT_PER_HOUR,
                pctUse);
        mgmtService.setStat(
                ServiceHostManagementService.STAT_NAME_CPU_USAGE_PCT_PER_DAY,
                pctUse);

        ConnectionPoolMetrics http11TagInfo = this.host.getClient()
                .getConnectionPoolMetrics(false);
        if (http11TagInfo != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP11_PENDING_OP_COUNT_PREFIX,
                    http11TagInfo.pendingRequestCount);
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP11_CONNECTION_COUNT_PREFIX,
                    http11TagInfo.inUseConnectionCount);
        }

        ConnectionPoolMetrics http2TagInfo = this.host.getClient()
                .getConnectionPoolMetrics(true);
        if (http2TagInfo != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP2_PENDING_OP_COUNT_PREFIX,
                    http2TagInfo.pendingRequestCount);
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_HTTP2_CONNECTION_COUNT_PREFIX,
                    http2TagInfo.inUseConnectionCount);
        }

        ForkJoinPool executor = (ForkJoinPool) this.host.getExecutor();
        if (executor != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_EXECUTOR_QUEUE_DEPTH,
                    executor.getQueuedSubmissionCount());
        }

        ScheduledThreadPoolExecutor scheduledExecutor = (ScheduledThreadPoolExecutor) this.host.getScheduledExecutor();
        if (scheduledExecutor != null) {
            createTimeSeriesStat(
                    ServiceHostManagementService.STAT_NAME_SCHEDULED_EXECUTOR_QUEUE_DEPTH,
                    scheduledExecutor.getQueue().size());
        }
    }

    private Service getManagementService() {
        if (this.mgmtService == null) {
            this.mgmtService = this.host.getManagementService();
        }
        return this.mgmtService;
    }

    public void setServiceStateCaching(boolean enable) {
        this.isServiceStateCaching = enable;
    }

    public void updateCachedServiceState(Service s,
             ServiceDocument st, Operation op) {

        if (ServiceHost.isServiceIndexed(s) && !isTransactional(op)) {
            this.persistedServiceLastAccessTimes.put(s.getSelfLink(), Utils.getNowMicrosUtc());
        }

        // if caching is disabled on the serviceHost, then we don't bother updating the cache
        // for persisted services. If it's a non-persisted service, then we DO update the cache.
        if (ServiceHost.isServiceIndexed(s) && !this.isServiceStateCaching) {
            return;
        }

        if (!isTransactional(op)) {
            synchronized (s.getSelfLink()) {
                ServiceDocument cachedState = this.cachedServiceStates.put(s.getSelfLink(), st);
                if (cachedState != null && cachedState.documentVersion > st.documentVersion) {
                    // restore cached state, discarding update, if the existing version is higher
                    this.cachedServiceStates.put(s.getSelfLink(), cachedState);
                }
            }
            return;
        }

        CachedServiceStateKey key = new CachedServiceStateKey(s.getSelfLink(),
                op.getTransactionId());

        this.cachedTransactionalServiceStates.compute(key, (k, cachedState) -> {
            if (cachedState != null && cachedState.documentVersion > st.documentVersion) {
                // No update if the existing version is higher
                return cachedState;
            } else {
                return st;
            }
        });
    }

    /**
     * called only for stateful services
     */
    public ServiceDocument getCachedServiceState(Service s, Operation op) {
        String servicePath = s.getSelfLink();
        ServiceDocument state = null;

        if (isTransactional(op)) {
            CachedServiceStateKey key = new CachedServiceStateKey(servicePath,
                    op.getTransactionId());
            state = this.cachedTransactionalServiceStates.get(key);
        } else {
            if (ServiceHost.isServiceIndexed(s)) {
                this.persistedServiceLastAccessTimes.put(servicePath,
                        this.host.getStateNoCloning().lastMaintenanceTimeUtcMicros);
            }
        }

        if (state == null) {
            // either the operational is not transactional or no transactional state found -
            // look for the state in the non-transactional map
            state = this.cachedServiceStates.get(servicePath);
        }

        if (state == null) {
            return null;
        }

        if (state.documentExpirationTimeMicros > 0
                && state.documentExpirationTimeMicros < state.documentUpdateTimeMicros) {
            // state expired, clear from cache
            stopService(s, true, state);
            return null;
        }

        return state;
    }

    private void stopService(Service s, boolean isExpired, ServiceDocument state) {
        if (s == null) {
            return;
        }
        if (isExpired && s.hasOption(ServiceOption.PERSISTENCE)) {
            // the index service tracks expiration of persisted services
            return;
        }
        // Issue DELETE to stop the service
        Operation deleteExp = Operation.createDelete(this.host, s.getSelfLink())
                .setBody(state)
                .disableFailureLogging(true)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_INDEX_UPDATE)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_FORWARDING)
                .setReferer(this.host.getUri());

        this.host.sendRequest(deleteExp);
    }

    public void clearCachedServiceState(Service s, String servicePath, Operation op) {
        this.clearCachedServiceState(s, servicePath, op, false);
    }

    private void clearCachedServiceState(Service s, String servicePath, Operation op,
            boolean keepLastAccessTime) {
        if (s != null && servicePath == null) {
            servicePath = s.getSelfLink();
        }

        if (!isTransactional(op)) {
            if (!keepLastAccessTime) {
                this.persistedServiceLastAccessTimes.remove(servicePath);
            }

            ServiceDocument doc = this.cachedServiceStates.remove(servicePath);
            if (s == null) {
                s = this.host.findService(servicePath, true);
            }
            if (s == null) {
                return;
            }
            if (doc != null) {
                updateCacheClearStats(s);
            }
            return;
        }

        clearTransactionalCachedServiceState(servicePath, op.getTransactionId());
    }

    public void clearTransactionalCachedServiceState(String servicePath, String transactionId) {
        CachedServiceStateKey key = new CachedServiceStateKey(servicePath,
                transactionId);
        ServiceDocument doc = this.cachedTransactionalServiceStates.remove(key);
        Service s = this.host.findService(servicePath, true);
        if (s == null) {
            return;
        }
        if (doc != null) {
            updateCacheClearStats(s);
        }
    }

    private void updateCacheClearStats(Service s) {
        s.adjustStat(Service.STAT_NAME_CACHE_CLEAR_COUNT, 1);
        this.host.getManagementService().adjustStat(
                ServiceHostManagementService.STAT_NAME_SERVICE_CACHE_CLEAR_COUNT, 1);
        if (s.hasOption(ServiceOption.ON_DEMAND_LOAD)) {
            this.host.getManagementService().adjustStat(
                    ServiceHostManagementService.STAT_NAME_ODL_CACHE_CLEAR_COUNT, 1);
        }
    }

    /**
     * Estimates how much memory is used by host caches, queues and based on the memory limits
     * takes appropriate action: clears cached service state, temporarily stops services
     */
    public void performMaintenance(long now, long deadlineMicros) {
        updateStats(now);
        ServiceHostState hostState = this.host.getStateNoCloning();
        int stopServiceCount = 0;
        long memoryLimitHighMB = this.host.getServiceMemoryLimitMB(ServiceHost.ROOT_PATH,
                MemoryLimitType.HIGH_WATERMARK);

        long memoryInUseMB = hostState.serviceCount
                * ServiceHost.DEFAULT_SERVICE_INSTANCE_COST_BYTES;
        memoryInUseMB /= (1024 * 1024);

        boolean shouldStop = memoryLimitHighMB <= memoryInUseMB;

        for (Service service : this.attachedServices.values()) {
            // skip factory services, they do not have state
            if (service.hasOption(ServiceOption.FACTORY)) {
                continue;
            }

            ServiceDocument s = this.cachedServiceStates.get(service.getSelfLink());
            Long lastAccessTime = this.persistedServiceLastAccessTimes.get(service.getSelfLink());
            boolean cacheCleared = s == null;

            if (s != null) {
                if (!ServiceHost.isServiceIndexed(service)) {
                    // we do not clear cache or stop in memory services but we do check expiration
                    if (s.documentExpirationTimeMicros > 0
                            && s.documentExpirationTimeMicros < now) {
                        stopService(service, true, s);
                    }
                    continue;
                }

                if (service.hasOption(ServiceOption.TRANSACTION_PENDING)) {
                    // don't clear cache for services under active transactions, for perf reasons.
                    // transactional cached state will be cleared at the end of transaction
                    continue;
                }

                if (lastAccessTime == null) {
                    lastAccessTime = s.documentUpdateTimeMicros;
                }

                long cacheClearDelayMicros = hostState.serviceCacheClearDelayMicros;
                if (ServiceHost.isServiceImmutable(service)) {
                    cacheClearDelayMicros = 0;
                }

                if ((cacheClearDelayMicros + lastAccessTime) < now) {
                    // The cached entry is old and should be cleared.
                    // Note that we are not going to clear the lastAccessTime here
                    // because we will need it in future maintenance runs to determine
                    // if the service should be stopped.
                    clearCachedServiceState(service, null, null, true);
                    cacheCleared = true;
                }
            }

            // If this host is the OWNER for the service and we didn't find it's entry
            // in the cache or the lastAccessTime map, and it's also a PERSISTENT service,
            // then probably the service is just starting up. So, we will skip stop..
            // However, if this host is not the OWNER, then we will proceed with stop.
            // This is because state is not cached on replicas.
            if (lastAccessTime == null &&
                    ServiceHost.isServiceIndexed(service) &&
                    service.hasOption(ServiceOption.DOCUMENT_OWNER)) {
                continue;
            }

            if (lastAccessTime != null &&
                    hostState.lastMaintenanceTimeUtcMicros - lastAccessTime < service
                    .getMaintenanceIntervalMicros() * 2) {
                // Skip stop for services that have been active within a maintenance interval
                continue;
            }

            // we still want to clear a cache for periodic services, so check here, after the cache clear
            if (service.hasOption(ServiceOption.PERIODIC_MAINTENANCE)) {
                // Services with periodic maintenance stay resident, for now. We might stop them in the future
                // if they have long periods
                continue;
            }

            if (!service.hasOption(ServiceOption.FACTORY_ITEM)) {
                continue;
            }

            if (!service.hasOption(ServiceOption.ON_DEMAND_LOAD)) {
                continue;
            }

            if (this.host.isServiceStarting(service, service.getSelfLink())) {
                continue;
            }

            if (this.host.hasPendingServiceAvailableCompletions(service.getSelfLink())) {
                this.host.log(Level.INFO,
                        "Pending available completions, skipping stop on %s",
                        service.getSelfLink());
                continue;
            }

            boolean hasSoftState = hasServiceSoftState(service);
            if (cacheCleared && !hasSoftState) {
                // if it's an on-demand-load service with no subscribers or stats,
                // instead simply stop them when the service is idle.
                stopServiceCount++;
                stopService(service, false, null);
                continue;
            }

            if (!shouldStop && !cacheCleared) {
                // if we are not under memory pressure only stop ODL services if their
                // cache is cleared.
                continue;
            }

            if (!cacheCleared) {
                // if we're going to stop it, clear state from cache if not already cleared
                clearCachedServiceState(service, null, null);
                // and check again if ON_DEMAND_LOAD with no subscriptions, then we need to stop
                if (!hasSoftState) {
                    stopServiceCount++;
                    stopService(service, false, null);
                    continue;
                }
            }

            if (deadlineMicros < Utils.getSystemNowMicrosUtc()) {
                break;
            }
        }

        if (hostState.serviceCount < 0) {
            // Make sure our service count matches the list contents, they could drift. Using size()
            // on a concurrent data structure is costly so we do this only when pausing services or
            // the count is negative
            synchronized (hostState) {
                hostState.serviceCount = this.attachedServices.size();
            }
        }

        if (stopServiceCount == 0) {
            return;
        }

        this.host.log(Level.FINE,
                "Attempt stop on %d services, attached: %d, cached: %d, persistedServiceLastAccessTimes: %d",
                stopServiceCount, hostState.serviceCount,
                this.cachedServiceStates.size(),
                this.persistedServiceLastAccessTimes.size());
    }

    boolean checkAndOnDemandStartService(Operation inboundOp) {
        String key = inboundOp.getUri().getPath();
        if (ServiceHost.isHelperServicePath(key)) {
            key = UriUtils.getParentPath(key);
        }

        String factoryPath = UriUtils.getParentPath(key);
        FactoryService factoryService = null;
        if (factoryPath != null) {
            Service parentService = this.host.findService(factoryPath);
            if (!(parentService instanceof FactoryService)) {
                Operation.failServiceNotFound(inboundOp,
                        ServiceErrorResponse.ERROR_CODE_SERVICE_PARENT_NOT_A_FACTORY,
                        "URI path appears invalid, parent is not a factory service");
                return true;
            }
            factoryService = (FactoryService) parentService;
        }

        if (factoryService == null) {
            Operation.failServiceNotFound(inboundOp);
            return true;
        }

        if (!factoryService.hasOption(ServiceOption.ON_DEMAND_LOAD)) {
            return false;
        }

        inboundOp.addPragmaDirective(Operation.PRAGMA_DIRECTIVE_INDEX_CHECK);

        String path = key;

        if (this.host.isStopping()
                && inboundOp.hasPragmaDirective(Operation.PRAGMA_DIRECTIVE_NO_INDEX_UPDATE)
                && inboundOp.getAction() == Action.DELETE) {
            inboundOp.complete();
            return true;
        }

        if (inboundOp.getExpirationMicrosUtc() < Utils.getSystemNowMicrosUtc()) {
            this.host.log(Level.WARNING, "Request to %s has expired", path);
            return false;
        }

        if (this.host.isStopping()) {
            return false;
        }

        this.host.log(Level.FINE, "(%d) ODL check for %s", inboundOp.getId(), path);
        return checkAndOnDemandStartService(inboundOp, factoryService);
    }

    boolean checkAndOnDemandStartService(Operation inboundOp, Service parentService) {
        if (!parentService.hasOption(ServiceOption.FACTORY)) {
            Operation.failServiceNotFound(inboundOp);
            return true;
        }

        if (!parentService.hasOption(ServiceOption.ON_DEMAND_LOAD)) {
            return false;
        }

        FactoryService factoryService = (FactoryService) parentService;

        String servicePath = inboundOp.getUri().getPath();
        if (ServiceHost.isHelperServicePath(servicePath)) {
            servicePath = UriUtils.getParentPath(servicePath);

        }
        String finalServicePath = servicePath;
        boolean doProbe = inboundOp.hasPragmaDirective(
                Operation.PRAGMA_DIRECTIVE_QUEUE_FOR_SERVICE_AVAILABILITY);

        if (!factoryService.hasOption(ServiceOption.REPLICATION)
                && inboundOp.getAction() == Action.DELETE) {
            // do a probe (GET) to avoid starting a service on a DELETE request. We only do this
            // for non replicated services since its safe to do a local only probe. By doing a GET
            // first, we avoid the following race on local services:
            // DELETE -> starts service to determine if it exists
            // client issues POST for same self link while service is starting during ODL start
            // client sees conflict, even if the service never existed
            doProbe = true;
        }

        if (!doProbe) {
            startServiceOnDemand(inboundOp, parentService, factoryService, finalServicePath);
            return true;
        }

        // we should not use startService for checking if a service ever existed. This can cause a race with
        // a client POST creating the service for the first time, when they use
        // PRAGMA_QUEUE_FOR_AVAILABILITY. Instead do an attempt to load state for the service path
        Operation getOp = Operation
                .createGet(inboundOp.getUri())
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_INDEX_CHECK)
                .transferRefererFrom(inboundOp)
                .setCompletion((o, e) -> {
                    if (e != null) {
                        inboundOp.fail(e);
                        return;
                    }

                    if (!o.hasBody()) {
                        // the index will return success, but no body if service is not found
                        this.host.checkPragmaAndRegisterForAvailability(finalServicePath,
                                inboundOp);
                        return;
                    }

                    // service state exists, proceed with starting service
                    startServiceOnDemand(inboundOp, parentService, factoryService,
                            finalServicePath);
                });

        Service indexService = this.host.getDocumentIndexService();
        if (indexService == null) {
            inboundOp.fail(new CancellationException("Index service is null"));
            return true;
        }
        indexService.handleRequest(getOp);
        return true;
    }

    void startServiceOnDemand(Operation inboundOp, Service parentService,
            FactoryService factoryService, String finalServicePath) {
        Operation onDemandPost = Operation.createPost(this.host, finalServicePath);

        CompletionHandler c = (o, e) -> {
            if (e != null) {
                if (e instanceof CancellationException) {
                    // local stop of idle service raced with client request to load it. Retry.
                    this.host.log(Level.WARNING, "Stop of idle service %s detected, retrying",
                            inboundOp
                            .getUri().getPath());
                    this.host.scheduleCore(() -> {
                        checkAndOnDemandStartService(inboundOp, parentService);
                    }, 1, TimeUnit.SECONDS);
                    return;
                }

                Action a = inboundOp.getAction();
                ServiceErrorResponse response = o.getErrorResponseBody();

                if (response != null) {
                    // Since we do a POST first for services using ON_DEMAND_LOAD to start the service,
                    // we can get back a 409 status code i.e. the service has already been started or was
                    // deleted previously. Differentiate based on action, if we need to fail or succeed
                    if (response.statusCode == Operation.STATUS_CODE_CONFLICT) {
                        if (!ServiceHost.isServiceCreate(inboundOp)
                                && response.errorCode == ServiceErrorResponse.ERROR_CODE_SERVICE_ALREADY_EXISTS) {
                            // service exists, action is not attempt to recreate, so complete as success
                            this.host.handleRequest(null, inboundOp);
                            return;
                        }

                        if (response.errorCode == ServiceErrorResponse.ERROR_CODE_STATE_MARKED_DELETED) {
                            if (a == Action.DELETE) {
                                // state marked deleted, and action is to delete again, return success
                                inboundOp.complete();
                            } else if (a == Action.POST) {
                                // POSTs will fail with conflict since we must indicate the client is attempting a restart of a
                                // existing service.
                                this.host.failRequestServiceAlreadyStarted(finalServicePath, null,
                                        inboundOp);
                            } else {
                                // All other actions fail with NOT_FOUND making it look like the service
                                // does not exist (or ever existed)
                                Operation.failServiceNotFound(inboundOp,
                                        ServiceErrorResponse.ERROR_CODE_STATE_MARKED_DELETED);
                            }
                            return;
                        }
                    }

                    // if the service we are trying to DELETE never existed, we swallow the 404 error.
                    // This is for consistency in behavior with non ON_DEMAND_LOAD services.
                    if (inboundOp.getAction() == Action.DELETE &&
                            response.statusCode == Operation.STATUS_CODE_NOT_FOUND) {
                        inboundOp.complete();
                        return;
                    }

                    // there is a possibility the user requests we queue and wait for service to show up
                    if (response.statusCode == Operation.STATUS_CODE_NOT_FOUND) {
                        this.host.log(Level.WARNING,
                                "Failed to start service %s with 404 status code.", finalServicePath);
                        this.host.checkPragmaAndRegisterForAvailability(finalServicePath,
                                inboundOp);
                        return;
                    }
                }

                this.host.log(Level.SEVERE,
                        "Failed to start service %s with statusCode %d",
                        finalServicePath, o.getStatusCode());
                inboundOp.setBodyNoCloning(o.getBodyRaw()).setStatusCode(o.getStatusCode());
                inboundOp.fail(e);
                return;
            }
            // proceed with handling original client request, service now started
            this.host.handleRequest(null, inboundOp);
        };

        onDemandPost.addPragmaDirective(Operation.PRAGMA_DIRECTIVE_INDEX_CHECK)
                .addPragmaDirective(Operation.PRAGMA_DIRECTIVE_VERSION_CHECK)
                .transferRefererFrom(inboundOp)
                .setExpiration(inboundOp.getExpirationMicrosUtc())
                .setReplicationDisabled(true)
                .setCompletion(c);

        Service childService;
        try {
            childService = factoryService.createServiceInstance();
            childService.toggleOption(ServiceOption.FACTORY_ITEM, true);
        } catch (Throwable e1) {
            inboundOp.fail(e1);
            return;
        }

        if (inboundOp.getAction() == Action.DELETE) {
            onDemandPost.disableFailureLogging(true);
            inboundOp.disableFailureLogging(true);
        }

        // bypass the factory, directly start service on host. This avoids adding a new
        // version to the index and various factory processes that are invoked on new
        // service creation
        this.host.startService(onDemandPost, childService);
    }

    void retryOnDemandLoadConflict(Operation op) {

        op.removePragmaDirective(Operation.PRAGMA_DIRECTIVE_INDEX_CHECK);
        String statName = ServiceHostManagementService.STAT_NAME_ODL_STOP_CONFLICT_COUNT;
        this.host.getManagementService().adjustStat(statName, 1);

        this.host.log(Level.WARNING,
                "ODL conflict: retrying %s (%d %s) on %s",
                op.getAction(), op.getId(), op.getContextId(),
                op.getUri().getPath());

        long interval = Math.max(TimeUnit.SECONDS.toMicros(1),
                this.host.getMaintenanceIntervalMicros());
        this.host.scheduleCore(() -> {
            this.host.handleRequest(null, op);
        }, interval, TimeUnit.MICROSECONDS);
    }

    public void close() {
        this.cachedServiceStates.clear();
        this.persistedServiceLastAccessTimes.clear();
    }

    private boolean isTransactional(Operation op) {
        return op != null && op.getTransactionId() != null
                && this.host.getTransactionServiceUri() != null;
    }

    private boolean hasServiceSoftState(Service service) {
        if (!service.hasOption(ServiceOption.ON_DEMAND_LOAD)) {
            return false;
        }

        UtilityService subUtilityService = (UtilityService) service
                .getUtilityService(ServiceHost.SERVICE_URI_SUFFIX_SUBSCRIPTIONS);
        UtilityService statsUtilityService = (UtilityService) service
                .getUtilityService(ServiceHost.SERVICE_URI_SUFFIX_STATS);
        boolean hasSoftState = false;
        if (subUtilityService != null && subUtilityService.hasSubscribers()) {
            hasSoftState = true;
        }
        if (statsUtilityService != null && statsUtilityService.hasStats()) {
            hasSoftState = true;
        }
        return hasSoftState;
    }
}
