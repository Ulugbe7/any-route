package uz.khurozov.fuzzyroute;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import net.sourceforge.jFuzzyLogic.FIS;
import uz.khurozov.fuzzyroute.util.GlobalVariables;
import uz.khurozov.fuzzyroute.util.ReliabilityGraphData;
import uz.khurozov.fuzzyroute.util.TopologyData;
import uz.khurozov.fuzzyroute.util.algo.Dijkstra;
import uz.khurozov.fuzzyroute.util.algo.RouteAlgorithm;
import uz.khurozov.fuzzyroute.util.algo.RouteUtil;
import uz.khurozov.fuzzyroute.component.*;
import uz.khurozov.fuzzyroute.component.dialog.*;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;


public class Controller {
    private int r = 0;
    private TreeMap<String, TreeMap<String, Connection>> connectionsTable;
    private TreeMap<String, Router> routersMap;
    private Connection currentConnection;
    private final ConPropsDialog conPropsDialog = new ConPropsDialog();
    private final Map<String, TopologyData> topologyDataCache = new LinkedHashMap<>();

    @FXML
    private AnchorPane desk;

    @FXML
    private ToggleButton routerBtn;

    @FXML
    private ToggleButton cableBtn;

    @FXML
    private ToggleButton deleteBtn;

    @FXML
    private ToggleGroup toggles;

    @FXML
    private ChoiceBox<String> algorithms;

    @FXML
    private Button findRouteBtn;

    @FXML
    private Button stopBtn;

    @FXML
    private VBox resultsPane;

    @FXML
    private Label time;

    @FXML
    private Label distance;

    @FXML
    private Label availability;

    @FXML
    private void initialize() {
        algorithms.getItems().addAll("Dijkstra", "Floyd", "Bellman-Ford", "Default fuzzy logic", "Real fuzzy logic");
        algorithms.getSelectionModel().selectFirst();

        connectionsTable = new TreeMap<>();
        routersMap = new TreeMap<>();
    }

    @FXML
    void mouseClickOnDesk(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (routerBtn.isSelected() && e.getTarget().equals(desk)) {
            Router router = new Router(++r, e.getX(), e.getY());

            String routerName = router.getName();
            router.setOnMouseClicked(mouseEvent -> {
                if (cableBtn.isSelected()) {
                    if (currentConnection == null) {
                        currentConnection = new Connection();
                        desk.getChildren().add(currentConnection);

                        currentConnection.setSource(router);
                        currentConnection.setEndXY(router.getLayoutX() + mouseEvent.getX(), router.getLayoutY() + mouseEvent.getY());

                        desk.setOnMouseMoved(event -> currentConnection.setEndXY(event.getX() + Math.signum(currentConnection.getStartX() - currentConnection.getEndX()), event.getY() + Math.signum(currentConnection.getStartY() - currentConnection.getEndY())));
                    } else {
                        if (connectionsTable.get(currentConnection.getSource()).get(routerName) != null || connectionsTable.get(routerName).get(currentConnection.getSource()) != null) {
                            //forbid doubling connection
                            return;
                        }
                        currentConnection.setTarget(router);

                        if (currentConnection.getEndX() == currentConnection.getStartX() && currentConnection.getEndY() == currentConnection.getStartY()) {
                            desk.getChildren().remove(currentConnection);
                        } else {
                            connectionsTable.get(currentConnection.getSource()).put(currentConnection.getTarget(), currentConnection);

                            currentConnection.setOnMouseClicked(mEvent -> {
                                Connection connection = (Connection) mEvent.getSource();
                                if (connection.isSendingData()) {
                                    return;
                                }
                                if (deleteBtn.isSelected()) {
                                    connectionsTable.get(connection.getSource()).put(connection.getTarget(), null);
                                    desk.getChildren().remove(connection);
                                } else if (mEvent.getClickCount() == 2 && currentConnection == null) {
                                    String[] names = new String[]{connection.getSource(), connection.getTarget()};
                                    Arrays.sort(names);
                                    conPropsDialog.setTitle(names[0] + " - " + names[1]);
                                    conPropsDialog.setProps(connection.getMetrics(), connection.getCableAvailability(), connection.getCableCount());

                                    conPropsDialog.showAndWait().ifPresent(conProps -> {
                                        connection.setProps(conProps.metrics(), conProps.availability());
                                        connection.setCableCount(conProps.count());
                                    });
                                }
                            });
                        }

                        currentConnection = null;
                        desk.setOnMouseMoved(null);

                    }
                } else if (deleteBtn.isSelected()) {
                    desk.getChildren().remove(router);
                    routersMap.remove(routerName);

                    routersMap.keySet().forEach(r -> {
                        Connection connection = connectionsTable.get(r).remove(routerName);
                        if (connection == null) {
                            connection = connectionsTable.get(routerName).remove(r);
                        }

                        if (connection != null) {
                            desk.getChildren().remove(connection);
                        }
                    });
                    connectionsTable.remove(routerName);

                    findRouteBtn.setDisable(connectionsTable.size() < 2);
                }
            });

            desk.getChildren().add(router);
            routersMap.put(routerName, router);

            connectionsTable.put(routerName, new TreeMap<>());

            findRouteBtn.setDisable(connectionsTable.size() < 2);
        }
    }

