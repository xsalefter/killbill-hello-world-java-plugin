/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014-2020 Groupon, Inc
 * Copyright 2020-2020 Equinix, Inc
 * Copyright 2014-2020 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.helloworld;

import java.util.Hashtable;
import java.util.Properties;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.killbill.billing.osgi.api.Healthcheck;
import org.killbill.billing.osgi.api.OSGIPluginProperties;
import org.killbill.billing.osgi.libs.killbill.KillbillActivatorBase;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillEventDispatcher;
import org.killbill.billing.osgi.libs.killbill.OSGIKillbillEventDispatcher.OSGIFrameworkEventHandler;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.plugin.api.notification.PluginConfigurationEventHandler;
import org.killbill.billing.plugin.core.config.PluginEnvironmentConfig;
import org.killbill.billing.plugin.core.resources.jooby.PluginApp;
import org.killbill.billing.plugin.core.resources.jooby.PluginAppBuilder;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.service.log.LogService;
import org.osgi.service.log.Logger;
import org.osgi.service.log.LoggerFactory;

public class HelloWorldActivator extends KillbillActivatorBase {

    //
    // Ideally that string should match the pluginName on the filesystem, but there is no enforcement
    //
    public static final String PLUGIN_NAME = "hello-world-plugin";

    private HelloWorldConfigurationHandler helloWorldConfigurationHandler;
    private OSGIKillbillEventDispatcher.OSGIKillbillEventHandler killbillEventHandler;
    private MetricsGeneratorExample metricsGenerator;

    @Override
    public void start(final BundleContext context) throws Exception {
        super.start(context);

        final String region = PluginEnvironmentConfig.getRegion(configProperties.getProperties());

        // Register an event listener for plugin configuration (optional)
        helloWorldConfigurationHandler = new HelloWorldConfigurationHandler(region, PLUGIN_NAME, killbillAPI);
        final Properties globalConfiguration = helloWorldConfigurationHandler.createConfigurable(configProperties.getProperties());
        helloWorldConfigurationHandler.setDefaultConfigurable(globalConfiguration);

        // Register an event listener (optional)
        killbillEventHandler = new HelloWorldListener(killbillAPI);

        // As an example, this plugin registers a PaymentPluginApi (this could be changed to any other plugin api)
        final PaymentPluginApi paymentPluginApi = new HelloWorldPaymentPluginApi();
        registerPaymentPluginApi(context, paymentPluginApi);

        // Expose metrics (optional)
        metricsGenerator = new MetricsGeneratorExample(metricRegistry);
        metricsGenerator.start();

        // Expose a healthcheck (optional), so other plugins can check on the plugin status
        final Healthcheck healthcheck = new HelloWorldHealthcheck();
        registerHealthcheck(context, healthcheck);

        // Logger testing
        final LoggerFactory loggerFactory = registerAndGetLoggerFactory(context);
        final Logger logger = loggerFactory.getLogger(context.getBundle(), HelloWorldActivator.class.getName(), Logger.class);
        logger.info(">>> Log comes from OSGI LoggerFactory in HelloWorldActivator");

        final LogService logService = registerAndGetLogService(context);
        logService.log(LogService.LOG_INFO, ">>> Log comes from OSGI LoggerService in HelloWorldActivator");

        // Register a servlet (optional)
        final PluginApp pluginApp = new PluginAppBuilder(PLUGIN_NAME, killbillAPI, dataSource, clock, configProperties)
                .withRouteClass(HelloWorldServlet.class)
                .withRouteClass(HelloWorldHealthcheck.class)
                .withService(healthcheck)
                .withService(loggerFactory)
                .withService(logService)
                .build();

        final HttpServlet httpServlet = PluginApp.createServlet(pluginApp);
        registerServlet(context, httpServlet);

        registerHandlers();
    }

    @Override
    public void stop(final BundleContext context) throws Exception {
        // Do additional work on shutdown (optional)
        metricsGenerator.stop();
        super.stop(context);
    }

    private void registerHandlers() {
        final PluginConfigurationEventHandler configHandler = new PluginConfigurationEventHandler(helloWorldConfigurationHandler);

        dispatcher.registerEventHandlers(configHandler,
                                         (OSGIFrameworkEventHandler) () -> dispatcher.registerEventHandlers(killbillEventHandler));
    }

    private void registerServlet(final BundleContext context, final Servlet servlet) {
        final Hashtable<String, String> props = new Hashtable<String, String>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, Servlet.class, servlet, props);
    }

    private void registerPaymentPluginApi(final BundleContext context, final PaymentPluginApi api) {
        final Hashtable<String, String> props = new Hashtable<String, String>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, PaymentPluginApi.class, api, props);
    }

    private void registerHealthcheck(final BundleContext context, final Healthcheck healthcheck) {
        final Hashtable<String, String> props = new Hashtable<String, String>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);
        registrar.registerService(context, Healthcheck.class, healthcheck, props);
    }

    private LoggerFactory registerAndGetLoggerFactory(final BundleContext context) {
        final Hashtable<String, String> props = new Hashtable<>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);

        final ServiceReference<LoggerFactory> srLoggerFactory = context.getServiceReference(LoggerFactory.class);
        if (srLoggerFactory == null) {
            throw new RuntimeException("Cannot find ServiceReference<LoggerFactory>. Perhaps killbill's default osgi-logger not registered properly?");
        }
        final LoggerFactory loggerFactory = context.getService(srLoggerFactory);
        if (loggerFactory == null) {
            throw new RuntimeException("Cannot find loggerFactory. Perhaps killbill's default osgi-logger not registered properly?");
        }

        registrar.registerService(context, LoggerFactory.class, loggerFactory, props);

        return loggerFactory;
    }

    private LogService registerAndGetLogService(final BundleContext context) {
        final Hashtable<String, String> props = new Hashtable<>();
        props.put(OSGIPluginProperties.PLUGIN_NAME_PROP, PLUGIN_NAME);

        final ServiceReference<LogService> srLogService = context.getServiceReference(LogService.class);
        if (srLogService == null) {
            throw new RuntimeException("Cannot find ServiceReference<LogService>. Perhaps killbill's default osgi-logger not registered properly?");
        }
        final LogService logService = context.getService(srLogService);
        if (logService == null) {
            throw new RuntimeException("Cannot find logService. Perhaps killbill's default osgi-logger not registered properly?");
        }

        registrar.registerService(context, LogService.class, logService, props);

        return logService;
    }
}
