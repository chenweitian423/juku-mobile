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

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 仅服务本应用自身更新包的 ContentProvider。
 *
 * 安全设计：
 * - 不对外导出（manifest 中 exported=false），避免其它应用遍历/探测更新包；
 * - 即便被以某种方式访问，query/openFile 也要求调用方是本应用（同 uid）；
 * - 路径限制在 cacheDir/updates 下，仅允许 *.apk，拒绝目录穿越。
 */
public class ApkFileProvider extends ContentProvider {
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
     * 只允许本应用（同 uid）访问。系统安装器通过 startActivity + FLAG_GRANT_READ_URI_PERMISSION
     * 获得临时授权时，Binder 的 callingUid 仍可能不同，因此这里对"已通过 URI 授权"的访问放行，
     * 仅拦截未经授权的外部探测。
     */
    private void enforceSelfOrGranted(Uri uri) throws FileNotFoundException {
        int callingUid = Binder.getCallingUid();
        int selfUid = android.os.Process.myUid();
        if (callingUid == selfUid || callingUid == 0) {
            return;
        }
        // 非本应用调用：只有确实被授予该 URI 读权限时才允许
        try {
            int mode = requireContext().checkCallingUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if ((mode & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                return;
            }
        } catch (Exception ignored) {
            // 落到下面抛异常
        }
        throw new FileNotFoundException("permission denied");
    }

    private File updateFile(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("[A-Za-z0-9._-]+\\.apk")) {
            throw new FileNotFoundException("invalid update file");
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
            throw new FileNotFoundException("update file not found");
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
        if (mode == null || !mode.startsWith("r") || mode.contains("w")) {
            throw new FileNotFoundException("updates are read-only");
        }
        enforceSelfOrGranted(uri);
        return ParcelFileDescriptor.open(updateFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            enforceSelfOrGranted(uri);
        } catch (FileNotFoundException error) {
            return null;
        }
        try {
            File file = updateFile(uri);
            // 按调用方请求的 projection 返回，避免部分 ROM 因列不匹配而判定 provider 异常
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
        } catch (FileNotFoundException error) {
            return null;
        }
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
