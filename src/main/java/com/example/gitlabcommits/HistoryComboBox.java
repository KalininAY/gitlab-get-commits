package com.example.gitlabcommits;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable JComboBox that remembers history.
 * The most recently used value is always at index 0.
 */
public class HistoryComboBox extends JComboBox<String> {

    public HistoryComboBox(List<String> items, String defaultValue) {
        super();
        setEditable(true);
        for (String item : items) addItem(item);
        if (items.isEmpty() && defaultValue != null && !defaultValue.isEmpty()) {
            addItem(defaultValue);
        }
        if (getItemCount() > 0) setSelectedIndex(0);
    }

    /** Returns the currently typed/selected value */
    public String getCurrentValue() {
        Object ed = getEditor().getItem();
        return ed != null ? ed.toString().trim() : "";
    }

    /** Returns all dropdown items */
    public List<String> getAllItems() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < getItemCount(); i++) list.add(getItemAt(i));
        return list;
    }

    /** Moves value to the top (or adds if absent) */
    public void pushValue(String value) {
        if (value == null || value.isEmpty()) return;
        for (int i = 0; i < getItemCount(); i++) {
            if (value.equals(getItemAt(i))) { removeItemAt(i); break; }
        }
        insertItemAt(value, 0);
        setSelectedIndex(0);
    }
}
