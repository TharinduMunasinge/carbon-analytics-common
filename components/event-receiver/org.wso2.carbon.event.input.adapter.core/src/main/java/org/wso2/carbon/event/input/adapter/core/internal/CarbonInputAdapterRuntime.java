/*
 * Copyright (c) 2005 - 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package org.wso2.carbon.event.input.adapter.core.internal;

import com.hazelcast.core.ILock;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.event.input.adapter.core.InputAdapterRuntime;
import org.wso2.carbon.event.input.adapter.core.InputEventAdapter;
import org.wso2.carbon.event.input.adapter.core.InputEventAdapterListener;
import org.wso2.carbon.event.input.adapter.core.InputEventAdapterSubscription;
import org.wso2.carbon.event.input.adapter.core.exception.ConnectionUnavailableException;
import org.wso2.carbon.event.input.adapter.core.exception.InputEventAdapterException;
import org.wso2.carbon.event.input.adapter.core.exception.InputEventAdapterRuntimeException;
import org.wso2.carbon.event.input.adapter.core.internal.ds.InputEventAdapterServiceValueHolder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created on 2/27/15.
 */
public class CarbonInputAdapterRuntime implements InputEventAdapterListener, InputAdapterRuntime {
    private static Log log = LogFactory.getLog(CarbonInputAdapterRuntime.class);
    private InputEventAdapter inputEventAdapter;
    private String name;
    private InputEventAdapterSubscription inputEventAdapterSubscription;
    private volatile boolean connected = false;
    private static boolean startPolling = false;
    private boolean start = false;
    private DecayTimer timer = new DecayTimer();
    private volatile long nextConnectionTime;
    private ExecutorService executorService;
    private ILock lock;

    public CarbonInputAdapterRuntime(InputEventAdapter inputEventAdapter, String name,
                                     InputEventAdapterSubscription inputEventAdapterSubscription) throws InputEventAdapterException {
        this.inputEventAdapter = inputEventAdapter;
        this.name = name;
        this.inputEventAdapterSubscription = inputEventAdapterSubscription;
        executorService = Executors.newSingleThreadExecutor();
        synchronized (this) {
            inputEventAdapter.init(this);
        }
    }

    public void startPolling() {
        if (!connected && start && isPolling()) {
            start();
        }
    }

    @Override
    public void start() {
        try {
            start = true;
            if (!isPolling() ||
                    InputEventAdapterServiceValueHolder.getCarbonInputEventAdapterService().isStartPolling()) {
                if (!connected) {
                    log.info("Connecting receiver "+this.name);
                    inputEventAdapter.connect();
                    connected = true;
                }
            } else {
                log.info("Waiting to connect receiver "+this.name);
            }
        } catch (ConnectionUnavailableException e) {
            connectionUnavailable(e);
        } catch (InputEventAdapterRuntimeException e) {
            connected = false;
            inputEventAdapter.disconnect();
            log.error("Error initializing " + this.name + ", hence this will be suspended indefinitely", e);
        }
    }

    public void destroy() {
        if (inputEventAdapter != null) {
            try {
                inputEventAdapter.disconnect();
            } finally {
                inputEventAdapter.destroy();
            }
        }
    }

    /**
     * when an event happens event proxy call this method with the received event.
     *
     * @param object - received event
     */
    @Override
    public void onEvent(Object object) {
        inputEventAdapterSubscription.onEvent(object);
    }

    @Override
    public synchronized void connectionUnavailable(ConnectionUnavailableException connectionUnavailableException) {
        try {
            try {
                if (!connected) {
                    if (nextConnectionTime <= System.currentTimeMillis()) {
                        inputEventAdapter.connect();
                        connected = true;
                        timer.reset();
                    }
                } else {
                    connected = false;
                    inputEventAdapter.disconnect();
                    timer.incrementPosition();
                    nextConnectionTime = System.currentTimeMillis() + timer.returnTimeToWait();
                    if (timer.returnTimeToWait() == 0) {
                        log.error("Connection unavailable on " + name + " reconnecting.", connectionUnavailableException);
                        inputEventAdapter.connect();
                    } else {
                        log.error("Connection unavailable on " + name + " reconnection will be retried in" + (timer.returnTimeToWait()) + " milliseconds.", connectionUnavailableException);
                        executorService.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(timer.returnTimeToWait());
                                } catch (InterruptedException e) {
                                    //nothing to be done
                                }
                                connectionUnavailable(null);
                            }
                        });
                    }
                }
            } catch (ConnectionUnavailableException e) {
                connectionUnavailable(e);
            }
        } catch (InputEventAdapterRuntimeException e) {
            connected = false;
            log.error("Error in connecting at " + this.name + ", hence this will be suspended indefinitely", e);
        }

    }

    @Override
    public boolean isEventDuplicatedInCluster() {
        return inputEventAdapter.isEventDuplicatedInCluster();
    }

    @Override
    public boolean isPolling() {
        return inputEventAdapter.isPolling();
    }

    public String getName() {
        return name;
    }
}
