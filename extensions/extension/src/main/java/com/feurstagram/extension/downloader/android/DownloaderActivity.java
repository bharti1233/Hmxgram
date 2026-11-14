package com.feurstagram.extension.downloader.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.feurstagram.extension.downloader.core.DownloadKind;
import com.feurstagram.extension.downloader.core.DownloaderRow;
import com.feurstagram.extension.ghost.GhostFeature;
import com.feurstagram.extension.ghost.android.GhostState;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HMX Insta downloader screen.
 *
 * <p>The screen is a view over the engine, never a second source of truth:
 * every row is built from {@link HmxDownloader#rows()}, which projects the
 * queue and the persisted history. It shows only downloads the user
 * explicitly requested — passive observations made while browsing are never
 * entries here.
 *
 * <p>A row shows the media type, the original username when the media exposed
 * one, the status, the progress, the download date, and the actions the
 * engine allows: retry, cancel and delete. Deleting removes the history entry;
 * the file already saved in the gallery stays.
 *
 * <p>It uses platform widgets only (no AndroidX, no third-party dependency)
 * and touches no Instagram class.
 */
public final class DownloaderActivity extends Activity {

    private static final long POLL_INTERVAL_MS = 500L;
    private static final int COLOR_OK = 0xFF1B5E20;
    private static final int COLOR_BAD = 0xFFB71C1C;
    private static final int COLOR_MUTED = 0xFF616161;
    private static final int THUMB_DP = 72;
    private static final int THUMB_CACHE = 24;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<DownloaderRow> rows = new ArrayList<DownloaderRow>();
    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> thumbnails =
            new LruCache<String, Bitmap>(THUMB_CACHE) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return Math.max(1, value.getByteCount() / 1024);
                }
            };
    private final DateFormat dateFormat =
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

    private HmxDownloader downloader;
    private ListView list;
    private TextView empty;
    private TextView banner;
    private Button reveal;
    private RowAdapter adapter;
    private boolean resumed;
    private boolean historyRevealed;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!resumed) {
                return;
            }
            refresh();
            handler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    private final HmxDownloader.Listener listener =
            new HmxDownloader.Listener() {
                @Override
                public void onDownloaderChanged() {
                    // The engine calls this from its worker threads.
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (resumed) {
                                refresh();
                            }
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        downloader = HmxDownloader.get(getApplicationContext());
        setContentView(buildContent());
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        // The first start reads the preferences and the pending ledger and
        // sweeps the cache: that is disk work, so it does not run on the UI
        // thread. The engine is idempotent, so a later call is a no-op.
        new Thread(new Runnable() {
            @Override
            public void run() {
                downloader.start();
            }
        }, "hmx-downloader-start").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        downloader.setListener(listener);
        refresh();
        handler.postDelayed(poll, POLL_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        resumed = false;
        handler.removeCallbacks(poll);
        downloader.setListener(null);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        thumbs.shutdownNow();
        super.onDestroy();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("HMX Insta Downloader");
        title.setTextSize(22f);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Only the downloads you requested. Tap HMX save on a photo,"
                + " video or Reel to start one.");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(COLOR_MUTED);
        root.addView(subtitle);

        banner = new TextView(this);
        banner.setText("Downloader is off. Turn it on in HMX Insta settings → Downloads."
                + " Your history and saved files are kept.");
        banner.setTextSize(13f);
        banner.setTextColor(COLOR_BAD);
        banner.setPadding(0, dp(8), 0, dp(8));
        banner.setVisibility(View.GONE);
        root.addView(banner);

        list = new ListView(this);
        list.setDividerHeight(dp(1));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        empty = new TextView(this);
        empty.setText("No downloads yet. Tap the HMX save chip on a photo, video or"
                + " Reel and confirm; it will appear here.");
        empty.setTextSize(14f);
        empty.setTextColor(COLOR_MUTED);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty);

        reveal = new Button(this);
        reveal.setText("Show the download history");
        reveal.setVisibility(View.GONE);
        reveal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                historyRevealed = true;
                refresh();
            }
        });
        root.addView(reveal);

        Button clear = new Button(this);
        clear.setText("Clear finished entries");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                confirmClear();
            }
        });
        root.addView(clear);
        return root;
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear finished entries")
                .setMessage("Remove every finished entry from the list? "
                        + "The files already saved in the gallery are not deleted.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        downloader.clearFinished();
                        refresh();
                    }
                })
                .show();
    }

    private void refresh() {
        if (banner != null) {
            banner.setVisibility(DownloaderSwitch.isEnabled(getApplicationContext())
                    ? View.GONE : View.VISIBLE);
        }
        List<DownloaderRow> current = downloader.rows();
        boolean hidden = historyHidden() && !historyRevealed;
        if (hidden) {
            // The Ghost Mode control keeps the list hidden until the user
            // asks for it.
            current = new ArrayList<DownloaderRow>();
        }
        if (sameRows(current)) {
            return;
        }
        rows.clear();
        rows.addAll(current);
        adapter.notifyDataSetChanged();
        reveal.setVisibility(hidden ? View.VISIBLE : View.GONE);
        empty.setVisibility(!hidden && rows.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(!hidden && rows.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Whether the Ghost Mode control keeps the history hidden. */
    private boolean historyHidden() {
        return GhostState.isActiveSafely(getApplicationContext(),
                GhostFeature.HIDE_DOWNLOAD_HISTORY);
    }

    /**
     * Whether the engine's rows are the ones already shown. The poll runs
     * twice a second, so an unchanged list must not redraw the list.
     */
    private boolean sameRows(List<DownloaderRow> current) {
        if (current.size() != rows.size()) {
            return false;
        }
        for (int index = 0; index < current.size(); index++) {
            if (!current.get(index).equals(rows.get(index))) {
                return false;
            }
        }
        return true;
    }

    private void act(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    /** Renders exactly the downloads the engine holds. */
    private final class RowAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout holder = (LinearLayout) convertView;
            RowViews views;
            if (holder == null) {
                holder = buildRow();
                views = (RowViews) holder.getTag();
            } else {
                views = (RowViews) holder.getTag();
            }
            bindRow(views, rows.get(position));
            return holder;
        }
    }

    /** The views of one row, so binding never depends on child indexes. */
    private static final class RowViews {
        ImageView thumbnail;
        TextView placeholder;
        TextView title;
        TextView meta;
        TextView status;
        ProgressBar progress;
        Button retry;
        Button cancel;
        Button delete;
    }

    private LinearLayout buildRow() {
        LinearLayout holder = new LinearLayout(DownloaderActivity.this);
        holder.setOrientation(LinearLayout.HORIZONTAL);
        holder.setPadding(dp(8), dp(8), dp(8), dp(8));

        RowViews views = new RowViews();

        FrameLayout thumbBox = new FrameLayout(DownloaderActivity.this);
        int thumbSize = dp(THUMB_DP);
        LinearLayout.LayoutParams thumbParams =
                new LinearLayout.LayoutParams(thumbSize, thumbSize);
        thumbParams.setMargins(0, 0, dp(12), 0);
        holder.addView(thumbBox, thumbParams);

        views.placeholder = new TextView(DownloaderActivity.this);
        views.placeholder.setTextSize(11f);
        views.placeholder.setGravity(Gravity.CENTER);
        views.placeholder.setTextColor(0xFFFFFFFF);
        views.placeholder.setBackgroundColor(COLOR_MUTED);
        thumbBox.addView(views.placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        views.thumbnail = new ImageView(DownloaderActivity.this);
        views.thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
        views.thumbnail.setVisibility(View.GONE);
        thumbBox.addView(views.thumbnail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout details = new LinearLayout(DownloaderActivity.this);
        details.setOrientation(LinearLayout.VERTICAL);
        holder.addView(details, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        views.title = new TextView(DownloaderActivity.this);
        views.title.setTextSize(15f);
        details.addView(views.title);

        views.meta = new TextView(DownloaderActivity.this);
        views.meta.setTextSize(12f);
        views.meta.setTextColor(COLOR_MUTED);
        details.addView(views.meta);

        views.status = new TextView(DownloaderActivity.this);
        views.status.setTextSize(13f);
        details.addView(views.status);

        views.progress = new ProgressBar(DownloaderActivity.this, null,
                android.R.attr.progressBarStyleHorizontal);
        views.progress.setMax(100);
        views.progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        progressParams.setMargins(0, dp(4), 0, 0);
        details.addView(views.progress, progressParams);

        LinearLayout actions = new LinearLayout(DownloaderActivity.this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.setMargins(0, dp(6), 0, 0);
        details.addView(actions, actionsParams);

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        views.retry = actionButton("Retry");
        views.cancel = actionButton("Cancel");
        views.delete = actionButton("Delete");
        actions.addView(views.retry, buttonParams);
        actions.addView(views.cancel, buttonParams);
        actions.addView(views.delete, buttonParams);

        holder.setTag(views);
        return holder;
    }

    private Button actionButton(String text) {
        Button button = new Button(DownloaderActivity.this);
        button.setText(text);
        button.setTextSize(12f);
        return button;
    }

    private void bindRow(RowViews views, DownloaderRow row) {
        views.title.setText(row.kindLabel());
        views.meta.setText(metaLabel(row));
        views.status.setText(row.statusLabel());
        if (row.isVerified()) {
            views.status.setTextColor(COLOR_OK);
        } else if (row.state() == com.feurstagram.extension.downloader.core.DownloadState.FAILED) {
            views.status.setTextColor(COLOR_BAD);
        } else {
            views.status.setTextColor(COLOR_MUTED);
        }

        int percent = row.progressPercent();
        if (row.isActive()) {
            views.progress.setVisibility(View.VISIBLE);
            views.progress.setIndeterminate(percent < 0);
            if (percent >= 0) {
                views.progress.setProgress(percent);
            }
        } else {
            views.progress.setVisibility(View.GONE);
        }

        bindThumbnail(views, row);

        views.retry.setVisibility(row.canRetry() ? View.VISIBLE : View.GONE);
        views.cancel.setVisibility(row.canCancel() ? View.VISIBLE : View.GONE);
        views.delete.setVisibility(row.canDelete() ? View.VISIBLE : View.GONE);

        // The listeners are bound per row: the view is recycled, so a listener
        // captured when the view was created would act on a different item.
        views.retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onRetry(row);
            }
        });
        views.cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onCancel(row);
            }
        });
        views.delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onDelete(row);
            }
        });
    }

    private String metaLabel(DownloaderRow row) {
        StringBuilder text = new StringBuilder(row.kindLabel());
        String owner = row.owner() == null ? "" : row.owner().trim();
        if (!owner.isEmpty() && !"instagram".equalsIgnoreCase(owner)) {
            text.append(" · @").append(owner);
        }
        if (row.createdAtMs() > 0L) {
            text.append(" · ").append(dateFormat.format(new Date(row.createdAtMs())));
        }
        return text.toString();
    }

    // --- Thumbnails ---------------------------------------------------------

    private void bindThumbnail(RowViews views, DownloaderRow row) {
        String path = row.savedPath();
        views.placeholder.setText(row.kindLabel().toUpperCase(java.util.Locale.ROOT));
        views.placeholder.setBackgroundColor(colorFor(row.kind()));
        views.thumbnail.setVisibility(View.GONE);
        views.thumbnail.setImageDrawable(null);
        views.placeholder.setVisibility(View.VISIBLE);
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        Bitmap cached = thumbnails.get(path);
        if (cached != null) {
            showThumbnail(views, cached);
            return;
        }
        final String key = path;
        final DownloadKind kind = row.kind();
        views.thumbnail.setTag(key);
        thumbs.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = readThumbnail(key, kind);
                if (bitmap == null) {
                    return;
                }
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        thumbnails.put(key, bitmap);
                        // The list refreshed while the thumbnail was loading:
                        // only paint it if this row still shows that file.
                        if (key.equals(views.thumbnail.getTag())) {
                            showThumbnail(views, bitmap);
                        }
                    }
                });
            }
        });
    }

    private void showThumbnail(RowViews views, Bitmap bitmap) {
        views.thumbnail.setImageBitmap(bitmap);
        views.thumbnail.setVisibility(View.VISIBLE);
        views.placeholder.setVisibility(View.GONE);
    }

    private int colorFor(DownloadKind kind) {
        if (kind == DownloadKind.VIDEO) {
            return 0xFF4527A0;
        }
        if (kind == DownloadKind.AUDIO) {
            return 0xFF00695C;
        }
        if (kind == DownloadKind.COVER) {
            return 0xFF37474F;
        }
        return 0xFF1565C0;
    }

    /** Reads a small bitmap for a published file, or {@code null}. */
    private static Bitmap readThumbnail(String savedPath, DownloadKind kind) {
        try {
            String path = savedPath.startsWith("/")
                    ? savedPath
                    : android.os.Environment.getExternalStorageDirectory()
                            + "/" + savedPath;
            File file = new File(path);
            if (!file.isFile()) {
                return null;
            }
            int size = 96;
            if (kind == DownloadKind.VIDEO) {
                return videoThumbnail(file, size);
            }
            Bitmap source = BitmapFactory.decodeFile(path);
            if (source == null) {
                return null;
            }
            Bitmap scaled = ThumbnailUtils.extractThumbnail(source, size, size);
            if (scaled != source) {
                source.recycle();
            }
            return scaled;
        } catch (Throwable failure) {
            // A thumbnail is cosmetic: a file that cannot be read simply keeps
            // the type placeholder.
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static Bitmap videoThumbnail(File file, int size) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                return ThumbnailUtils.createVideoThumbnail(file, new Size(size, size), null);
            }
            return ThumbnailUtils.createVideoThumbnail(file.getAbsolutePath(),
                    android.provider.MediaStore.Video.Thumbnails.MINI_KIND);
        } catch (java.io.IOException e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // --- Actions ------------------------------------------------------------

    private void onRetry(DownloaderRow row) {
        if (!DownloaderSwitch.isEnabled(getApplicationContext())) {
            act("Downloader is off. Turn it on in HMX Insta settings → Downloads.");
            return;
        }
        if (downloader.retry(row.id())) {
            act("Retrying");
        } else {
            act("This download cannot be retried");
        }
        refresh();
    }

    private void onCancel(DownloaderRow row) {
        if (downloader.cancel(row.id())) {
            act("Cancelled");
        } else {
            act("This download is no longer running");
        }
        refresh();
    }

    private void onDelete(DownloaderRow row) {
        if (downloader.delete(row.id())) {
            act("Entry removed");
        } else {
            act("The entry could not be removed");
        }
        refresh();
    }
}
