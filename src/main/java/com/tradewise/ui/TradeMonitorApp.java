package com.tradewise.ui;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * JavaFX UI for TradeWise - Trade Monitoring Application.
 * Improved with better styling, component organization, and error handling.
 */
public class TradeMonitorApp extends Application {

    // Resolve backend URL from system property or env var; fallback matches application.properties server.port.
    private final String apiBaseUrl = resolveApiBaseUrl();
    // Non-blocking HTTP client backed by a virtual/cached thread pool so JavaFX thread never blocks.
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .executor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "tradewise-http");
                t.setDaemon(true);
                return t;
            }))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private Label statusLabel;
    private TextField accessTokenField;
    private TableView<TradeRow> tradeTable;
    private Label summaryLabel;
    private TextArea logArea;
    private Button killSwitchBtn;
    private Button authBtn;
    private Label riskStatusLabel;
    private TextField tradeFilterField;
    private Label tradeCountLabel;
    private Label lastUpdatedLabel;
    private final ObservableList<TradeRow> allTradeRows = FXCollections.observableArrayList();
    private TableView<PositionRow> positionTable;
    private TableView<OrderRow> orderTable;
    private final ObservableList<OrderRow> allOrderRows = FXCollections.observableArrayList();
    private Label totalRealizedPnlLabel;
    private Label totalUnrealizedPnlLabel;
    private Label totalOverallPnlLabel;
    private Label dailyStatsLabel;
    private Label tradingLockedIndicator;
    private Timeline autoRefreshTimeline;
    private ComboBox<String> refreshIntervalBox;
    private Label countdownLabel;
    private int countdownSeconds = 0;
    private Timeline countdownTimeline;
    private boolean loggedIn;
    private VBox accessTokenInputBox;

    @Override
    public void start(Stage primaryStage) {
        try {
            primaryStage.setTitle("TradeWise - Trade Monitor");
            primaryStage.setWidth(1000);
            primaryStage.setHeight(700);
            primaryStage.setScene(createMainScene());
            primaryStage.show();

            addLog("Backend endpoint: " + apiBaseUrl);

            applyAutoRefreshMode("5s");
        } catch (Exception ex) {
            showAlert("Startup Error", ex.getMessage());
        }
    }

    @Override
    public void stop() {
        if (autoRefreshTimeline != null) {
            autoRefreshTimeline.stop();
        }
        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }
    }

    private Scene createMainScene() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.getStyleClass().add("app-root");

        // Top section - Login & Controls
        root.getChildren().add(createLoginSection());

        // Middle section — Trades + Orders tabs
        Tab tradesTab = new Tab("📊 Trades");
        tradesTab.setClosable(false);
        VBox tradesContent = new VBox(8);
        tradesContent.getStyleClass().addAll("card", "card-trades");
        tradesContent.setPadding(new Insets(8));
        tradesContent.getChildren().addAll(createTradesHeader(), createTradeTable(), createSummaryBar());
        tradesTab.setContent(tradesContent);

        Tab ordersTab = new Tab("📋 Orders");
        ordersTab.setClosable(false);
        VBox ordersContent = new VBox(8);
        ordersContent.getStyleClass().addAll("card", "card-trades");
        ordersContent.setPadding(new Insets(8));
        ordersContent.getChildren().add(createOrderTable());
        ordersTab.setContent(ordersContent);

        TabPane tabPane = new TabPane(tradesTab, ordersTab);
        tabPane.getStyleClass().add("trade-tab-pane");
        VBox.setVgrow(tabPane, Priority.ALWAYS);
        root.getChildren().add(tabPane);

        // Position and PnL section
        root.getChildren().add(createPositionsSection());

        // Risk status bar
        root.getChildren().add(createRiskStatusBar());

        // Keep a backing log store even though logs are now shown in a popup dialog.
        logArea = new TextArea();
        logArea.setWrapText(true);
        logArea.setEditable(false);

        Scene scene = new Scene(root);
        var stylesheetUrl = TradeMonitorApp.class.getResource("/styles/trade-monitor.css");
        if (stylesheetUrl != null) {
            scene.getStylesheets().add(stylesheetUrl.toExternalForm());
        }
        return scene;
    }

    private HBox createLoginSection() {
        HBox loginBox = new HBox(10);
        loginBox.setPadding(new Insets(10));
        loginBox.getStyleClass().addAll("card", "card-login");

        // Status indicator
        statusLabel = new Label("🔴 Not Logged In");
        statusLabel.getStyleClass().add("status-pill");

        // Input fields
        accessTokenInputBox = new VBox(3);
        accessTokenInputBox.getChildren().addAll(
                new Label("Access Token:"),
                accessTokenField = new TextField()
        );
        accessTokenField.setPromptText("Enter your Dhan access token");
        accessTokenField.setPrefWidth(200);

        // Single auth button toggles between login/logout.
        authBtn = new Button("🔐 Login");
        authBtn.getStyleClass().addAll("app-btn", "btn-primary");
        authBtn.setOnAction(event -> handleAuthAction());

        Button fundBtn = new Button("💰 View Funds");
        fundBtn.getStyleClass().addAll("app-btn", "btn-accent");
        fundBtn.setOnAction(event -> showFundDialog());

        Button logsBtn = new Button("📜 Activity Log");
        logsBtn.getStyleClass().addAll("app-btn", "btn-secondary");
        logsBtn.setOnAction(event -> showActivityLogDialog());

        Button killSwitchBtn = new Button("🛑 Kill Switch");
        killSwitchBtn.getStyleClass().addAll("app-btn", "btn-kill-switch");
        killSwitchBtn.setOnAction(event -> toggleKillSwitch());
        this.killSwitchBtn = killSwitchBtn;
        killSwitchBtn.setDisable(true);
        setKillSwitchVisual(false, "NONE");

        HBox.setHgrow(accessTokenInputBox, Priority.ALWAYS);
        loginBox.getChildren().addAll(statusLabel, accessTokenInputBox,
                authBtn, fundBtn, logsBtn, killSwitchBtn);
        setLoggedOutState();
        return loginBox;
    }

    private void handleAuthAction() {
        if (loggedIn) {
            handleLogout();
            return;
        }
        handleLogin();
    }

    private void showActivityLogDialog() {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Activity Log");
        dialog.setHeaderText("Recent platform events");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        styleDialog(dialog.getDialogPane(), "popup-info");

        VBox content = new VBox(10);
        content.getStyleClass().add("activity-log-dialog");
        content.setPadding(new Insets(12));

        HBox toolbar = new HBox(8);
        toolbar.getStyleClass().add("activity-log-toolbar");
        Button refreshBtn = new Button("Refresh");
        refreshBtn.getStyleClass().addAll("app-btn", "btn-secondary");
        Button clearBtn = new Button("Clear");
        clearBtn.getStyleClass().addAll("app-btn", "btn-secondary");
        Button copyBtn = new Button("Copy Visible");
        copyBtn.getStyleClass().addAll("app-btn", "btn-secondary");

        TextField filterField = new TextField();
        filterField.setPromptText("Filter logs...");
        filterField.getStyleClass().add("activity-log-filter");

        CheckBox liveUpdateCheck = new CheckBox("Live");
        liveUpdateCheck.setSelected(true);
        CheckBox autoScrollCheck = new CheckBox("Auto-scroll");
        autoScrollCheck.setSelected(true);

        Label metaLabel = new Label();
        metaLabel.getStyleClass().add("activity-log-meta");

        toolbar.getChildren().addAll(refreshBtn, clearBtn, copyBtn, filterField, liveUpdateCheck, autoScrollCheck, metaLabel);

        TextArea logViewer = new TextArea(logArea != null ? logArea.getText() : "");
        logViewer.getStyleClass().add("log-area");
        logViewer.setEditable(false);
        logViewer.setWrapText(true);
        logViewer.setPrefSize(840, 480);

        Runnable renderLogView = () -> updateActivityLogViewer(
                logViewer,
                metaLabel,
                filterField.getText(),
                autoScrollCheck.isSelected()
        );

        ChangeListener<String> sourceListener = (obs, oldValue, newValue) -> {
            if (liveUpdateCheck.isSelected()) {
                renderLogView.run();
            }
        };
        if (logArea != null) {
            logArea.textProperty().addListener(sourceListener);
        }

        refreshBtn.setOnAction(e -> renderLogView.run());
        autoScrollCheck.selectedProperty().addListener((obs, oldValue, newValue) -> renderLogView.run());
        filterField.textProperty().addListener((obs, oldValue, newValue) -> renderLogView.run());

        copyBtn.setOnAction(e -> {
            ClipboardContent clipboardContent = new ClipboardContent();
            clipboardContent.putString(logViewer.getText());
            Clipboard.getSystemClipboard().setContent(clipboardContent);
            addLog("Copied visible activity log entries.");
        });

        clearBtn.setOnAction(e -> {
            if (showConfirmationDialog(
                    "Clear Activity Log",
                    "Clear all log entries?",
                    "This only clears local UI logs.",
                    "popup-warning")) {
                if (logArea != null) {
                    logArea.clear();
                }
                renderLogView.run();
            }
        });

        dialog.setOnHidden(e -> {
            if (logArea != null) {
                logArea.textProperty().removeListener(sourceListener);
            }
        });

        renderLogView.run();

        content.getChildren().addAll(toolbar, logViewer);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinWidth(900);
        dialog.getDialogPane().setMinHeight(560);
        dialog.showAndWait();
    }

    private void updateActivityLogViewer(TextArea logViewer, Label metaLabel, String filterText, boolean autoScroll) {
        String sourceText = logArea != null ? logArea.getText() : "";
        List<String> allEntries = sourceText.lines()
                .filter(line -> !line.isBlank())
                .toList();

        String normalizedFilter = filterText == null ? "" : filterText.trim().toLowerCase(Locale.ROOT);
        List<String> visibleEntries = normalizedFilter.isEmpty()
                ? allEntries
                : allEntries.stream()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains(normalizedFilter))
                .toList();

        logViewer.setText(String.join("\n", visibleEntries));
        metaLabel.setText("Showing " + visibleEntries.size() + " of " + allEntries.size() + " entries");

        if (autoScroll) {
            logViewer.positionCaret(logViewer.getText().length());
        }
    }

    private TableView<TradeRow> createTradeTable() {
        tradeTable = new TableView<>();
        tradeTable.getStyleClass().add("data-table");
        tradeTable.setPlaceholder(new Label("No trades available yet."));
        // Fill available width so there is no unused blank area after the last column.
        tradeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<TradeRow, Long> idCol = new TableColumn<>("ID");
        idCol.setCellValueFactory(cellData -> new SimpleLongProperty(cellData.getValue().id).asObject());
        idCol.setMaxWidth(80);

        TableColumn<TradeRow, String> symbolCol = new TableColumn<>("Symbol");
        symbolCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().symbol));
        symbolCol.setStyle("-fx-font-weight: bold;");

        TableColumn<TradeRow, String> sideCol = new TableColumn<>("Side");
        sideCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().side));
        sideCol.setMaxWidth(110);
        sideCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(item);
                if ("BUY".equalsIgnoreCase(item)) {
                    setStyle("-fx-text-fill: #1b5e20; -fx-font-weight: bold; -fx-alignment: CENTER;");
                } else if ("SELL".equalsIgnoreCase(item)) {
                    setStyle("-fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-alignment: CENTER;");
                } else {
                    setStyle("-fx-alignment: CENTER;");
                }
            }
        });

        TableColumn<TradeRow, Integer> qtyCol = new TableColumn<>("Qty");
        qtyCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().quantity).asObject());
        qtyCol.setMaxWidth(120);
        qtyCol.setStyle("-fx-alignment: CENTER-RIGHT;");

        TableColumn<TradeRow, Double> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(cellData -> new SimpleDoubleProperty(cellData.getValue().price).asObject());
        priceCol.setMaxWidth(140);
        priceCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        priceCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("₹ %,.2f", item));
            }
        });

        TableColumn<TradeRow, Double> notionalCol = new TableColumn<>("Notional");
        notionalCol.setCellValueFactory(cellData ->
                new SimpleDoubleProperty(cellData.getValue().quantity * cellData.getValue().price).asObject());
        notionalCol.setMaxWidth(170);
        notionalCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        notionalCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("₹ %,.2f", item));
            }
        });

        TableColumn<TradeRow, String> timeCol = new TableColumn<>("Traded At");
        timeCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().tradedAt));

        tradeTable.getColumns().addAll(idCol, symbolCol, sideCol, qtyCol, priceCol, notionalCol, timeCol);
        tradeTable.setPrefHeight(320);

        // Zebra rows improve scanability for active monitoring.
        tradeTable.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(TradeRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setStyle("");
                } else if (getIndex() % 2 == 0) {
                    setStyle("-fx-background-color: #fafafa;");
                } else {
                    setStyle("-fx-background-color: #ffffff;");
                }
            }
        });

        return tradeTable;
    }

    private void applyTradeFilter() {
        if (tradeTable == null || tradeFilterField == null) {
            return;
        }

        String query = tradeFilterField.getText() == null ? "" : tradeFilterField.getText().trim().toLowerCase();
        List<TradeRow> filtered = allTradeRows.stream()
                .filter(row -> query.isEmpty()
                        || String.valueOf(row.id).contains(query)
                        || row.symbol.toLowerCase().contains(query)
                        || row.side.toLowerCase().contains(query)
                        || row.tradedAt.toLowerCase().contains(query))
                .toList();

        tradeTable.setItems(FXCollections.observableArrayList(filtered));

        if (tradeCountLabel != null) {
            tradeCountLabel.setText(String.format("Showing %d / %d trades", filtered.size(), allTradeRows.size()));
        }
    }

    private HBox createTradesHeader() {
        HBox header = new HBox(10);
        header.getStyleClass().add("section-header");

        Label title = new Label("Active Trades");
        title.getStyleClass().add("section-title");

        tradeCountLabel = new Label("Showing 0 trades");
        tradeCountLabel.getStyleClass().add("muted-label");

        lastUpdatedLabel = new Label("Last updated: --");
        lastUpdatedLabel.getStyleClass().add("muted-label");

        tradeFilterField = new TextField();
        tradeFilterField.setPromptText("Filter by symbol, side, id...");
        tradeFilterField.setPrefWidth(220);
        tradeFilterField.getStyleClass().add("search-field");
        tradeFilterField.textProperty().addListener((obs, oldVal, newVal) -> applyTradeFilter());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        header.getChildren().addAll(title, tradeCountLabel, spacer, lastUpdatedLabel, tradeFilterField);
        return header;
    }

     private HBox createRiskStatusBar() {
         HBox bar = new HBox(20);
         bar.setPadding(new Insets(8, 12, 8, 12));
         bar.getStyleClass().addAll("card", "risk-bar");

         Label title = new Label("Risk Monitor:");
         title.getStyleClass().add("section-title");

         riskStatusLabel = new Label("Loading...");
         riskStatusLabel.getStyleClass().add("muted-label");

         // Add daily stats and lock indicator
         dailyStatsLabel = new Label("Daily Stats: Loading...");
         dailyStatsLabel.getStyleClass().add("muted-label");

         tradingLockedIndicator = new Label("🟢 Trading Allowed");
         tradingLockedIndicator.getStyleClass().add("metric-label");
         tradingLockedIndicator.setStyle("-fx-text-fill: #388e3c; -fx-font-weight: bold;");

         bar.getChildren().addAll(title, riskStatusLabel, dailyStatsLabel, tradingLockedIndicator);
         return bar;
     }

    private VBox createPositionsSection() {
        VBox section = new VBox(8);
        section.getStyleClass().addAll("card", "card-positions");

        HBox header = new HBox(12);
        Label title = new Label("Real-Time Positions");
        title.getStyleClass().add("section-title");

        totalRealizedPnlLabel = new Label("Realized P&L: ₹0.00");
        totalRealizedPnlLabel.getStyleClass().add("metric-label");
        totalUnrealizedPnlLabel = new Label("Unrealized P&L: ₹0.00");
        totalUnrealizedPnlLabel.getStyleClass().add("metric-label");
        totalOverallPnlLabel = new Label("Total P&L: ₹0.00");
        totalOverallPnlLabel.getStyleClass().add("metric-label");

        Button exitAllBtn = new Button("Exit All Positions");
        exitAllBtn.getStyleClass().addAll("app-btn", "btn-kill-switch", "btn-kill-inactive");
        exitAllBtn.setOnAction(event -> exitAllPositions());

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        header.getChildren().addAll(title, spacer, totalRealizedPnlLabel, totalUnrealizedPnlLabel, totalOverallPnlLabel, exitAllBtn);

        positionTable = createPositionTable();
        section.getChildren().addAll(header, positionTable);
        return section;
    }

    private TableView<PositionRow> createPositionTable() {
        TableView<PositionRow> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setPlaceholder(new Label("No open or closed positions yet."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPrefHeight(170);

        TableColumn<PositionRow, String> symbolCol = new TableColumn<>("Symbol");
        symbolCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().symbol));
        symbolCol.setStyle("-fx-font-weight: bold;");

        TableColumn<PositionRow, Integer> buyQtyCol = new TableColumn<>("Buy Qty");
        buyQtyCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().buyQuantity).asObject());

        TableColumn<PositionRow, Integer> sellQtyCol = new TableColumn<>("Sell Qty");
        sellQtyCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().sellQuantity).asObject());

        TableColumn<PositionRow, Integer> netQtyCol = new TableColumn<>("Net Qty");
        netQtyCol.setCellValueFactory(cellData -> new SimpleIntegerProperty(cellData.getValue().netQuantity).asObject());

        TableColumn<PositionRow, Double> markCol = new TableColumn<>("Mark");
        markCol.setCellValueFactory(cellData -> new SimpleDoubleProperty(cellData.getValue().markPrice).asObject());
        markCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : String.format("₹ %,.2f", item));
            }
        });

        TableColumn<PositionRow, Double> realizedCol = new TableColumn<>("Realized P&L");
        realizedCol.setCellValueFactory(cellData -> new SimpleDoubleProperty(cellData.getValue().realizedPnl).asObject());
        realizedCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(String.format("₹ %,.2f", item));
                setStyle(item >= 0 ? "-fx-text-fill: #1b5e20; -fx-font-weight: bold;" : "-fx-text-fill: #b71c1c; -fx-font-weight: bold;");
            }
        });

        TableColumn<PositionRow, Double> unrealizedCol = new TableColumn<>("Unrealized P&L");
        unrealizedCol.setCellValueFactory(cellData -> new SimpleDoubleProperty(cellData.getValue().unrealizedPnl).asObject());
        unrealizedCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(String.format("₹ %,.2f", item));
                setStyle(item >= 0 ? "-fx-text-fill: #1b5e20; -fx-font-weight: bold;" : "-fx-text-fill: #b71c1c; -fx-font-weight: bold;");
            }
        });

        TableColumn<PositionRow, Double> totalCol = new TableColumn<>("Total P&L");
        totalCol.setCellValueFactory(cellData -> new SimpleDoubleProperty(cellData.getValue().totalPnl).asObject());
        totalCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(String.format("₹ %,.2f", item));
                setStyle(item >= 0 ? "-fx-text-fill: #1b5e20; -fx-font-weight: bold;" : "-fx-text-fill: #b71c1c; -fx-font-weight: bold;");
            }
        });

        table.getColumns().addAll(symbolCol, buyQtyCol, sellQtyCol, netQtyCol, markCol, realizedCol, unrealizedCol, totalCol);
        return table;
    }

    @SuppressWarnings("unchecked")
    private TableView<OrderRow> createOrderTable() {
        orderTable = new TableView<>();
        orderTable.getStyleClass().add("data-table");
        orderTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        orderTable.setPlaceholder(new Label("No orders yet — login and refresh."));

        TableColumn<OrderRow, String> symbolCol = new TableColumn<>("Symbol");
        symbolCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().symbol));
        symbolCol.setStyle("-fx-font-weight: bold;");

        TableColumn<OrderRow, String> sideCol = new TableColumn<>("Side");
        sideCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().side));
        sideCol.setMaxWidth(80);
        sideCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                setStyle("BUY".equalsIgnoreCase(item)
                        ? "-fx-text-fill: #1b5e20; -fx-font-weight: bold; -fx-alignment: CENTER;"
                        : "-fx-text-fill: #b71c1c; -fx-font-weight: bold; -fx-alignment: CENTER;");
            }
        });

        TableColumn<OrderRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().status));
        statusCol.setMaxWidth(110);
        statusCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setStyle(""); return; }
                setText(item);
                switch (item.toUpperCase()) {
                    case "TRADED"    -> setStyle("-fx-text-fill: #1b5e20; -fx-font-weight: bold;");
                    case "REJECTED",
                         "CANCELLED" -> setStyle("-fx-text-fill: #b71c1c; -fx-font-weight: bold;");
                    case "PENDING",
                         "TRANSIT"   -> setStyle("-fx-text-fill: #e65100; -fx-font-weight: bold;");
                    default          -> setStyle("-fx-text-fill: #37474f;");
                }
            }
        });

        TableColumn<OrderRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().orderType));
        typeCol.setMaxWidth(90);

        TableColumn<OrderRow, String> productCol = new TableColumn<>("Product");
        productCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().productType));
        productCol.setMaxWidth(80);

        TableColumn<OrderRow, Number> qtyCol = new TableColumn<>("Qty");
        qtyCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().quantity));
        qtyCol.setMaxWidth(70);

        TableColumn<OrderRow, Number> filledCol = new TableColumn<>("Filled");
        filledCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().filledQty));
        filledCol.setMaxWidth(70);

        TableColumn<OrderRow, Number> remCol = new TableColumn<>("Rem.");
        remCol.setCellValueFactory(c -> new SimpleIntegerProperty(c.getValue().remaining));
        remCol.setMaxWidth(70);

        TableColumn<OrderRow, Double> priceCol = new TableColumn<>("Price");
        priceCol.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().price).asObject());
        priceCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null || item == 0 ? "-" : String.format("₹ %,.2f", item));
            }
        });

        TableColumn<OrderRow, Double> avgCol = new TableColumn<>("Avg. Traded");
        avgCol.setCellValueFactory(c -> new SimpleDoubleProperty(c.getValue().avgTradedPrice).asObject());
        avgCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null || item == 0 ? "-" : String.format("₹ %,.2f", item));
            }
        });

        TableColumn<OrderRow, String> timeCol = new TableColumn<>("Created At");
        timeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().createTime));

        TableColumn<OrderRow, String> errCol = new TableColumn<>("Error");
        errCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().errorDescription == null || "UNKNOWN".equals(c.getValue().errorDescription) ? "" : c.getValue().errorDescription));

        orderTable.getColumns().addAll(symbolCol, sideCol, statusCol, typeCol, productCol,
                qtyCol, filledCol, remCol, priceCol, avgCol, timeCol, errCol);
        orderTable.setPrefHeight(310);
        return orderTable;
    }

    private void refreshOrders() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/orders"))
                .GET().build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        addLog("❌ Failed to fetch orders: " + extractErrorMessage(response.body()));
                        return;
                    }
                    try {
                        List<OrderDto> orders = objectMapper.readValue(response.body(), new TypeReference<>() {});
                        Platform.runLater(() -> {
                            allOrderRows.clear();
                            for (OrderDto o : orders) {
                                allOrderRows.add(new OrderRow(
                                        o.orderId, o.tradingSymbol, o.transactionType, o.orderStatus,
                                        o.orderType, o.productType, o.exchangeSegment,
                                        o.quantity, o.filledQty, o.remainingQuantity,
                                        o.price, o.triggerPrice, o.averageTradedPrice,
                                        formatTime(o.createTime), o.omsErrorDescription));
                            }
                            if (orderTable != null) {
                                orderTable.setItems(FXCollections.observableArrayList(allOrderRows));
                            }
                        });
                    } catch (Exception ex) {
                        addLog("❌ Error parsing orders: " + ex.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    addLog("❌ Error fetching orders: " + ex.getMessage());
                    return null;
                });
    }

    private void refreshKillSwitchStatus() {
        HttpRequest riskReq = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/risk-status"))
                .GET().build();

        httpClient.sendAsync(riskReq, HttpResponse.BodyHandlers.ofString())
                .thenAccept(riskResp -> {
            if (riskResp.statusCode() >= 400) {
                addLog("❌ Failed to fetch risk status (HTTP " + riskResp.statusCode() + ")");
                return;
            }
            try {
                RiskStatusDto risk = objectMapper.readValue(riskResp.body(), RiskStatusDto.class);
                Platform.runLater(() -> {
                    String pnlText = risk.netPnl >= 0
                            ? String.format("+₹%,.2f", risk.netPnl)
                            : String.format("-₹%,.2f", Math.abs(risk.netPnl));
                    String cooldownText = risk.cooldownActive
                            ? "  |  Cooldown: ⏳ " + formatCooldown(risk.cooldownRemainingSeconds)
                            : "";
                    riskStatusLabel.setText("Trades: " + risk.totalTrades + "/" + risk.configuredMaxTrades
                            + "  |  Net P&L: " + pnlText
                            + "  |  Loss limit (₹" + String.format("%,.0f", risk.configuredMaxLoss) + "): " + (risk.loss10kTriggered ? "⛔ HIT" : "✅ OK")
                            + "  |  Profit limit (₹" + String.format("%,.0f", risk.configuredMaxProfit) + "): " + (risk.profit25kTriggered ? "⛔ HIT" : "✅ OK")
                            + "  |  Trade limit: " + (risk.tradeLimit10Triggered ? "⛔ HIT" : "✅ OK")
                            + cooldownText);

                    // Keep risk bar in sync even if kill-switch endpoint is temporarily unavailable.
                    setKillSwitchVisual(risk.killSwitchActive, risk.killSwitchReason);
                });
            } catch (Exception ex) {
                addLog("❌ Error parsing risk status: " + ex.getMessage());
            }
        }).exceptionally(ex -> {
            addLog("❌ Error fetching risk status: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
            return null;
        });

        HttpRequest killSwitchReq = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/kill-switch"))
                .GET().build();

        httpClient.sendAsync(killSwitchReq, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        addLog("❌ Failed to fetch kill-switch status: " + extractErrorMessage(response.body()));
                        return;
                    }
                    try {
                        KillSwitchDto killSwitch = objectMapper.readValue(response.body(), KillSwitchDto.class);
                        Platform.runLater(() -> setKillSwitchVisual(killSwitch.active, killSwitch.reason));
                    } catch (Exception ex) {
                        addLog("❌ Error parsing kill-switch status: " + ex.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    addLog("❌ Error fetching kill-switch status: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    return null;
                });
    }

    private HBox createSummaryBar() {
        HBox summaryBox = new HBox(20);
        summaryBox.setPadding(new Insets(10));
        summaryBox.getStyleClass().addAll("card", "summary-bar");

        summaryLabel = new Label("Total Trades: 0 | Total Notional: ₹0.00");
        summaryLabel.getStyleClass().add("metric-label");

        Button refreshBtn = new Button("🔄 Refresh");
        refreshBtn.getStyleClass().addAll("app-btn", "btn-secondary");
        refreshBtn.setOnAction(event -> handleManualRefresh());

        refreshIntervalBox = new ComboBox<>();
        refreshIntervalBox.getItems().addAll("1s", "2s", "3s", "5s", "Manual");
        refreshIntervalBox.setValue("2s");
        refreshIntervalBox.setPrefWidth(110);
        refreshIntervalBox.setOnAction(event -> applyAutoRefreshMode(refreshIntervalBox.getValue()));

        countdownLabel = new Label("⏱ Next: --");
        countdownLabel.getStyleClass().add("muted-label");
        countdownLabel.setStyle("-fx-font-size: 11; -fx-text-fill: #546e7a;");

        HBox.setHgrow(summaryLabel, Priority.ALWAYS);
        summaryBox.getChildren().addAll(summaryLabel, new Label("Auto Refresh:"), refreshIntervalBox, countdownLabel, refreshBtn);
        return summaryBox;
    }

    private void applyAutoRefreshMode(String mode) {
        if (autoRefreshTimeline != null) {
            autoRefreshTimeline.stop();
            autoRefreshTimeline = null;
        }
        if (countdownTimeline != null) {
            countdownTimeline.stop();
            countdownTimeline = null;
        }

        if (!loggedIn) {
            if (countdownLabel != null) {
                countdownLabel.setText("⏱ Login required");
            }
            return;
        }

        if (mode == null || "Manual".equalsIgnoreCase(mode)) {
            addLog("⏸ Auto-refresh paused (Manual mode)");
            if (countdownLabel != null) {
                countdownLabel.setText("⏱ Manual");
            }
            return;
        }

        double intervalSeconds;
        try {
            intervalSeconds = Double.parseDouble(mode.replace("s", ""));
        } catch (NumberFormatException ex) {
            intervalSeconds = 2.0;
        }

        final int intervalInt = (int) intervalSeconds;
        countdownSeconds = intervalInt;

        // Countdown label ticker (every 1 s)
        countdownTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (countdownLabel != null) {
                countdownLabel.setText("⏱ Next: " + countdownSeconds + "s");
            }
            if (countdownSeconds > 0) countdownSeconds--;
            else countdownSeconds = intervalInt;
        }));
        countdownTimeline.setCycleCount(Timeline.INDEFINITE);
        countdownTimeline.play();

        autoRefreshTimeline = new Timeline(new KeyFrame(Duration.seconds(intervalSeconds), event -> {
            countdownSeconds = intervalInt;
            refreshAll();
        }));
        autoRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        autoRefreshTimeline.play();
        addLog("🔄 Auto-refresh set to every " + mode);
    }

    private void handleManualRefresh() {
        if (!loggedIn) {
            showAlert("Login Required", "Please login before refreshing data.");
            return;
        }
        refreshAll();
    }

    private void handleLogin() {
        String token = accessTokenField.getText().trim();
        String clientId = "";

        if (token.isEmpty()) {
            showAlert("Validation Error", "Access token is required");
            return;
        }

        try {
            String body = objectMapper.writeValueAsString(new LoginRequest(token, clientId));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/dhan/login"))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .header("Content-Type", "application/json")
                    .build();

            Platform.runLater(() -> statusLabel.setText("⏳ Logging in..."));
            if (authBtn != null) {
                authBtn.setDisable(true);
            }

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> Platform.runLater(() -> {
                        if (response.statusCode() == 200) {
                            setLoggedInState();
                            addLog("✅ Successfully logged in to Dhan");
                            refreshAll();
                        } else {
                            setLoggedOutState();
                            String errorMsg = extractErrorMessage(response.body());
                            showAlert("Login Failed", "HTTP " + response.statusCode() + ": " + errorMsg);
                            addLog("❌ Login failed: " + errorMsg);
                        }
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> {
                            setLoggedOutState();
                            showAlert("Login Error", ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                            addLog("❌ Login error: " + ex.getMessage());
                        });
                        return null;
                    });
        } catch (Exception ex) {
            showAlert("Login Error", ex.getMessage());
        }
    }

    private void handleLogout() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/dhan/logout"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        if (authBtn != null) {
            authBtn.setDisable(true);
        }

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.statusCode() == 200) {
                        setLoggedOutState();
                        accessTokenField.clear();
                        allTradeRows.clear();
                        applyTradeFilter();
                        if (positionTable != null) positionTable.setItems(FXCollections.observableArrayList());
                        resetPnlLabels();
                        addLog("✅ Successfully logged out");
                    } else {
                        String errorMsg = extractErrorMessage(response.body());
                        showAlert("Logout Failed", "HTTP " + response.statusCode() + ": " + errorMsg);
                        addLog("❌ Logout failed: " + errorMsg);
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        showAlert("Logout Error", ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                        addLog("❌ Logout error: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void resetPnlLabels() {
        if (totalRealizedPnlLabel != null) {
            totalRealizedPnlLabel.setText("Realized P&L: ₹0.00");
            totalRealizedPnlLabel.getStyleClass().setAll("metric-label");
        }
        if (totalUnrealizedPnlLabel != null) {
            totalUnrealizedPnlLabel.setText("Unrealized P&L: ₹0.00");
            totalUnrealizedPnlLabel.getStyleClass().setAll("metric-label");
        }
        if (totalOverallPnlLabel != null) {
            totalOverallPnlLabel.setText("Total P&L: ₹0.00");
            totalOverallPnlLabel.getStyleClass().setAll("metric-label");
        }
    }

    private void exitAllPositions() {
        if (!showExitAllConfirmationDialog()) {
            return;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/positions/exit-all"))
                .DELETE()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.statusCode() >= 400) {
                        String errorMsg = extractErrorMessage(response.body());
                        showAlert("Exit Positions Failed", "HTTP " + response.statusCode() + ": " + errorMsg);
                        addLog("❌ Exit all positions failed: " + errorMsg);
                        return;
                    }

                    try {
                        ExitAllPositionsDto dto = objectMapper.readValue(response.body(), ExitAllPositionsDto.class);
                        String msg = dto.message == null || dto.message.isBlank() ? "Exit request sent." : dto.message;
                        addLog("✅ " + msg + " Cooldown activated if configured.");
                    } catch (Exception ignored) {
                        addLog("✅ Exit request sent successfully. Cooldown activated if configured.");
                    }

                    refreshAll();
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                        showAlert("Exit Positions Error", msg);
                        addLog("❌ Exit all positions error: " + msg);
                    });
                    return null;
                });
    }

    private boolean showExitAllConfirmationDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Exit All Positions");
        dialog.setHeaderText("Confirm emergency flattening");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        styleDialog(dialog.getDialogPane(), "popup-warning");

        VBox content = new VBox(10);
        content.setPadding(new Insets(14));

        Label summaryTitle = new Label("Current P&L Snapshot");
        summaryTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");

        VBox snapshot = new VBox(4);
        snapshot.setPadding(new Insets(8));
        snapshot.setStyle("-fx-background-color: #f7f9fc; -fx-background-radius: 6; -fx-border-color: #dfe3e8; -fx-border-radius: 6;");
        snapshot.getChildren().addAll(
                new Label(totalRealizedPnlLabel != null ? totalRealizedPnlLabel.getText() : "Realized P&L: --"),
                new Label(totalUnrealizedPnlLabel != null ? totalUnrealizedPnlLabel.getText() : "Unrealized P&L: --"),
                new Label(totalOverallPnlLabel != null ? totalOverallPnlLabel.getText() : "Total P&L: --")
        );

        Label impactTitle = new Label("What happens next");
        impactTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 12;");

        VBox impacts = new VBox(6);
        impacts.getChildren().addAll(
                createExitImpactLine("• All open positions will be exited at market."),
                createExitImpactLine("• Pending orders will be cancelled by broker flow."),
                createExitImpactLine("• Cooldown may activate based on your settings.")
        );

        Label warning = new Label("This action is intended for risk control during volatile moves.");
        warning.setStyle("-fx-text-fill: #b71c1c; -fx-font-size: 11;");

        content.getChildren().addAll(summaryTitle, snapshot, impactTitle, impacts, warning);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinWidth(470);

        return dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private Label createExitImpactLine(String text) {
        Label line = new Label(text);
        line.setStyle("-fx-text-fill: #37474f; -fx-font-size: 11.5;");
        return line;
    }

    private void refreshPositions() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/positions"))
                .GET().build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        addLog("❌ Failed to fetch positions: " + extractErrorMessage(response.body()));
                        return;
                    }
                    try {
                        List<PositionDto> positions = objectMapper.readValue(response.body(), new TypeReference<>() {});
                        Platform.runLater(() -> {
                            ObservableList<PositionRow> rows = FXCollections.observableArrayList();
                            double totalRealized = 0.0;
                            double totalUnrealized = 0.0;
                            for (PositionDto p : positions) {
                                rows.add(new PositionRow(
                                        p.symbol, p.buyQuantity, p.sellQuantity, p.netQuantity,
                                        p.markPrice, p.realizedPnl, p.unrealizedPnl, p.totalPnl));
                                totalRealized += p.realizedPnl;
                                totalUnrealized += p.unrealizedPnl;
                            }
                            double totalPnl = totalRealized + totalUnrealized;
                            if (positionTable != null) positionTable.setItems(rows);
                            updatePnlLabel(totalRealizedPnlLabel, "Realized P&L: ₹%,.2f", totalRealized);
                            updatePnlLabel(totalUnrealizedPnlLabel, "Unrealized P&L: ₹%,.2f", totalUnrealized);
                            updatePnlLabel(totalOverallPnlLabel, "Total P&L: ₹%,.2f", totalPnl);
                        });
                    } catch (Exception ex) {
                        addLog("❌ Error parsing positions: " + ex.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    addLog("❌ Error fetching positions: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    return null;
                });
    }

    private void updatePnlLabel(Label label, String fmt, double value) {
        if (label == null) return;
        label.setText(String.format(fmt, value));
        label.getStyleClass().setAll("metric-label", value >= 0 ? "pnl-positive" : "pnl-negative");
    }


     private void refreshAll() {
         if (!loggedIn) {
             return;
         }
         addLog("🔁 Refreshing data from backend...");
         refreshTrades();
         refreshPositions();
         refreshOrders();
         refreshKillSwitchStatus();
         refreshDailyStats();
     }

    private void setLoggedInState() {
        loggedIn = true;
        statusLabel.setText("🟢 Logged In");
        statusLabel.getStyleClass().setAll("status-pill", "status-online");
        if (accessTokenField != null) {
            accessTokenField.setDisable(true);
        }
        if (accessTokenInputBox != null) {
            accessTokenInputBox.setVisible(false);
            accessTokenInputBox.setManaged(false);
        }
        if (authBtn != null) {
            authBtn.setDisable(false);
            authBtn.setText("🚪 Logout");
            authBtn.getStyleClass().setAll("app-btn", "btn-secondary");
        }
        if (killSwitchBtn != null) {
            killSwitchBtn.setDisable(false);
        }
        applyAutoRefreshMode(refreshIntervalBox != null ? refreshIntervalBox.getValue() : "2s");
    }

    private void setLoggedOutState() {
        loggedIn = false;
        if (autoRefreshTimeline != null) {
            autoRefreshTimeline.stop();
            autoRefreshTimeline = null;
        }
        if (countdownTimeline != null) {
            countdownTimeline.stop();
            countdownTimeline = null;
        }
        statusLabel.setText("🔴 Not Logged In");
        statusLabel.getStyleClass().setAll("status-pill", "status-offline");
        if (accessTokenField != null) {
            accessTokenField.setDisable(false);
        }
        if (accessTokenInputBox != null) {
            accessTokenInputBox.setVisible(true);
            accessTokenInputBox.setManaged(true);
        }
        if (authBtn != null) {
            authBtn.setDisable(false);
            authBtn.setText("🔐 Login");
            authBtn.getStyleClass().setAll("app-btn", "btn-primary");
        }
        if (killSwitchBtn != null) {
            killSwitchBtn.setDisable(true);
        }
        if (countdownLabel != null) {
            countdownLabel.setText("⏱ Login required");
        }
        setKillSwitchVisual(false, "NONE");
    }

    private void setKillSwitchVisual(boolean active, String reason) {
        if (killSwitchBtn == null) {
            return;
        }
        if (active) {
            killSwitchBtn.setText("MANUAL".equals(reason) || "NONE".equals(reason)
                    ? "🛑 Kill Switch: ACTIVE"
                    : "🛑 Kill Switch: ACTIVE [" + reason + "]");
            killSwitchBtn.getStyleClass().setAll("app-btn", "btn-kill-switch", "btn-kill-active");
            return;
        }
        killSwitchBtn.setText(loggedIn ? "🟢 Kill Switch: INACTIVE" : "🛑 Kill Switch (Login Required)");
        killSwitchBtn.getStyleClass().setAll("app-btn", "btn-kill-switch", "btn-kill-inactive");
    }

    private void refreshTrades() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades"))
                .GET().build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        addLog("❌ Failed to fetch trades: " + extractErrorMessage(response.body()));
                        return;
                    }
                    try {
                        List<TradeDto> trades = objectMapper.readValue(response.body(), new TypeReference<>() {});
                        Platform.runLater(() -> {
                            allTradeRows.clear();
                            for (TradeDto trade : trades) {
                                allTradeRows.add(new TradeRow(trade.id, trade.symbol, trade.quantity,
                                        trade.price, trade.side, formatTime(trade.tradedAt)));
                            }
                            applyTradeFilter();
                            if (lastUpdatedLabel != null) {
                                lastUpdatedLabel.setText("Last updated: " +
                                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                            }
                        });
                        fetchSummary();
                    } catch (Exception ex) {
                        addLog("❌ Error parsing trades: " + ex.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    addLog("❌ Error fetching trades: " + ex.getMessage());
                    return null;
                });
    }

    private void fetchSummary() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/summary"))
                .GET().build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) return;
                    try {
                        SummaryDto summary = objectMapper.readValue(response.body(), SummaryDto.class);
                        Platform.runLater(() -> summaryLabel.setText(String.format(
                                "Total Trades: %d | Total Notional: ₹%,.2f",
                                summary.totalTrades, summary.totalNotional)));
                    } catch (Exception ignored) {}
                })
                .exceptionally(ex -> null);
    }


    private void toggleKillSwitch() {
        if (!loggedIn) {
            showAlert("Login Required", "Please login before changing kill-switch state.");
            return;
        }

        HttpRequest statusRequest = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/kill-switch"))
                .GET().build();

        httpClient.sendAsync(statusRequest, HttpResponse.BodyHandlers.ofString())
                .thenCompose(statusResponse -> {
                    if (statusResponse.statusCode() >= 400) {
                        Platform.runLater(() -> showAlert("Kill Switch", extractKillSwitchResponseMessage(statusResponse.body())));
                        return CompletableFuture.completedFuture(null);
                    }
                    try {
                        KillSwitchDto current = objectMapper.readValue(statusResponse.body(), KillSwitchDto.class);
                        return confirmKillSwitchAction(current.active).thenCompose(confirmed -> {
                            if (!confirmed) {
                                Platform.runLater(() -> addLog("ℹ️ Kill-switch action cancelled by user."));
                                return CompletableFuture.completedFuture(null);
                            }

                            String actionPath = current.active
                                    ? "/api/trades/kill-switch/deactivate"
                                    : "/api/trades/kill-switch/activate";
                            HttpRequest actionRequest = HttpRequest.newBuilder(URI.create(apiBaseUrl + actionPath))
                                    .POST(HttpRequest.BodyPublishers.noBody()).build();
                            return httpClient.sendAsync(actionRequest, HttpResponse.BodyHandlers.ofString());
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> showAlert("Kill Switch Error", ex.getMessage()));
                        return CompletableFuture.completedFuture(null);
                    }
                })
                .thenAccept(actionResponse -> {
                    if (actionResponse == null) return;
                    Platform.runLater(() -> {
                        String responseMessage = extractKillSwitchResponseMessage(actionResponse.body());
                        if (actionResponse.statusCode() >= 400) {
                            showAlert("Kill Switch Response", responseMessage);
                            addLog("❌ " + responseMessage);
                        } else {
                            try {
                                KillSwitchDto updated = objectMapper.readValue(actionResponse.body(), KillSwitchDto.class);
                                addLog((updated.active ? "🛑 " : "✅ ") + responseMessage);
                                showInfoDialog(
                                        "Kill Switch Response",
                                        updated.active ? "Kill switch activated" : "Kill switch deactivated",
                                        responseMessage
                                );
                            } catch (Exception ignored) {}
                            refreshKillSwitchStatus();
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> showAlert("Kill Switch Error",
                            ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    return null;
                });
    }

    private CompletableFuture<Boolean> confirmKillSwitchAction(boolean currentlyActive) {
        CompletableFuture<Boolean> confirmation = new CompletableFuture<>();
        Platform.runLater(() -> {
            Alert confirm = createStyledAlert(Alert.AlertType.CONFIRMATION, null, null, null,
                    currentlyActive ? "popup-warning" : "popup-danger");
            if (currentlyActive) {
                confirm.setTitle("Confirm Kill Switch Deactivation");
                confirm.setHeaderText("Deactivate kill switch?");
                confirm.setContentText("This will allow new trades again. Continue?");
            } else {
                confirm.setTitle("Confirm Kill Switch Activation");
                confirm.setHeaderText("Activate kill switch?");
                confirm.setContentText("This will block new trades until deactivated or auto-reset. Continue?");
            }
            boolean approved = confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
            confirmation.complete(approved);
        });
        return confirmation;
    }

    private void showFundDialog() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/dhan/fundlimit"))
                .GET().build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> Platform.runLater(() -> {
                    try {
                        if (response.statusCode() >= 400) {
                            showAlert("Error", "Backend error " + response.statusCode() + ": " + extractErrorMessage(response.body()));
                            addLog("❌ Could not fetch fund details");
                            return;
                        }
                        FundDto fund = objectMapper.readValue(response.body(), FundDto.class);

                        Dialog<Void> dialog = new Dialog<>();
                        dialog.setTitle("Funds & Margins — Dhan");
                        dialog.setHeaderText("Live account balances and usage");
                        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                        styleDialog(dialog.getDialogPane(), "popup-info");

                        VBox content = new VBox(12);
                        content.setPadding(new Insets(16));

                        // Top highlight cards for the two most important metrics.
                        HBox highlights = new HBox(10);
                        highlights.getChildren().addAll(
                                createFundMetricCard("Available", fund.availableBalance, "positive"),
                                createFundMetricCard("Withdrawable", fund.withdrawableBalance, "neutral")
                        );

                        VBox details = new VBox(8);
                        details.getChildren().addAll(
                                createFundDetailRow("Utilised Amount", fund.utilizedAmount),
                                createFundDetailRow("SOD Limit", fund.sodLimit),
                                createFundDetailRow("Collateral Amount", fund.collateralAmount),
                                createFundDetailRow("Receivable Amount", fund.receivableAmount),
                                createFundDetailRow("Blocked Payout", fund.blockedPayoutAmount)
                        );

                        content.getChildren().addAll(highlights, details);

                        dialog.getDialogPane().setContent(content);
                        dialog.getDialogPane().setMinWidth(520);
                        dialog.showAndWait();
                        addLog("✅ Fund details viewed");
                    } catch (Exception ex) {
                        showAlert("Error", ex.getMessage());
                        addLog("❌ Could not fetch fund details: " + ex.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        showAlert("Error", ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage());
                        addLog("❌ Could not fetch fund details: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private VBox createFundMetricCard(String label, double amount, String tone) {
        VBox card = new VBox(4);
        card.setPadding(new Insets(10));
        card.setPrefWidth(230);

        String bg = "#f5f7fa";
        String valueColor = "#1f2937";
        if ("positive".equals(tone)) {
            bg = "#e8f5e9";
            valueColor = "#1b5e20";
        }

        card.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 8; -fx-border-color: #d9e2ec; -fx-border-radius: 8;");

        Label name = new Label(label);
        name.setStyle("-fx-text-fill: #546e7a; -fx-font-size: 11;");

        Label value = new Label(formatCurrency(amount));
        value.setStyle("-fx-font-size: 18; -fx-font-weight: bold; -fx-text-fill: " + valueColor + ";");

        card.getChildren().addAll(name, value);
        return card;
    }

    private HBox createFundDetailRow(String label, double amount) {
        HBox row = new HBox();
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setStyle("-fx-background-color: #ffffff; -fx-background-radius: 6; -fx-border-color: #eceff1; -fx-border-radius: 6;");

        Label key = new Label(label);
        key.setStyle("-fx-font-weight: bold; -fx-text-fill: #37474f;");

        Label value = new Label(formatCurrency(amount));
        value.setStyle("-fx-text-fill: #263238;");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().addAll(key, spacer, value);
        return row;
    }

    private String formatCurrency(double amount) {
        return String.format("₹ %,.2f", amount);
    }

    private String formatTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }

        String cleaned = raw.trim();
        DateTimeFormatter out = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        try {
            return java.time.OffsetDateTime.parse(cleaned, DateTimeFormatter.ISO_DATE_TIME)
                    .toLocalDateTime()
                    .format(out);
        } catch (Exception ignored) {
        }

        try {
            return LocalDateTime.parse(cleaned.replace(" ", "T"), DateTimeFormatter.ISO_DATE_TIME)
                    .format(out);
        } catch (Exception ignored) {
        }

        // Fallback to a compact 19-char timestamp if API sends extra timezone/fractional parts.
        if (cleaned.length() >= 19) {
            return cleaned.substring(0, 19).replace('T', ' ');
        }
        return cleaned;
    }

    private String extractErrorMessage(String json) {
        try {
            var node = objectMapper.readTree(json);
            return node.has("error") ? node.get("error").asText() : "Unknown error";
        } catch (Exception ignored) {
            return json;
        }
    }

    private String extractKillSwitchResponseMessage(String json) {
        try {
            var node = objectMapper.readTree(json);

            String message = null;
            if (node.has("message") && !node.get("message").isNull()) {
                message = node.get("message").asText();
            } else if (node.has("error") && !node.get("error").isNull()) {
                message = node.get("error").asText();
            } else if (node.has("remarks") && !node.get("remarks").isNull()) {
                message = node.get("remarks").asText();
            } else if (node.has("killSwitchStatus") && !node.get("killSwitchStatus").isNull()) {
                message = "Kill switch status: " + node.get("killSwitchStatus").asText();
            }

            StringBuilder details = new StringBuilder();
            if (node.has("active") && !node.get("active").isNull()) {
                details.append("Status: ").append(node.get("active").asBoolean() ? "ACTIVE" : "INACTIVE");
            }
            if (node.has("reason") && !node.get("reason").isNull()) {
                if (details.length() > 0) {
                    details.append(" | ");
                }
                details.append("Reason: ").append(node.get("reason").asText());
            }

            if (message == null || message.isBlank()) {
                return details.length() > 0 ? details.toString() : json;
            }

            if (details.length() > 0) {
                return message + "\n" + details;
            }
            return message;
        } catch (Exception ignored) {
            return json;
        }
    }

    private String resolveApiBaseUrl() {
        String fromProperty = System.getProperty("tradewise.api.base-url");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = System.getenv("TRADEWISE_API_BASE_URL");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return "http://localhost:9092";
    }

    private void showAlert(String title, String message) {
        Alert alert = createStyledAlert(Alert.AlertType.ERROR, title, null, message, "popup-danger");
        alert.showAndWait();
    }

    private boolean showConfirmationDialog(String title, String header, String message, String variantClass) {
        Alert confirm = createStyledAlert(Alert.AlertType.CONFIRMATION, title, header, message, variantClass);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private Alert createStyledAlert(Alert.AlertType type, String title, String header, String message, String variantClass) {
        Alert alert = new Alert(type);
        if (title != null) {
            alert.setTitle(title);
        }
        alert.setHeaderText(header);
        alert.setContentText(message);
        styleDialog(alert.getDialogPane(), variantClass);
        return alert;
    }

    private void styleDialog(DialogPane dialogPane, String variantClass) {
        if (dialogPane == null) {
            return;
        }

        String stylesheet = null;
        var stylesheetUrl = TradeMonitorApp.class.getResource("/styles/trade-monitor.css");
        if (stylesheetUrl != null) {
            stylesheet = stylesheetUrl.toExternalForm();
            if (!dialogPane.getStylesheets().contains(stylesheet)) {
                dialogPane.getStylesheets().add(stylesheet);
            }
        }

        if (!dialogPane.getStyleClass().contains("popup-dialog")) {
            dialogPane.getStyleClass().add("popup-dialog");
        }
        if (variantClass != null && !variantClass.isBlank() && !dialogPane.getStyleClass().contains(variantClass)) {
            dialogPane.getStyleClass().add(variantClass);
        }
        dialogPane.setMinWidth(440);
    }

    private void showInfoDialog(String title, String header, String message) {
        Alert alert = createStyledAlert(Alert.AlertType.INFORMATION, title, header, message, "popup-info");
        alert.setTitle(title);
        alert.showAndWait();
    }

    private void addLog(String message) {
        Platform.runLater(() -> {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.appendText("[" + timestamp + "] " + message + "\n");
        });
    }

    private String formatCooldown(long remainingSeconds) {
        long mins = remainingSeconds / 60;
        long secs = remainingSeconds % 60;
        if (mins <= 0) {
            return secs + "s";
        }
        return mins + "m " + secs + "s";
    }

    /**
     * Show trade reason/journal dialog before recording a trade.
     */
    public String showTradeReasonDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("📝 Trade Journal Entry");
        dialog.setHeaderText("Provide reason for this trade");
        styleDialog(dialog.getDialogPane(), "popup-info");

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));

        Label reasonLabel = new Label("Reason for trade:");
        reasonLabel.setStyle("-fx-font-weight: bold;");
        TextField reasonField = new TextField();
        reasonField.setPromptText("e.g., Support breakout, Technical signal, etc.");
        reasonField.setPrefHeight(35);

        Label journalLabel = new Label("Trade Journal / Notes:");
        journalLabel.setStyle("-fx-font-weight: bold;");
        TextArea journalArea = new TextArea();
        journalArea.setPromptText("Detailed notes about this trade...");
        journalArea.setPrefHeight(150);
        journalArea.setWrapText(true);

        content.getChildren().addAll(
                reasonLabel, reasonField,
                journalLabel, journalArea
        );

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setMinWidth(500);

        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                String reason = reasonField.getText().trim();
                String journal = journalArea.getText().trim();
                if (reason.isEmpty()) {
                    showAlert("Validation Error", "Please provide a reason for the trade");
                    return null;
                }
                return reason + " | Journal: " + (journal.isEmpty() ? "N/A" : journal);
            }
            return null;
        });

        return dialog.showAndWait().orElse(null);
    }

    /**
     * Refresh daily stats from backend.
     */
    private void refreshDailyStats() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBaseUrl + "/api/trades/daily-stats"))
                .GET().build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        addLog("❌ Failed to fetch daily stats: " + extractErrorMessage(response.body()));
                        return;
                    }
                    try {
                        DailyStatsDto stats = objectMapper.readValue(response.body(), DailyStatsDto.class);
                        Platform.runLater(() -> {
                            String lockStatus = stats.tradingLocked() ? "🔒 Closed" : "🟢 Open";
                            String pnlStatus = stats.dailyLossLimitHit() ? "Loss ❌" :
                                               stats.dailyProfitLimitHit() ? "Profit Target ✅" :
                                               stats.dailyTradesLimitHit() ? "Trades ⛔" :
                                               "TRAILING_PROFIT_STOP".equals(stats.lockedReason()) ? "Trailing 🎯" :
                                               "Normal";

                            String statsText = String.format(
                                    "%s | Trades: %d | PnL: ₹%,.2f | Peak: ₹%,.2f | %s",
                                    lockStatus, stats.tradesCount(), stats.totalPnl(),
                                    stats.peakPnl(), pnlStatus
                            );
                            if (dailyStatsLabel != null) {
                                dailyStatsLabel.setText(statsText);
                            }

                            // Update trading lock indicator
                            if (tradingLockedIndicator != null) {
                                if (stats.tradingLocked()) {
                                    String lockReasonText = switch (stats.lockedReason()) {
                                        case "DAILY_LOSS_LIMIT", "LOSS_LIMIT" -> "🔒 Loss Limit Hit";
                                        case "DAILY_PROFIT_LIMIT", "PROFIT_LIMIT" -> "🔒 Profit Target Hit";
                                        case "DAILY_TRADES_LIMIT", "TRADE_LIMIT" -> "🔒 Max Trades Hit";
                                        case "TRAILING_PROFIT_STOP" -> "🎯 Trailing Stop Triggered";
                                        default -> "🔒 " + stats.lockedReason();
                                    };
                                    tradingLockedIndicator.setText(lockReasonText);
                                    tradingLockedIndicator.setStyle("-fx-text-fill: #d32f2f; -fx-font-weight: bold;");
                                } else if (stats.trailingStopActive()) {
                                    tradingLockedIndicator.setText(String.format(
                                            "🎯 Trailing Active: Peak ₹%,.0f  (↓₹%,.0f / ₹%,.0f limit)",
                                            stats.peakPnl(), stats.trailingDrawdownSoFar(), stats.trailingDrawdown()));
                                    tradingLockedIndicator.setStyle("-fx-text-fill: #e65100; -fx-font-weight: bold;");
                                } else {
                                    tradingLockedIndicator.setText("🟢 Trading Allowed");
                                    tradingLockedIndicator.setStyle("-fx-text-fill: #388e3c; -fx-font-weight: bold;");
                                }
                            }
                        });
                    } catch (Exception ex) {
                        addLog("❌ Error parsing daily stats: " + ex.getMessage());
                    }
                })
                .exceptionally(ex -> {
                    addLog("❌ Error fetching daily stats: " + (ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage()));
                    return null;
                });
    }


    public static void main(String[] args) {
        launch(args);
    }

    // DTOs
    private record LoginRequest(String accessToken, String clientId) {}


    private record DailyStatsDto(
            String date,
            int tradesCount,
            double realizedPnl,
            double unrealizedPnl,
            double totalPnl,
            boolean dailyLossLimitHit,
            boolean dailyProfitLimitHit,
            boolean dailyTradesLimitHit,
            boolean tradingLocked,
            String lockedReason,
            long lastResetAt,
            long marketOpenAt,
            double peakPnl,
            boolean trailingStopActive,
            double trailingStopLevel,
            double trailingDrawdown,
            double trailingDrawdownSoFar
    ) {}

    private record TradeDto(long id, String symbol, int quantity, double price, String side, String tradedAt) {}

    private record SummaryDto(int totalTrades, double totalNotional) {}

    private record KillSwitchDto(boolean active, String reason, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExitAllPositionsDto(String status, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrderDto(
            String orderId,
            String orderStatus,
            String transactionType,
            String exchangeSegment,
            String productType,
            String orderType,
            String tradingSymbol,
            int quantity,
            int filledQty,
            int remainingQuantity,
            double price,
            double triggerPrice,
            double averageTradedPrice,
            String createTime,
            String omsErrorDescription
    ) {}

    private record PositionDto(
            String symbol,
            int buyQuantity,
            int sellQuantity,
            int netQuantity,
            double averageBuyPrice,
            double averageSellPrice,
            double realizedPnl,
            double markPrice,
            double unrealizedPnl,
            double totalPnl
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RiskStatusDto(
            int totalTrades,
            double totalBuyValue,
            double totalSellValue,
            double netPnl,
            boolean tradeLimit10Triggered,
            boolean loss10kTriggered,
            boolean profit25kTriggered,
            boolean killSwitchActive,
            String killSwitchReason,
            int configuredMaxTrades,
            double configuredMaxLoss,
            double configuredMaxProfit,
            boolean cooldownActive,
            long cooldownRemainingSeconds,
            String cooldownEndsAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FundDto(
            @JsonAlias({"dhanClietId", "dhanClientId"})
            String dhanClientId,
            @JsonAlias({"availabelBalance", "availableBalance"})
            double availableBalance,
            double withdrawableBalance,
            double utilizedAmount,
            double sodLimit,
            double collateralAmount,
            @JsonAlias({"receiveableAmount", "receivableAmount"})
            double receivableAmount,
            double blockedPayoutAmount
    ) {}

}
