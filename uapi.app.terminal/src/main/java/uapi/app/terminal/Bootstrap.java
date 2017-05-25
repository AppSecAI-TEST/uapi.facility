/*
 * Copyright (C) 2017. The UAPI Authors
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at the LICENSE file.
 *
 * You must gained the permission from the authors if you want to
 * use the project into a commercial product
 */

package uapi.app.terminal;

import uapi.Tags;
import uapi.UapiException;
import uapi.app.AppErrors;
import uapi.app.AppException;
import uapi.app.ExitSystemRequest;
import uapi.app.internal.AppServiceLoader;
import uapi.app.internal.SystemShuttingDownEvent;
import uapi.app.internal.SystemStartingUpEvent;
import uapi.app.terminal.internal.CliConfigProvider;
import uapi.common.CollectionHelper;
import uapi.config.ICliConfigProvider;
import uapi.event.IAttributedEventHandler;
import uapi.event.IEventBus;
import uapi.rx.Looper;
import uapi.service.IRegistry;
import uapi.service.IService;
import uapi.service.ITagged;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * The UAPI application entry point
 * The Bootstrap's responsibility is load basic services, parse command line arguments and
 * send out system startup event
 */
public class Bootstrap {

    private static final String[] basicSvcTags = new String[] {
            Tags.REGISTRY, Tags.CONFIG, Tags.LOG, Tags.EVENT, Tags.BEHAVIOR,
            Tags.PROFILE, Tags.APPLICATION
    };

    private static final AppServiceLoader appSvcLoader = new AppServiceLoader();
    private static final Semaphore semaphore = new Semaphore(0);

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        Iterable<IService> svcLoaders = appSvcLoader.loadServices();
        final List<IRegistry> svcRegistries = new ArrayList<>();
        final List<IService> basicSvcs = new ArrayList<>();
        final List<IService> otherSvcs = new ArrayList<>();
        Looper.on(svcLoaders)
                .foreach(svc -> {
                    if (svc instanceof IRegistry) {
                        svcRegistries.add((IRegistry) svc);
                    }
                    if (svc instanceof ITagged) {
                        ITagged taggedSvc = (ITagged) svc;
                        String[] tags = taggedSvc.getTags();
                        if (CollectionHelper.contains(tags, basicSvcTags) != null) {
                            basicSvcs.add(svc);
                        } else {
                            otherSvcs.add(svc);
                        }
                    } else {
                        otherSvcs.add(svc);
                    }
                });

        if (svcRegistries.size() == 0) {
            throw AppException.builder()
                    .errorCode(AppErrors.REGISTRY_IS_REQUIRED)
                    .build();
        }
        if (svcRegistries.size() > 1) {
            throw AppException.builder()
                    .errorCode(AppErrors.MORE_REGISTRY)
                    .variables(new AppErrors.MoreRegistry()
                            .registries(svcRegistries))
                    .build();
        }

        IRegistry svcRegistry = svcRegistries.get(0);
        // Register basic service first
        svcRegistry.register(basicSvcs.toArray(new IService[basicSvcs.size()]));
        String svcRegType = svcRegistry.getClass().getCanonicalName();
        svcRegistry = svcRegistry.findService(IRegistry.class);
        if (svcRegistry == null) {
            throw AppException.builder()
                    .errorCode(AppErrors.REGISTRY_IS_UNSATISFIED)
                    .variables(new AppErrors.RepositoryIsUnsatisfied()
                            .serviceRegistryType(svcRegType))
                    .build();
        }

        // Parse command line parameters
        CliConfigProvider cliCfgProvider = svcRegistry.findService(CliConfigProvider.class);
        if (cliCfgProvider == null) {
            throw AppException.builder()
                    .errorCode(AppErrors.SPECIFIC_SERVICE_NOT_FOUND)
                    .variables(new AppErrors.SpecificServiceNotFound()
                            .serviceType(ICliConfigProvider.class.getCanonicalName()))
                    .build();
        }
        cliCfgProvider.parse(args);

        // All base service must be activated
        Looper.on(basicSvcTags).foreach(svcRegistry::activateTaggedService);

        // Send system starting up event
        SystemStartingUpEvent sysLaunchedEvent = new SystemStartingUpEvent(startTime, otherSvcs);
        IEventBus eventBus = svcRegistry.findService(IEventBus.class);
        eventBus.register(new ExitSystemRequestHandler());
        eventBus.fire(sysLaunchedEvent);

        Exception ex = null;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(new ShutdownHook()));
            semaphore.acquire();
        } catch (InterruptedException e) {
            ex = e;
        }

        // Send system shutting down event
        SystemShuttingDownEvent shuttingDownEvent = new SystemShuttingDownEvent(otherSvcs, ex);
        eventBus.fire(shuttingDownEvent, true);
    }

    private static final class ShutdownHook implements Runnable {

        @Override
        public void run() {
            semaphore.release();
        }
    }

    private static final class ExitSystemRequestHandler implements IAttributedEventHandler<ExitSystemRequest> {

        @Override
        public String topic() {
            return ExitSystemRequest.TOPIC;
        }

        @Override
        public void handle(ExitSystemRequest event) throws UapiException {
            Bootstrap.semaphore.release();
        }

        @Override
        public Map<Object, Object> getAttributes() {
            return null;
        }
    }

    // Private constructor
    private Bootstrap() {}
}