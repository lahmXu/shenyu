/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shenyu.web.loader;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.shenyu.common.concurrent.ShenyuThreadFactory;
import org.apache.shenyu.common.config.ShenyuConfig;
import org.apache.shenyu.common.config.ShenyuConfig.ExtPlugin;
import org.apache.shenyu.plugin.api.ExtendDataBase;
import org.apache.shenyu.plugin.api.ShenyuPlugin;
import org.apache.shenyu.plugin.base.cache.ExtendDataHandler;
import org.apache.shenyu.web.handler.ShenyuWebHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The type Shenyu loader service.
 */
public class ShenyuLoaderService {

    private static final Logger LOG = LoggerFactory.getLogger(ShenyuLoaderService.class);

    private final ShenyuWebHandler webHandler;

    private final ShenyuConfig shenyuConfig;

    private final List<ExtendDataHandler<?>> extendDataHandlers;

    /**
     * Instantiates a new Shenyu loader service.
     *
     * @param webHandler         the web handler
     * @param shenyuConfig       the shenyu config
     * @param extendDataHandlers       addDataHandlers
     */
    public ShenyuLoaderService(final ShenyuWebHandler webHandler, final ShenyuConfig shenyuConfig, final List<ExtendDataHandler<?>> extendDataHandlers) {
        this.webHandler = webHandler;
        this.extendDataHandlers = extendDataHandlers;
        this.shenyuConfig = shenyuConfig;
        ExtPlugin config = shenyuConfig.getExtPlugin();
        if (config.getEnabled()) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(config.getThreads(), ShenyuThreadFactory.create("plugin-ext-loader", true));
            executor.scheduleAtFixedRate(this::loaderExtPlugins, config.getScheduleDelay(), config.getScheduleTime(), TimeUnit.SECONDS);
        }
    }

    private void loaderExtPlugins() {
        try {
            List<PluginJarParser.PluginJar> uploadPluginJars = ShenyuExtPathPluginJarLoader.loadExtendPlugins(shenyuConfig.getExtPlugin().getPath());
            List<ShenyuLoaderResult> extendPlugins = new ArrayList<>();
            for (PluginJarParser.PluginJar extPath : uploadPluginJars) {
                LOG.info("shenyu extPlugin find new {} to load", extPath.getAbsolutePath());
                ShenyuPluginClassLoader extPathClassLoader = ShenyuPluginClassloaderHolder.getSingleton().createExtPathClassLoader(extPath);
                extendPlugins.addAll(extPathClassLoader.loadUploadedJarPlugins(this.getClass().getClassLoader()));
            }
            loaderPlugins(extendPlugins);
        } catch (Exception e) {
            LOG.error("shenyu ext plugins load has error ", e);
        }
    }

    /**
     * loadUploadedJarPlugins.
     *
     * @param uploadedJarResourceBase64 uploadedJarResourceBase64
     */
    public void loadUploadedJarPlugins(final String uploadedJarResourceBase64) {
        loadJarPlugins(new ByteArrayInputStream(Base64.getDecoder().decode(uploadedJarResourceBase64)), this.getClass().getClassLoader());
    }


    /**
     * loadJarPlugins.
     *
     * @param parseJarInputStream parseJarInputStream
     * @param classLoader classLoader
     * @return a list of ShenyuLoaderResult
     */
    public List<ShenyuLoaderResult> loadJarPlugins(final InputStream parseJarInputStream, ClassLoader classLoader) {
        try {
            PluginJarParser.PluginJar pluginJar = PluginJarParser.parseJar(parseJarInputStream);
            ShenyuPluginClassLoader shenyuPluginClassLoader = ShenyuPluginClassloaderHolder.getSingleton().getUploadClassLoader(pluginJar);
            if (Objects.nonNull(shenyuPluginClassLoader) && shenyuPluginClassLoader.compareVersion(pluginJar.getVersion())) {
                LOG.info("shenyu uploadPlugin has same version don't reload it");
                return Collections.emptyList();
            }
            shenyuPluginClassLoader = ShenyuPluginClassloaderHolder.getSingleton().recreateUploadClassLoader(pluginJar);
            List<ShenyuLoaderResult> uploadPlugins = shenyuPluginClassLoader.loadUploadedJarPlugins(classLoader);
            loaderPlugins(uploadPlugins);
            return uploadPlugins;
        } catch (Exception e) {
            LOG.error("Shenyu upload plugins load has error ", e);
            return Collections.emptyList();
        }
    }

    /**
     * loaderPlugins.
     *
     * @param results results
     */
    public void loaderPlugins(final List<ShenyuLoaderResult> results) {
        if (CollectionUtils.isEmpty(results)) {
            return;
        }
        List<ShenyuPlugin> shenyuExtendPlugins = results.stream().map(ShenyuLoaderResult::getShenyuPlugin).filter(Objects::nonNull).collect(Collectors.toList());
        webHandler.putExtPlugins(shenyuExtendPlugins);
        List<ExtendDataBase> handlers = results.stream().map(ShenyuLoaderResult::getExtendDataBase)
                .filter(Objects::nonNull).collect(Collectors.toList());
        extendDataHandlers.forEach(addDataHandlers1 -> addDataHandlers1.putExtendDataHandler(handlers));
    }
}
