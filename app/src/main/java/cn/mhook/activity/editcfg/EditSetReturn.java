package cn.mhook.activity.editcfg;

import android.app.Activity;
import android.widget.EditText;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cn.mhook.mhook.R;
import cn.mhook.widget.GlassToast;

public class EditSetReturn extends Activity {

    EditText className, methodName, paramsName, returnType, returnData;
    String classNames, methodNames, paramsNames, returnTypes, returnDatas;
    View success, save;
    JSONObject hookJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_set_return);
        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        Bundle b = getIntent().getExtras();
        if (b != null && b.containsKey("data")) {
            hookJson = JSONObject.parseObject(b.getString("data"));
        }
        initView();
        if (hookJson == null) {
            hookJson = new JSONObject(true);
        }
    }

    private void initView() {
        success = findViewById(R.id.success);
        save = findViewById(R.id.save);
        View.OnFocusChangeListener onFocusChangeListener = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    save.setVisibility(View.GONE);
                }
            }
        };
        className = findViewById(R.id.className);
        className.setOnFocusChangeListener(onFocusChangeListener);
        methodName = findViewById(R.id.methodName);
        methodName.setOnFocusChangeListener(onFocusChangeListener);
        paramsName = findViewById(R.id.paramsName);
        paramsName.setOnFocusChangeListener(onFocusChangeListener);
        returnType = findViewById(R.id.returnType);
        returnType.setOnFocusChangeListener(onFocusChangeListener);
        returnData = findViewById(R.id.returnData);
        returnData.setOnFocusChangeListener(onFocusChangeListener);

        if (hookJson != null) {
            className.setText(hookJson.getString("className"));
            methodName.setText(hookJson.getString("methodName"));
            paramsName.setText(hookJson.getJSONArray("paramsName") == null ? "" : hookJson.getJSONArray("paramsName").toJSONString());
            returnType.setText(hookJson.getString("returnType"));
            returnData.setText(hookJson.getString("returnData"));
        }

        success.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                putData();
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveData();
            }
        });
    }

    private void saveData(){
        classNames = className.getText().toString();
        methodNames = methodName.getText().toString();
        paramsNames = paramsName.getText().toString();
        returnTypes = returnType.getText().toString();
        returnDatas = returnData.getText().toString();
        hookJson.put("className",classNames);
        hookJson.put("methodName",methodNames);
        hookJson.put("paramsName",JSONArray.parseArray(paramsNames));
        hookJson.put("returnType",returnTypes);
        hookJson.put("returnData",returnDatas);
        hookJson.put("hookType","setRet");

        Intent intent = new Intent();
        Bundle bundle = new Bundle();
        bundle.putString("data",hookJson.toJSONString());
        intent.putExtras(bundle);
        setResult(2, intent);//返回值调用函数，其中2为resultCode，返回值的标志
        finish();//传值结束
    }

    private void putData(){
        classNames = className.getText().toString();
        methodNames = methodName.getText().toString();
        paramsNames = paramsName.getText().toString();
        returnTypes = returnType.getText().toString();
        returnDatas = returnData.getText().toString();
        if (classNames.isEmpty()||methodNames.isEmpty()||returnTypes.isEmpty()||returnDatas.isEmpty()){
            if (!methodNames.isEmpty()&&paramsNames.isEmpty()&&returnTypes.isEmpty()){
                goneFun(methodNames);
            }else {
                GlassToast.warning(this, "填写不完整");
                return;
            }
        }
        if (!paramsNames.isEmpty()){
           JSONArray parms = parmsToArray(paramsNames);
           paramsName.setText(parms.toJSONString());
        }
        className.setText(classNameToClass(classNames));
        methodName.setText(methodNames);
        returnType.setText(returnTypes);
        if (!returnDatas.isEmpty()){
            save.setVisibility(View.VISIBLE);
        }else {
            GlassToast.success(this, "格式化成功");
        }
    }


    private void goneFun(String method){
        String pattern = ".*\\(.*\\).*";
        method = method.trim();
        if (!method.endsWith(";")){
            method=method+";";
        }
        boolean isMatch = Pattern.matches(pattern, method);
        if (isMatch){
            String methodName = "";
            // 创建 Pattern 对象
            Pattern r = Pattern.compile("(.*?)\\(");
            // 现在创建 matcher 对象
            Matcher m = r.matcher(method);
            if (m.find( )) {
                methodName = m.group(1);
            } else {

            }

            String parms = "";
            Pattern r1 = Pattern.compile("\\((.*?)\\)");
            // 现在创建 matcher 对象
            Matcher m1 = r1.matcher(method);
            if (m1.find( )) {
                parms = m1.group(1);
            } else {

            }

            String retType = "";
            Pattern r2 = Pattern.compile("\\)(.*);");
            // 现在创建 matcher 对象
            Matcher m2 = r2.matcher(method);
            if (m2.find( )) {
                retType = m2.group(1);
            } else {

            }
            methodNames = methodName;
            paramsNames = parms;
            returnTypes = retType;
        }
    }


    private String classNameToClass(String L){
        L =L.trim();
        if (L.startsWith("L")){
            L = L.substring(1);
        }
        L=L.replace("/",".");
        L=L.replace(";","");
        return L;
    }

    private JSONArray parmsToArray(String L){
        JSONArray ret = new JSONArray();
        try {
            ret = JSONArray.parseArray(L);
            return ret;
        }catch (Throwable e){
            if (L.startsWith("[")||L.endsWith("]")||L.isEmpty()){
                return ret;
            }
            if (L.startsWith("(")){
                L = L.substring(1);
            }
            if (L.endsWith(")")){
                L = L.substring(0,L.length()-1);
            }
            for(int i=0;i<L.length();i++){
                String subStr = L.substring(i, i+1);
                if (subStr.equals("L")&&L.substring(i).contains(";")){
                    String other = L.substring(i);
                    int fh = other.indexOf(";", 0);
                    String cls = other.substring(0,fh);
                    String add = classNameToClass(cls);
                    ret.add(add);
                    i+=cls.length();
                }else {
                    ret.add(subStr);
                }
            }
            return ret;
        }
    }

}
