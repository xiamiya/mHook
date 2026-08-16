package cn.mhook.activity;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import cn.mhook.mhook.R;

/**
 * 用户协议页（玻璃拟态）
 */
public class UserAgreementActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_text_page);

        ((TextView) findViewById(R.id.page_title)).setText("用户协议");
        ((TextView) findViewById(R.id.page_content)).setText(R.string.mianze);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
