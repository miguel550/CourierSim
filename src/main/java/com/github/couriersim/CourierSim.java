package com.github.couriersim;

/*
 * Copyright (C) 2011-2018 Rinde R.S. van Lon
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static com.google.common.collect.Maps.newHashMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map;

import javax.annotation.Nullable;

import com.github.couriersim.pruner.CenterPruner;
import com.github.rinde.rinsim.core.model.road.RoadUser;
import com.github.rinde.rinsim.geom.*;
import com.google.common.base.Predicate;
import org.apache.commons.math3.random.RandomGenerator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;

import com.github.rinde.rinsim.core.Simulator;
import com.github.rinde.rinsim.core.model.pdp.DefaultPDPModel;
import com.github.rinde.rinsim.core.model.pdp.Depot;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.core.model.pdp.ParcelDTO;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.core.model.road.RoadModelBuilders;
import com.github.rinde.rinsim.core.model.time.TickListener;
import com.github.rinde.rinsim.core.model.time.TimeLapse;
import com.github.rinde.rinsim.event.Listener;
import com.github.couriersim.TaxiRenderer.Language;
import com.github.rinde.rinsim.geom.io.DotGraphIO;
import com.github.rinde.rinsim.geom.io.Filters;
import com.github.rinde.rinsim.ui.View;
import com.github.rinde.rinsim.ui.renderers.GraphRoadModelRenderer;
import com.github.rinde.rinsim.ui.renderers.RoadUserRenderer;

/**
 * Example showing a fleet of taxis that have to pickup and transport customers
 * around the city of Leuven.
 * <p>
 * If this class is run on MacOS it might be necessary to use
 * -XstartOnFirstThread as a VM argument.
 * @author Rinde van Lon
 */
public final class CourierSim {

    private static final int NUM_DEPOTS = 1;
    private static final int NUM_TAXIS = 2;
    private static final int NUM_CUSTOMERS = 3;

    // time in ms
    private static final long SERVICE_DURATION = 5 * 60 * 1000;
    private static final int TAXI_CAPACITY = 5;
    private static final int DEPOT_CAPACITY = 100;

    private static final int SPEED_UP = 9;
    private static final int MAX_CAPACITY = 5;
    private static final double NEW_CUSTOMER_PROB = .003;

    private static final String MAP_FILE = "/data/maps/leuven-simple.dot";
    private static final Map<String, Graph<MultiAttributeData>> GRAPH_CACHE =
            newHashMap();

    private static final long TEST_STOP_TIME = 1 * 60 * 60 * 1000;
    private static final int TEST_SPEED_UP = 60 * 60 * 1000;

    private CourierSim() {}

    /**
     * Starts the {@link CourierSim}.
     * @param args The first option may optionally indicate the end time of the
     *          simulation.
     */
    public static void main(@Nullable String[] args) {
        final long endTime = args != null && args.length >= 1 ? Long
                .parseLong(args[0]) : Long.MAX_VALUE;

        final String graphFile = args != null && args.length >= 2 ? args[1]
                : MAP_FILE;
        run(false, endTime, graphFile, null /* new Display() */, null, null);
    }

    /**
     * Run the example.
     * @param testing If <code>true</code> enables the test mode.
     */
    public static void run(boolean testing) {
        run(testing, Long.MAX_VALUE, MAP_FILE, null, null, null);
    }

