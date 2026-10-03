package com.dsh.zipsplit;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 核心算法层 —— 与原 "文件分割 - zip2.0" 行为一致：
 *  - 分割：每次随机取 [minBytes, maxBytes] 与剩余大小取小值切一片
 *  - 分片：写入「源文件全路径 + .parts」的同名文件夹，命名 "<源文件名>_N.zip"
 *          （.zip 只是伪装后缀，用于规避网盘的后缀校验）
 *  - 合并：把文件夹里连续的 "<原名>_1.zip ... <原名>_N.zip" 顺序拼回原文件
 * 实现上全部流式复制，不把文件读进内存。
 */
public final class Engine {

    public interface Cb {
        void progress(int pct, String msg);
        boolean cancelled();
    }

    public static class Cancelled extends IOException {
        Cancelled() { super("已取消"); }
    }

    public static final long MB = 1024L * 1024L;
    /** 原程序 split_file 的默认参数 */
    public static final long DEF_MIN = 83886080L;   // 80 MB
    public static final long DEF_MAX = 94371840L;   // 90 MB
    /** 分割文件夹后缀：movie.mkv -> movie.mkv.parts/ */
    public static final String PARTS_SUFFIX = ".parts";

    private static final int BUF = 1 << 20;         // 1 MB 缓冲
    private static final Pattern PART_RE =
            Pattern.compile("^(.*)_(\\d+)\\.zip$", Pattern.CASE_INSENSITIVE);

    private Engine() {}

    // ----------------------------- 路径推导 -----------------------------

    /** 源文件对应的分片文件夹：/a/movie.mkv -> /a/movie.mkv.parts */
    public static File partsDir(File src) {
        return new File(src.getAbsolutePath() + PARTS_SUFFIX);
    }

    /** 分片文件：<dir>/<base>_<index>.zip */
    public static File partIn(File dir, String base, int index) {
        return new File(dir, base + "_" + index + ".zip");
    }

    /** 从 "xxx_3.zip" 推出 "xxx"；不是分片名则返回 null */
    public static String baseOfPartName(String name) {
        Matcher m = PART_RE.matcher(name);
        return m.matches() ? m.group(1) : null;
    }

    /** 数出 dir 下 <base>_1.zip 起连续存在多少片 */
    public static int countPartsInDir(File dir, String base) {
        int n = 0;
        while (partIn(dir, base, n + 1).exists()) {
            n++;
            if (n > 1000000) break;
        }
        return n;
    }

