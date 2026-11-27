package com.limelight;

import static com.limelight.porta.PortaViews.*;

import android.Manifest;
import android.app.Service;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.*;
import android.preference.PreferenceManager;
import android.view.*;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.loadingindicator.LoadingIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.*;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.*;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.*;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.porta.*;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.StreamSettings;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.*;
import java.net.UnknownHostException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Porta home. Discovery, pairing and launch continue through Moonlight's existing services. */
public class PcView extends AppCompatActivity {
    private final List<ComputerDetails> computers = new ArrayList<>();
    private final HostsAdapter adapter = new HostsAdapter();
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private ShortcutHelper shortcutHelper;
    private boolean bound, runningPolling, inForeground, freezeUpdates, firstRun;
    private volatile boolean pairing, pairingCancelled;
    private android.app.Dialog pinDialog;
    private LinearLayout pinContent;
    private MaterialButton pendingMic;
    private String pendingMicUuid;
    private LinearLayout welcome;
    private MaterialToolbar toolbar;
    private LinearLayout emptyState;
    private RecyclerView list;
    private ExtendedFloatingActionButton add;
    private final ServiceConnection serviceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    ComputerManagerService.ComputerManagerBinder local =
                            (ComputerManagerService.ComputerManagerBinder) binder;
                    new Thread(
                                    () -> {
                                        local.waitForReady();
                                        new AndroidCryptoProvider(PcView.this)
                                                .getClientCertificate();
                                        runOnUiThread(
                                                () -> {
                                                    if (isDestroyed()) return;
                                                    managerBinder = local;
                                                    startComputerUpdates();
                                                });
                                    },
                                    "Porta home ready")
                            .start();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    managerBinder = null;
                    runningPolling = false;
                }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        UiHelper.setLocale(this);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        shortcutHelper = new ShortcutHelper(this);
        try (ComputerStore store = new ComputerStore(this)) {
            computers.addAll(store.db.getAllComputers());
        }
        initializeWithRenderer();
        bound =
                bindService(
                        new Intent(this, ComputerManagerService.class),
                        serviceConnection,
                        Service.BIND_AUTO_CREATE);
    }

    private void initializeWithRenderer() {
        com.limelight.preferences.GlPreferences prefs =
                com.limelight.preferences.GlPreferences.readPreferences(this);
        if (prefs.savedFingerprint.equals(Build.FINGERPRINT) && !prefs.glRenderer.isEmpty()) {
            initializeViews();
            return;
        }
        android.opengl.GLSurfaceView gl = new android.opengl.GLSurfaceView(this);
        gl.setRenderer(
                new android.opengl.GLSurfaceView.Renderer() {
                    @Override
                    public void onSurfaceCreated(
                            javax.microedition.khronos.opengles.GL10 renderer,
                            javax.microedition.khronos.egl.EGLConfig config) {
                        prefs.glRenderer =
                                renderer.glGetString(
                                        javax.microedition.khronos.opengles.GL10.GL_RENDERER);
                        prefs.savedFingerprint = Build.FINGERPRINT;
                        prefs.writePreferences();
                        runOnUiThread(
                                () -> {
                                    if (!isDestroyed()) initializeViews();
                                });
                    }

                    @Override
                    public void onSurfaceChanged(
                            javax.microedition.khronos.opengles.GL10 renderer, int w, int h) {}

                    @Override
                    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 renderer) {}
                });
        setContentView(gl);
    }

    // Keeps database ownership explicit for short-lived UI operations.
    private static final class ComputerStore implements AutoCloseable {
        final ComputerDatabaseManager db;

        ComputerStore(android.content.Context c) {
            db = new ComputerDatabaseManager(c);
        }

        @Override
        public void close() {
            db.close();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        recreate(); // Re-resolve DayNight resources, retaining hosts in the database.
    }

    private void initializeViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(color(this, R.color.porta_background));
        root.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    v.setPadding(
                            insets.getSystemWindowInsetLeft(),
                            insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetRight(),
                            insets.getSystemWindowInsetBottom());
                    return insets;
                });
        LinearLayout page = column(this);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        toolbar = new MaterialToolbar(this);
        toolbar.setContentInsetsRelative(dp(this, 16), dp(this, 4));
        LinearLayout brand = row(this);
        brand.addView(image(this, R.drawable.porta_logo, 30, 30));
        ImageView wordmark = image(this, R.drawable.ic_porta_wordmark, 72, 26);
        LinearLayout.LayoutParams wp = size(this, 72, 26);
        wp.leftMargin = dp(this, 10);
        brand.addView(wordmark, wp);
        brand.setContentDescription(getString(R.string.app_label));
        toolbar.addView(
                brand,
                new androidx.appcompat.widget.Toolbar.LayoutParams(
                        -2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        android.graphics.drawable.Drawable settingsIcon =
                androidx.appcompat.content.res.AppCompatResources.getDrawable(
                                this, R.drawable.ic_porta_settings)
                        .mutate();
        settingsIcon.setTint(color(this, R.color.porta_on_surface_variant));
        toolbar.getMenu()
                .add(R.string.porta_settings)
                .setIcon(settingsIcon)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        toolbar.setOnMenuItemClickListener(
                item -> {
                    startActivity(new Intent(this, StreamSettings.class));
                    return true;
                });
        page.addView(toolbar, size(this, -1, 64));
        welcome = column(this);
        welcome.setClipChildren(false);
        welcome.setGravity(Gravity.CENTER_HORIZONTAL);
        welcome.setPadding(dp(this, 16), 0, dp(this, 16), 0);
        LinearLayout hero = column(this);
        hero.setClipChildren(false);
        hero.setGravity(Gravity.CENTER);
        FrameLayout heroBrand = brand(this, R.drawable.porta_logo, 96, 96, 264, 264);
        LinearLayout.LayoutParams heroBrandLp = size(this, 264, 264);
        heroBrandLp.setMargins(0, dp(this, -60), 0, dp(this, -60));
        hero.addView(heroBrand, heroBrandLp);
        space(hero, 0);
        hero.addView(image(this, R.drawable.ic_porta_wordmark, 116, 42));
        boolean compact =
                getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE;
        hero.setVisibility(compact ? View.GONE : View.VISIBLE);
        welcome.addView(hero, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout title = row(this);
        title.addView(
                text(this, getString(R.string.porta_pair_title), 22, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        LoadingIndicator loading = loading(this, 24);
        title.addView(loading, size(this, 24, 24));
        title.setMinimumHeight(dp(this, 56));
        welcome.addView(title, size(this, -1, -2));
        boolean largeText = getResources().getConfiguration().fontScale > 1.3f;
        page.addView(
                welcome,
                new LinearLayout.LayoutParams(
                        -1, compact ? -2 : 0, compact ? 0 : largeText ? 0.6f : 0.49f));
        list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 100));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, largeText ? 0.4f : 0.51f));
        emptyState = column(this);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(this, 24), 0, dp(this, 24), dp(this, 64));
        emptyState.addView(image(this, R.drawable.porta_arch_off, 64, 88));
        space(emptyState, 24);
        TextView emptyLabel = text(this, getString(R.string.porta_empty), 22, true);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyState.addView(emptyLabel);
        page.addView(emptyState, new LinearLayout.LayoutParams(-1, 0, 1));
        add = new ExtendedFloatingActionButton(this);
        add.setText(R.string.porta_add);
        add.setShapeAppearanceModel(
                ShapeAppearanceModel.builder().setAllCornerSizes(dp(this, 28)).build());
        add.setStateListAnimator(null);
        add.setElevation(getResources().getBoolean(R.bool.porta_is_light) ? dp(this, 2) : 0);

        add.setIconResource(R.drawable.ic_porta_add);
        add.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        color(this, R.color.porta_primary_container)));
        add.setTextColor(color(this, R.color.porta_on_primary_container));
        add.setIconTint(
                android.content.res.ColorStateList.valueOf(
                        color(this, R.color.porta_on_primary_container)));
        add.setOnClickListener(v -> showAdd());
        FrameLayout.LayoutParams fab =
                new FrameLayout.LayoutParams(-2, dp(this, 56), Gravity.BOTTOM | Gravity.END);
        fab.setMargins(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 20));
        root.addView(add, fab);
        list.addOnScrollListener(
                new RecyclerView.OnScrollListener() {
                    @Override
                    public void onScrolled(RecyclerView rv, int dx, int dy) {
                        if (dy > 0) add.shrink();
                        else if (dy < 0) add.extend();
                    }
                });
        setContentView(root);
        refreshPresentation();
    }

    private void refreshPresentation() {
        boolean paired =
                computers.stream()
                        .anyMatch(
                                c ->
                                        c.pairState == PairState.PAIRED
                                                || (c.state != ComputerDetails.State.ONLINE
                                                        && c.serverCert != null));
        if (paired)
            getSharedPreferences("porta_home", MODE_PRIVATE)
                    .edit()
                    .putBoolean("has_paired", true)
                    .apply();
        firstRun =
                !paired
                        && !getSharedPreferences("porta_home", MODE_PRIVATE)
                                .getBoolean("has_paired", false);
        if (welcome == null) return;
        toolbar.setVisibility(
                firstRun
                                && getResources().getConfiguration().orientation
                                        != Configuration.ORIENTATION_LANDSCAPE
                        ? View.GONE
                        : View.VISIBLE);
        welcome.setVisibility(firstRun ? View.VISIBLE : View.GONE);
        add.setVisibility(firstRun ? View.GONE : View.VISIBLE);
        boolean isEmpty = !firstRun && computers.isEmpty();
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        computers.sort(ComputerPresentation.ORDER);
        adapter.notifyDataSetChanged();
        androidx.fragment.app.Fragment sheet =
                getSupportFragmentManager().findFragmentByTag("connect");
        if (sheet instanceof ConnectSheet) ((ConnectSheet) sheet).refreshConnectionState();
    }

    private void startComputerUpdates() {
        if (managerBinder == null || runningPolling || !inForeground || pairing) return;
        freezeUpdates = false;
        runningPolling = true;
        managerBinder.startPolling(
                details -> {
                    if (!freezeUpdates)
                        runOnUiThread(
                                () -> {
                                    if (!isDestroyed()) updateComputer(details);
                                });
                    if (details.pairState == PairState.PAIRED)
                        shortcutHelper.createAppViewShortcutForOnlineHost(details);
                });
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder == null || !runningPolling) return;
        freezeUpdates = true;
        managerBinder.stopPolling();
        if (wait) managerBinder.waitForPollingStopped();
        runningPolling = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        UiHelper.showDecoderCrashDialog(this);
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        inForeground = false;
        stopComputerUpdates(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (pairing) cancelPairing();
    }

    @Override
    protected void onDestroy() {
        if (pairing) cancelPairing();
        if (pinDialog != null) pinDialog.dismiss();
        if (bound) unbindService(serviceConnection);
        super.onDestroy();
    }

    private void updateComputer(ComputerDetails details) {
        for (int i = 0; i < computers.size(); i++)
            if (Objects.equals(computers.get(i).uuid, details.uuid)) {
                details.lastUsed = Math.max(details.lastUsed, computers.get(i).lastUsed);
                computers.set(i, details);
                refreshPresentation();
                return;
            }
        computers.add(details);
        refreshPresentation();
    }

    public ComputerDetails getComputer(String uuid) {
        for (ComputerDetails c : computers) if (Objects.equals(uuid, c.uuid)) return c;
        return null;
    }

    public String displayName(ComputerDetails c) {
        return getSharedPreferences("porta_home", MODE_PRIVATE).getString("name_" + c.uuid, c.name);
    }

    private void openComputer(ComputerDetails c) {
        if (ComputerPresentation.status(c) == ComputerPresentation.Status.UNPAIRED) doPair(c);
        else ConnectSheet.show(this, c.uuid);
    }

    private void showAdd() {
        startActivity(new Intent(this, AddComputerManually.class));
    }

    public void showComputerMenu(View anchor, ComputerDetails c) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.porta_pair_again);
        if (ComputerPresentation.hasMac(c)) menu.getMenu().add(0, 2, 1, R.string.porta_wake);
        menu.getMenu().add(0, 3, 2, R.string.porta_rename);
        menu.getMenu().add(0, 4, 3, R.string.porta_remove);
        menu.getMenu().add(0, 5, 4, R.string.porta_apps);
        menu.setOnMenuItemClickListener(
                item -> {
                    switch (item.getItemId()) {
                        case 1:
                            doPair(c, true);
                            break;
                        case 2:
                            doWakeOnLan(c);
                            break;
                        case 3:
                            rename(c);
                            break;
                        case 4:
                            new MaterialAlertDialogBuilder(this)
                                    .setMessage(
                                            getString(
                                                    R.string.porta_remove_confirm, displayName(c)))
                                    .setNegativeButton(R.string.porta_cancel, null)
                                    .setPositiveButton(
                                            R.string.porta_remove, (d, w) -> removeComputer(c))
                                    .show();
                            break;
                        case 5:
                            doAppList(c, false, true);
                            break;
                    }
                    return true;
                });
        menu.show();
    }

    private void rename(ComputerDetails c) {
        TextInputLayout field = new TextInputLayout(this);
        field.setHint(getString(R.string.porta_name));
        TextInputEditText input = new TextInputEditText(field.getContext());
        input.setSingleLine(true);
        input.setText(displayName(c));
        field.addView(input);
        int p = dp(this, 24);
        field.setPadding(p, dp(this, 8), p, 0);
        androidx.appcompat.app.AlertDialog dialog =
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.porta_rename)
                        .setView(field)
                        .setNegativeButton(R.string.porta_cancel, null)
                        .setPositiveButton(R.string.porta_save, null)
                        .create();
        dialog.setOnShowListener(
                d ->
                        dialog.getButton(-1)
                                .setOnClickListener(
                                        v -> {
                                            String name = input.getText().toString().trim();
                                            if (name.isEmpty()) {
                                                field.setError(getString(R.string.porta_name));
                                                return;
                                            }
                                            getSharedPreferences("porta_home", MODE_PRIVATE)
                                                    .edit()
                                                    .putString("name_" + c.uuid, name)
                                                    .apply();
                                            refreshPresentation();
                                            androidx.fragment.app.Fragment sheet =
                                                    getSupportFragmentManager()
                                                            .findFragmentByTag("connect");
                                            if (sheet instanceof ConnectSheet)
                                                ((ConnectSheet) sheet).refreshName();
                                            dialog.dismiss();
                                        }));
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
    }

    private void removeComputer(ComputerDetails c) {
        if (managerBinder == null) return;
        managerBinder.removeComputer(c);
        new DiskAssetLoader(this).deleteAssetsForComputer(c.uuid);
        shortcutHelper.disableComputerShortcut(c, getString(R.string.scut_deleted_pc));
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(c.uuid)
                .apply();
        getSharedPreferences("porta_home", MODE_PRIVATE)
                .edit()
                .remove("name_" + c.uuid)
                .remove("mic_" + c.uuid)
                .apply();
        androidx.fragment.app.Fragment sheet =
                getSupportFragmentManager().findFragmentByTag("connect");
        if (sheet instanceof ConnectSheet) ((ConnectSheet) sheet).dismiss();
        computers.removeIf(host -> Objects.equals(c.uuid, host.uuid));
        refreshPresentation();
    }

    private final class HostsAdapter extends RecyclerView.Adapter<HostsAdapter.Holder> {
        class Holder extends RecyclerView.ViewHolder {
            Holder(View v) {
                super(v);
            }
        }

        @Override
        public int getItemCount() {
            return computers.size() + (firstRun ? 1 : 0);
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup p, int type) {
            MaterialCardView card = new MaterialCardView(PcView.this);
            card.setCardElevation(0);
            card.setStrokeWidth(0);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, -2);
            lp.bottomMargin = dp(PcView.this, 2);
            card.setLayoutParams(lp);
            return new Holder(card);
        }

        @Override
        public void onBindViewHolder(Holder holder, int pos) {
            MaterialCardView card = (MaterialCardView) holder.itemView;
            card.removeAllViews();
            card.setCardBackgroundColor(color(PcView.this, R.color.porta_surface_container));
            boolean addRow = pos >= computers.size();
            RecyclerView.LayoutParams rowParams =
                    (RecyclerView.LayoutParams) card.getLayoutParams();
            rowParams.topMargin = dp(PcView.this, addRow && !computers.isEmpty() ? 16 : 0);
            card.setLayoutParams(rowParams);
            float top = dp(PcView.this, pos == 0 || addRow ? 16 : 4),
                    bottom = dp(PcView.this, pos == computers.size() - 1 || addRow ? 16 : 4);
            card.setShapeAppearanceModel(
                    ShapeAppearanceModel.builder()
                            .setTopLeftCornerSize(top)
                            .setTopRightCornerSize(top)
                            .setBottomLeftCornerSize(bottom)
                            .setBottomRightCornerSize(bottom)
                            .build());
            LinearLayout row = row(PcView.this);
            row.setPadding(
                    dp(PcView.this, 16),
                    dp(PcView.this, 12),
                    dp(PcView.this, 12),
                    dp(PcView.this, 12));
            row.setMinimumHeight(dp(PcView.this, 72));
            card.addView(row);
            if (pos >= computers.size()) {
                ImageView addIcon = image(PcView.this, R.drawable.ic_porta_add, 44, 44);
                addIcon.setPadding(
                        dp(PcView.this, 10),
                        dp(PcView.this, 10),
                        dp(PcView.this, 10),
                        dp(PcView.this, 10));
                addIcon.setImageTintList(
                        android.content.res.ColorStateList.valueOf(
                                color(PcView.this, R.color.porta_on_surface_variant)));
                android.graphics.drawable.GradientDrawable iconBorder =
                        background(PcView.this, R.color.porta_surface_container, 12);
                iconBorder.setStroke(
                        dp(PcView.this, 1.5f), color(PcView.this, R.color.porta_outline_variant));
                addIcon.setBackground(iconBorder);
                row.addView(addIcon);
                TextView label = text(PcView.this, getString(R.string.porta_add_address), 16, true);
                row.setMinimumHeight(dp(PcView.this, 60));
                row.setPadding(
                        dp(PcView.this, 16),
                        dp(PcView.this, 8),
                        dp(PcView.this, 12),
                        dp(PcView.this, 8));
                LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(0, -2, 1);
                labelLp.leftMargin = dp(PcView.this, 16);
                row.addView(label, labelLp);
                card.setOnClickListener(v -> showAdd());
                card.setOnLongClickListener(null);
                return;
            }
            ComputerDetails c = computers.get(pos);
            boolean offline = ComputerPresentation.status(c) == ComputerPresentation.Status.OFFLINE;
            LinearLayout content = row(PcView.this);
            content.setAlpha(offline ? 0.38f : 1f);
            content.addView(computerIcon(PcView.this, 44));
            LinearLayout labels = column(PcView.this);
            LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(0, -2, 1);
            labelsLp.leftMargin = dp(PcView.this, 16);
            content.addView(labels, labelsLp);
            TextView name = text(PcView.this, displayName(c), 16, true);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(name);
            LinearLayout status = row(PcView.this);
            status.setGravity(Gravity.TOP);
            if (ComputerPresentation.status(c) == ComputerPresentation.Status.REFRESHING) {
                LoadingIndicator loading = loading(PcView.this, 12);
                status.addView(loading, size(PcView.this, 12, 12));
            } else {
                View dot = new View(PcView.this);
                android.graphics.drawable.GradientDrawable shape =
                        background(
                                PcView.this,
                                offline
                                        ? R.color.porta_surface_container
                                        : !firstRun && ComputerPresentation.status(c)
                                                        == ComputerPresentation.Status.UNPAIRED
                                                ? R.color.porta_warning
                                                : R.color.porta_online,
                                8);
                if (offline) {
                    shape.setColor(android.graphics.Color.TRANSPARENT);
                    shape.setStroke(
                            dp(PcView.this, 1.5f), color(PcView.this, R.color.porta_outline));
                }
                dot.setBackground(shape);
                LinearLayout.LayoutParams dotLp = size(PcView.this, 8, 8);
                dotLp.topMargin = dp(PcView.this, 6);
                status.addView(dot, dotLp);
            }
            TextView sub = text(PcView.this, subtitle(PcView.this, c, firstRun), 14, false);
            sub.setTextColor(color(PcView.this, R.color.porta_on_surface_variant));
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(0, -2, 1);
            subLp.leftMargin = dp(PcView.this, 6);
            status.addView(sub, subLp);
            labels.addView(status);
            boolean stackedPair =
                    firstRun && !offline && getResources().getConfiguration().fontScale > 1.3f;
            if (stackedPair) row.setOrientation(LinearLayout.VERTICAL);
            row.addView(
                    content,
                    new LinearLayout.LayoutParams(stackedPair ? -1 : 0, -2, stackedPair ? 0 : 1));
            if (firstRun && !offline) {
                MaterialButton pair = button(PcView.this, R.string.porta_pair_again, true);
                pair.setText(R.string.porta_pair);
                pair.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(
                                color(PcView.this, R.color.porta_secondary_container)));
                pair.setTextColor(color(PcView.this, R.color.porta_on_secondary_container));
                pair.setOnClickListener(v -> doPair(c));
                LinearLayout.LayoutParams pairLp = size(PcView.this, -2, -2);
                if (stackedPair) {
                    pairLp.gravity = Gravity.END;
                    pairLp.topMargin = dp(PcView.this, 8);
                }
                row.addView(pair, pairLp);
            } else if (offline && ComputerPresentation.hasMac(c)) {
                MaterialButton wake =
                        iconButton(PcView.this, R.drawable.ic_porta_power, R.string.porta_wake);
                wake.setContentDescription(getString(R.string.porta_wake_host, displayName(c)));
                wake.setOnClickListener(v -> doWakeOnLan(c));
                row.addView(wake, size(PcView.this, 48, 48));
            }
            card.setContentDescription(accessibilityDescription(PcView.this, c, displayName(c)));
            card.setOnClickListener(v -> openComputer(c));
            card.setOnLongClickListener(
                    v -> {
                        showComputerMenu(v, c);
                        return true;
                    });
        }
    }

    public void doPair(final ComputerDetails computer) {
        doPair(computer, false);
    }

    private void doPair(final ComputerDetails computer, boolean force) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(
                            PcView.this,
                            getResources().getString(R.string.pair_pc_offline),
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(
                            PcView.this,
                            getResources().getString(R.string.error_manager_not_running),
                            Toast.LENGTH_LONG)
                    .show();
            return;
        }

        final ComputerManagerService.ComputerManagerBinder binder = managerBinder;
        final int generation;
        synchronized (pairingLock) {
            if (pairing) return;
            generation = ++pairGeneration;
            pairing = true;
            pairingCancelled = false;
        }
        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                NvHTTP httpConn;
                                String message;
                                boolean success = false;
                                java.security.cert.X509Certificate pairedCert = null;
                                try {
                                    // Stop updates and wait while pairing
                                    stopComputerUpdates(true);

                                    httpConn =
                                            new NvHTTP(
                                                    ServerHelper.getCurrentAddressFromComputer(
                                                            computer),
                                                    computer.httpsPort,
                                                    binder.getUniqueId(),
                                                    computer.serverCert,
                                                    PlatformBinding.getCryptoProvider(PcView.this));
                                    if (!publishPairingHttp(generation, httpConn)) return;
                                    if (force) httpConn.unpair();
                                    if (httpConn.getPairState() == PairState.PAIRED) {
                                        // Already paired: return to the computer list.
                                        message = null;
                                        success = true;
                                    } else {
                                        final String pinStr = PairingManager.generatePinString();

                                        runOnUiThread(
                                                () -> {
                                                    if (generation == pairGeneration)
                                                        showPin(computer, pinStr);
                                                });

                                        PairingManager pm = httpConn.getPairingManager();

                                        PairState pairState =
                                                pm.pair(httpConn.getServerInfo(true), pinStr);
                                        if (pairState == PairState.PIN_WRONG) {
                                            message =
                                                    getResources()
                                                            .getString(R.string.pair_incorrect_pin);
                                        } else if (pairState == PairState.FAILED) {
                                            if (computer.runningGameId != 0) {
                                                message =
                                                        getResources()
                                                                .getString(R.string.pair_pc_ingame);
                                            } else {
                                                message =
                                                        getResources()
                                                                .getString(R.string.pair_fail);
                                            }
                                        } else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                                            message =
                                                    getResources()
                                                            .getString(
                                                                    R.string
                                                                            .pair_already_in_progress);
                                        } else if (pairState == PairState.PAIRED) {
                                            // Keep pairing success on the home flow.
                                            message = null;
                                            success = true;

                                            pairedCert = pm.getPairedCert();
                                        } else {
                                            // Should be no other values
                                            message = null;
                                        }
                                    }
                                } catch (UnknownHostException e) {
                                    message = getResources().getString(R.string.error_unknown_host);
                                } catch (FileNotFoundException e) {
                                    message = getResources().getString(R.string.error_404);
                                } catch (XmlPullParserException | IOException e) {
                                    e.printStackTrace();
                                    message = e.getMessage();
                                }

                                final java.security.cert.X509Certificate certificate = pairedCert;
                                final String toastMessage = message;
                                final boolean toastSuccess = success;
                                runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                synchronized (pairingLock) {
                                                    if (generation != pairGeneration) return;
                                                    pairingHttp = null;
                                                    pairing = false;
                                                }
                                                if (isFinishing() || isDestroyed()) return;
                                                if (toastSuccess) {
                                                    if (certificate != null) {
                                                        ComputerDetails pairedComputer = binder.getComputer(computer.uuid);
                                                        if (pairedComputer != null) pairedComputer.serverCert = certificate;
                                                        binder.invalidateStateForComputer(computer.uuid);
                                                    }
                                                    computer.pairState = PairState.PAIRED;
                                                    getSharedPreferences("porta_home", MODE_PRIVATE)
                                                            .edit()
                                                            .putBoolean("has_paired", true)
                                                            .apply();
                                                    showPinSuccess();
                                                    updateComputer(computer);
                                                } else if (!pairingCancelled
                                                        && pinDialog != null
                                                        && pinDialog.isShowing()) {
                                                    showPinFailure(computer);
                                                } else if (toastMessage != null
                                                        && !pairingCancelled) {
                                                    Toast.makeText(
                                                                    PcView.this,
                                                                    toastMessage,
                                                                    Toast.LENGTH_LONG)
                                                            .show();
                                                }
                                                startComputerUpdates();
                                            }
                                        });
                            }
                        })
                .start();
    }

    public void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(
                            PcView.this,
                            getResources().getString(R.string.wol_pc_online),
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(
                            PcView.this,
                            getResources().getString(R.string.wol_no_mac),
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }

        new Thread(
                        new Runnable() {
                            @Override
                            public void run() {
                                String message;
                                try {
                                    WakeOnLanSender.sendWolPacket(computer);
                                    message = getResources().getString(R.string.wol_waking_msg);
                                } catch (IOException e) {
                                    message = getResources().getString(R.string.wol_fail);
                                }

                                final String toastMessage = message;
                                runOnUiThread(
                                        new Runnable() {
                                            @Override
                                            public void run() {
                                                Toast.makeText(
                                                                PcView.this,
                                                                toastMessage,
                                                                Toast.LENGTH_LONG)
                                                        .show();
                                            }
                                        });
                            }
                        })
                .start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(
                            PcView.this,
                            getResources().getString(R.string.error_pc_offline),
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(
                            PcView.this,
                            getResources().getString(R.string.error_manager_not_running),
                            Toast.LENGTH_LONG)
                    .show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    private final Object pairingLock = new Object();
    private NvHTTP pairingHttp;
    private volatile int pairGeneration;

    boolean publishPairingHttp(int generation, NvHTTP http) {
        synchronized (pairingLock) {
            if (!pairingCancelled && generation == pairGeneration) {
                pairingHttp = http;
                return true;
            }
        }
        http.cancelPendingRequests();
        return false;
    }

    private void cancelPairing() {
        NvHTTP http;
        synchronized (pairingLock) {
            pairingCancelled = true;
            pairGeneration++;
            pairing = false;
            http = pairingHttp;
            pairingHttp = null;
        }
        if (http != null) http.cancelPendingRequests();
        if (pinDialog != null) pinDialog.dismiss();
        startComputerUpdates();
    }

    private void showPin(ComputerDetails c, String pin) {
        if (isFinishing() || isDestroyed() || pairingCancelled) return;
        pinDialog = new android.app.Dialog(this, R.style.Theme_Porta);
        LinearLayout page = column(this);
        page.setPadding(dp(this, 24), dp(this, 24), dp(this, 24), dp(this, 16));
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        ViewCompat.setOnApplyWindowInsetsListener(
                page,
                (view, insets) -> {
                    androidx.core.graphics.Insets bars =
                            insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    view.setPadding(
                            dp(this, 24) + bars.left,
                            dp(this, 24) + bars.top,
                            dp(this, 24) + bars.right,
                            dp(this, 16)
                                    + insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom);
                    return insets;
                });
        page.setBackgroundColor(color(this, R.color.porta_background));
        pinContent = column(this);
        pinContent.setClipChildren(false);
        pinContent.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout arch = brand(this, R.drawable.porta_arch, 64, 88, 192, 192);
        pinContent.addView(arch);
        space(pinContent, 0);
        TextView title = text(this, getString(R.string.porta_pin_title, displayName(c)), 24, true);
        title.setGravity(Gravity.CENTER);
        androidx.core.widget.TextViewCompat.setLineHeight(
                title, Math.round(32 * getResources().getDisplayMetrics().scaledDensity));
        pinContent.addView(title, size(this, -1, -2));
        space(pinContent, 32);
        LinearLayout digits = row(this);
        digits.setGravity(Gravity.CENTER);
        for (char ch : pin.toCharArray()) {
            TextView digit = text(this, String.valueOf(ch), 36, true);
            digit.setGravity(Gravity.CENTER);
            digit.setFontFeatureSettings("tnum");
            digit.setLetterSpacing(0.02f);
            digit.setBackground(background(this, R.color.porta_surface_container_high, 8));
            digit.setMinHeight(dp(this, 76));
            LinearLayout.LayoutParams lp = size(this, 60, -2);
            lp.leftMargin = digits.getChildCount() == 0 ? 0 : dp(this, 10);
            digits.addView(digit, lp);
        }
        pinContent.addView(digits, size(this, -1, -2));
        space(pinContent, 32);
        LinearLayout waiting = row(this);
        waiting.setGravity(Gravity.CENTER);
        LoadingIndicator spinner = loading(this, 20);
        waiting.addView(spinner, size(this, 20, 20));
        TextView label = text(this, getString(R.string.porta_waiting), 14, false);
        label.setTextColor(color(this, R.color.porta_on_surface_variant));
        LinearLayout.LayoutParams labelLp = size(this, -2, -2);
        labelLp.leftMargin = dp(this, 8);
        waiting.addView(label, labelLp);
        waiting.setMinimumHeight(dp(this, 32));
        pinContent.addView(waiting, size(this, -1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        LinearLayout stack = column(this);
        stack.setClipChildren(false);
        stack.setGravity(Gravity.CENTER_HORIZONTAL);
        stack.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 0.18f));
        stack.addView(pinContent, size(this, -1, -2));
        stack.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));
        scroll.addView(stack, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        MaterialButton cancel = button(this, R.string.porta_cancel, false);
        cancel.setOnClickListener(v -> cancelPairing());
        page.addView(cancel, size(this, -2, -2));
        pinDialog.setContentView(page);
        pinDialog.setOnCancelListener(d -> cancelPairing());
        pinDialog.show();
        WindowCompat.setDecorFitsSystemWindows(pinDialog.getWindow(), false);
        pinDialog.getWindow().setLayout(-1, -1);
        ViewCompat.requestApplyInsets(page);
    }

    private void showPinSuccess() {
        if (pinDialog == null || !pinDialog.isShowing() || pinContent == null) return;
        boolean reduced =
                android.provider.Settings.Global.getFloat(
                                getContentResolver(),
                                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                                1f)
                        == 0;
        android.app.Dialog completedDialog = pinDialog;
        FrameLayout arch = (FrameLayout) pinContent.getChildAt(0);
        View core = new View(this);
        android.graphics.drawable.GradientDrawable light =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                        new int[] {0xF2FFFBF2, 0x8CFFE7C2, 0x00FFD9A6});
        light.setGradientType(android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT);
        light.setGradientRadius(dp(this, 40));
        core.setBackground(light);
        FrameLayout.LayoutParams coreLp =
                new FrameLayout.LayoutParams(
                        dp(this, 80), dp(this, 40), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        coreLp.topMargin = dp(this, 120);
        arch.addView(core, coreLp);
        core.setAlpha(reduced ? 1f : 0.6f);
        if (!reduced) core.animate().alpha(1f).setDuration(250).start();
        LinearLayout digits = (LinearLayout) pinContent.getChildAt(4);
        for (int i = 0; i < digits.getChildCount(); i++) {
            View digit = digits.getChildAt(i);
            if (reduced) digit.setBackground(background(this, R.color.porta_primary_container, 8));
            else
                digit.postDelayed(
                        () -> {
                            if (completedDialog.isShowing())
                                digit.setBackground(
                                        background(this, R.color.porta_primary_container, 8));
                        },
                        i * 40L);
        }
        pinContent.postDelayed(
                () -> {
                    if (completedDialog.isShowing()) completedDialog.dismiss();
                },
                reduced ? 150 : 600);
    }

    private void showPinFailure(ComputerDetails c) {
        LinearLayout digits = (LinearLayout) pinContent.getChildAt(4);
        for (int i = 0; i < digits.getChildCount(); i++)
            ((TextView) digits.getChildAt(i)).setTextColor(color(this, R.color.porta_error));
        pinContent.removeViews(6, pinContent.getChildCount() - 6);
        TextView title = (TextView) pinContent.getChildAt(2);
        title.setText(R.string.porta_pair_failed);
        MaterialButton retry = button(this, R.string.porta_retry, true);
        retry.setOnClickListener(
                v -> {
                    pinDialog.dismiss();
                    doPair(c);
                });
        pinContent.addView(retry, size(this, -1, 56));
    }

    public boolean microphoneEnabled(String uuid) {
        boolean enabled = PreferenceConfiguration.microphoneEnabled(this, uuid);
        return enabled
                && (Build.VERSION.SDK_INT < 23
                        || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED);
    }

    public void toggleMicrophone(String uuid, MaterialButton tile) {
        if (!microphoneEnabled(uuid)
                && Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            pendingMic = tile;
            pendingMicUuid = uuid;
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 42);
            return;
        }
        setMicrophone(uuid, !microphoneEnabled(uuid));
        ConnectSheet.updateTile(tile, microphoneEnabled(uuid), false);
    }

    private void setMicrophone(String uuid, boolean enabled) {
        getSharedPreferences("porta_home", MODE_PRIVATE)
                .edit()
                .putBoolean("mic_" + uuid, enabled)
                .apply();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 42 && pendingMicUuid != null) {
            boolean allowed = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            setMicrophone(pendingMicUuid, allowed);
            if (pendingMic != null) ConnectSheet.updateTile(pendingMic, allowed, false);
            if (!allowed)
                Snackbar.make(
                                findViewById(android.R.id.content),
                                R.string.porta_mic_denied,
                                Snackbar.LENGTH_LONG)
                        .setAction(
                                R.string.porta_settings,
                                v ->
                                        startActivity(
                                                new Intent(
                                                        android.provider.Settings
                                                                .ACTION_APPLICATION_DETAILS_SETTINGS,
                                                        android.net.Uri.parse(
                                                                "package:" + getPackageName()))))
                        .show();
            pendingMic = null;
            pendingMicUuid = null;
        }
    }

    public void measureLatency(ComputerDetails c, java.util.function.Consumer<String> result) {
        ComputerDetails snapshot = new ComputerDetails(c);
        new Thread(
                        () -> {
                            String value = getString(R.string.porta_latency_unknown);
                            if (snapshot.activeAddress != null) {
                                long start = SystemClock.elapsedRealtime();
                                try (java.net.Socket socket = new java.net.Socket()) {
                                    socket.connect(
                                            new java.net.InetSocketAddress(
                                                    snapshot.activeAddress.address,
                                                    snapshot.activeAddress.port),
                                            3000);
                                    value =
                                            getString(
                                                    R.string.porta_latency,
                                                    SystemClock.elapsedRealtime() - start);
                                } catch (IOException ignored) {
                                }
                            }
                            String text = value;
                            runOnUiThread(
                                    () -> {
                                        if (!isDestroyed()) result.accept(text);
                                    });
                        },
                        "Porta latency")
                .start();
    }

    public void connectDesktop(
            ComputerDetails computer, BooleanSupplier camera, MaterialButton button, Runnable launched) {
        final ComputerManagerService.ComputerManagerBinder binder = managerBinder;
        if (binder == null) return;
        ComputerDetails live = binder.getComputer(computer.uuid);
        if (live == null) return;
        if (live.state == ComputerDetails.State.OFFLINE) {
            if (ComputerPresentation.hasMac(live)) doWakeOnLan(live);
            return;
        }
        if (live.state == ComputerDetails.State.UNKNOWN
                || live.pairState == null
                || live.activeAddress == null) {
            button.setEnabled(false);
            return;
        }
        if (live.pairState != PairState.PAIRED) {
            launched.run();
            doPair(live);
            return;
        }
        button.setEnabled(false);
        ComputerDetails snapshot = new ComputerDetails(live);
        new Thread(
                        () -> {
                            try {
                                NvHTTP http =
                                        new NvHTTP(
                                                ServerHelper.getCurrentAddressFromComputer(
                                                        snapshot),
                                                snapshot.httpsPort,
                                                binder.getUniqueId(),
                                                snapshot.serverCert,
                                                PlatformBinding.getCryptoProvider(this));
                                NvApp desktop = null;
                                for (NvApp app : http.getAppList())
                                    if ("Desktop".equalsIgnoreCase(app.getAppName())) {
                                        desktop = app;
                                        break;
                                    }
                                NvApp selected = desktop;
                                runOnUiThread(
                                        () -> {
                                            if (isDestroyed()
                                                    || !inForeground
                                                    || !button.isAttachedToWindow()
                                                    || managerBinder != binder) {
                                                button.setEnabled(true);
                                                return;
                                            }
                                            button.setEnabled(true);
                                            if (selected == null) {
                                                Snackbar.make(
                                                                findViewById(android.R.id.content),
                                                                R.string.porta_desktop_missing,
                                                                Snackbar.LENGTH_LONG)
                                                        .show();
                                                return;
                                            }
                                            Intent intent =
                                                    ServerHelper.createStartIntent(
                                                            this, selected, snapshot, binder);
                                            intent.putExtra(
                                                    "phoneCameraRequested", camera.getAsBoolean());
                                            launched.run();
                                            startActivity(intent);
                                        });
                            } catch (IOException | XmlPullParserException e) {
                                runOnUiThread(
                                        () -> {
                                            if (!isDestroyed() && button.isAttachedToWindow()) {
                                                button.setEnabled(true);
                                                Snackbar.make(
                                                                findViewById(android.R.id.content),
                                                                R.string.porta_connect_failed,
                                                                Snackbar.LENGTH_LONG)
                                                        .show();
                                            }
                                        });
                            }
                        },
                        "Porta Desktop")
                .start();
    }

    // Legacy grid adapter is retained for upstream compatibility, but is no longer used by home.
    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) throw new IllegalArgumentException("details");
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
    }
}
