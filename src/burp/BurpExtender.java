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
import java.util.*;
import java.util.List;

public class BurpExtender implements BurpExtension {

    private MontoyaApi api;
    private JPanel mainPanel;
    private DefaultTableModel rulesTableModel;
    private JTable rulesTable;
    private JTabbedPane requestTabs;
    private int tabCounter = 0;

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

        api.extension().registerUnloadingHandler(() -> saveSettings());

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

        requestTabs = new JTabbedPane();
        requestTabs.setBorder(BorderFactory.createTitledBorder("Requests"));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, rulesPanel, requestTabs);
        splitPane.setResizeWeight(0.2);
        mainPanel.add(splitPane, BorderLayout.CENTER);

        api.userInterface().applyThemeToComponent(mainPanel);
    }

    // ==================== REQUEST PROCESSING ====================

    private void processMessage(HttpRequestResponse original) {
        new Thread(() -> {
            try {
                HttpRequest request = original.request();
                HttpRequest modifiedRequest = applyReplacements(request);
                HttpRequestResponse response = api.http().sendRequest(modifiedRequest);
                SwingUtilities.invokeLater(() -> addRequestTab(modifiedRequest, response.response()));
            } catch (Exception ex) {
                api.logging().logToError("Error: " + ex.getMessage());
            }
        }).start();
    }

    private void addRequestTab(HttpRequest modifiedRequest, HttpResponse response) {
        tabCounter++;

        String method = modifiedRequest.method();
        String path = modifiedRequest.path();
        if (path.length() > 40) path = path.substring(0, 37) + "...";
        int statusCode = response != null ? response.statusCode() : 0;
        String tabTitle = "#" + tabCounter + " [" + (statusCode > 0 ? statusCode : "?") + "] " + method + " " + path;

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

        requestTabs.addTab(tabTitle, split);
        int tabIndex = requestTabs.getTabCount() - 1;
        requestTabs.setTabComponentAt(tabIndex, createTabHeader(tabTitle, split));
        requestTabs.setSelectedIndex(tabIndex);
    }

    private JPanel createTabHeader(String title, Component tabContent) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        header.setOpaque(false);
        JLabel titleLabel = new JLabel(title);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 5));
        JButton closeBtn = new JButton("\u00D7");
        closeBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        closeBtn.setMargin(new Insets(0, 3, 0, 3));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusable(false);
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.addActionListener(e -> {
            int idx = requestTabs.indexOfComponent(tabContent);
            if (idx >= 0) requestTabs.removeTabAt(idx);
        });
        header.add(titleLabel);
        header.add(closeBtn);
        return header;
    }

    // ==================== REPLACEMENT LOGIC ====================

    private HttpRequest applyReplacements(HttpRequest request) {
        List<ReplacementRule> rules = getRules();
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

            int choice = JOptionPane.showOptionDialog(mainPanel,
                    "Found " + cookies.size() + " cookies. How to import?",
                    "Import Cookies", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, new String[]{"Merge (update existing, add new)", "Replace all", "Cancel"}, "Merge");
            if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) return;

            if (choice == 1) {
                for (int i = rulesTableModel.getRowCount() - 1; i >= 0; i--)
                    if ("Cookie".equals(rulesTableModel.getValueAt(i, 0))) rulesTableModel.removeRow(i);
            }

            for (String[] cookie : cookies) {
                String name = cookie[0], value = cookie[1];
                if (choice == 0) {
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