    enum Pred implements Predicate<RoadUser> {
        TAXIS {
            @Override
            public boolean apply(@org.jetbrains.annotations.Nullable RoadUser roadUser) {
                return roadUser instanceof Taxi;
            }
        },
        PARCELS {
            @Override
            public boolean apply(@org.jetbrains.annotations.Nullable RoadUser roadUser) {
                return roadUser instanceof Parcel;
            }
        }
    }
    /**
     * Starts the example.
     * @param testing Indicates whether the method should run in testing mode.
     * @param endTime The time at which simulation should stop.
     * @param graphFile The graph that should be loaded.
     * @param display The display that should be used to show the ui on.
     * @param m The monitor that should be used to show the ui on.
     * @param list A listener that will receive callbacks from the ui.
     * @return The simulator instance.
     */
    public static Simulator run(boolean testing, final long endTime,
                                String graphFile,
                                @Nullable Display display, @Nullable Monitor m, @Nullable Listener list) {

        final View.Builder view = createGui(testing, display, m, list);

        // use map of leuven
        final Simulator simulator = Simulator.builder()
                .addModel(RoadModelBuilders.staticGraph(loadGraph(graphFile)))
                .addModel(DefaultPDPModel.builder())
                .addModel(view)
                .build();
        final RandomGenerator rng = simulator.getRandomGenerator();

        final RoadModel roadModel = simulator.getModelProvider().getModel(
                RoadModel.class);
        // add depots, taxis and parcels to simulator
        for (int i = 0; i < NUM_DEPOTS; i++) {
            simulator.register(new TaxiBase(roadModel.getRandomPosition(rng),
                    DEPOT_CAPACITY));
        }
        for (int i = 0; i < NUM_TAXIS; i++) {
            simulator.register(new Taxi(roadModel.getRandomPosition(rng),
                    TAXI_CAPACITY));
        }
        for (int i = 0; i < NUM_CUSTOMERS; i++) {
            simulator.register(new Customer(
                    Parcel.builder(roadModel.getRandomPosition(rng),
                                    roadModel.getRandomPosition(rng))
                            .serviceDuration(SERVICE_DURATION)
                            .neededCapacity(1 + rng.nextInt(MAX_CAPACITY))
                            .buildDTO()));
        }

        simulator.addTickListener(new TickListener() {
            private double printed;
            private void printlnonce(double s){
                if (printed != s) {
                    printed = s;
                    System.out.println(s);
                }
            }
            @Override
            public void tick(TimeLapse time) {
                if (time.getStartTime() > endTime) {
                    simulator.stop();
                } else if (rng.nextDouble() < NEW_CUSTOMER_PROB) {
                    simulator.register(new Customer(
                            Parcel
                                    .builder(roadModel.getRandomPosition(rng),
                                            roadModel.getRandomPosition(rng))
                                    .serviceDuration(SERVICE_DURATION)
                                    .neededCapacity(1 + rng.nextInt(MAX_CAPACITY))
                                    .buildDTO()));
                }
                double total = 0;
                for (RoadUser ru: roadModel.getObjects(Pred.TAXIS)) {
                    total += ((Taxi) ru).getProfit();
                }
                printlnonce(total);
            }

            @Override
            public void afterTick(TimeLapse timeLapse) {

            }
        });
        simulator.start();

        return simulator;
    }

    static View.Builder createGui(
            boolean testing,
            @Nullable Display display,
            @Nullable Monitor m,
            @Nullable Listener list) {

        View.Builder view = View.builder()
                .with(GraphRoadModelRenderer.builder())
                .with(RoadUserRenderer.builder()
                        .withImageAssociation(
                                TaxiBase.class, "/graphics/flat/warehouse-64.png")
                        .withImageAssociation(
                                Taxi.class, "/graphics/perspective/deliverytruck.png")
                        .withImageAssociation(
                                Customer.class, "/graphics/flat/person-blue-32.png")
                        .withImageAssociation(Parcel.class, "/graphics/perspective/deliverypackage.png"))
                .with(TaxiRenderer.builder(Language.ENGLISH))
                .withTitleAppendix("Courier Service");

        if (testing) {
            view = view.withAutoClose()
                    .withAutoPlay()
                    .withSimulatorEndTime(TEST_STOP_TIME)
                    .withSpeedUp(TEST_SPEED_UP);
        } else if (m != null && list != null && display != null) {
            view = view.withMonitor(m)
                    .withSpeedUp(TEST_SPEED_UP)
                    .withSimulatorEndTime(TEST_STOP_TIME)
                    .withResolution(m.getClientArea().width, m.getClientArea().height)
                    .withDisplay(display)
                    .withCallback(list)
                    .withAsync()
                    .withAutoPlay()
                    .withAutoClose();
        }
        return view;
    }

    // load the graph file
    static Graph<MultiAttributeData> loadGraph(String name) {
        try {
            if (GRAPH_CACHE.containsKey(name)) {
                return GRAPH_CACHE.get(name);
            }
            Graph<MultiAttributeData> g = new TableGraph<>();
            if(name.endsWith(".dot")) {

                g = DotGraphIO
                        .getMultiAttributeGraphIO(
                                Filters.selfCycleFilter())
                        .read(CourierSim.class.getResourceAsStream(name));
            } else if (name.endsWith(".osm")) {
                OsmConverter osmc = new OsmConverter();
                CenterPruner cp = new CenterPruner();
                g = osmc
                        .withPruner(cp)
                        .convert(CourierSim.class.getResource(name).getPath());
            }
            GRAPH_CACHE.put(name, g);
            return g;
        } catch (final FileNotFoundException e) {
            throw new IllegalStateException(e);
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A customer with very permissive time windows.
     */
    static class Customer extends Parcel {
        Customer(ParcelDTO dto) {
            super(dto);
        }

        @Override
        public void initRoadPDP(RoadModel pRoadModel, PDPModel pPdpModel) {

        }
    }

    // currently has no function
    static class TaxiBase extends Depot {
        TaxiBase(Point position, double capacity) {
            super(position);
            setCapacity(capacity);
        }

        @Override
        public void initRoadPDP(RoadModel pRoadModel, PDPModel pPdpModel) {}
    }

}
