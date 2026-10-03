package com.dsh.zipsplit;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「文件分割合并」——"文件分割 - zip2.0" 的 Android 版本。
 * 保留全部功能：随机分片大小 80~90MB、分片名 "原名_N.zip"、合并自动探测片数。
 */
public class MainActivity extends Activity {

    private static final int C_BG       = 0xFFEFF1F5;
    private static final int C_CARD     = 0xFFFFFFFF;
    private static final int C_PRIMARY  = 0xFF2F6FED;
    private static final int C_TEXT     = 0xFF17181C;
    private static final int C_SUB      = 0xFF7A7C86;
    private static final int C_LINE     = 0xFFE1E4EA;
    private static final int C_OK       = 0xFF16A34A;
    private static final int C_ERR      = 0xFFDC2626;

    private static final int MODE_SPLIT = 1;
    private static final int MODE_MERGE = 2;
    private int mode = MODE_SPLIT;

    private EditText pathInput;
    private EditText minInput;
    private EditText maxInput;
    private LinearLayout sizeRow;
    private LinearLayout mergeOpts;
    private CheckBox delPartsBox;
    private TextView infoText;
    private TextView statusText;
    private ProgressBar bar;
    private Button startBtn;
    private Button tabSplit;
    private Button tabMerge;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean cancelFlag = false;
    private volatile boolean running = false;
    private AlertDialog dirDialog;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            getWindow().setStatusBarColor(0xFF1B4FD8);
        } catch (Throwable ignored) {
        }
        setContentView(buildUi());
        switchMode(MODE_SPLIT);
        checkStorage(false);
    }

    // ------------------------------- UI 构建 -------------------------------

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private GradientDrawable bg(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12.5f);
        t.setTextColor(C_SUB);
        t.setPadding(dp(2), dp(14), 0, dp(6));
        return t;
    }

    private View card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(bg(C_CARD, 14));
        l.setPadding(dp(16), dp(4), dp(16), dp(16));
        return l;
    }

    private Button flatButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(15f);
        b.setTextColor(color);
        b.setAllCaps(false);
        b.setBackground(bg(0xFFF3F5F9, 10));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("文件分割合并");
        title.setTextSize(23f);
        title.setTextColor(C_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("大文件分片打包 · 便于网盘上传 / 下载后还原   v1.0");
        sub.setTextSize(12f);
        sub.setTextColor(C_SUB);
        sub.setPadding(0, dp(4), 0, dp(16));
        root.addView(sub);

        // 模式切换
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackground(bg(0xFFE4E7EE, 12));
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabSplit = tabButton("分割文件");
        tabMerge = tabButton("合并文件");
        LinearLayout.LayoutParams tw = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabs.addView(tabSplit, tw);
        LinearLayout.LayoutParams tw2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabs.addView(tabMerge, tw2);
        root.addView(tabs);

        // 目标文件
        root.addView(label("目标文件"));
        LinearLayout c1 = (LinearLayout) card();
        pathInput = new EditText(this);
        pathInput.setHint("/storage/emulated/0/Download/movie.mkv");
        pathInput.setHintTextColor(0xFFB4B8C2);
        pathInput.setTextSize(13f);
        pathInput.setTextColor(C_TEXT);
        pathInput.setSingleLine(true);
        pathInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        pathInput.setBackground(bg(0xFFF4F6FA, 9));
        pathInput.setPadding(dp(12), dp(11), dp(12), dp(11));
        c1.addView(pathInput);

        LinearLayout browseRow = new LinearLayout(this);
        browseRow.setOrientation(LinearLayout.HORIZONTAL);
        browseRow.setPadding(0, dp(10), 0, 0);
        Button browse = flatButton("浏览文件…", C_PRIMARY);
        browse.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickFile();
            }
        });
        browseRow.addView(browse);
        c1.addView(browseRow);
        root.addView(c1);

        // 分片大小（仅分割模式）
        sizeRow = new LinearLayout(this);
        sizeRow.setOrientation(LinearLayout.VERTICAL);
        sizeRow.addView(label("分片大小范围（MB）— 默认 80 ~ 90，随机取值"));
        LinearLayout c2 = (LinearLayout) card();
        LinearLayout sr = new LinearLayout(this);
        sr.setOrientation(LinearLayout.HORIZONTAL);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        minInput = numBox("80");
        TextView tilde = new TextView(this);
        tilde.setText("  ~  ");
        tilde.setTextColor(C_SUB);
        tilde.setTextSize(15f);
        maxInput = numBox("90");
        sr.addView(minInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        sr.addView(tilde);
        sr.addView(maxInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c2.addView(sr);
        sizeRow.addView(c2);
        root.addView(sizeRow);

        // 合并选项（仅合并模式可见）
        mergeOpts = new LinearLayout(this);
        mergeOpts.setOrientation(LinearLayout.VERTICAL);
        mergeOpts.addView(label("合并选项"));
        LinearLayout c2b = (LinearLayout) card();
        delPartsBox = new CheckBox(this);
        delPartsBox.setText("合并成功后删除切片文件");
        delPartsBox.setTextSize(14f);
        delPartsBox.setTextColor(C_TEXT);
        delPartsBox.setChecked(false);
        c2b.addView(delPartsBox);
        TextView delTip = new TextView(this);
        delTip.setText("默认不勾选。勾选后只有在还原成功时才删除该组切片；"
                + "若切片位于 .parts 文件夹内且被删空，空文件夹也会一并删掉。"
                + "只会删 xxx_N.zip，不会碰还原出来的文件。");
        delTip.setTextSize(11.5f);
        delTip.setTextColor(0xFF9A9DA8);
        delTip.setPadding(0, dp(6), 0, 0);
        delTip.setLineSpacing(dp(3), 1f);
        c2b.addView(delTip);
        mergeOpts.addView(c2b);
        root.addView(mergeOpts);

        // 信息 / 操作
        infoText = new TextView(this);
        infoText.setTextSize(12.5f);
        infoText.setTextColor(C_SUB);
        infoText.setPadding(dp(2), dp(16), 0, dp(6));
        root.addView(infoText);

        LinearLayout c3 = (LinearLayout) card();
        startBtn = new Button(this);
        startBtn.setText("开始分割");
        startBtn.setTextSize(16f);
        startBtn.setTextColor(0xFFFFFFFF);
        startBtn.setAllCaps(false);
        startBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        startBtn.setBackground(bg(C_PRIMARY, 12));
        startBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        startBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onStartClick();
            }
        });
        c3.addView(startBtn);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(10));
        bp.topMargin = dp(14);
        c3.addView(bar, bp);

        statusText = new TextView(this);
        statusText.setText("就绪");
        statusText.setTextSize(12.5f);
        statusText.setTextColor(C_SUB);
        statusText.setPadding(dp(2), dp(10), 0, 0);
        c3.addView(statusText);
        root.addView(c3);

        TextView tip = new TextView(this);
        tip.setText("说明\n"
                + "· 分割：在同目录建立「源文件名.parts」文件夹，切片以 源文件名_1.zip、_2.zip… 放进去；"
                + "每片大小在设定区间内随机。.zip 与压缩无关，仅用于通过网盘的后缀校验。\n"
                + "· 合并：选任意一个切片文件即可，程序会自动检测【同目录】的全部切片，"
                + "按序还原成外层同名文件（若切片在 xxx.parts/ 里，选里面任一片或外层任一文件都能找到）。\n"
                + "· 全程流式读写，不占用大内存；合并先写临时文件再改名，不会留下半成品。");
        tip.setTextSize(11.5f);
        tip.setTextColor(0xFF9A9DA8);
        tip.setPadding(dp(4), dp(20), dp(4), 0);
        tip.setLineSpacing(dp(3), 1f);
        root.addView(tip);

        TextWatcher refresh = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                refreshInfo();
            }
        };
        pathInput.addTextChangedListener(refresh);
        minInput.addTextChangedListener(refresh);
        maxInput.addTextChangedListener(refresh);
        return scroll;
    }

    private Button tabButton(final String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14.5f);
        b.setAllCaps(false);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switchMode(text.startsWith("分割") ? MODE_SPLIT : MODE_MERGE);
            }
        });
        return b;
    }

    private EditText numBox(String def) {
        EditText e = new EditText(this);
        e.setText(def);
        e.setTextSize(15f);
        e.setTextColor(C_TEXT);
        e.setGravity(Gravity.CENTER);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setBackground(bg(0xFFF4F6FA, 9));
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        return e;
    }

    // ------------------------------- 模式切换 -------------------------------

    private void switchMode(int m) {
        mode = m;
        boolean split = m == MODE_SPLIT;
        tabSplit.setTextColor(split ? 0xFFFFFFFF : C_SUB);
        tabSplit.setBackground(bg(split ? C_PRIMARY : 0x00000000, 9));
        tabMerge.setTextColor(!split ? 0xFFFFFFFF : C_SUB);
        tabMerge.setBackground(bg(!split ? C_PRIMARY : 0x00000000, 9));
        sizeRow.setVisibility(split ? View.VISIBLE : View.GONE);
        mergeOpts.setVisibility(split ? View.GONE : View.VISIBLE);
        startBtn.setText(split ? "开始分割" : "开始合并");
        pathInput.setHint(split
                ? "/storage/emulated/0/Download/movie.mkv"
                : "/storage/emulated/0/Download/movie.mkv.parts/movie.mkv_1.zip  （选任一切片即可）");
        if (bar != null) bar.setProgress(0);
        if (statusText != null) {
            statusText.setTextColor(C_SUB);
            statusText.setText("就绪");
        }
        refreshInfo();
    }

    private void refreshInfo() {
        String p = pathInput.getText().toString().trim();
        if (p.isEmpty()) {
            infoText.setText(mode == MODE_SPLIT ? "请选择或填写要分割的文件"
                    : "请选择任意一个切片文件，自动检测同目录的所有切片");
            return;
        }
        File f = new File(p);
        if (mode == MODE_SPLIT) {
            if (!f.exists()) {
                infoText.setText("路径不存在");
            } else if (f.isDirectory()) {
                infoText.setText("这是文件夹。分割请短按「浏览文件…」选择具体文件");
            } else {
                long n = f.length();
                long minMB = parseMB(minInput, 80);
                long maxMB = parseMB(maxInput, 90);
                long parts = (maxMB > 0) ? (long) Math.ceil(n / (double) (maxMB * Engine.MB)) : 0;
                File dir = Engine.partsDir(f);
                int ex = Engine.countPartsInDir(dir, f.getName());
                String note = ex > 0 ? "（已有 " + ex + " 个同名分片，将被覆盖）" : "";
                infoText.setText("大小 " + Engine.human(n) + "  ≈ 生成 " + parts
                        + " 个分片（每片 " + minMB + "~" + maxMB + " MB 随机）\n"
                        + "分片文件夹：" + dir.getName() + "/ " + note);
            }
        } else {
            Engine.PartSet ps = Engine.resolve(f);
            if (ps != null) {
                infoText.setText(ps.summary());
            } else if (f.isDirectory()) {
                infoText.setText("这个文件夹里没有找到成组的分片\n（需要有 name_1.zip、name_2.zip 这样的连续分片）");
            } else {
                infoText.setText("同目录没检测到切片\n（需要有 xxx_1.zip、xxx_2.zip 这样的连续切片）");
            }
        }
    }

    private long parseMB(EditText e, long def) {
        try {
            String s = e.getText().toString().trim();
            if (s.isEmpty()) return def;
            long v = Long.parseLong(s);
            if (v <= 0) return def;
            return v;
        } catch (Exception ex) {
            return def;
        }
    }

    // ------------------------------- 权限 -------------------------------

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkStorage(boolean nag) {
        if (hasStorage()) return;
        new AlertDialog.Builder(this)
                .setTitle("需要文件访问权限")
                .setMessage("分割 / 合并需要读写存储中的任意文件。\n"
                        + "请在下一屏把「允许管理所有文件」打开，然后回到本应用。")
                .setPositiveButton("去授权", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        requestStorage();
                    }
                })
                .setNegativeButton(nag ? "取消" : "稍后", null)
                .show();
    }

    private void requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception e2) {
                    Toast.makeText(this, "请到 系统设置 → 应用 → 本应用 中开启文件权限",
                            Toast.LENGTH_LONG).show();
                }
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        super.onRequestPermissionsResult(rc, p, g);
        if (rc == 1 && g.length > 0 && g[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "未授权将无法读写文件", Toast.LENGTH_SHORT).show();
        }
        refreshInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pathInput != null) refreshInfo();
    }

    // ------------------------------- 文件浏览 -------------------------------

    private void pickFile() {
        File root = Environment.getExternalStorageDirectory();
        File start = new File(root, "Download");
        if (!start.isDirectory()) start = root;
        String cur = pathInput.getText().toString().trim();
        if (!cur.isEmpty()) {
            File cf = new File(cur);
            File d = cf.isDirectory() ? cf : cf.getParentFile();
            if (d != null && d.isDirectory()) start = d;
        }
        showDir(start);
    }

    private void showDir(final File dir) {
        if (dirDialog != null && dirDialog.isShowing()) dirDialog.dismiss();
        File[] list = dir.listFiles();
        final List<File> dirs = new ArrayList<File>();
        final List<File> files = new ArrayList<File>();
        if (list != null) {
            for (File f : list) {
                if (f.isDirectory()) dirs.add(f);
                else files.add(f);
            }
        }
        Comparator<File> byName = new Comparator<File>() {
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        };
        Collections.sort(dirs, byName);
        Collections.sort(files, byName);

        final List<File> all = new ArrayList<File>();
        final List<String> names = new ArrayList<String>();
        File parent = dir.getParentFile();
        if (parent != null) {
            all.add(parent);
            names.add("⬆  上一级");
        }
        for (File d : dirs) {
            String mark = d.getName().endsWith(Engine.PARTS_SUFFIX) ? "📦  " : "📁  ";
            all.add(d);
            names.add(mark + d.getName());
        }
        for (File f : files) {
            all.add(f);
            names.add("📄  " + f.getName() + "   (" + Engine.human(f.length()) + ")");
        }
        if (names.isEmpty()) {
            all.add(dir);
            names.add("（空文件夹）");
        }

        dirDialog = new AlertDialog.Builder(this)
                .setTitle(dir.getAbsolutePath())
                .setItems(names.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        File f = all.get(which);
                        if (f.isDirectory()) {
                            if (f.equals(dir) && names.get(which).startsWith("（空文件夹）")) return;
                            showDir(f);
                        } else {
                            pathInput.setText(f.getAbsolutePath());
                            refreshInfo();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        dirDialog.show();
    }

    // ------------------------------- 执行 -------------------------------

    private void onStartClick() {
        if (running) {
            cancelFlag = true;
            startBtn.setText("正在取消…");
            return;
        }
        if (!hasStorage()) {
            checkStorage(true);
            return;
        }
        final String p = pathInput.getText().toString().trim();
        if (p.isEmpty()) {
            Toast.makeText(this, "请先选择或填写目标文件", Toast.LENGTH_SHORT).show();
            return;
        }
        final File f = new File(p);
        if (mode == MODE_SPLIT) doSplit(f);
        else doMerge(f);
    }

    private void doSplit(final File src) {
        if (!src.exists()) {
            toast("文件不存在：" + src.getAbsolutePath());
            return;
        }
        if (src.isDirectory()) {
            toast("这是文件夹，不能分割");
            return;
        }
        if (src.getName().toLowerCase(Locale.US).endsWith(".zip")
                && src.getName().matches(".*_\\d+\\.zip")) {
            new AlertDialog.Builder(this)
                    .setTitle("这看起来是一个分片")
                    .setMessage("它本身可能是别人分割出来的分片，继续分割会引起混乱。仍要继续？")
                    .setPositiveButton("继续", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            startSplitTask(src);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        startSplitTask(src);
    }

    private void startSplitTask(final File src) {
        final long minB = parseMB(minInput, 80) * Engine.MB;
        final long maxB = parseMB(maxInput, 90) * Engine.MB;
        if (maxB < minB) {
            toast("最大分片不能小于最小分片");
            return;
        }
        runTask("分割", new TaskBody() {
            public String run() throws Exception {
                int n = Engine.split(src, minB, maxB, cb);
                return "已生成 " + n + " 个分片 → " + Engine.partsDir(src).getName() + "/";
            }
        });
    }

    private void doMerge(final File picked) {
        final Engine.PartSet ps = Engine.resolve(picked);
        if (ps == null) {
            toast("未找到可分片：请选择分片文件夹，或 xxx_1.zip 这类连续分片");
            return;
        }
        final boolean delAfter = delPartsBox != null && delPartsBox.isChecked();
        String msg = "探测到 " + ps.count + " 个分片，合计 " + Engine.human(ps.total)
                + "\n\n分片文件夹：\n" + ps.dir.getAbsolutePath()
                + "\n\n将还原为：\n" + ps.out.getAbsolutePath();
        if (ps.out.exists()) msg += "\n\n⚠ 目标文件已存在，将被覆盖";
        if (delAfter) msg += "\n\n合并成功后将删除这 " + ps.count + " 个切片文件";
        new AlertDialog.Builder(this)
                .setTitle("确认合并")
                .setMessage(msg)
                .setPositiveButton("开始合并", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        runTask("合并", new TaskBody() {
                            public String run() throws Exception {
                                long got = Engine.merge(ps, cb);
                                String extra = "";
                                if (delAfter) {
                                    int d = Engine.deleteParts(ps);
                                    if (d > 0) extra = "，已删除 " + d + " 个切片";
                                }
                                return "已还原 " + Engine.human(got) + " → " + ps.out.getName() + extra;
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String safeDir(File f) {
        File d = f.getAbsoluteFile().getParentFile();
        return d == null ? f.getAbsolutePath() : d.getAbsolutePath();
    }

    interface TaskBody {
        String run() throws Exception;
    }

    private final Engine.Cb cb = new Engine.Cb() {
        public void progress(final int pct, final String msg) {
            ui.post(new Runnable() {
                public void run() {
                    bar.setProgress(pct);
                    statusText.setTextColor(C_SUB);
                    statusText.setText(pct + "%  " + msg);
                }
            });
        }

        public boolean cancelled() {
            return cancelFlag;
        }
    };

    private void runTask(final String what, final TaskBody body) {
        cancelFlag = false;
        running = true;
        bar.setProgress(0);
        statusText.setTextColor(C_SUB);
        statusText.setText("正在" + what + "…");
        startBtn.setText("取消");
        startBtn.setBackground(bg(0xFFE4E7EE, 12));
        startBtn.setTextColor(C_TEXT);

        new Thread(new Runnable() {
            public void run() {
                String result = null;
                String err = null;
                final long t0 = System.currentTimeMillis();
                try {
                    result = body.run();
                } catch (Engine.Cancelled c) {
                    err = "已取消";
                } catch (Throwable t) {
                    err = String.valueOf(t.getMessage() == null ? t : t.getMessage());
                }
                final String fRes = result, fErr = err;
                final long cost = System.currentTimeMillis() - t0;
                ui.post(new Runnable() {
                    public void run() {
                        running = false;
                        startBtn.setText(mode == MODE_SPLIT ? "开始分割" : "开始合并");
                        startBtn.setBackground(bg(C_PRIMARY, 12));
                        startBtn.setTextColor(0xFFFFFFFF);
                        if (fErr != null) {
                            statusText.setTextColor(C_ERR);
                            statusText.setText("✗ " + fErr);
                            bar.setProgress(0);
                        } else {
                            statusText.setTextColor(C_OK);
                            long sec = cost / 1000;
                            statusText.setText("✓ " + fRes + "   （耗时 "
                                    + String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
                                    + "）");
                            bar.setProgress(100);
                            refreshInfo();
                        }
                    }
                });
            }
        }, "zip2-android-task").start();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
