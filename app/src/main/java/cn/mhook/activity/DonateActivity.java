package cn.mhook.activity;

import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import cn.mhook.BaseActivity;
import cn.mhook.mhook.R;

/**
 * 打赏支持：展示支付宝 / 微信收款码，供扫码打赏。
 */
public class DonateActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_donate);
        findViewById(R.id.donate_title).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        ImageView alipay = findViewById(R.id.donate_alipay_img);
        ImageView wxpay = findViewById(R.id.donate_wxpay_img);
        alipay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        wxpay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