    /** 扫描目录里所有 *_N.zip 分组，返回连续片数最多的那个前缀 */
    public static String bestGroupInDir(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        Map<String, Integer> max = new HashMap<String, Integer>();
        for (File f : fs) {
            if (!f.isFile()) continue;
            Matcher m = PART_RE.matcher(f.getName());
            if (!m.matches()) continue;
            int v;
            try {
                v = Integer.parseInt(m.group(2));
            } catch (NumberFormatException e) {
                continue;
            }
            String b = m.group(1);
            Integer cur = max.get(b);
            if (cur == null || v > cur) max.put(b, v);
        }
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : max.entrySet()) {
            int c = countPartsInDir(dir, e.getKey());
            if (c > bestCount) {
                bestCount = c;
                best = e.getKey();
            }
        }
        return bestCount > 0 ? best : null;
    }

    /** 一组可合并的分片 */
    public static class PartSet {
        public File dir;        // 分片所在文件夹
        public String base;     // 原文件名（不含 _N.zip）
        public int count;       // 片数
        public long total;      // 分片合计字节
        public File out;        // 还原目标文件

        public String summary() {
            return "已探测到 " + count + " 个分片，合计 " + human(total)
                    + "\n将还原为：" + out.getAbsolutePath();
        }
    }

    /** 在 dir 里解析出一组切片：优先用 preferredBase，其次目录名（.parts），最后扫描取片数最多的一组。 */
    public static PartSet findInDir(File dir, String preferredBase) {
        if (dir == null || !dir.isDirectory()) return null;
        String base = null;
        if (preferredBase != null && countPartsInDir(dir, preferredBase) > 0) {
            base = preferredBase;
        }
        if (base == null) {
            String dn = dir.getName();
            if (dn.endsWith(PARTS_SUFFIX) && dn.length() > PARTS_SUFFIX.length()) {
                String cand = dn.substring(0, dn.length() - PARTS_SUFFIX.length());
                if (countPartsInDir(dir, cand) > 0) base = cand;
            }
        }
        if (base == null) base = bestGroupInDir(dir);
        if (base == null) return null;
        return buildSet(dir, base);
    }

    /** 用明确的 base 组装切片集合；不足 2 片也返回（单片也能还原） */
    public static PartSet buildSet(File dir, String base) {
        int count = countPartsInDir(dir, base);
        if (count <= 0) return null;
        PartSet ps = new PartSet();
        ps.dir = dir;
        ps.base = base;
        ps.count = count;
        long total = 0;
        for (int i = 1; i <= count; i++) total += partIn(dir, base, i).length();
        ps.total = total;
        File parentDir = dir.getParentFile();
        if (dir.getName().equals(base + PARTS_SUFFIX) && parentDir != null) {
            ps.out = new File(parentDir, base);      // 出自 .parts 文件夹 -> 回到外层
        } else {
            ps.out = new File(dir, base);            // 散放的切片 -> 本目录还原
        }
        return ps;
    }

    /**
     * 把用户的选择解析成一组切片。核心规则（v1.2）：
     *   · 选中的是切片文件（xxx_3.zip）      -> 只用它的前缀，检测同目录的连续切片
     *   · 选中的是 xxx（不带 _N 的前缀路径）  -> 检测同目录的 xxx_1.zip…
     *   · 选中的是其它任意文件                -> 自动检测同目录里成组的切片（片数最多的一组）
     *   · 同目录找不到时，再看该目录下的 *.parts 子文件夹（便于在外层随便选个文件）
     *   · 选中的本身就是文件夹                -> 直接在该文件夹里检测
     */
    public static PartSet resolve(File picked) {
        if (picked == null) return null;

        if (picked.exists() && picked.isDirectory()) return findInDir(picked, null);

        // 注意：路径指向的文件可能已被删除/移走（下载切片后原文件往往不在），
        // 所以这里不要求 picked 存在，一律按它的父目录去找切片。
        File dir = picked.getAbsoluteFile().getParentFile();
        if (dir == null || !dir.isDirectory()) return null;

        String name = picked.getName();
        String base = baseOfPartName(name);
        if (base != null) {
            // 明确是切片名：严格按它的前缀找，不猜别的组
            PartSet ps = buildSet(dir, base);
            if (ps != null) return ps;
        } else if (countPartsInDir(dir, name) > 0) {
            // 用户填的是不带 _N 的前缀路径
            return buildSet(dir, name);
        }

        // 同目录里成组的切片
        PartSet ps = findInDir(dir, null);
        if (ps != null) return ps;

        // 优先找同名的 xxx.parts 文件夹（选 movie.mkv 时命中 movie.mkv.parts/）
        File namedParts = new File(dir, name + PARTS_SUFFIX);
        if (namedParts.isDirectory()) {
            ps = findInDir(namedParts, null);
            if (ps != null) return ps;
        }
        // 再退一步：目录下任意 *.parts 子文件夹
        File[] subs = dir.listFiles();
        if (subs != null) {
            for (File d : subs) {
                if (d.isDirectory() && d.getName().endsWith(PARTS_SUFFIX)) {
                    ps = findInDir(d, null);
                    if (ps != null) return ps;
                }
            }
        }
        return null;
    }

    // ------------------------------- 分割 -------------------------------

    /** 分割文件，返回生成的分片数。分片落在 <源文件>.parts/ 内。 */
    public static int split(File src, long minBytes, long maxBytes, Cb cb) throws IOException {
        if (!src.exists()) throw new IOException("文件不存在: " + src);
        if (src.isDirectory()) throw new IOException("不能分割文件夹");
        long total = src.length();
        if (total <= 0) throw new IOException("文件为空");
        if (maxBytes < minBytes) maxBytes = minBytes;
        if (minBytes < 1) minBytes = 1;

        File outDir = partsDir(src);
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("无法创建分片文件夹: " + outDir);
        }

        Random rnd = new Random();
        byte[] buf = new byte[BUF];
        long done = 0;
        int n = 1;

        try (InputStream in = new FileInputStream(src)) {
            while (true) {
                long remain = total - done;
                if (remain <= 0) break;
                if (cb != null && cb.cancelled()) throw new Cancelled();

                double r = minBytes + rnd.nextDouble() * (double) (maxBytes - minBytes + 1);
                long want = (long) r;
                if (want > maxBytes) want = maxBytes;
                if (want > remain) want = remain;
                if (want < 1) want = 1;

                File part = partIn(outDir, src.getName(), n);
                try (OutputStream os = new FileOutputStream(part)) {
                    long left = want;
                    while (left > 0) {
                        if (cb != null && cb.cancelled()) throw new Cancelled();
                        int k = (int) Math.min((long) buf.length, left);
                        int got = in.read(buf, 0, k);
                        if (got <= 0) break;
                        os.write(buf, 0, got);
                        left -= got;
                        done += got;
                        report(cb, done, total, "正在写入 " + part.getName());
                    }
                }
                n++;
                if (n > 1000000) throw new IOException("分片数量异常");
            }
        }
        report(cb, total, total, "完成");
        return n - 1;
    }

    // ------------------------------- 合并 -------------------------------

    /** 按 PartSet 合并分片，返回写出的总字节数。 */
    public static long merge(PartSet ps, Cb cb) throws IOException {
        if (ps == null || ps.count <= 0) throw new IOException("未找到可合并的分片");
        byte[] buf = new byte[BUF];
        long done = 0;
        File out = ps.out;
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录: " + parent);
        }
        File tmp = new File(out.getAbsolutePath() + ".merging");
        try (OutputStream os = new FileOutputStream(tmp)) {
            for (int i = 1; i <= ps.count; i++) {
                File p = partIn(ps.dir, ps.base, i);
                if (!p.exists()) throw new IOException("缺少分片: " + p.getName());
                try (InputStream in = new FileInputStream(p)) {
                    int got;
                    while ((got = in.read(buf)) > 0) {
                        if (cb != null && cb.cancelled()) throw new Cancelled();
                        os.write(buf, 0, got);
                        done += got;
                        report(cb, done, ps.total, "正在合并 " + p.getName());
                    }
                }
            }
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        // 原子落位，避免半成品覆盖原文件
        if (out.exists() && !out.delete()) {
            tmp.delete();
            throw new IOException("无法覆盖已存在的文件: " + out);
        }
        if (!tmp.renameTo(out)) {
            tmp.delete();
            throw new IOException("重命名失败: " + out);
        }
        report(cb, ps.total, ps.total, "完成");
        return done;
    }

    /**
     * 删除该组切片。只删 "<base>_N.zip"，绝不会碰还原出来的目标文件；
     * 若切片所在目录是 "<base>.parts" 且已删空，则连同空文件夹一起删掉。
     * 返回实际删除的文件数。
     */
    public static int deleteParts(PartSet ps) {
        if (ps == null) return 0;
        int n = 0;
        for (int i = 1; i <= ps.count; i++) {
            File p = partIn(ps.dir, ps.base, i);
            try {
                if (p.exists() && p.delete()) n++;
            } catch (Throwable ignored) {
            }
        }
        // 目录名恰为 <base>.parts 且已空 -> 顺手删掉空文件夹
        try {
            if (ps.dir.getName().equals(ps.base + PARTS_SUFFIX)) {
                File[] rest = ps.dir.listFiles();
                if (rest != null && rest.length == 0) ps.dir.delete();
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    private static void report(Cb cb, long done, long total, String msg) {
        if (cb == null) return;
        int pct = total <= 0 ? 0 : (int) (done * 100 / total);
        if (pct > 100) pct = 100;
        cb.progress(pct, msg);
    }

    public static String human(long n) {
        if (n < 1024) return n + " B";
        double v = n / 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f KB", v);
        v /= 1024.0;
        if (v < 1024) return String.format(Locale.US, "%.1f MB", v);
        v /= 1024.0;
        return String.format(Locale.US, "%.2f GB", v);
    }
}
