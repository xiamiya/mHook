package cn.mhook.mhook.contentprovider;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.Nullable;

public class SuperContentProvider extends ContentProvider {
    private SQLiteDatabase db;
    private static final String MAUTHORITIESNAME = "mHookData";
    private static UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);
    private static final int print = 1;
    private static final int jsonCfg = 2;
    private static final int appCfg = 3;
    private static final String TABLE_NAME = "printLog";
    private static final String TABLE_NAME2 = "jsonCfg";
    private static final String TABLE_NAME3 = "appCfg";
    // 构建URI
    static {
        // content://programandroid/person
        matcher.addURI(MAUTHORITIESNAME, "print", print);
        matcher.addURI(MAUTHORITIESNAME, "jsonCfg", jsonCfg);
        matcher.addURI(MAUTHORITIESNAME, "appCfg", appCfg);
    }

    @Override
    public boolean onCreate() {
        DBHelper helper = new DBHelper(getContext());
        // 创建数据库
        db = helper.getWritableDatabase();
        try {
            // 多进程/多应用并发写时减少 SQLITE_BUSY 失败
            db.execSQL("PRAGMA busy_timeout = 5000;");
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Nullable
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {

        // 过滤URI
        int match = matcher.match(uri);
        switch (match) {
            case print:
                return db.query(TABLE_NAME, projection, selection, selectionArgs,
                        null, null, sortOrder);
            case jsonCfg:
                return db.query(TABLE_NAME2, projection, selection, selectionArgs,
                        null, null, sortOrder);
            case appCfg:
                return db.query(TABLE_NAME3, projection, selection, selectionArgs,
                        null, null, sortOrder);
            default:
                break;
        }
        return null;
    }


    @Nullable
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // 过滤URI
        int match = matcher.match(uri);
        switch (match) {
            case print:
                // content://autoname/person
                getContext().getContentResolver().notifyChange(uri, null);
                long id = db.insert(TABLE_NAME, null, values);
                // 将原有的uri跟id进行拼接从而获取新的uri
                return ContentUris.withAppendedId(uri, id);
            case jsonCfg:
                getContext().getContentResolver().notifyChange(uri, null);
                long id2 = db.insert(TABLE_NAME2, null, values);
                // 将原有的uri跟id进行拼接从而获取新的uri
                return ContentUris.withAppendedId(uri, id2);
            case appCfg:
                getContext().getContentResolver().notifyChange(uri, null);
                long id3 = db.insert(TABLE_NAME3, null, values);
                // 将原有的uri跟id进行拼接从而获取新的uri
                return ContentUris.withAppendedId(uri, id3);
            default:
                break;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // 过滤URI
        int match = matcher.match(uri);
        switch (match) {
            case print:
                // content://autoname/person

                int id = db.delete(TABLE_NAME,selection,selectionArgs);

                // 将原有的uri跟id进行拼接从而获取新的uri
                return id;
            case jsonCfg:
                int id2 = db.delete(TABLE_NAME2,selection,selectionArgs);

                // 将原有的uri跟id进行拼接从而获取新的uri
                return id2;
            case appCfg:
                int id3 = db.delete(TABLE_NAME3,selection,selectionArgs);

                // 将原有的uri跟id进行拼接从而获取新的uri
                return id3;
            default:
                break;
        }
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        // 过滤URI
        int match = matcher.match(uri);
        switch (match) {
            case print:
                // content://autoname/person
                int id = db.update(TABLE_NAME,values,selection,selectionArgs);
                // 将原有的uri跟id进行拼接从而获取新的uri
                return id;
            case jsonCfg:
                // content://autoname/person
                int id2 = db.update(TABLE_NAME2,values,selection,selectionArgs);
                // 将原有的uri跟id进行拼接从而获取新的uri
                return id2;
            case appCfg:
                // content://autoname/person
                int id3 = db.update(TABLE_NAME3,values,selection,selectionArgs);
                // 将原有的uri跟id进行拼接从而获取新的uri
                return id3;
            default:
                break;
        }
        return 0;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        return null;
    }

}

