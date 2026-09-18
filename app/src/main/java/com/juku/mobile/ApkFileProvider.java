package com.juku.mobile;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 仅服务本应用自身更新包的 ContentProvider。
 *
 * ★ 为什么 manifest 里是 exported="true"（而不是 FileProvider 那样 false）：
 *
 * 安装一条 APK，读这个 URI 的**不止系统安装器**。国产 ROM（MIUI/HyperOS 等）在弹出
 * 安装界面之前，会由「安全中心 / 病毒扫描」这类**独立进程**先读一遍安装包，而
 * `FLAG_GRANT_READ_URI_PERMISSION` 的临时授权只给到安装器本身，扫描进程并没有授权。
 * 一旦 provider 不导出（或内部再按 uid 拒绝），这一步就会失败，用户看到的是
 *   「解析软件包时出现问题。(11) Failed opening content provider: content://<pkg>.apk/xxx.apk」
 * —— 真正的原因（provider 读不到）被完全掩盖，而且**在模拟器上永远复现不出来**
 * （AOSP 安装器没有第三方扫描这一步）。
 *
 * 所以安全边界不放在「谁可以调用」，而是放在「能读到什么」：
 * - 只读（任何写操作一律拒绝）；
 * - 只暴露 cacheDir/updates 目录下的文件；
 * - 文件名必须匹配 [A-Za-z0-9._-]+\.apk，并做 canonical path 校验拒绝目录穿越；
 * - 文件必须存在且非空。
 * 也就是最坏情况下别人只能读到「我们刚从公网下载下来的、本来就公开可下载的安装包」。
 *
 * 每次读取都会记一条日志（含调用方 uid / 包名），便于售后定位
 * 「到底是哪个进程在读、读到了没有」：
 *   adb logcat -s JukuMobile
 */
public class ApkFileProvider extends ContentProvider {
    private static final String LOG_TAG = "JukuMobile";
    private static final String AUTHORITY_SUFFIX = ".apk";
    private static final String UPDATE_DIRECTORY = "updates";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    public static Uri uriForFile(Context context, File file) {
        String authority = context.getPackageName() + AUTHORITY_SUFFIX;
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(file.getName())
                .build();
    }

    /**
     * 只做可观测性，不做拦截。
     *
     * 曾经这里是「非本应用一律拒绝」，结果是厂商安装链路上的扫描进程读不到包、
     * 安装彻底失败，而报错信息完全指不到这里。现在改为记录调用方，
     * 由下面的路径白名单承担安全职责。
     */
    private void noteCaller(String method, Uri uri) {
        int callingUid = Binder.getCallingUid();
        String caller = "<unknown>";
        try {
            String[] packages = requireContext().getPackageManager().getPackagesForUid(callingUid);
            if (packages != null && packages.length > 0) {
                StringBuilder builder = new StringBuilder();
                for (String name : packages) {
                    if (builder.length() > 0) {
                        builder.append(',');
                    }
                    builder.append(name);
                }
                caller = builder.toString();
            }
        } catch (Exception ignored) {
            // 取不到包名不影响放行，日志里保留 <unknown> 即可
        }
        Log.i(LOG_TAG, "provider " + method + " uri=" + uri + " uid=" + callingUid + " pkg=" + caller);
    }

    private File updateFile(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.apk")) {
            throw new FileNotFoundException("invalid update file: " + name);
        }
        File directory = new File(requireContext().getCacheDir(), UPDATE_DIRECTORY);
        File file = new File(directory, name);
        try {
            String parent = file.getCanonicalFile().getParentFile().getCanonicalPath();
            if (!parent.equals(directory.getCanonicalPath())) {
                throw new FileNotFoundException("invalid update path");
            }
        } catch (Exception error) {
            throw new FileNotFoundException("invalid update path");
        }
        if (!file.isFile() || file.length() <= 0L) {
            throw new FileNotFoundException("update file not found: " + name);
        }
        return file;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return APK_MIME;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        noteCaller("openFile(" + mode + ")", uri);
        // 只允许读。注意不能要求 mode 恰好等于 "r" —— 个别 ROM 会传 "rt" 之类，
        // 只要不带写意图就放行，实际打开的永远是 MODE_READ_ONLY。
        if (mode != null && mode.startsWith("w")) {
            throw new FileNotFoundException("updates are read-only");
        }
        return ParcelFileDescriptor.open(updateFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        noteCaller("query", uri);
        File file;
        try {
            file = updateFile(uri);
        } catch (FileNotFoundException error) {
            // ContentProvider.query 的签名不允许抛受检异常，而「返回 null cursor」
            // 会被安装器当成 provider 异常、报出含糊的「解析软件包时出现问题」。
            // 这里改成非受检异常把原因带出去（androidx FileProvider 也是这个做法）。
            throw new IllegalArgumentException("update file unavailable: " + error.getMessage(), error);
        }
        // 按调用方请求的 projection 返回：部分 ROM 会把「列不匹配」判成 provider 异常，
        // 进而报出「解析软件包时出现问题」。
        String[] columns;
        if (projection == null || projection.length == 0) {
            columns = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        } else {
            columns = projection;
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            String column = columns[index];
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row[index] = file.getName();
            } else if (OpenableColumns.SIZE.equals(column)) {
                row[index] = file.length();
            } else {
                row[index] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("updates are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("updates are read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("updates are read-only");
    }
}
