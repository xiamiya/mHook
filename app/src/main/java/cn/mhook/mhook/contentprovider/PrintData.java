package cn.mhook.mhook.contentprovider;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

public class PrintData {


    public static void putData(Context context, String str){
        Uri bookUri = Uri.parse("content://mHookData/print");
        for (int i = 0; i < 3; i++) {
            try {
                ContentValues values = new ContentValues();
                values.put("msg", str);
                values.put("time", "");
                context.getContentResolver().insert(bookUri, values);
                return;
            } catch (Throwable t) {
                try { Thread.sleep(200); } catch (Throwable ignored) {
                }
            }
        }
    }

    public static JSONObject getData(Context context,int startId){
        Uri bookUri = Uri.parse("content://mHookData/print");
        JSONObject ret = new JSONObject(true);
        JSONArray msgArr = new JSONArray();
        Cursor bookCursor = context.getContentResolver().query(bookUri, new String[]{"_id", "msg", "time"}, "_id>?", new String[]{String.valueOf(startId)}, null);
        if (bookCursor != null) {
            while (bookCursor.moveToNext()) {
                int _id = bookCursor.getInt(0);
                String msg = bookCursor.getString(1);
                String time = bookCursor.getString(2);
                msgArr.add(JSONObject.parseObject(msg));
                ret.put("endId",_id);
            }
        }
        if (bookCursor != null) {
            bookCursor.close();
        }
        ret.put("msg",msgArr);
        return ret;
    }

    public static void delAll(Context context){
        Uri bookUri = Uri.parse("content://mHookData/print");
        context.getContentResolver().delete(bookUri,"_id>?",new String[]{"0"});
    }

}
