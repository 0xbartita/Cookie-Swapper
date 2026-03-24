/**
 * Cookie Swapper v1.0 - Burp Suite Extension
 * Author: 0xbartita
 *
 * Automatically replaces cookies and headers in requests
 * with user-defined values. Supports JSON import from
 * Cookie Editor and persistent rule names across sessions.
 */
package burp;

import burp.api.montoya.*;
import burp.api.montoya.http.*;
import burp.api.montoya.http.message.*;
import burp.api.montoya.http.message.requests.*;
import burp.api.montoya.http.message.responses.*;
import burp.api.montoya.ui.contextmenu.*;
import burp.api.montoya.ui.hotkey.*;
import burp.api.montoya.persistence.*;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class BurpExtender implements BurpExtension {

    private MontoyaApi api;
    private JPanel mainPanel;
    private DefaultTableModel rulesTableModel;
    private JTable rulesTable;
    private int tabCounter = 0;
    private final ExecutorService requestExecutor = Executors.newFixedThreadPool(3);

    // Custom tab system
    private JPanel tabBarPanel;
    private JScrollPane tabBarScroll;
    private JPanel contentPanel;
    private CardLayout contentLayout;
    private final Map<String, JToggleButton> tabButtons = new LinkedHashMap<>();
    private final Map<String, Component> tabContents = new LinkedHashMap<>();
    private final Map<String, Integer> tabStatusCodes = new LinkedHashMap<>();
    private String selectedTabId = null;
    private ButtonGroup tabGroup = new ButtonGroup();
    private String activeFilter = "all";

    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        api.extension().setName("Cookie Swapper");

        SwingUtilities.invokeLater(() -> {
            buildUI();
            loadSettings();
            api.userInterface().registerSuiteTab("Cookie Swapper", mainPanel);
        });

        // Right-click context menu
        api.userInterface().registerContextMenuItemsProvider(new ContextMenuItemsProvider() {
            @Override
            public List<Component> provideMenuItems(ContextMenuEvent event) {
                List<Component> items = new ArrayList<>();
                JMenuItem sendItem = new JMenuItem("Send to Cookie Swapper");
                sendItem.addActionListener(e -> {
                    List<HttpRequestResponse> msgs = new ArrayList<>(event.selectedRequestResponses());
                    event.messageEditorRequestResponse().ifPresent(editor -> {
                        if (msgs.isEmpty()) msgs.add(editor.requestResponse());
                    });
                    for (HttpRequestResponse msg : msgs) processMessage(msg);
                });
                items.add(sendItem);
                return items;
            }
        });

        // Register hotkey in all relevant contexts
        HotKey hotKey = HotKey.hotKey("Send to Cookie Swapper", "Ctrl+Shift+Q");

        // Works when viewing a request in the message editor (bottom panel)
        api.userInterface().registerHotKeyHandler(HotKeyContext.HTTP_MESSAGE_EDITOR, hotKey, event -> {
            event.messageEditorRequestResponse().ifPresent(editor -> {
                processMessage(editor.requestResponse());
            });
        });

        // Works when selecting a row in proxy history table
        api.userInterface().registerHotKeyHandler(HotKeyContext.PROXY_HTTP_HISTORY, hotKey, event -> {
            event.messageEditorRequestResponse().ifPresent(editor -> {
                processMessage(editor.requestResponse());
            });
        });

        // Works when selecting in site map
        api.userInterface().registerHotKeyHandler(HotKeyContext.SITE_MAP_CONTENTS_TABLE, hotKey, event -> {
            event.messageEditorRequestResponse().ifPresent(editor -> {
                processMessage(editor.requestResponse());
            });
        });

        // Works in organizer
        api.userInterface().registerHotKeyHandler(HotKeyContext.ORGANIZER_ENTRIES, hotKey, event -> {
            event.messageEditorRequestResponse().ifPresent(editor -> {
                processMessage(editor.requestResponse());
            });
        });

        api.extension().registerUnloadingHandler(() -> {
            requestExecutor.shutdownNow();
            saveSettings();
        });

        api.logging().logToOutput("Cookie Swapper v1.0 by 0xbartita loaded.");
        api.logging().logToOutput("Hotkey: Ctrl+Shift+Q (change in Burp Settings > Hotkeys)");
    }

    // ==================== UI ====================

    private void buildUI() {
        mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

        JPanel rulesPanel = new JPanel(new BorderLayout(5, 5));
        rulesPanel.setBorder(BorderFactory.createTitledBorder("Replacement Rules"));

        rulesTableModel = new DefaultTableModel(
            new Object[]{"Type", "Name", "Value", "Enabled"}, 0
        ) {
            @Override
            public Class<?> getColumnClass(int col) {
                return col == 3 ? Boolean.class : String.class;
            }
            @Override
            public boolean isCellEditable(int row, int col) {
                return true;
            }
        };
        rulesTable = new JTable(rulesTableModel);
        rulesTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        rulesTable.setSurrendersFocusOnKeystroke(true);
        rulesTable.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE && !rulesTable.isEditing()) {
                    int[] rows = rulesTable.getSelectedRows();
                    for (int i = rows.length - 1; i >= 0; i--) rulesTableModel.removeRow(rows[i]);
                }
            }
        });

        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"Cookie", "Header"});
        rulesTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(typeCombo));
        rulesTable.getColumnModel().getColumn(0).setPreferredWidth(80);
        rulesTable.getColumnModel().getColumn(0).setMaxWidth(100);
        rulesTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        rulesTable.getColumnModel().getColumn(2).setPreferredWidth(500);
        rulesTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        rulesTable.getColumnModel().getColumn(3).setMaxWidth(80);

        JScrollPane rulesScroll = new JScrollPane(rulesTable);
        rulesScroll.setPreferredSize(new Dimension(0, 150));

        JPanel rulesBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JButton addBtn = new JButton("Add Rule");
        JButton removeBtn = new JButton("Remove Selected");
        JButton clearBtn = new JButton("Clear All");
        JButton importJsonBtn = new JButton("Import Cookies JSON");
        JButton clearValuesBtn = new JButton("Clear Values Only");

        addBtn.addActionListener(e -> rulesTableModel.addRow(new Object[]{"Cookie", "", "", true}));
        removeBtn.addActionListener(e -> {
            int[] rows = rulesTable.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) rulesTableModel.removeRow(rows[i]);
        });
        clearBtn.addActionListener(e -> rulesTableModel.setRowCount(0));
        importJsonBtn.addActionListener(e -> importCookiesFromJson());
        clearValuesBtn.addActionListener(e -> {
            for (int i = 0; i < rulesTableModel.getRowCount(); i++)
                rulesTableModel.setValueAt("", i, 2);
        });

        rulesBtnPanel.add(addBtn);
        rulesBtnPanel.add(removeBtn);
        rulesBtnPanel.add(clearBtn);
        rulesBtnPanel.add(Box.createHorizontalStrut(15));
        rulesBtnPanel.add(importJsonBtn);
        rulesBtnPanel.add(clearValuesBtn);

        rulesPanel.add(rulesScroll, BorderLayout.CENTER);
        rulesPanel.add(rulesBtnPanel, BorderLayout.SOUTH);

        // Custom tab bar with wrapping, limited to 3 rows
        tabBarPanel = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                // Force wrap by using parent's width
                int width = getParent() != null ? getParent().getWidth() : 800;
                if (width <= 0) width = 800;
                FlowLayout fl = (FlowLayout) getLayout();
                int x = fl.getHgap(), y = fl.getVgap(), rowHeight = 0;
                for (Component c : getComponents()) {
                    if (!c.isVisible()) continue;
                    Dimension d = c.getPreferredSize();
                    if (x + d.width + fl.getHgap() > width) {
                        y += rowHeight + fl.getVgap();
                        x = fl.getHgap();
                        rowHeight = 0;
                    }
                    x += d.width + fl.getHgap();
                    rowHeight = Math.max(rowHeight, d.height);
                }
                y += rowHeight + fl.getVgap();
                return new Dimension(width, y);
            }
        };
        tabBarPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 3, 3));
        tabBarScroll = new JScrollPane(tabBarPanel);
        tabBarScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tabBarScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        tabBarScroll.setBorder(null);
        tabBarScroll.setPreferredSize(new Dimension(0, 105)); // ~3 rows at default font
        tabBarScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 105));
        tabBarScroll.setMinimumSize(new Dimension(0, 105));
        tabBarScroll.getViewport().addChangeListener(e -> {
            tabBarPanel.revalidate();
        });

        contentLayout = new CardLayout();
        contentPanel = new JPanel(contentLayout);

        JPanel requestsPanel = new JPanel(new BorderLayout());
        JPanel headerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JLabel requestsLabel = new JLabel("Requests");
        requestsLabel.setFont(requestsLabel.getFont().deriveFont(Font.BOLD));
        JButton closeAllBtn = new JButton("Close All");
        closeAllBtn.addActionListener(e -> closeAllTabs());
        JToggleButton filterAll = new JToggleButton("All");
        JToggleButton filter2xx = new JToggleButton("2xx");
        JToggleButton filter3xx = new JToggleButton("3xx");
        JToggleButton filter4xx = new JToggleButton("4xx");
        JToggleButton filter5xx = new JToggleButton("5xx");
        filterAll.setSelected(true);
        filter2xx.setForeground(new Color(34, 197, 94));
        filter3xx.setForeground(new Color(59, 130, 246));
        filter4xx.setForeground(new Color(249, 115, 22));
        filter5xx.setForeground(new Color(239, 68, 68));
        ButtonGroup filterGroup = new ButtonGroup();
        for (JToggleButton fb : new JToggleButton[]{filterAll, filter2xx, filter3xx, filter4xx, filter5xx}) {
            fb.setMargin(new Insets(2, 6, 2, 6));
            fb.setFocusable(false);
            filterGroup.add(fb);
        }
        filterAll.addActionListener(e -> applyFilter("all"));
        filter2xx.addActionListener(e -> applyFilter("2xx"));
        filter3xx.addActionListener(e -> applyFilter("3xx"));
        filter4xx.addActionListener(e -> applyFilter("4xx"));
        filter5xx.addActionListener(e -> applyFilter("5xx"));

        headerLeft.add(requestsLabel);
        headerLeft.add(closeAllBtn);
        headerLeft.add(Box.createHorizontalStrut(10));
        headerLeft.add(filterAll);
        headerLeft.add(filter2xx);
        headerLeft.add(filter3xx);
        headerLeft.add(filter4xx);
        headerLeft.add(filter5xx);

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 2));
        JButton overflowBtn = new JButton("All Tabs \u25BE");
        overflowBtn.addActionListener(e -> showTabOverflowMenu(overflowBtn));
        headerRight.add(overflowBtn);

        JPanel requestsHeader = new JPanel(new BorderLayout());
        requestsHeader.add(headerLeft, BorderLayout.WEST);
        requestsHeader.add(headerRight, BorderLayout.EAST);

        JPanel tabTopPanel = new JPanel(new BorderLayout());
        tabTopPanel.add(requestsHeader, BorderLayout.NORTH);
        tabTopPanel.add(tabBarScroll, BorderLayout.CENTER);

        JSplitPane tabSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabTopPanel, contentPanel);
        tabSplit.setResizeWeight(0.0);
        tabSplit.setDividerSize(6);
        requestsPanel.add(tabSplit, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rulesPanel, requestsPanel);
        splitPane.setResizeWeight(0.2);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(mainPanel);
    }

    // ==================== REQUEST PROCESSING ====================

    private void processMessage(HttpRequestResponse original) {
        // Read rules on EDT before sending to background thread
        List<ReplacementRule> rules = getRules();
        requestExecutor.submit(() -> {
            try {
                HttpRequest request = original.request();
                HttpRequest modifiedRequest = applyReplacements(request, rules);
                HttpRequestResponse response = api.http().sendRequest(modifiedRequest);
                SwingUtilities.invokeAndWait(() -> addRequestTab(modifiedRequest, response.response()));
            } catch (Exception ex) {
                api.logging().logToError("Error: " + ex.getMessage());
            }
        });
    }

    private void addRequestTab(HttpRequest modifiedRequest, HttpResponse response) {
        tabCounter++;
        String tabId = "tab_" + tabCounter;

        String path = modifiedRequest.path();
        int qIdx = path.indexOf('?');
        if (qIdx >= 0) path = path.substring(0, qIdx);
        if (path.length() > 50) path = path.substring(0, 47) + "...";
        int statusCode = response != null ? response.statusCode() : 0;
        String tabTitle = "#" + tabCounter + " [" + (statusCode > 0 ? statusCode : "?") + "] " + path;

        burp.api.montoya.ui.editor.HttpRequestEditor reqEditor = api.userInterface().createHttpRequestEditor();
        burp.api.montoya.ui.editor.HttpResponseEditor resEditor = api.userInterface().createHttpResponseEditor();
        reqEditor.setRequest(modifiedRequest);
        if (response != null) resEditor.setResponse(response);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.5);

        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.setBorder(BorderFactory.createTitledBorder("Request"));
        reqPanel.add(reqEditor.uiComponent(), BorderLayout.CENTER);

        JPanel reqBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JButton sendBtn = new JButton("Send");
        sendBtn.addActionListener(e -> {
            new Thread(() -> {
                try {
                    HttpRequest req = applyReplacements(reqEditor.getRequest());
                    reqEditor.setRequest(req);
                    HttpRequestResponse resp = api.http().sendRequest(req);
                    if (resp.response() != null)
                        SwingUtilities.invokeLater(() -> resEditor.setResponse(resp.response()));
                } catch (Exception ex) {
                    api.logging().logToError("Send error: " + ex.getMessage());
                }
            }).start();
        });

        JButton sendNoReplaceBtn = new JButton("Send (No Replace)");
        sendNoReplaceBtn.addActionListener(e -> {
            new Thread(() -> {
                try {
                    HttpRequestResponse resp = api.http().sendRequest(reqEditor.getRequest());
                    if (resp.response() != null)
                        SwingUtilities.invokeLater(() -> resEditor.setResponse(resp.response()));
                } catch (Exception ex) {
                    api.logging().logToError("Send error: " + ex.getMessage());
                }
            }).start();
        });

        reqBtnPanel.add(sendBtn);
        reqBtnPanel.add(sendNoReplaceBtn);
        reqPanel.add(reqBtnPanel, BorderLayout.SOUTH);

        JPanel resPanel = new JPanel(new BorderLayout());
        resPanel.setBorder(BorderFactory.createTitledBorder("Response"));
        resPanel.add(resEditor.uiComponent(), BorderLayout.CENTER);

        split.setLeftComponent(reqPanel);
        split.setRightComponent(resPanel);

        // Create tab button with color
        Color tabColor = statusCode >= 500 ? new Color(239, 68, 68) :
                          statusCode >= 400 ? new Color(249, 115, 22) :
                          statusCode >= 300 ? new Color(59, 130, 246) :
                          statusCode >= 200 ? new Color(34, 197, 94) : null;

        JToggleButton tabBtn = new JToggleButton(tabTitle);
        tabBtn.setMargin(new Insets(4, 10, 4, 10));
        tabBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (tabColor != null) tabBtn.setForeground(tabColor);

        tabBtn.addActionListener(e -> selectTab(tabId));
        tabBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isMiddleMouseButton(e)) {
                    closeTab(tabId);
                }
            }
        });

        tabGroup.add(tabBtn);
        tabButtons.put(tabId, tabBtn);
        tabContents.put(tabId, split);
        tabStatusCodes.put(tabId, statusCode);
        tabBarPanel.add(tabBtn);
        contentPanel.add(split, tabId);
        tabBtn.setVisible(matchesFilter(statusCode));

        selectTab(tabId);
        tabBarPanel.revalidate();
        tabBarPanel.repaint();

        // Scroll to bottom to show new tab
        SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = tabBarScroll.getVerticalScrollBar();
            vBar.setValue(vBar.getMaximum());
        });
    }

    private void selectTab(String tabId) {
        selectedTabId = tabId;
        JToggleButton btn = tabButtons.get(tabId);
        if (btn != null) btn.setSelected(true);
        contentLayout.show(contentPanel, tabId);
    }

    private void closeTab(String tabId) {
        JToggleButton btn = tabButtons.remove(tabId);
        Component content = tabContents.remove(tabId);
        tabStatusCodes.remove(tabId);
        if (btn != null) {
            tabGroup.remove(btn);
            tabBarPanel.remove(btn);
        }
        if (content != null) contentPanel.remove(content);

        // Select another tab if we closed the active one
        if (tabId.equals(selectedTabId)) {
            if (!tabButtons.isEmpty()) {
                String lastId = null;
                for (String id : tabButtons.keySet()) lastId = id;
                selectTab(lastId);
            } else {
                selectedTabId = null;
            }
        }
        tabBarPanel.revalidate();
        tabBarPanel.repaint();
    }

    private void showTabOverflowMenu(Component anchor) {
        if (tabButtons.isEmpty()) return;
        JPopupMenu menu = new JPopupMenu();
        for (Map.Entry<String, JToggleButton> entry : tabButtons.entrySet()) {
            String id = entry.getKey();
            JToggleButton btn = entry.getValue();
            JMenuItem item = new JMenuItem(btn.getText());
            item.setForeground(btn.getForeground());
            if (id.equals(selectedTabId)) item.setFont(item.getFont().deriveFont(Font.BOLD));
            item.addActionListener(e -> selectTab(id));
            menu.add(item);
        }
        menu.show(anchor, 0, anchor.getHeight());
    }

    private void closeAllTabs() {
        tabButtons.clear();
        tabContents.clear();
        tabStatusCodes.clear();
        tabGroup = new ButtonGroup();
        tabBarPanel.removeAll();
        contentPanel.removeAll();
        selectedTabId = null;
        tabBarPanel.revalidate();
        tabBarPanel.repaint();
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    private boolean matchesFilter(int statusCode) {
        if ("all".equals(activeFilter)) return true;
        if ("2xx".equals(activeFilter)) return statusCode >= 200 && statusCode < 300;
        if ("3xx".equals(activeFilter)) return statusCode >= 300 && statusCode < 400;
        if ("4xx".equals(activeFilter)) return statusCode >= 400 && statusCode < 500;
        if ("5xx".equals(activeFilter)) return statusCode >= 500;
        return true;
    }

    private void applyFilter(String filter) {
        activeFilter = filter;
        for (Map.Entry<String, JToggleButton> entry : tabButtons.entrySet()) {
            String id = entry.getKey();
            JToggleButton btn = entry.getValue();
            Integer sc = tabStatusCodes.get(id);
            btn.setVisible(sc != null && matchesFilter(sc));
        }
        tabBarPanel.revalidate();
        tabBarPanel.repaint();
    }

    // ==================== REPLACEMENT LOGIC ====================

    private HttpRequest applyReplacements(HttpRequest request) {
        return applyReplacements(request, getRules());
    }

    private HttpRequest applyReplacements(HttpRequest request, List<ReplacementRule> rules) {
        if (rules.isEmpty()) return request;

        List<HttpHeader> headers = new ArrayList<>(request.headers());
        List<HttpHeader> newHeaders = new ArrayList<>();
        Set<String> processedHeaderRules = new HashSet<>();

        for (HttpHeader header : headers) {
            String headerName = header.name();
            if (headerName.equalsIgnoreCase("Cookie")) {
                newHeaders.add(HttpHeader.httpHeader("Cookie", replaceCookies(header.value(), rules)));
            } else {
                boolean replaced = false;
                for (ReplacementRule rule : rules) {
                    if (rule.type.equals("Header") && rule.name.equalsIgnoreCase(headerName)) {
                        newHeaders.add(HttpHeader.httpHeader(headerName, rule.value));
                        processedHeaderRules.add(rule.name.toLowerCase());
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) newHeaders.add(header);
            }
        }

        for (ReplacementRule rule : rules) {
            if (rule.type.equals("Header") && !processedHeaderRules.contains(rule.name.toLowerCase())) {
                boolean exists = false;
                for (HttpHeader h : newHeaders) {
                    if (h.name().equalsIgnoreCase(rule.name)) { exists = true; break; }
                }
                if (!exists) newHeaders.add(HttpHeader.httpHeader(rule.name, rule.value));
            }
        }

        HttpRequest result = request;
        for (HttpHeader h : headers) result = result.withRemovedHeader(h.name());
        for (HttpHeader h : newHeaders) result = result.withAddedHeader(h);
        return result;
    }

    private String replaceCookies(String cookieHeader, List<ReplacementRule> rules) {
        String[] cookies = cookieHeader.split(";");
        StringBuilder result = new StringBuilder();
        for (String cookie : cookies) {
            cookie = cookie.trim();
            if (cookie.isEmpty()) continue;
            String[] parts = cookie.split("=", 2);
            String cookieName = parts[0].trim();
            String cookieValue = parts.length > 1 ? parts[1].trim() : "";
            for (ReplacementRule rule : rules) {
                if (rule.type.equals("Cookie") && rule.name.equalsIgnoreCase(cookieName)) {
                    cookieValue = rule.value;
                    break;
                }
            }
            if (result.length() > 0) result.append("; ");
            result.append(cookieName).append("=").append(cookieValue);
        }
        return result.toString();
    }

    private List<ReplacementRule> getRules() {
        if (rulesTable.isEditing()) rulesTable.getCellEditor().stopCellEditing();
        List<ReplacementRule> rules = new ArrayList<>();
        for (int i = 0; i < rulesTableModel.getRowCount(); i++) {
            String type = (String) rulesTableModel.getValueAt(i, 0);
            String name = (String) rulesTableModel.getValueAt(i, 1);
            String value = (String) rulesTableModel.getValueAt(i, 2);
            Boolean enabled = (Boolean) rulesTableModel.getValueAt(i, 3);
            if (name != null && !name.isEmpty() && enabled != null && enabled
                    && value != null && !value.isEmpty()) {
                rules.add(new ReplacementRule(type, name, value));
            }
        }
        return rules;
    }

    // ==================== JSON IMPORT ====================

    private void importCookiesFromJson() {
        String json = "";
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
                json = ((String) clipboard.getData(DataFlavor.stringFlavor)).trim();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(mainPanel, "Failed to read clipboard:\n" + ex.getMessage(),
                    "Clipboard Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (json.isEmpty() || !json.startsWith("[")) {
            JOptionPane.showMessageDialog(mainPanel,
                    "Clipboard does not contain a JSON array.\nCopy cookies JSON first, then click Import.",
                    "Import", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            List<String[]> cookies = parseJsonCookies(json);
            if (cookies.isEmpty()) {
                JOptionPane.showMessageDialog(mainPanel, "No cookies found.", "Import", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // If no existing cookie rules, just add directly without asking
            boolean hasExistingCookies = false;
            for (int i = 0; i < rulesTableModel.getRowCount(); i++) {
                if ("Cookie".equals(rulesTableModel.getValueAt(i, 0))) { hasExistingCookies = true; break; }
            }

            int choice = 0; // default to merge
            if (hasExistingCookies) {
                choice = JOptionPane.showOptionDialog(mainPanel,
                        "Found " + cookies.size() + " cookies. How to import?",
                        "Import Cookies", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, new String[]{"Merge (update existing, add new)", "Replace all", "Cancel"}, "Merge");
                if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;

                if (choice == 1) {
                    for (int i = rulesTableModel.getRowCount() - 1; i >= 0; i--)
                        if ("Cookie".equals(rulesTableModel.getValueAt(i, 0))) rulesTableModel.removeRow(i);
                }
            }

            for (String[] cookie : cookies) {
                String name = cookie[0], value = cookie[1];
                if (choice == 0 && hasExistingCookies) {
                    boolean found = false;
                    for (int i = 0; i < rulesTableModel.getRowCount(); i++) {
                        if ("Cookie".equals(rulesTableModel.getValueAt(i, 0))
                                && name.equals(rulesTableModel.getValueAt(i, 1))) {
                            rulesTableModel.setValueAt(value, i, 2);
                            found = true; break;
                        }
                    }
                    if (!found) rulesTableModel.addRow(new Object[]{"Cookie", name, value, true});
                } else {
                    rulesTableModel.addRow(new Object[]{"Cookie", name, value, true});
                }
            }
            api.logging().logToOutput("Imported " + cookies.size() + " cookies from JSON.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(mainPanel, "Failed to parse JSON:\n" + ex.getMessage(),
                    "Import Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private List<String[]> parseJsonCookies(String json) {
        List<String[]> result = new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[")) throw new RuntimeException("Expected JSON array");
        json = json.substring(1, json.length() - 1).trim();
        List<String> objects = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    objects.add(json.substring(start, i + 1).trim());
                    start = i + 1;
                    while (start < json.length() && ",  \n\r\t".indexOf(json.charAt(start)) >= 0) start++;
                }
            }
        }
        for (String obj : objects) {
            String name = extractJsonString(obj, "name");
            String value = extractJsonString(obj, "value");
            if (name != null) result.add(new String[]{name, value != null ? value : ""});
        }
        return result;
    }

    private String extractJsonString(String obj, String key) {
        String pattern = "\"" + key + "\"";
        int idx = obj.indexOf(pattern);
        if (idx < 0) return null;
        idx = obj.indexOf(":", idx + pattern.length());
        if (idx < 0) return null;
        idx++;
        while (idx < obj.length() && obj.charAt(idx) == ' ') idx++;
        if (idx >= obj.length()) return null;
        if (obj.charAt(idx) == '"') {
            idx++;
            StringBuilder sb = new StringBuilder();
            while (idx < obj.length() && obj.charAt(idx) != '"') {
                if (obj.charAt(idx) == '\\' && idx + 1 < obj.length()) { idx++; sb.append(obj.charAt(idx)); }
                else sb.append(obj.charAt(idx));
                idx++;
            }
            return sb.toString();
        } else if (obj.substring(idx).startsWith("null")) {
            return null;
        } else {
            int end = idx;
            while (end < obj.length() && ",} \n".indexOf(obj.charAt(end)) < 0) end++;
            return obj.substring(idx, end);
        }
    }

    // ==================== PERSISTENCE ====================

    private void saveSettings() {
        try {
            if (rulesTable.isEditing()) rulesTable.getCellEditor().stopCellEditing();
            PersistedObject store = api.persistence().extensionData();
            int count = rulesTableModel.getRowCount();
            store.setInteger("count", count);
            for (int i = 0; i < count; i++) {
                store.setString("type_" + i, String.valueOf(rulesTableModel.getValueAt(i, 0)));
                store.setString("name_" + i, String.valueOf(rulesTableModel.getValueAt(i, 1)));
                Boolean enabled = (Boolean) rulesTableModel.getValueAt(i, 3);
                store.setBoolean("enabled_" + i, enabled != null ? enabled : true);
            }
            api.logging().logToOutput("Cookie Swapper: saved " + count + " rules.");
        } catch (Exception e) {
            api.logging().logToError("Save failed: " + e.getMessage());
        }
    }

    private void loadSettings() {
        try {
            PersistedObject store = api.persistence().extensionData();
            Integer count = store.getInteger("count");
            if (count == null) return;
            for (int i = 0; i < count; i++) {
                String type = store.getString("type_" + i);
                String name = store.getString("name_" + i);
                Boolean enabled = store.getBoolean("enabled_" + i);
                if (type != null && name != null)
                    rulesTableModel.addRow(new Object[]{type, name, "", enabled != null ? enabled : true});
            }
            api.logging().logToOutput("Cookie Swapper: loaded " + count + " rule names.");
        } catch (Exception e) {
            api.logging().logToError("Load failed: " + e.getMessage());
        }
    }

    private static class ReplacementRule {
        String type, name, value;
        ReplacementRule(String type, String name, String value) {
            this.type = type; this.name = name; this.value = value;
        }
    }
}