    @FXML
    void clearDesk() {
        stopAnimation();
        resultsPane.setVisible(false);
        connectionsTable.clear();
        desk.getChildren().clear();
        findRouteBtn.setDisable(true);
        r = 0;

        if (toggles.getSelectedToggle() == null) return;
        toggles.getSelectedToggle().setSelected(false);
    }

    @FXML
    void findRoute() {
        stopAnimation();
        resultsPane.setVisible(false);

        Font font = new Font("JetBrainsMono Nerd Font", 16);

        SourceTargetPane stPane = new SourceTargetPane(routersMap.keySet(), font);
        stPane.getColumnConstraints().addAll(new ColumnConstraints(100, 100, 100), new ColumnConstraints(100, 100, 100));

        Dialog<Map<String, String>> stDialog = new Dialog<>();
        stDialog.getDialogPane().setContent(stPane);
        stDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        stDialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                return stPane.getValue();
            }
            return null;
        });

        stDialog.showAndWait().ifPresent(st -> Platform.runLater(() -> {
            String r1 = st.get("source");
            String r2 = st.get("target");

            Thread thread = new Thread(() -> {
                findRouteBtn.setDisable(true);

                String algoName = algorithms.getValue();
                List<String> route;
                AtomicLong begin = new AtomicLong(), end = new AtomicLong();

                try {
                    RouteAlgorithm algo = RouteUtil.getRouteAlgorithm(algoName);
                    begin.set(System.nanoTime());
                    route = algo.findRoute(getMetricsTable(!"Bellman-Ford".equals(algoName)), r1, r2);
                    end.set(System.nanoTime());
                } catch (RuntimeException e) {
                    Platform.runLater(() -> new JustAlert(JustAlert.Message.RUNTIME_ERROR).showAndWait());
                    System.out.println(e.getMessage());
                    return;
                }

                if (route == null) {
                    Platform.runLater(() -> new JustAlert(JustAlert.Message.ROUTE_NOT_FOUND).showAndWait());
                } else {
                    switch (algoName) {
                        case "Bellman-Ford", "Floyd", "Dijkstra" -> {
                            String p1;
                            String p2 = route.get(0);

                            AtomicLong dis = new AtomicLong(0);
                            double graphAvailability;

                            ArrayList<Connection> connections = new ArrayList<>();

                            for (int i = 1; i < route.size(); i++) {
                                p1 = p2;
                                p2 = route.get(i);

                                Connection connection = connectionsTable.get(p1).get(p2);
                                if (connection == null) {
                                    connection = connectionsTable.get(p2).get(p1);
                                }
                                connections.add(connection);
                                connection.startSendingData(connection.getSource().equals(p1));
                                dis.addAndGet(connection.getMetrics());
                            }
                            graphAvailability = Math.pow(GlobalVariables.routerAvailability, connections.size() + 1) * Math.pow(GlobalVariables.connectionAvailability, connections.size());
                            stopBtn.setDisable(false);

                            double finalGraphAvailability = graphAvailability;
                            Platform.runLater(() -> {
                                long t = end.get() - begin.get();
                                long frac = 0;
                                String unit = "ns";
                                if (t > 1000) {
                                    frac = t % 1000;
                                    t /= 1000;
                                    unit = "mks";

                                    if (t > 1000) {
                                        frac = t % 1000;
                                        t /= 1000;
                                        unit = "ms";

                                        if (t > 1000) {
                                            frac = t % 1000;
                                            t /= 1000;
                                            unit = "s";
                                        }
                                    }
                                }
                                time.setText(String.format("≈ %d.%03d %s", t, frac, unit));
                                distance.setText(String.valueOf(dis.get()));
                                availability.setText(String.format("%.5f", finalGraphAvailability));
                            });
                            resultsPane.setVisible(true);
                        }
                        case "Default fuzzy logic" -> {
                            long mStartTime = System.currentTimeMillis();

                            List<Double> availabilities = new ArrayList<>();
                            List<Long> metrics = new ArrayList<>();
                            List<List<String>> routes = new ArrayList<>();

                            for (String s : route) {
                                routes.add(stringToStringList(s));
                            }

                            for (List<String> mRoute : routes) {
                                String p1;
                                String p2 = mRoute.get(0);

                                AtomicLong dis = new AtomicLong(0);
                                double graphAvailability;

                                ArrayList<Connection> connections = new ArrayList<>();

                                for (int i = 1; i < mRoute.size(); i++) {
                                    p1 = p2;
                                    p2 = mRoute.get(i);

                                    Connection connection = connectionsTable.get(p1).get(p2);
                                    if (connection == null) {
                                        connection = connectionsTable.get(p2).get(p1);
                                    }
                                    connections.add(connection);
                                    //connection.startSendingData(connection.getSource().equals(p1));
                                    dis.addAndGet(connection.getMetrics());
                                }

                                graphAvailability = Math.floor(Math.pow(GlobalVariables.routerAvailability, (connections.size() + 1)) * Math.pow(GlobalVariables.connectionAvailability, connections.size()) * 100000) / 100000;
                                availabilities.add(graphAvailability);
                                metrics.add(dis.longValue());
                            }

                            // start average route
                            FuzzyData fuzzyData = findRouteWithFuzzyLogic(routes, metrics, availabilities);

                            if (fuzzyData != null) {

                                List<String> fuzzyRoute = fuzzyData.route();

                                String p1;
                                String p2 = fuzzyRoute.get(0);

                                for (int i = 1; i < fuzzyRoute.size(); i++) {
                                    p1 = p2;
                                    p2 = fuzzyRoute.get(i);

                                    Connection connection = connectionsTable.get(p1).get(p2);
                                    if (connection == null) {
                                        connection = connectionsTable.get(p2).get(p1);
                                    }
                                    connection.startSendingData(connection.getSource().equals(p1));
                                }

                                stopBtn.setDisable(false);

                                Platform.runLater(() -> {
                                    long t = end.get() - begin.get() + mStartTime + System.currentTimeMillis();
                                    long frac = 0;
                                    String unit = "ns";
                                    if (t > 1000) {
                                        frac = t % 1000;
                                        t /= 1000;
                                        unit = "mks";

                                        if (t > 1000) {
                                            frac = t % 1000;
                                            t /= 1000;
                                            unit = "ms";

                                            if (t > 1000) {
                                                frac = t % 1000;
                                                t /= 1000;
                                                unit = "s";
                                            }
                                        }
                                    }
                                    time.setText(String.format("≈ %d.%03d %s", t, frac, unit));
                                    distance.setText(String.valueOf(fuzzyData.metrics()));
                                    availability.setText(String.format("%.5f", fuzzyData.availability()));
                                });
                                resultsPane.setVisible(true);
                            }
                        }
                        case "Real fuzzy logic" -> {

                            // fuzzy logic with difference available

                            long mStartTime = System.currentTimeMillis();

                            List<Double> availabilities = new ArrayList<>();
                            List<Long> metrics = new ArrayList<>();
                            List<List<String>> routes = new ArrayList<>();

                            for (String s : route) {
                                routes.add(stringToStringList(s));
                            }

                            for (List<String> mRoute : routes) {
                                String p1;
                                String p2 = mRoute.get(0);

                                AtomicLong dis = new AtomicLong(0);
                                double graphAvailability = 1.0;

                                ArrayList<Connection> connections = new ArrayList<>();

                                for (int i = 1; i < mRoute.size(); i++) {
                                    p1 = p2;
                                    p2 = mRoute.get(i);

                                    Connection connection = connectionsTable.get(p1).get(p2);
                                    if (connection == null) {
                                        connection = connectionsTable.get(p2).get(p1);
                                    }
                                    connections.add(connection);
                                    //connection.startSendingData(connection.getSource().equals(p1));
                                    dis.addAndGet(connection.getMetrics());
                                }
                                System.out.println(connections);
                                for (Connection connection : connections) {
                                    graphAvailability *= (connection.getCableAvailability() / 1000d);
                                }

                                graphAvailability *= Math.floor(Math.pow(GlobalVariables.routerAvailability, (connections.size() + 1)) * 100000) / 100000;
                                availabilities.add(graphAvailability);
                                metrics.add(dis.longValue());
                            }

                            // start average route
                            FuzzyData fuzzyData = findRouteWithFuzzyLogic(routes, metrics, availabilities);

                            if (fuzzyData != null) {

                                List<String> fuzzyRoute = fuzzyData.route();

                                String p1;
                                String p2 = fuzzyRoute.get(0);

                                for (int i = 1; i < fuzzyRoute.size(); i++) {
                                    p1 = p2;
                                    p2 = fuzzyRoute.get(i);

                                    Connection connection = connectionsTable.get(p1).get(p2);
                                    if (connection == null) {
                                        connection = connectionsTable.get(p2).get(p1);
                                    }
                                    connection.startSendingData(connection.getSource().equals(p1));
                                }

                                stopBtn.setDisable(false);

                                Platform.runLater(() -> {
                                    long t = end.get() - begin.get() + mStartTime + System.currentTimeMillis();
                                    long frac = 0;
                                    String unit = "ns";
                                    if (t > 1000) {
                                        frac = t % 1000;
                                        t /= 1000;
                                        unit = "mks";

                                        if (t > 1000) {
                                            frac = t % 1000;
                                            t /= 1000;
                                            unit = "ms";

                                            if (t > 1000) {
                                                frac = t % 1000;
                                                t /= 1000;
                                                unit = "s";
                                            }
                                        }
                                    }
                                    time.setText(String.format("≈ %d.%03d %s", t, frac, unit));
                                    distance.setText(String.valueOf(fuzzyData.metrics()));
                                    availability.setText(String.format("%.5f", fuzzyData.availability()));
                                });
                                resultsPane.setVisible(true);
                            }
                        }
                    }
                }

                findRouteBtn.setDisable(false);
            });

            thread.setDaemon(true);
            thread.start();
        }));
    }

    @FXML
    void stopAnimation() {
        desk.getChildren().parallelStream().filter(node -> node instanceof Connection).map(node -> (Connection) node).forEach(Connection::stopSendingData);

        stopBtn.setDisable(true);
    }

    //Menus

    @FXML
    @SuppressWarnings("unchecked")
    void graphByTopology() {
        if (topologyDataCache.size() < 2) {
            new JustAlert(JustAlert.Message.AT_LEAST_TWO_TOPOLOGIES).showAndWait();
            return;
        }

        Optional<Map<String, Object>> dataOpt = new ComparingGraphDialog(topologyDataCache.keySet(), true).showAndWait();

        if (dataOpt.isEmpty()) return;

        Map<String, Object> data = dataOpt.get();

        double routerRel = ((double) data.get("routerRel"));
        List<String> topologyNames = (List<String>) data.get("topologies");
        List<TopologyData> topologies = topologyDataCache.values().stream().filter(topologyData -> topologyNames.contains(topologyData.name())).collect(Collectors.toList());

        showComparingGraphView(ReliabilityGraphData.comparingTopologies(routerRel, topologies));
    }

    @FXML
    void graphByCableCount() {
        if (routersMap.size() < 2) {
            new JustAlert(JustAlert.Message.AT_LEAST_TWO_ROUTERS).showAndWait();
            return;
        }

        Optional<Map<String, Object>> dataOpt = new ComparingGraphDialog(routersMap.keySet(), false).showAndWait();

        if (dataOpt.isEmpty()) return;

        Map<String, Object> data = dataOpt.get();

        double routerRel = ((double) data.get("routerRel"));
        String source = ((String) data.get("source"));
        String target = ((String) data.get("target"));
        Integer cableCountFrom = ((Integer) data.get("cableCountFrom"));
        Integer cableCountTo = ((Integer) data.get("cableCountTo"));

        List<String> route = new Dijkstra().findRoute(getMetricsTable(true), source, target);

        if (route == null) {
            new JustAlert(JustAlert.Message.ROUTE_NOT_FOUND).showAndWait();
            return;
        }

        showComparingGraphView(ReliabilityGraphData.comparingCableCount(routerRel, cableCountFrom, cableCountTo, route.size()));
    }

    @FXML
    void setUpAvailability() {
        new AvailabilityDialog().showAndWait();
    }

    @FXML
    void saveTopology() {
        if (routersMap.size() < 2) {
            new JustAlert(JustAlert.Message.AT_LEAST_TWO_ROUTERS).showAndWait();
            return;
        }
        Optional<Map<String, String>> stringStringMap = new SaveTopologyDialog(routersMap.keySet(), topologyDataCache.size()).showAndWait();
        stringStringMap.ifPresent(map -> {
            String name = map.get("name");
            String source = map.get("source");
            String target = map.get("target");
            List<String> route = new Dijkstra().findRoute(getMetricsTable(true), source, target);

            if (route == null) {
                new JustAlert(JustAlert.Message.ROUTE_NOT_FOUND).showAndWait();
                return;
            }

            if (topologyDataCache.containsKey(name)) {
                new JustAlert(JustAlert.Message.TOPOLOGY_NAME_EXISTS).showAndWait();
                return;
            }

            topologyDataCache.put(name, new TopologyData(name, source, target, getIsConnectedTable()));
            clearDesk();
        });
    }

    @FXML
    void clearCache() {
        new JustAlert(JustAlert.Message.DELETE_TOPOLOGY_CACHE_CONFIRMATION).showAndWait().ifPresent(buttonType -> {
            if (buttonType == ButtonType.OK) {
                topologyDataCache.clear();
            }
        });
    }

    @FXML
    void showSchema(ActionEvent event) {
        String algo = ((MenuItem) event.getTarget()).getText();
        InputStream schemaFile = App.class.getResourceAsStream("/images/block-schema/" + algo + ".png");

        if (schemaFile == null) {
            new JustAlert(JustAlert.Message.RUNTIME_ERROR).showAndWait();
            return;
        }
        Image image = new Image(schemaFile);

        Stage stage = new Stage();

        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(algo + " block-schema");

        ScrollPane root = new ScrollPane(new ImageView(image));
        double height = Screen.getPrimary().getBounds().getHeight() * 0.6;
        stage.setScene(new Scene(root, image.getWidth() + 16, height));

        stage.showAndWait();
    }

    //helper methods

    public void bindKeys() {
        Scene scene = desk.getScene();
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN), () -> routerBtn.setSelected(true));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN), () -> cableBtn.setSelected(true));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.D, KeyCombination.CONTROL_DOWN), () -> deleteBtn.setSelected(true));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN), this::findRoute);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.ESCAPE, KeyCombination.CONTROL_ANY), this::cancel);
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F1), () -> new JustAlert(JustAlert.Message.SOURCE_CODE).showAndWait());
    }

    private void cancel() {
        if (currentConnection == null) {
            Toggle selectedBtn = toggles.getSelectedToggle();
            if (selectedBtn != null) {
                selectedBtn.setSelected(false);
            }
        } else {
            desk.getChildren().remove(currentConnection);
            desk.setOnMouseMoved(null);
            currentConnection = null;
        }
    }

    private TreeMap<String, TreeMap<String, Integer>> getMetricsTable(boolean isUndirected) {
        TreeMap<String, TreeMap<String, Integer>> table = new TreeMap<>();

        connectionsTable.forEach((r, cableMap) -> table.put(r, new TreeMap<>()));

        connectionsTable.forEach((r1, row) -> row.forEach((r2, con) -> {
            if (con != null) {

                table.get(r1).put(r2, con.getMetrics());

                if (isUndirected) {
                    if (con.getMetrics() < 0)
                        throw new IllegalArgumentException("Negative metrics on undirected connection");
                    table.get(r2).put(r1, con.getMetrics());
                }
            }
        }));

        return table;
    }

    private Map<String, Map<String, Boolean>> getIsConnectedTable() {
        Map<String, Map<String, Boolean>> map = new TreeMap<>();
        Set<String> routerNames = routersMap.keySet();

        connectionsTable.forEach((r, cableMap) -> map.put(r, new TreeMap<>()));
        connectionsTable.forEach((r1, row) -> {
            row.forEach((r2, con) -> {
                map.get(r1).put(r2, true);
                map.get(r2).put(r1, true);
            });
            routerNames.forEach(r2 -> map.get(r1).putIfAbsent(r2, false));
        });

        return map;
    }

    private void showComparingGraphView(Map<String, double[]> chartData) {
        new ComparingGraphView(chartData, new double[]{0.99, 0.99099, 0.99198, 0.99297, 0.99396, 0.99495, 0.99594, 0.99693, 0.99792, 0.99891, 0.9999,}).showAndWait();
    }

    private List<String> stringToStringList(String route) {
        String mRoute = route.replace("[", "").replace("]", "");
        List<String> temp = new ArrayList<>(List.of(mRoute.replace("[", "").replace("]", "").split(",")));
        temp.replaceAll(String::trim);
        return temp;
    }

    private FuzzyData findRouteWithFuzzyLogic(List<List<String>> routes, List<Long> metrics, List<Double> availabilities) {

        List<FuzzyData> evaluatedList = new ArrayList<>();

        InputStream model = getClass().getResourceAsStream("/Model.fcl");
        FIS fis = FIS.load(model, true);

        if (fis == null) {
            System.err.println("Cannot load file: Model.fcl");
            return null;
        }

        // Set up an array of input values and their corresponding membership degrees
        double[][] inputValues = new double[metrics.size()][2];

        for (int i = 0; i < inputValues.length; i++) {
            inputValues[i][0] = metrics.get(i);
            inputValues[i][1] = availabilities.get(i);
        }

        // Set inputs
        for (int i = 0; i < inputValues.length; i++) {

            double metric = inputValues[i][0];
            double availability = inputValues[i][1];

            fis.setVariable("metrics", metric);
            fis.setVariable("availability", availability);

            fis.evaluate();
            double outputValue = fis.getVariable("routeQuality").defuzzify();

            evaluatedList.add(new FuzzyData(routes.get(i), metric, availability, outputValue));
        }
        evaluatedList.sort(Comparator.comparing(FuzzyData::metrics));
        evaluatedList.sort((o1, o2) -> o2.fuzziedValue().compareTo(o1.fuzziedValue()));

        System.out.println(evaluatedList);

        if (evaluatedList.isEmpty()) {
            return null;
        } else {
            return evaluatedList.get(0);
        }
    }
}
