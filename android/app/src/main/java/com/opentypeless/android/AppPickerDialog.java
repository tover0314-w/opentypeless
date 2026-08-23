package com.opentypeless.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.opentypeless.android.config.AppPickerModel;

import java.util.List;
import java.util.Objects;

/** Searchable dialog for launchable applications with an explicit manual-package fallback. */
final class AppPickerDialog {
    private AppPickerDialog() {}

    interface Listener {
        void onAppSelected(AppPickerModel.Entry entry);

        void onAdvancedPackageRequested();
    }

    static AlertDialog show(Activity activity, Listener listener) {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(listener, "listener");

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0);

        EditText search = new EditText(activity);
        search.setId(R.id.app_picker_search);
        search.setSingleLine(true);
        search.setHint(R.string.app_picker_search_hint);
        search.setContentDescription(activity.getString(R.string.app_picker_search_hint));
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setMinHeight(dp(activity, 48));
        content.addView(search, matchWrap());

        TextView status = AppVisualSystem.body(activity, "");
        status.setId(R.id.app_picker_status);
        content.addView(status, matchWrap());

        ListView list = new ListView(activity);
        list.setId(R.id.app_picker_list);
        list.setDividerHeight(0);
        list.setContentDescription(activity.getString(R.string.app_picker_list_description));
        content.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 420)));

        InstalledAppCatalog.Snapshot snapshot = null;
        AppAdapter adapter = null;
        try {
            snapshot = InstalledAppCatalog.load(activity);
            adapter = new AppAdapter(activity, snapshot);
            list.setAdapter(adapter);
            updateStatus(activity, status, adapter.getCount(), false);
        } catch (InstalledAppCatalog.CatalogUnavailableException error) {
            status.setText(R.string.app_picker_unavailable);
            list.setVisibility(View.GONE);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.app_picker_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.app_picker_advanced_package, null)
                .create();

        AppAdapter finalAdapter = adapter;
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (finalAdapter == null) return;
            listener.onAppSelected(finalAdapter.entry(position));
            dialog.dismiss();
        });
        if (adapter != null) {
            AppAdapter searchableAdapter = adapter;
            search.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    try {
                        searchableAdapter.filter(s == null ? "" : s.toString());
                        search.setError(null);
                        updateStatus(activity, status, searchableAdapter.getCount(), true);
                    } catch (IllegalArgumentException error) {
                        searchableAdapter.clear();
                        search.setError(activity.getString(R.string.app_picker_search_too_long));
                        updateStatus(activity, status, 0, true);
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {}
            });
        }
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> {
                    dialog.dismiss();
                    listener.onAdvancedPackageRequested();
                }));
        dialog.show();
        return dialog;
    }

    private static void updateStatus(Activity activity, TextView status, int count, boolean search) {
        if (count == 0) {
            status.setText(search ? R.string.app_picker_no_results : R.string.app_picker_unavailable);
        } else {
            status.setText(activity.getString(R.string.app_picker_result_count, count));
        }
    }

    private static final class AppAdapter extends BaseAdapter {
        private final Activity activity;
        private final InstalledAppCatalog.Snapshot snapshot;
        private final LruCache<String, Drawable> icons = new LruCache<>(32);
        private List<AppPickerModel.Entry> visible;

        AppAdapter(Activity activity, InstalledAppCatalog.Snapshot snapshot) {
            this.activity = activity;
            this.snapshot = snapshot;
            visible = snapshot.model().entries();
        }

        void filter(String query) {
            visible = snapshot.model().search(query);
            notifyDataSetChanged();
        }

        void clear() {
            visible = List.of();
            notifyDataSetChanged();
        }

        AppPickerModel.Entry entry(int position) {
            return visible.get(position);
        }

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public AppPickerModel.Entry getItem(int position) {
            return entry(position);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).packageName().hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Row row;
            if (convertView == null) {
                row = new Row(activity);
                convertView = row.root;
                convertView.setTag(row);
            } else {
                row = (Row) convertView.getTag();
            }
            AppPickerModel.Entry entry = getItem(position);
            row.label.setText(entry.label());
            row.packageName.setText(entry.packageName());
            row.root.setContentDescription(activity.getString(
                    R.string.app_picker_entry_description, entry.label(), entry.packageName()));
            Drawable icon = icons.get(entry.packageName());
            if (icon == null) {
                icon = snapshot.iconFor(activity, entry);
                icons.put(entry.packageName(), icon);
            }
            row.icon.setImageDrawable(icon);
            return convertView;
        }
    }

    private static final class Row {
        final LinearLayout root;
        final ImageView icon;
        final TextView label;
        final TextView packageName;

        Row(Activity activity) {
            root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setMinimumHeight(dp(activity, 64));
            root.setPadding(0, dp(activity, 6), 0, dp(activity, 6));
            root.setBackgroundResource(R.drawable.app_row_background);

            icon = new ImageView(activity);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            root.addView(icon, new LinearLayout.LayoutParams(dp(activity, 44), dp(activity, 44)));

            LinearLayout text = new LinearLayout(activity);
            text.setOrientation(LinearLayout.VERTICAL);
            text.setPadding(dp(activity, 12), 0, 0, 0);
            label = AppVisualSystem.section(activity, "");
            packageName = AppVisualSystem.body(activity, "");
            text.addView(label, matchWrap());
            text.addView(packageName, matchWrap());
            root.addView(text, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
